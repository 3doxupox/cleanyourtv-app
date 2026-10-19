package tv.cleanyour.core.engine

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.ops.Watchdog
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.session.InstallOutcome
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Section 3.4 end to end on the scripted TV: the watchdog armed, the stock launcher off, HOME set
// and verified, the check screen, then the user's answer, the timer, the live focus check, a
// verify failure, a lost connection and the resume that runs step R.
class SectionTest {

    private suspend fun TestScope.openCheckScreen(h: Harness, engine: Engine, plan: Plan): RunState.AwaitingConfirm {
        assertEquals(StartResult.Started, engine.start(plan, h.tv, plan.hash, h.pass()))
        advanceTimeBy(20_000); runCurrent()
        val s = engine.runState(h.tv.tvId).value
        assertIs<RunState.AwaitingConfirm>(s, "state after the section's last step")
        return s
    }

    private fun Harness.assertStockHomeIsBack(token: String) {
        assertEquals(stock, tv.home)
        assertEquals(EnabledState.ENABLED, tv.state(stock.pkg))
        assertTrue(tv.watchdogs.isEmpty(), "the watchdog was killed")
        assertTrue(tv.files.containsKey("/data/local/tmp/cleanyourtv/${Watchdog.TOKEN_FILE_PREFIX}$token"), "the token file defuses it too")
        for (p in debloat) assertEquals(EnabledState.DISABLED_USER, tv.state(p), "the debloat part is kept")
    }

