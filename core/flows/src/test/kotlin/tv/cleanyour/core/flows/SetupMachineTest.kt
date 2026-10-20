package tv.cleanyour.core.flows

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.discovery.ServiceRef
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.StartResult
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.model.WizardDraft
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.NarrationLine
import tv.cleanyour.core.engine.NarrationState
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.engine.SectionState
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.testing.fixtures.FakeTvSession
import tv.cleanyour.core.store.GateMethod
import tv.cleanyour.testing.fixtures.FakeCredentialGate
import tv.cleanyour.testing.fixtures.FakeEngine
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SetupMachineTest {

    private fun found(tv: FoundTv = miTvFound) = DiscoveryEvent.Found(tv)

    // Welcome to Found: discovery, Start, the port opening, the Allow dialog, the probe and the plan.
    private suspend fun kotlinx.coroutines.test.TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(found())
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
    }

    @Test fun welcomeListsTvsAndStopsLookingAfterEightSeconds() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        settle()
        assertEquals(1, h.discovery.activeCollectors)
        h.discovery.emit(found())
        settle()
        val w = assertIs<SetupState.Welcome>(m.state.value)
        assertEquals(listOf(HOST), w.found.map { it.host })
        assertTrue(w.looking)
        settle(8_000)
        assertFalse(assertIs<SetupState.Welcome>(m.state.value).looking)
    }

    @Test fun twoTvsOpenThePickerAndPickingOneOpensTheDoor() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        h.discovery.emit(found())
        h.discovery.emit(found(miTvFound.copy(host = "192.0.2.11", friendlyName = "Bedroom")))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        assertIs<SetupState.Picker>(m.state.value)
        m.dispatch(SetupIntent.PickTv("192.0.2.11"))
        settle()
        val d = assertIs<SetupState.OpenDoor>(m.state.value)
        assertEquals("192.0.2.11", d.tv.host)
        assertEquals(DoorPhase.STEPS, d.phase)
        assertEquals(Families.ATV, d.family)
        // F1: discovery stops once Welcome and the picker are left.
        assertEquals(0, h.discovery.activeCollectors)
    }

    @Test fun portOpeningConnectsAndTheAllowDialogShowsTheFingerprint() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        m.effects.test {
            h.discovery.emit(found())
            settle()
            m.dispatch(SetupIntent.Start)
            settle()
            h.discovery.open5555[HOST] = true
            settle(2_100)
            assertEquals(SetupEffect.HapticDouble, drain().first())
            val d = assertIs<SetupState.OpenDoor>(m.state.value)
            assertEquals(DoorPhase.CONNECTED, d.phase)
            assertEquals("AB:CD:7F:2A", d.fingerprint)
            settle(700)
            assertIs<SetupState.Found>(m.state.value)
        }
        assertEquals("7F:2A", fingerprintTail("ab:cd:7f:2a"))
    }

    @Test fun noAllowDialogOnANewTvAsksIsThisYourTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        h.discovery.emit(found()); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertEquals(DoorPhase.IDENTITY, assertIs<SetupState.OpenDoor>(m.state.value).phase)
        assertEquals(Samples.miTvFacts().serialLast4, assertNotNull(m.extras.value.identity).serialLast4)
        m.dispatch(SetupIntent.ConfirmIdentity)
        settle(700)
        assertIs<SetupState.Found>(m.state.value)
    }

    @Test fun probeSavesTheBaselineAndRecordsTheTvWithItsDraft() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val f = assertIs<SetupState.Found>(m.state.value)
        assertEquals(h.setupPlan.id, f.plan.id)
        assertEquals(Level.SIMPLE, f.level)
        assertTrue("snapshot BASELINE" in h.prober.calls)
        assertNotNull(h.snapshots.baseline(f.tvId))
        val tv = h.tvs.tvs.value.single()
        assertEquals("Mi TV 4A", tv.nickname)
        assertEquals(3, tv.wizard?.step)
        assertEquals(DebugState.ON, tv.debugging)
        assertEquals("xiaomi-mitv-axso0", tv.profileId)
        val setup = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertTrue(setup.choices.installLauncher)
        assertEquals("com.netflix.ninja", setup.choices.dock?.single()?.value)
    }

    // The dock sheet's rows (S3b): the draft keeps one ordered list, the dock then Row 2 and Row 3,
    // cut at three rows of the look's columns; the plan and the sheet get the same list.
    @Test fun aDockWithRowsIsOneListCutAtThreeRows() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val apps = List(14) { PackageName("com.example.app$it") }
        m.dispatch(SetupIntent.SetDock(apps + apps.first()))
        settle()
        val last = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertEquals(apps.take(12), last.choices.dock)
        assertEquals(apps.take(12), h.tvs.tvs.value.single().wizard?.dock)
        assertEquals(apps.take(12), m.extras.value.dock.map { it.pkg })
        assertEquals(4, m.extras.value.dockColumns)
    }

    @Test fun keepingAPackageReplansAndPersistsTheDraft() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.Keep(promoPkg, keep = true))
        settle()
        val last = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertEquals(setOf(promoPkg), last.choices.kept)
        assertEquals(setOf(promoPkg), h.tvs.tvs.value.single().wizard?.kept)
        m.dispatch(SetupIntent.Keep(promoPkg, keep = false))
        settle()
        assertEquals(emptySet(), h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.kept)
    }

    @Test fun customiseEntersTheCustomLevel() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.Open(Sheet.CUSTOMISE))
        settle()
        val f = assertIs<SetupState.Found>(m.state.value)
        assertEquals(Sheet.CUSTOMISE, f.sheet)
        assertEquals(Level.CUSTOM, f.level)
        assertEquals(Level.CUSTOM, h.tvs.tvs.value.single().level)
    }

    @Test fun makeItCleanPromptsOnceAndStartsWithAPassForThisTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { emit(it.tvId, RunState.Running(it, 0, listOf(NarrationLine("Restore point saved", NarrationState.DOING)), 10, null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.effects.test {
            m.dispatch(SetupIntent.MakeItClean)
            settle()
            assertEquals(listOf(SetupEffect.RequestCredential, SetupEffect.RequestNotificationPermission), drain().takeLast(2))
        }
        assertEquals(1, h.credential.prompts.size)
        assertEquals("GATE_SUBTITLE{tv=Mi TV 4A}", h.credential.prompts.single().subtitle)
        val start = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().single()
        assertEquals(StartResult.Started, start.result)
        assertEquals(h.setupPlan.hash, start.confirmed)
        val w = assertIs<SetupState.Working>(m.state.value)
        assertEquals(10, w.run.percent)
        assertEquals(4, h.tvs.tvs.value.single().wizard?.step)
        assertEquals(listOf(h.setupPlan.id), h.ledger.runs(w.tvId).map { it.planId })
    }

    @Test fun cancelledPromptStartsNothing() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.credential.mode = FakeCredentialGate.Mode.FAIL
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean)
        settle()
        assertTrue(h.engine.calls.isEmpty())
        assertIs<SetupState.Found>(m.state.value)
        assertFalse(m.extras.value.starting)
    }

    @Test fun noScreenLockRunsWithANonePassAndShowsTheGreyLine() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.credential.mode = FakeCredentialGate.Mode.NO_SCREEN_LOCK
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean)
        settle()
        val start = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().first()
        assertEquals(GateMethod.NONE, start.pass?.method)
        assertTrue(m.extras.value.noScreenLock)
    }

    @Test fun theCheckScreenBuzzesFirmAndYesConfirmsHome() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        h.engine.onStart = { p -> emit(p.tvId, RunState.AwaitingConfirm(p, p.sections.single(), Samples.NOW_MS + 90_000, null, null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.effects.test {
            m.dispatch(SetupIntent.MakeItClean)
            settle()
            val effects = drain()
            assertTrue(SetupEffect.HapticFirm in effects)
            assertTrue(SetupEffect.Notify("NOTIFY_LOOK") in effects)
        }
        val c = assertIs<SetupState.Check>(m.state.value)
        assertEquals(Samples.NOW_MS + 90_000, c.run.deadlineMs)
        m.dispatch(SetupIntent.YesISeeIt)
        settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.ConfirmHome })
        // The engine reports the confirmed section and the run ends: Done, home on.
        h.engine.finish(c.run.plan, RunStatus.COMPLETED, HomeOutcome.CONFIRMED)
        settle()
        val d = assertIs<SetupState.Done>(m.state.value)
        assertTrue(d.summary.homeScreenOn)
        assertEquals(1, d.summary.switchedOff)
        assertTrue(d.summary.restartRecommended)   // the profile's reboot_tested is false (D24)
        assertNull(h.tvs.tvs.value.single().wizard)
        // D44: the restore point plan ran after the setup plan.
        assertTrue(h.planner.requests.any { it is PlanRequest.WriteRestorePoint })
    }

    @Test fun noPutItBackEndsOnTheUserSaidNoCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        h.engine.onStart = { p -> emit(p.tvId, RunState.AwaitingConfirm(p, p.sections.single(), Samples.NOW_MS + 90_000, null, null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        m.dispatch(SetupIntent.NoPutItBack); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.RejectHome })
        h.engine.finish(h.setupPlan, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.USER_SAID_NO)
        settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.HOME_USER_SAID_NO, s.card.case)
        assertTrue(s.card.primary.startsWith("STOP_HOME_USER_SAID_NO_PRIMARY"))
        // "Try again" plans from a fresh snapshot and starts once more.
        m.dispatch(SetupIntent.StopAction(StopAction.PRIMARY)); settle()
        assertEquals(2, h.engine.calls.count { it is FakeEngine.Call.Start })
        assertTrue(h.prober.calls.count { it == "snapshot ${SnapshotReason.MANUAL}" } >= 1)
    }

    @Test fun timeoutGivesTheTimedOutCardAndOkLeavesTheOldHomeScreen() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.TIMED_OUT) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StopCase.HOME_TIMED_OUT, assertIs<SetupState.Stopped>(m.state.value).card.case)
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertFalse(assertIs<SetupState.Done>(m.state.value).summary.homeScreenOn)
    }

    @Test fun connectionLostShowsCountsThenCarryOnContinuesWithThePass() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Paused(p, 1, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.CONNECTION_LOST, s.card.case)
        assertEquals("STOP_CONNECTION_LOST_BODY{done=0,total=2}", s.card.body)
        assertTrue(s.card.tertiary.orEmpty().startsWith("STOP_CONNECTION_LOST_TERTIARY"))
        assertEquals(ChipState.OFFLINE, m.extras.value.chip)
        settle()
        assertEquals(Reconnect.BACK, m.extras.value.reconnect)   // the fake session is still connected
        m.dispatch(SetupIntent.CarryOn); settle()
        val cont = h.engine.calls.filterIsInstance<FakeEngine.Call.ContinueRun>().single()
        assertNotNull(cont.pass)
        assertNull(cont.refused)
        assertEquals(1, h.credential.prompts.size)   // one prompt per TV session (D17)
    }

    @Test fun stopAndUndoStopsThenUndoesOnThePause() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Running(p, 0, emptyList(), 5, null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        assertIs<SetupState.Found>(m.state.value, "found before Make it clean")
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Working>(m.state.value, "after Make it clean: ${h.engine.calls} ${m.extras.value.starting} ${h.credential.prompts}")
        assertTrue(m.back(), "back is consumed on 4a")
        assertTrue(m.extras.value.highlightStop, "back only highlights Stop and undo")
        m.dispatch(SetupIntent.StopAndUndo); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Stop }, "engine.stop called")
        h.engine.emit(h.setupPlan.tvId, RunState.Paused(h.setupPlan, 1, PauseReason.USER_STOP, null))
        settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.UndoRun }, "undoRun after the user stop: ${h.engine.calls}")
        h.engine.finish(h.setupPlan, RunStatus.ROLLED_BACK)
        settle()
        assertEquals(StopCase.UNDONE, assertIs<SetupState.Stopped>(m.state.value).card.case)
    }

    @Test fun finishSwitchesDebuggingOffAndOpensManage() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        h.discovery.open5555[HOST] = false
        m.effects.test {
            m.dispatch(SetupIntent.Finish)
            settle(700)
            assertEquals(SetupEffect.Navigate("manage/${Samples.miTvId().value}"), drain().last())
        }
        assertTrue(h.planner.requests.any { it == PlanRequest.DebugOff })
        val debugOff = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().last()
        assertNull(debugOff.pass)   // SESSION_END needs no pass (R0: 6)
        assertEquals(DebugState.OFF, h.tvs.tvs.value.single().debugging)
        assertEquals(Finishing.DONE, m.extras.value.finishing)
    }

    // R5: a STALE pause leaves the run open; the machine ends it so the fresh plan can start.
    @Test fun aStaleRunIsEndedSoTheFreshPlanCanStart() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val first = h.setupPlan
        val second = h.plan(listOf(Op.Disable(adPkg)), id = "01FAKEPLAN0000000000000002")
        h.engine.onStart = { p ->
            if (p.id == first.id) { h.setupPlan = second; emit(p.tvId, RunState.Paused(p, 0, PauseReason.STALE, null)) }
            else finish(p, RunStatus.COMPLETED)
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon }, "the stale run is ended")
        assertEquals(second.id, assertIs<SetupState.Found>(m.state.value).plan.id)
        assertTrue(m.extras.value.staleNotice)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StartResult.Started, h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().last().result)
        assertIs<SetupState.Done>(m.state.value)
    }

    // R5: a start refused RUN_OPEN shows the open run instead of leaving the screen on Found.
    @Test fun anOpenRunIsShownWhenTheStartIsRefused() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val open = h.plan(listOf(Op.Disable(promoPkg)), id = "01FAKEPLAN0000000000000009")
        h.engine.emit(open.tvId, RunState.Paused(open, 1, PauseReason.USER_STOP, null))
        m.dispatch(SetupIntent.MakeItClean); settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.RESUME_AFTER_DEATH, s.card.case)
        assertEquals(open.id, s.card.planId)
        assertFalse(m.extras.value.starting)
    }

    // R5, D7: the 10 minute background rule only switches debugging off where a write ran.
    @Test fun tenMinutesInTheBackgroundSwitchesDebuggingOffOnlyAfterAWrite() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        h.sessionEnd.watch(backgroundScope)
        toFound(h, m)
        h.foreground.set(false); settle(10 * 60_000L + 100)
        assertFalse(h.planner.requests.any { it == PlanRequest.DebugOff }, "a probe-only session never sends debug_off")
        h.foreground.set(true); settle()
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        h.discovery.open5555[HOST] = false
        h.foreground.set(false); settle(10 * 60_000L + 100)
        assertEquals(1, h.planner.requests.count { it == PlanRequest.DebugOff })
        assertNull(h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().last().pass)
        assertEquals(DebugState.OFF, h.tvs.tvs.value.single().debugging)
    }

    // R5, D7: Finish only says "done" when the probe confirmed debugging is off.
    @Test fun finishDoesNotClaimDebuggingOffWhenThePortStaysOpen() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        val before = h.discovery.probes.size
        m.effects.test {
            m.dispatch(SetupIntent.Finish)
            settle(3_100)
            assertEquals(SetupEffect.Navigate("manage/${Samples.miTvId().value}"), drain().last())
        }
        assertEquals(3, h.discovery.probes.size - before, "three probes a second apart before recording on")
        assertEquals(DebugState.ON, h.tvs.tvs.value.single().debugging)
        assertTrue(m.extras.value.finishing != Finishing.DONE)
        assertTrue(m.extras.value.chip != ChipState.DEBUGGING_OFF)
    }

    // RFC-CURTAIN-1: replan hands the bundled curtain to the planner; Custom's switch turns it off and
    // on again, the draft keeps the choice; a pin that refuses its asset plans without it.
    @Test fun theTvCurtainIsHandedInAndTheCustomSwitchTurnsItOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val sha = tv.cleanyour.core.model.Sha256("e".repeat(64))
        val curtain = tv.cleanyour.core.model.LauncherArtifact(PackageName("tv.cleanyour.curtain"), 1, "0.1", sha, Samples.zeroHash,
            tv.cleanyour.core.model.BlobRef(sha, 49_571, "bundled:cleanyourtv-curtain.apk"))
        val m = SetupMachine(h.setupDeps(curtainPin = object : tv.cleanyour.core.trust.CurtainPin { override fun bundled() = curtain }), backgroundScope)
        toFound(h, m)
        fun last() = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertEquals(curtain, last().curtain)
        assertTrue(last().choices.curtain)
        m.dispatch(SetupIntent.Open(Sheet.CUSTOMISE)); settle()
        m.dispatch(SetupIntent.ShowOnTv(false)); settle()
        assertNull(last().curtain)
        assertFalse(last().choices.curtain)
        assertFalse(h.tvs.tvs.value.single().wizard!!.curtain, "the draft keeps the choice")
        m.dispatch(SetupIntent.ShowOnTv(true)); settle()
        assertEquals(curtain, last().curtain)

        val h2 = Harness()
        h2.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val refusing = SetupMachine(h2.setupDeps(curtainPin = object : tv.cleanyour.core.trust.CurtainPin { override fun bundled() = error("does not match its pin") }), backgroundScope)
        toFound(h2, refusing)
        assertNull(h2.planner.requests.filterIsInstance<PlanRequest.Setup>().last().curtain)
        assertIs<SetupState.Found>(refusing.state.value)
    }

    @Test fun keepDebuggingOnSkipsDebugOffAtFinish() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.KeepDebuggingOn(true)); settle()
        assertTrue(h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.keepDebuggingOn)
        m.dispatch(SetupIntent.MakeItClean); settle()
        m.dispatch(SetupIntent.Finish); settle(700)
        assertFalse(h.planner.requests.any { it == PlanRequest.DebugOff })
    }

    @Test fun pairingOnlyTvGoesToThePairingDeadEnd() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        h.discovery.emit(found(miTvFound.copy(port5555Open = false, connectService = ServiceRef("adb-tls", HOST, 37000))))
        settle()
        m.dispatch(SetupIntent.Start); settle()
        assertIs<SetupState.PairingNeeded>(m.state.value)
        assertTrue(m.back())
        assertIs<SetupState.Welcome>(m.state.value)
    }

    @Test fun typedAddressMustBeAHomeNetworkAddress() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.Open(Sheet.ADDRESS)); settle()
        assertEquals(Sheet.ADDRESS, m.extras.value.sheet)
        m.dispatch(SetupIntent.EnterAddress("8.8.8.8")); settle()
        assertTrue(m.extras.value.addressError)
        assertIs<SetupState.Welcome>(m.state.value)
        m.dispatch(SetupIntent.EnterAddress("192.168.1.50")); settle()
        assertEquals("192.168.1.50", assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        assertNull(m.extras.value.sheet)
        assertTrue(isHomeAddress("10.0.0.2") && isHomeAddress("172.16.4.1") && isHomeAddress("169.254.1.1"))
        assertFalse(isHomeAddress("172.32.0.1") || isHomeAddress("192.168.1") || isHomeAddress("192.168.1.256"))
    }

    @Test fun startWithNoTvWaitsOnTheDoorAndAdoptsTheFirstTvFound() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.Start); settle()
        assertEquals("", assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        assertEquals(1, h.discovery.activeCollectors)
        settle(20_000)
        assertEquals(StopCase.TV_NOT_FOUND, m.extras.value.card?.case)
        h.discovery.emit(found()); settle()
        assertEquals(HOST, assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        assertNull(m.extras.value.card)
    }

    @Test fun waitingForTheAllowTapCountsTimeAndAskAgainRetries() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.EnterAddress("192.168.1.50")); settle()
        settle(3_000)
        assertTrue(assertIs<SetupState.OpenDoor>(m.state.value).waitedMs >= 3_000)
        m.dispatch(SetupIntent.ChangeFamily("Amazon")); settle()
        val d = assertIs<SetupState.OpenDoor>(m.state.value)
        assertEquals(Families.FIRE, d.family)
        assertEquals("BANNER_FIRE", d.banner)
    }

    @Test fun wrongTvAtARememberedAddressShowsTheIdentityCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val other = Samples.tv().copy(id = TvId("0123456789abcdef"))
        h.tvs.upsert(other)
        val m = h.setup(backgroundScope)
        h.discovery.emit(found()); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertEquals(StopCase.IDENTITY_CHANGED, m.extras.value.card?.case)
        assertTrue(h.session.calls.isEmpty())   // D37: no line sent to the wrong TV
    }

    @Test fun resumeStartShowsTheCountsAndResumesTheEngine() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.ledger.record(h.setupPlan)
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Resume(tv.id, h.setupPlan.id))
        settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.RESUME_AFTER_DEATH, s.card.case)
        assertEquals("STOP_RESUME_BODY{done=0,total=2,tv=Mi TV 4A}", s.card.body)
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Resume })
        h.engine.emit(tv.id, RunState.Paused(h.setupPlan, 1, PauseReason.IN_DOUBT, null))
        m.dispatch(SetupIntent.StopAction(StopAction.TERTIARY)); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
    }

    // R5, D7: after process death the journal shows writes ran, so the TV is ended like any other.
    @Test fun resumeAfterProcessDeathKeepsTheDebugOffPromise() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.ledger.record(h.setupPlan)
        h.journal.append(JournalRecord.PlanStart(h.setupPlan.id, tv.id, Samples.zeroHash, 2, h.setupPlan.hash, "0.1.0", 0))
        h.journal.append(JournalRecord.Begin(h.setupPlan.id, 0, Op.Disable(adPkg), listOf("fake"), null, 1))
        h.journal.append(JournalRecord.Done(h.setupPlan.id, 0, 0, true, 5))
        h.discovery.open5555[HOST] = true
        h.setup(backgroundScope, SetupStart.Resume(tv.id, h.setupPlan.id))
        settle()
        h.discovery.open5555[HOST] = false
        h.sessionEnd.endAll()
        assertTrue(h.planner.requests.any { it == PlanRequest.DebugOff })
    }

    // R5, D15: Done remembers the digest of the config.json the run pushed, so Manage can warn on drift.
    @Test fun doneRemembersThePushedConfigDigest() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        val digest = Sha256("ab".repeat(32))
        val push = Op.PushAsset(BlobRef(digest, 10, "blobs/config"), RemotePath(RemoteDir.OVERLAY, listOf("config.json")), digest)
        h.engine.onStart = { p ->
            h.journal.append(JournalRecord.Begin(p.id, 0, push, listOf("fake"), null, 1))
            h.journal.append(JournalRecord.Done(p.id, 0, 0, true, 5))
            finish(p, RunStatus.COMPLETED, HomeOutcome.CONFIRMED)
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        assertEquals(digest, h.tvs.tvs.value.single().launcher?.ourOverlayDigest)
    }

    // RFC-DOCK-1: replan encodes the dock (with its rows) and the look into the BlobStore, exactly as
    // Manage's ApplyTheme does, and hands both blobs to the Setup request; Done then finds the run's
    // config.json push and remembers its digest (D15).
    @Test fun replanSendsTheDockAndTheLookAsOneConfigJson() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val config = RemotePath(RemoteDir.OVERLAY, listOf("config.json"))
        h.planner.respond = { request, _ ->
            val r = request as PlanRequest.Setup
            val push = r.config?.let { Op.PushAsset(it, config, it.sha256) }
            PlanResult.Ok(h.plan(listOfNotNull(Op.Disable(adPkg), push), takeover = true))
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        fun last() = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        fun expected(doc: LauncherConfigDoc, dock: List<PackageName>) = LauncherConfigCodec.encode(DockLayers.applyTo(doc, dock))
        // The first plan: the picked dock on the launcher's own look.
        val first = expected(LauncherConfigDoc(), listOf(PackageName("com.netflix.ninja")))
        assertTrue(first.contentEquals(h.blobs.bytes(assertNotNull(last().config))))
        assertNull(last().wallpaper)
        // A look and a dock with a row under it: one config.json with both.
        val apps = List(6) { PackageName("com.example.app$it") }
        m.dispatch(SetupIntent.SetTheme("aurora")); settle()
        m.dispatch(SetupIntent.SetDock(apps)); settle()
        val aurora = h.themes.bundled().single { it.id == "aurora" }
        val bytes = expected(aurora.doc, apps)
        val sent = assertNotNull(last().config)
        assertTrue(bytes.contentEquals(h.blobs.bytes(sent)), "the blob is DockLayers' bytes for the dock and the look")
        assertEquals(sha256Of(bytes), sent.sha256)
        assertEquals(aurora.wallpaper, last().wallpaper)
        assertEquals(1, assertIs<tv.cleanyour.core.spec.DecodeResult.Ok>(LauncherConfigCodec.decode(bytes)).doc.rowsBelowDock)
        // The run pushes it; Done remembers what it pushed.
        h.engine.onStart = { p ->
            val i = p.steps.indexOfFirst { it.op is Op.PushAsset }
            h.journal.append(JournalRecord.Begin(p.id, i, p.steps[i].op, listOf("fake"), null, 1))
            h.journal.append(JournalRecord.Done(p.id, i, 0, true, 5))
            finish(p, RunStatus.COMPLETED, HomeOutcome.CONFIRMED)
        }
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        assertEquals(sha256Of(bytes), h.tvs.tvs.value.single().launcher?.ourOverlayDigest)
        assertEquals(sha256Of(bytes), h.tvs.tvs.value.single().launcher?.overlayDigest)
    }

    @Test fun startDestinationFollowsTheDisk() = runTest(timeout = 30.seconds) {
        val h = Harness()
        assertEquals(StartDestination.Welcome, resolveStart(emptyList(), h.journal))
        val tv = Samples.tv()
        assertEquals(StartDestination.Manage(tv.id), resolveStart(listOf(tv), h.journal))
        val draft = tv.copy(wizard = WizardDraft(3, Level.SIMPLE, emptySet(), emptySet(), null, null, false, null, 0))
        assertEquals(StartDestination.ContinueSetup(tv.id), resolveStart(listOf(draft), h.journal))
        h.journal.append(JournalRecord.PlanStart(h.setupPlan.id, tv.id, Samples.zeroHash, 2, h.setupPlan.hash, "0.1.0", 0))
        assertEquals(StartDestination.ResumeRun(tv.id, h.setupPlan.id), resolveStart(listOf(draft), h.journal))
    }

    @Test fun continueStartReconnectsToTheDraftTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv().copy(wizard = WizardDraft(3, Level.CUSTOM, setOf(promoPkg), emptySet(), null, null, false, null, 0))
        h.tvs.upsert(tv)
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Continue(tv.id))
        settle(); settle(700)
        val f = assertIs<SetupState.Found>(m.state.value)
        assertEquals(Level.CUSTOM, f.level)
        assertEquals(setOf(promoPkg), h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.kept)
    }

    // RFC-W8-1: "No" on the identity line is an intent; it closes the session and records nothing.
    @Test fun noOnTheIdentityLineClosesTheSessionAndRecordsNothing() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        h.discovery.emit(found()); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertEquals(DoorPhase.IDENTITY, assertIs<SetupState.OpenDoor>(m.state.value).phase)
        m.dispatch(SetupIntent.RejectIdentity); settle()
        assertIs<SetupState.Welcome>(m.state.value)
        assertTrue(FakeTvSession.Call.Close in h.session.calls)
        assertTrue(h.tvs.tvs.value.isEmpty())
        // Finding 1: "No" offers the address entry, and the same device is not offered again.
        assertEquals(Sheet.ADDRESS, m.extras.value.sheet)
        assertNull(m.extras.value.identity)
        h.discovery.emit(found()); settle()
        assertTrue(assertIs<SetupState.Welcome>(m.state.value).found.isEmpty())
        m.back(); settle()                                  // the sheet closes; Start waits for a TV
        m.dispatch(SetupIntent.Start); settle()
        assertEquals("", assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        h.discovery.emit(found()); settle()
        assertEquals("", assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
    }

    // RFC-W8-5: the restart test is a REBOOT plan through the runner (journaled, no prompt), never a
    // direct session call; things that came back on are switched off after it.
    @Test fun theRestartTestRunsARebootPlanWithoutAPrompt() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        val prompts = h.credential.prompts.size
        m.dispatch(SetupIntent.Restart); settle()
        assertTrue(h.planner.requests.any { it == PlanRequest.Reboot })
        val reboot = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().last()
        assertEquals(PlanKind.REBOOT, reboot.plan.kind)
        assertNull(reboot.pass)
        assertEquals(StartResult.Started, reboot.result)
        assertEquals(prompts, h.credential.prompts.size)
        assertTrue(h.session.calls.none { it == FakeTvSession.Call.Reboot })
        assertIs<RestartState.Back>(assertIs<SetupState.Done>(m.state.value).restartState)
    }

    // RFC-W8-5: a boot wait that runs out leaves the reboot step in doubt and the run open; the
    // machine ends it (nothing to undo) so later writes are not refused, and shows the 5 minute card.
    @Test fun aRestartThatDoesNotComeBackEndsItsRunAndShowsTheCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        h.engine.onStart = { p ->
            if (p.kind == PlanKind.REBOOT) emit(p.tvId, RunState.Paused(p, 0, PauseReason.CONNECTION_LOST, AdbError.Transport.Timeout("boot")))
            else finish(p, RunStatus.COMPLETED)
        }
        m.dispatch(SetupIntent.Restart); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
        assertIs<RestartState.NotBack>(assertIs<SetupState.Done>(m.state.value).restartState)
        assertEquals(StopCase.NOT_BACK_AFTER_RESTART, m.extras.value.card?.case)
    }

    // R1 R-11: with the HOME section open and the TV away, the engine's abandon does nothing; the
    // card says to reconnect, and the abandon runs once the TV answers again.
    @Test fun stopHereWithTheHomeSectionOpenWaitsForTheTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        h.engine.onStart = { p ->
            h.journal.append(JournalRecord.PlanStart(p.id, p.tvId, Samples.zeroHash, p.steps.size, p.hash, "0.1.0", 0))
            h.journal.append(JournalRecord.Section(p.id, "home_takeover", RunToken("00000000000000aa"), SectionState.ARMED, 0))
            emit(p.tvId, RunState.Paused(p, 2, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed))
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StopCase.CONNECTION_LOST, assertIs<SetupState.Stopped>(m.state.value).card.case)
        val connected = h.session.state.value
        h.session.setState(ConnectionState.Idle)
        h.discovery.open5555[HOST] = false
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.Abandon })
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertTrue(s.card.body.startsWith("STOP_RECONNECT_HOME_BODY"), s.card.body)
        h.session.setState(connected)
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
    }

    // W3 D-W3-2: step R rebuilt from the journal ends the run ABANDONED with no steps of its own
    // and home CONNECTION_LOST; the card says the old home screen is back.
    @Test fun stepRFromTheJournalEndsOnTheOldHomeScreenIsBackCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.ledger.record(h.setupPlan)
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Resume(tv.id, h.setupPlan.id))
        settle()
        h.engine.emit(tv.id, RunState.Finished(h.setupPlan.copy(steps = emptyList()), RunStatus.ABANDONED, emptyList(), HomeOutcome.CONNECTION_LOST))
        settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.HOME_PUT_BACK, s.card.case)
        assertEquals("STOP_HOME_PUT_BACK_TITLE{tv=Mi TV 4A}", s.card.title)
        assertEquals("STOP_RESUME_HOME_BODY{tv=Mi TV 4A}", s.card.body)
    }

    // R5-W8 O2: the resume card for an open Manage write does not say "setting up", and the run
    // ends in Manage, not on the wizard's Done.
    @Test fun theResumeCardForAManageWriteSaysChangeAndEndsInManage() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        val put = h.plan(listOf(Op.Enable(adPkg)), kind = PlanKind.PUT_BACK, id = "01FAKEPUTBACK0000000000001")
        h.ledger.record(put)
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Resume(tv.id, put.id))
        settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals("STOP_RESUME_CHANGE_TITLE{done=0,total=0,tv=Mi TV 4A}", s.card.title)
        assertTrue(s.card.body.startsWith("STOP_RESUME_CHANGE_BODY"), s.card.body)
        h.engine.emit(tv.id, RunState.Paused(put, 0, PauseReason.IN_DOUBT, null)); settle()
        m.effects.test {
            h.engine.finish(put, RunStatus.COMPLETED); settle()
            assertEquals(SetupEffect.Navigate("manage/${tv.id.value}"), drain().last())
        }
        assertFalse(m.state.value is SetupState.Done)
    }

    // Bug 2 of the emulator run: an address typed on the "No TV found yet" card never probed port
    // 5555, because the placeholder door and the typed door are the same state class and phase.
    @Test fun anAddressTypedFromTheNotFoundCardProbesItsPortAndConnects() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.register(TYPED, h.session)
        h.sessions.allowPromptFor[TYPED] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.Start); settle()
        settle(20_000)
        assertEquals(StopCase.TV_NOT_FOUND, m.extras.value.card?.case)
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertEquals(Sheet.ADDRESS, m.extras.value.sheet)
        h.discovery.open5555[TYPED] = true
        m.dispatch(SetupIntent.EnterAddress(TYPED)); settle()
        assertTrue(TYPED in h.discovery.probes)
        assertEquals(0, h.discovery.activeCollectors)       // a typed door stops looking
        assertNull(m.extras.value.card)
        settle(700)
        assertIs<SetupState.Found>(m.state.value)
        assertEquals(listOf<tv.cleanyour.core.model.Endpoint>(tv.cleanyour.core.model.Endpoint.PlainTcp(TYPED)), h.sessions.opens)
    }

    @Test fun anAddressTypedOnWelcomeProbesItsPortAndConnects() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.register(TYPED, h.session)
        h.sessions.allowPromptFor[TYPED] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        h.discovery.emit(found()); settle()                  // one TV found, and still the address is offered
        m.dispatch(SetupIntent.Open(Sheet.ADDRESS)); settle()
        assertEquals(Sheet.ADDRESS, m.extras.value.sheet)
        m.dispatch(SetupIntent.EnterAddress(TYPED)); settle()
        assertEquals(TYPED, assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        settle(4_100)
        assertTrue(h.discovery.probes.count { it == TYPED } >= 2)   // waiting: the poll runs
        h.discovery.open5555[TYPED] = true
        settle(2_100)
        settle(700)
        assertIs<SetupState.Found>(m.state.value)
        assertTrue(HOST !in h.discovery.probes)
    }

    @Test fun typingAnotherAddressWhileWaitingMovesThePollToIt() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.register(TYPED, h.session)
        h.sessions.allowPromptFor[TYPED] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        val wrong = "192.168.1.40"
        m.dispatch(SetupIntent.EnterAddress(wrong)); settle()
        settle(4_100)
        assertTrue(h.discovery.probes.count { it == wrong } >= 2)
        m.dispatch(SetupIntent.Open(Sheet.ADDRESS)); settle()
        h.discovery.open5555[TYPED] = true
        m.dispatch(SetupIntent.EnterAddress(TYPED)); settle()
        val wrongProbes = h.discovery.probes.count { it == wrong }
        assertTrue(TYPED in h.discovery.probes)
        settle(4_100)
        assertEquals(wrongProbes, h.discovery.probes.count { it == wrong })   // the old poll stopped
        assertIs<SetupState.Found>(m.state.value)
        assertEquals(listOf<tv.cleanyour.core.model.Endpoint>(tv.cleanyour.core.model.Endpoint.PlainTcp(TYPED)), h.sessions.opens)
    }

    // Finding 3: the dock sheet offers launchable apps only, never a home screen or a provider.
    @Test fun theDockSheetOffersOnlyLaunchableApps() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val netflix = tv.cleanyour.core.model.PackageName("com.netflix.ninja")
        val calendar = tv.cleanyour.core.model.PackageName("com.android.providers.calendar")
        val stock = stockComponent.pkg
        fun st(system: Boolean, enabled: tv.cleanyour.core.model.EnabledState = tv.cleanyour.core.model.EnabledState.ENABLED) =
            tv.cleanyour.core.model.PackageState(enabled, system, 1, null, false, false, null, false)
        val vlc = tv.cleanyour.core.model.PackageName("org.videolan.vlc")
        val gboard = tv.cleanyour.core.model.PackageName("com.google.android.inputmethod.latin")
        val packages = mapOf(netflix to st(true), calendar to st(true), stock to st(true), vlc to st(false), gboard to st(true), adPkg to st(true))
        h.prober.snapshotFor = { Samples.snapshot(packages = packages, reason = it) }
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.launchable = setOf(netflix, stock, calendar, vlc)
        val m = h.setup(backgroundScope)
        toFound(h, m)
        assertEquals(listOf(netflix, vlc).sortedBy { it.value }, m.extras.value.apps.map { it.pkg }.sortedBy { it.value })
        // The TV could not say: user-installed apps and known streaming apps only.
        assertEquals(setOf(vlc), DockCandidates.of(Samples.snapshot(packages = packages), null, miProfile, h.facts))
        assertTrue(DockCandidates.isProvider(calendar) && !DockCandidates.isProvider(netflix))
    }

    // CytLog: the wizard writes redacted milestones for "Share diagnostics", never an address,
    // a serial or a package name.
    @Test fun theWizardLogsItsMilestonesWithoutAddressesSerialsOrPackages() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { finish(it, RunStatus.COMPLETED, null) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val log = h.logLines.joinToString("\n")
        for (want in listOf("setup: connect start", "setup: connected", "setup: probe start", "setup: probe done", "setup: plan ready", "setup: run finished COMPLETED")) {
            assertTrue(h.logLines.any { it.startsWith(want) }, "missing $want in\n$log")
        }
        val serial = Samples.miTvFacts().serialLast4
        for (secret in listOf(HOST, serial, adPkg.value, promoPkg.value, "com.netflix.ninja", Samples.miTvId().value)) assertTrue(secret !in log, "$secret logged")
    }
}
