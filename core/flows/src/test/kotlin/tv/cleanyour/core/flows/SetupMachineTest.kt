package tv.cleanyour.core.flows

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.discovery.ServiceRef
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.StartResult
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.model.WizardDraft
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.NarrationLine
import tv.cleanyour.core.engine.NarrationState
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.planner.PlanRequest
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
        m.stopAction(StopAction.PRIMARY); settle()
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
        m.stopAction(StopAction.SECONDARY); settle()
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
        m.stopAction(StopAction.TERTIARY); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
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
}