    @Test fun yesISeeItConfirmsTheSection() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        val section = plan.sections.single()
        val s = openCheckScreen(h, engine, plan)
        assertEquals(section, s.section)
        assertEquals(h.clock.nowMs() - 20_000 + 15_000 + 90_000, s.deadlineMs, "90 s from the section's last step, with a thumbnail available (D23)")
        assertNotNull(s.thumbnail)
        assertNull(s.homeCheck, "no HOME key pressed yet")
        // The TV in the meantime: our launcher is HOME, the stock one off, the watchdog alive.
        assertEquals(h.launcher, h.tv.home)
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(h.stock.pkg))
        assertTrue(section.token.value in h.tv.watchdogs)
        // The user presses Home on the remote: the live check sees our launcher.
        h.tv.focus = h.launcher
        advanceTimeBy(2_500); runCurrent()
        assertEquals(true, (engine.runState(h.tv.tvId).value as RunState.AwaitingConfirm).homeCheck)
        assertTrue(h.prober.calls.count { it == "homeFocus" } >= 3, "the 2 s live check")
        engine.confirmHome(h.tv.tvId)
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value
        assertIs<RunState.Finished>(fin)
        assertEquals(RunStatus.COMPLETED, fin.status)
        assertNull(fin.home)
        assertEquals(h.launcher, h.tv.home)
        assertTrue(h.tv.watchdogs.isEmpty(), "pkill ran")
        assertTrue(h.tv.files.containsKey("/data/local/tmp/cleanyourtv/home-ok-${section.token.value}"), "the token file was written")
        val types = h.types(plan)
        assertEquals(listOf("section:ARMED", "section:CONFIRMED"), types.filter { it.startsWith("section") })
        assertTrue(types.indexOf("section:ARMED") < types.indexOf("begin${section.from}"), "the token is in the journal before the watchdog is armed")
        assertEquals("end:COMPLETED", types.last())
        val armed = h.records(plan).filterIsInstance<JournalRecord.Section>().first()
        assertEquals(section.token, armed.token)
        assertTrue(armed.deadlineWallMs >= Samples.NOW_MS + 15_000 + 120_000, "the TV watchdog fires 120 s after arming")
    }

    @Test fun noPutItBackRunsStepR() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        val section = plan.sections.single()
        openCheckScreen(h, engine, plan)
        engine.rejectHome(h.tv.tvId)
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(HomeOutcome.USER_SAID_NO, fin.home)
        h.assertStockHomeIsBack(section.token.value)
        assertEquals(plan.steps.drop(section.to + 1), fin.skipped, "everything after the section is skipped")
        val types = h.types(plan)
        val r = types.indexOf("rollback${section.from}")
        assertTrue(r > 0)
        assertEquals(listOf("begin-1", "done-1", "begin-1", "done-1", "begin${section.from + 1}", "done${section.from + 1}", "begin${section.to - 1}", "done${section.to - 1}", "begin-1", "done-1", "begin-1", "done-1", "section:ROLLED_BACK", "end:COMPLETED_WITH_SKIPS"), types.drop(r + 1))
        // Change records: the debloat stays, the section's changes were undone.
        val changes = ChangeRecords.fromJournal(h.records(plan))
        assertEquals(h.debloat.size + 1, changes.size, "five disables and the install")
        assertTrue(changes.none { it.op is Op.SetHome || (it.op as? Op.Disable)?.pkg == h.stock.pkg })
        assertTrue(h.tv.execLog.map { tv.cleanyour.testing.fixtures.Recording.normalize(it) }.last() == "input keyevent 3")
    }

    @Test fun theTimerRollsBack() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        openCheckScreen(h, engine, plan)
        advanceTimeBy(60_000); runCurrent()
        assertIs<RunState.AwaitingConfirm>(engine.runState(h.tv.tvId).value)
        advanceTimeBy(40_000); runCurrent()
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(HomeOutcome.TIMED_OUT, fin.home)
        h.assertStockHomeIsBack(plan.sections.single().token.value)
    }

    @Test fun withoutARecentThumbnailTheWindowIs120s() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.prober.screenshotOkCount = 5   // through the section's screencap precondition; the check screen's fetch fails
        val engine = h.engine()
        val plan = h.setupPlan()
        val s = openCheckScreen(h, engine, plan)
        assertNotNull(s.thumbnail, "the last good one is still shown")
        assertTrue(h.prober.calls.count { it == "screenshot" } >= 6)
        assertEquals(h.clock.nowMs() - 20_000 + 15_000 + 120_000, s.deadlineMs)
        advanceTimeBy(95_000); runCurrent()
        assertIs<RunState.AwaitingConfirm>(engine.runState(h.tv.tvId).value)
        advanceTimeBy(30_000); runCurrent(); h.settle(engine)
        assertEquals(HomeOutcome.TIMED_OUT, (engine.runState(h.tv.tvId).value as RunState.Finished).home)
    }

    // S10: the launcher crashes on HOME, so the HOME key lands on FallbackHome. The stock launcher
    // is HOME again without any user action.
    @Test fun aCrashingLauncherIsCaughtByTheLiveCheck() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        openCheckScreen(h, engine, plan)
        h.tv.focus = Harness.fallbackHome
        advanceTimeBy(2_500); runCurrent(); h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(HomeOutcome.WRONG_FOCUS, fin.home)
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        h.assertStockHomeIsBack(plan.sections.single().token.value)
        assertTrue(h.clock.monotonicMs() < 40_000, "rolled back at once, not at the deadline")
    }

    @Test fun theWizardFocusAndTheStockLauncherFocusRollBackToo() = runTest(timeout = TEST_TIMEOUT) {
        for (focus in listOf(Component(PackageName("com.google.android.tungsten.setupwraith"), ".RecoveryActivity"), Samples.stockLauncher)) {
            val h = Harness(this)
            val engine = h.engine()
            val plan = h.setupPlan()
            openCheckScreen(h, engine, plan)
            h.tv.focus = focus
            advanceTimeBy(2_500); runCurrent(); h.settle(engine)
            assertEquals(HomeOutcome.WRONG_FOCUS, (engine.runState(h.tv.tvId).value as RunState.Finished).home, focus.flat)
        }
    }

    @Test fun verifyHomeFailureRollsBackAtOnce() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        val wizard = Component(PackageName("com.google.android.tungsten.setupwraith"), ".RecoveryActivity")
        h.tv.resolveHomeAs = { if (it == h.launcher) wizard else it }   // the wizard's RecoveryActivity wins HOME (issue #49)
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(HomeOutcome.VERIFY_FAILED, fin.home)
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        h.assertStockHomeIsBack(plan.sections.single().token.value)
        val types = h.types(plan)
        val to = plan.sections.single().to
        assertTrue(("failed$to" in types || "failed${to - 1}" in types) && "section:ROLLED_BACK" in types)
        assertTrue(plan.steps[to + 1] in fin.skipped && plan.steps[to + 2] in fin.skipped)
        assertEquals(0, h.prober.calls.count { it == "homeFocus" }, "the check screen never opened")
    }

    @Test fun theWizardQuirkDisablesAndRestoresTheWizard() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val quirky = h.profile(setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), h.wizard)
        h.profiles = tv.cleanyour.testing.fixtures.FakeProfileSource(listOf(quirky))   // the executor's live check must see the same profile
        val engine = h.engine()
        val plan = h.setupPlan(profile = quirky)
        assertTrue(plan.steps.any { it.op == Op.Disable(h.wizard) })
        openCheckScreen(h, engine, plan)
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(h.wizard))
        engine.rejectHome(h.tv.tvId)
        h.settle(engine)
        assertEquals(EnabledState.ENABLED, h.tv.state(h.wizard))
        assertEquals(h.stock, h.tv.home)
    }

    @Test fun aFailedInstallSkipsTheSectionWithoutArmingAnything() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.installOutcome = InstallOutcome.Failed("INSTALL_FAILED_INSUFFICIENT_STORAGE", "no space")
        val engine = h.engine()
        val plan = h.setupPlan()
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(HomeOutcome.VERIFY_FAILED, fin.home)
        assertTrue(h.tv.watchdogs.isEmpty() && h.tv.home == h.stock && h.tv.state(h.stock.pkg) == EnabledState.ENABLED)
        assertTrue(h.types(plan).none { it.startsWith("section") })
        assertTrue(fin.skipped.any { it.op is Op.InstallStream } && fin.skipped.any { it.op is Op.ArmHomeWatchdog })
        for (p in h.debloat) assertEquals(EnabledState.DISABLED_USER, h.tv.state(p))
    }

    @Test fun aLostConnectionInsideTheSectionIsRolledBackByResume() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        val section = plan.sections.single()
        h.tv.dropOnLine = { it.contains("resolve-activity") }   // the TV vanishes right after set-home-activity
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val paused = engine.runState(h.tv.tvId).value as RunState.Paused
        assertEquals(PauseReason.CONNECTION_LOST, paused.reason)
        assertEquals(section.to - 1, paused.at, "SetHome's verify read hit the dead socket")
        assertEquals(h.launcher, h.tv.home, "the TV is left with the new home screen and the watchdog running")
        assertTrue(section.token.value in h.tv.watchdogs)
        assertEquals(listOf("section:ARMED"), h.types(plan).filter { it.startsWith("section") })
        // The app comes back (a new engine, the journal and the plan file survive), reconnects, resumes.
        h.tv.dropOnLine = null; h.tv.reconnect()
        val engine2 = h.engine()
        assertEquals(listOf(plan.id), h.journal.openRuns(h.tv.tvId))
        engine2.resume(h.tv.tvId, h.tv)
        val after = engine2.runState(h.tv.tvId).value
        assertIs<RunState.Paused>(after)
        assertEquals(PauseReason.CONNECTION_LOST, after.reason)
        h.assertStockHomeIsBack(section.token.value)
        assertEquals(listOf("section:ARMED", "section:ROLLED_BACK"), h.types(plan).filter { it.startsWith("section") })
        // The in-doubt VerifyHome step was re-verified and found not to hold (the stock launcher is back).
        assertTrue(h.types(plan).none { it.endsWith("r") && it.startsWith("done") })
        // Carrying on does not re-enter the section: the run ends and a new plan is needed.
        engine2.continueRun(h.tv.tvId, h.pass())
        h.settle(engine2)
        val fin = engine2.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(HomeOutcome.CONNECTION_LOST, fin.home)
        assertEquals("end:COMPLETED_WITH_SKIPS", h.types(plan).last())
        assertTrue(h.journal.openRuns(h.tv.tvId).isEmpty())
        // RUN_OPEN held while the run was open, and a start after the end is allowed again.
        val again = h.setupPlan()
        assertEquals(StartResult.Started, engine2.start(again, h.tv, again.hash, h.pass()))
    }

    @Test fun continueAfterALostConnectionInTheCheckScreenRunsStepRFirst() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        openCheckScreen(h, engine, plan)
        h.tv.dropOnLine = { it.contains("'dumpsys' 'activity'") }   // the live check hits a dead socket
        advanceTimeBy(2_500); runCurrent(); h.settle(engine)
        val paused = engine.runState(h.tv.tvId).value as RunState.Paused
        assertEquals(PauseReason.CONNECTION_LOST, paused.reason)
        h.tv.dropOnLine = null; h.tv.reconnect()
        engine.continueRun(h.tv.tvId, h.pass())
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(HomeOutcome.CONNECTION_LOST, fin.home)
        h.assertStockHomeIsBack(plan.sections.single().token.value)
    }

    // R1: step R that cannot put the TV's own home screen back is surfaced, never recorded as done.
    @Test fun aFailingStepRPausesWithTheErrorAndIsRetried() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        val section = plan.sections.single()
        openCheckScreen(h, engine, plan)
        h.tv.refuse = { it == h.stock.pkg }   // pm enable of the stock launcher now fails
        engine.rejectHome(h.tv.tvId)
        h.settle(engine)
        val paused = engine.runState(h.tv.tvId).value
        assertIs<RunState.Paused>(paused)
        assertEquals(PauseReason.STEP_FAILED, paused.reason)
        assertIs<tv.cleanyour.core.model.AdbError.NonZeroExit>(paused.error)
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(h.stock.pkg))
        assertEquals(listOf("section:ARMED"), h.types(plan).filter { it.startsWith("section") }, "not ROLLED_BACK")
        // The TV lets the enable through this time: continue runs step R again and the run ends.
        h.tv.refuse = { false }
        engine.continueRun(h.tv.tvId, h.pass())
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        h.assertStockHomeIsBack(section.token.value)
        assertEquals(listOf("section:ARMED", "section:ROLLED_BACK"), h.types(plan).filter { it.startsWith("section") })
    }

    // R1: giving up a paused run with an open section puts the TV's own home screen back first, and
    // needs the TV for that.
    @Test fun abandonWithAnOpenSectionRunsStepRFirst() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        openCheckScreen(h, engine, plan)
        h.tv.dropOnLine = { it.contains("'dumpsys' 'activity'") }
        advanceTimeBy(2_500); runCurrent(); h.settle(engine)
        assertEquals(PauseReason.CONNECTION_LOST, (engine.runState(h.tv.tvId).value as RunState.Paused).reason)
        engine.abandon(h.tv.tvId)   // disconnected: nothing can be put back yet, so the run stays paused
        h.settle(engine)
        assertIs<RunState.Paused>(engine.runState(h.tv.tvId).value)
        assertEquals(h.launcher, h.tv.home)
        h.tv.dropOnLine = null; h.tv.reconnect()
        engine.abandon(h.tv.tvId)
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.ABANDONED, fin.status)
        h.assertStockHomeIsBack(plan.sections.single().token.value)
        assertEquals("end:ABANDONED", h.types(plan).last())
    }

    // R1: a plan runs once; a second start of the same plan (same run token) is refused.
    @Test fun aPlanRunsOnce() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        openCheckScreen(h, engine, plan)
        engine.confirmHome(h.tv.tvId)
        h.settle(engine)
        assertIs<RunState.Finished>(engine.runState(h.tv.tvId).value)
        assertEquals(StartResult.Refused(StartRefusal.RUN_OPEN), engine.start(plan, h.tv, plan.hash, h.pass()))
        val again = h.setupPlan()
        assertTrue(again.id != plan.id)
    }
}
