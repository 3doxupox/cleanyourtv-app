package tv.cleanyour.core.engine

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.testing.fixtures.MemoryJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The journal crash matrix (ARCHITECTURE 6.3, F5): the process dies after every journal record of a
// full Setup run with the takeover; a fresh engine resumes from the journal and the plan file and
// the TV ends consistent with the change records. Killing is simulated by a journal that throws
// after n appends, which is where a real process death leaves a torn or missing record.
class ResumeTest {

    // Drives a run to its end, answering "yes" on the check screen.
    private suspend fun TestScope.drive(engine: Engine, h: Harness): RunState {
        var guard = 0
        while (true) {
            val s = engine.runState(h.tv.tvId).value
            if (s is RunState.Finished || s is RunState.Paused || s is RunState.Idle) return s
            if (s is RunState.AwaitingConfirm) engine.confirmHome(h.tv.tvId)
            advanceTimeBy(1_000); runCurrent()
            check(guard++ < 10_000) { "run did not settle" }
        }
    }

    @Test fun killAfterEveryRecordThenResumeReachesAConsistentState() = runTest(timeout = TEST_TIMEOUT) {
        val probe = Harness(this)
        val full = probe.setupPlan()
        val e0 = probe.engine()
        e0.start(full, probe.tv, full.hash, probe.pass())
        assertIs<RunState.Finished>(drive(e0, probe))
        val total = probe.records(full).size
        assertTrue(total > 30, "records in a full run: $total")

        for (n in 1 until total) {
            val h = Harness(this)
            val crashing = MemoryJournal().also { it.crashAfter = n }
            val e1 = h.engine(journal = crashing)
            val plan = h.setupPlan()
            e1.start(plan, h.tv, plan.hash, h.pass())
            val first = drive(e1, h)
            assertIs<RunState.Paused>(first, "crash after $n records")
            // The app comes back: a new engine over the same journal, plan file and TV.
            crashing.crashAfter = null
            val e2 = DefaultEngine(h.renderer, h.prober, crashing, h.snapshots, h.plans, h.clock, backgroundScope, h.profiles)
            e2.resume(h.tv.tvId, h.tv)
            val resumed = e2.runState(h.tv.tvId).value
            assertIs<RunState.Paused>(resumed, "after resume, crash at $n")
            assertTrue(resumed.reason in setOf(PauseReason.IN_DOUBT, PauseReason.CONNECTION_LOST, PauseReason.STEP_FAILED), "crash at $n: ${resumed.reason}")
            val records = crashing.read(plan.id)
            assertTrue(records.none { it is JournalRecord.End }, "still open")
            if (records.filterIsInstance<JournalRecord.Section>().any { it.state == SectionState.ROLLED_BACK }) assertTrue(h.tv.watchdogs.isEmpty(), "crash at $n: no watchdog left after step R")
            // Every step before `at` is done on the TV; `at` itself may or may not be.
            for (s in plan.steps.take(resumed.at)) {
                val op = s.op
                if (op is Op.Disable && op.pkg != h.stock.pkg) assertEquals(EnabledState.DISABLED_USER, h.tv.state(op.pkg), "crash at $n: step ${s.index} ${op.pkg.value}")
            }
            e2.continueRun(h.tv.tvId, h.pass())
            val fin = drive(e2, h)
            assertIs<RunState.Finished>(fin, "crash at $n")
            val all = crashing.read(plan.id)
            assertEquals(1, all.count { it is JournalRecord.End }, "crash at $n")
            // Consistency: the change records describe the TV exactly.
            val changes = ChangeRecords.fromJournal(all)
            for (p in h.debloat) {
                assertEquals(EnabledState.DISABLED_USER, h.tv.state(p), "crash at $n: ${p.value}")
                assertTrue(changes.any { (it.op as? Op.Disable)?.pkg == p }, "crash at $n: a change record for ${p.value}")
            }
            assertEquals(h.apkSha, h.tv.installed[h.launcher.pkg], "crash at $n: launcher installed")
            val rolledBack = all.filterIsInstance<JournalRecord.Section>().any { it.state == SectionState.ROLLED_BACK }
            if (rolledBack) {
                assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status, "crash at $n")
                assertEquals(h.stock, h.tv.home, "crash at $n")
                assertEquals(EnabledState.ENABLED, h.tv.state(h.stock.pkg), "crash at $n")
                assertTrue(changes.none { it.op is Op.SetHome || (it.op as? Op.Disable)?.pkg == h.stock.pkg }, "crash at $n")
            } else {
                assertEquals(RunStatus.COMPLETED, fin.status, "crash at $n")
                assertEquals(h.launcher, h.tv.home, "crash at $n")
                assertTrue(changes.any { it.op is Op.SetHome }, "crash at $n")
            }
            assertTrue(h.tv.watchdogs.isEmpty(), "crash at $n: no watchdog left running")
            // No step ran twice without being idempotent: at most one disable attempt per package beyond the retry of the in-doubt step.
            for (p in h.debloat) assertTrue(h.tv.execs("pm disable-user --user 0 ${p.value}") <= 2, "crash at $n: ${p.value}")
            assertTrue(crashing.openRuns(h.tv.tvId).isEmpty(), "crash at $n")
        }
    }

    @Test fun resumeWithNothingOpenDoesNothing() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        engine.resume(h.tv.tvId, h.tv)
        assertEquals(RunState.Idle, engine.runState(h.tv.tvId).value)
        assertTrue(h.tv.execLog.isEmpty())
    }

    @Test fun aTamperedPlanFileIsNeverResumed() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val e1 = h.engine()
        val plan = h.setupPlan(takeover = false, installLauncher = false)
        h.tv.dropOnLine = { it.contains(h.debloat[1].value) && it.contains("disable-user") }
        e1.start(plan, h.tv, plan.hash, h.pass())
        drive(e1, h)
        assertIs<RunState.Paused>(e1.runState(h.tv.tvId).value)
        // Someone edits the plan file: a different package in a step.
        val path = "tvs/${h.tv.tvId.value}/plans/${plan.id.value}.json"
        val text = h.documents.bytes(path)!!.decodeToString().replace(h.debloat[3].value, "com.android.systemui")
        h.documents.write(path, text.encodeToByteArray())
        h.tv.dropOnLine = null; h.tv.reconnect()
        val e2 = h.engine()
        e2.resume(h.tv.tvId, h.tv)
        assertEquals(RunState.Idle, e2.runState(h.tv.tvId).value)
        assertEquals("end:ABANDONED", h.types(plan).last())
        assertTrue(h.journal.openRuns(h.tv.tvId).isEmpty())
        assertEquals(0, h.tv.execs("dumpsys package com.android.systemui") + h.tv.execs("pm disable-user --user 0 com.android.systemui"))
    }

    @Test fun resumeVerifiesTheInDoubtStepAndMarksItResumed() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val crashing = MemoryJournal()
        val e1 = h.engine(journal = crashing)
        val plan = h.setupPlan(takeover = false, installLauncher = false)
        crashing.crashAfter = 4   // plan, begin0, done0, begin1, then the crash on done1: the op ran, the record did not land
        e1.start(plan, h.tv, plan.hash, h.pass())
        drive(e1, h)
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(h.debloat[1]))
        assertEquals(listOf("plan", "begin0", "done0", "begin1"), crashing.read(plan.id).map { r ->
            when (r) { is JournalRecord.PlanStart -> "plan"; is JournalRecord.Begin -> "begin${r.i}"; is JournalRecord.Done -> "done${r.i}"; else -> r::class.simpleName!! }
        })
        crashing.crashAfter = null
        val e2 = DefaultEngine(h.renderer, h.prober, crashing, h.snapshots, h.plans, h.clock, backgroundScope, h.profiles)
        e2.resume(h.tv.tvId, h.tv)
        val paused = e2.runState(h.tv.tvId).value as RunState.Paused
        assertEquals(PauseReason.IN_DOUBT, paused.reason)
        assertEquals(2, paused.at, "the in-doubt step verified as done, so the run continues after it")
        val done = crashing.read(plan.id).filterIsInstance<JournalRecord.Done>().last()
        assertEquals(1, done.i); assertTrue(done.resumed)
        e2.continueRun(h.tv.tvId, h.pass())
        assertEquals(RunStatus.COMPLETED, (drive(e2, h) as RunState.Finished).status)
        assertEquals(1, h.tv.execs("pm disable-user --user 0 ${h.debloat[1].value}"), "not sent again")
    }
}
