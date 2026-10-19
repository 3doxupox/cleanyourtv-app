package tv.cleanyour.core.flows

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.ArtifactRef
import tv.cleanyour.core.model.ApkInfo
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Module
import tv.cleanyour.core.model.ModuleVersion
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PermissionStatement
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Tier
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.WizardDraft
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.registry.FetchFailure
import tv.cleanyour.core.registry.IndexState
import tv.cleanyour.core.trust.VerifiedIndex
import tv.cleanyour.testing.fixtures.FakeApkInspector
import tv.cleanyour.testing.fixtures.FakeArtifactFetcher
import tv.cleanyour.testing.fixtures.FakeCredentialGate
import tv.cleanyour.testing.fixtures.FakeEngine
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.testing.fixtures.sha256Of
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ManageMachinesTest {

    // The journal of a finished run that switched `pkgs` off, recorded the way the engine writes it.
    private suspend fun Harness.ranDisable(tv: Tv, pkgs: List<PackageName>, id: String = "01FAKERUN0000000000000001") {
        val plan = plan(pkgs.map { Op.Disable(it) }, id = id)
        ledger.record(plan)
        journal.append(JournalRecord.PlanStart(plan.id, tv.id, Samples.zeroHash, pkgs.size, plan.hash, "0.1.0", 0))
        pkgs.forEachIndexed { i, p ->
            journal.append(JournalRecord.Begin(plan.id, i, Op.Disable(p), listOf("fake"), null, 10L + i))
            journal.append(JournalRecord.Done(plan.id, i, 0, true, 5))
        }
        journal.append(JournalRecord.End(plan.id, RunStatus.COMPLETED))
    }

    private fun Harness.respond(kind: PlanKind, ops: List<Op>) {
        val previous = planner.respond
        planner.respond = { r, snap ->
            val k = when (r) { is PlanRequest.PutBack -> PlanKind.PUT_BACK; is PlanRequest.SwitchOff -> PlanKind.SWITCH_OFF
                is PlanRequest.Leftovers -> PlanKind.LEFTOVERS; is PlanRequest.UndoEverything -> PlanKind.UNDO_EVERYTHING
                is PlanRequest.AddModule -> PlanKind.ADD_MODULE; is PlanRequest.RemoveModule -> PlanKind.REMOVE_MODULE; else -> null }
            if (k == kind) PlanResult.Ok(plan(ops, kind = kind, id = "01FAKE" + kind.name.take(4) + "00000000000000001")) else previous(r, snap)
        }
    }

    @Test fun ledgerDerivesChangesAndWhatIsStillOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg, promoPkg))
        assertEquals(2, h.ledger.changes(tv.id).size)
        assertEquals(listOf(adPkg, promoPkg), h.ledger.switchedOffByUs(tv.id))
        val back = h.plan(listOf(Op.Enable(adPkg)), id = "01FAKERUN0000000000000002")
        h.ledger.record(back)
        h.journal.append(JournalRecord.Begin(back.id, 0, Op.Enable(adPkg), listOf("fake"), null, 99))
        h.journal.append(JournalRecord.Done(back.id, 0, 0, true, 5))
        assertEquals(listOf(promoPkg), h.ledger.switchedOffByUs(tv.id))
        val p = h.ledger.progress(PlanId("01FAKERUN0000000000000001"))
        assertEquals(2, p.doneDisables)
        assertTrue(p.ended)
    }

    @Test fun putBackRunsOnePlanWithTheGateAndOffersUndo() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        val m = SwitchedOffMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        assertEquals(listOf(adPkg), m.state.value.groups.flatMap { g -> g.rows.map { it.pkg } })
        m.effects.test {
            m.dispatch(SwitchedOffIntent.PutBack(adPkg))
            settle()
            val snack = assertIs<ManageEffect.Snackbar>(drain().single())
            assertEquals(SnackKind.PUT_BACK, snack.kind)
            assertTrue(snack.undo)
            assertEquals(adPkg, snack.pkg)
        }
        assertEquals(1, h.credential.prompts.size)
        assertTrue(h.planner.requests.any { it == PlanRequest.PutBack(adPkg) })
        assertTrue(h.planner.requests.any { it is PlanRequest.WriteRestorePoint })   // D44 after every plan
        assertNull(m.state.value.busy)
    }

    @Test fun putBackCancelledAtTheGateChangesNothing() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        h.credential.mode = FakeCredentialGate.Mode.FAIL
        val m = SwitchedOffMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(SwitchedOffIntent.PutBack(adPkg)); settle()
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.Start })
    }

    // R5, D7: a write cancelled at the gate is not a write, so the background rule leaves the TV
    // alone; closing the session after a real write (Forget this TV) switches debugging off first.
    @Test fun debuggingGoesOffOnlyAfterAWriteAndBeforeTheSessionCloses() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        h.credential.mode = FakeCredentialGate.Mode.FAIL
        h.sessionEnd.watch(backgroundScope)
        val m = SwitchedOffMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(SwitchedOffIntent.PutBack(adPkg)); settle()
        h.foreground.set(false); settle(10 * 60_000L + 100)
        assertFalse(h.planner.requests.any { it == PlanRequest.DebugOff }, "a cancelled gate is not a write")
        h.foreground.set(true); settle()
        h.credential.mode = FakeCredentialGate.Mode.PASS
        m.dispatch(SwitchedOffIntent.PutBack(adPkg)); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Start && it.plan.kind == PlanKind.PUT_BACK })
        val s = TvSettingsMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        s.dispatch(TvSettingsIntent.Forget); settle()
        assertTrue(h.planner.requests.any { it == PlanRequest.DebugOff }, "Forget closes the session, debug_off first")
        assertTrue(h.tvs.tvs.value.isEmpty())
    }

    @Test fun leftoversFindsWhatCameBackAndSwitchesItOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.LEFTOVERS, listOf(Op.Disable(adPkg)))
        val m = LeftoversMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        val found = assertIs<LeftoversState.Found>(m.state.value)
        assertEquals(listOf(adPkg.value), found.names)
        assertEquals(PlanRequest.Leftovers(setOf(adPkg)), h.planner.requests.filterIsInstance<PlanRequest.Leftovers>().first())
        m.dispatch(LeftoversIntent.SwitchOff); settle()
        assertEquals(LeftoversState.Done(1), m.state.value)
    }

    @Test fun leftoversWithNothingBack() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val m = LeftoversMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        assertEquals(LeftoversState.Nothing, m.state.value)
    }

    @Test fun undoEverythingPreviewsThenConfirmsThenRuns() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg, promoPkg))
        h.respond(PlanKind.UNDO_EVERYTHING, listOf(Op.Enable(promoPkg), Op.Enable(adPkg)))
        val m = RestorePointMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(RestorePointIntent.PreviewUndo); settle()
        val p = assertIs<RestorePhase.Preview>(m.state.value.phase)
        assertEquals(2, p.preview.thingsBack)
        assertFalse(p.confirming)
        m.dispatch(RestorePointIntent.PutEverythingBack); settle()   // not before the confirmation sheet
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.Start })
        m.dispatch(RestorePointIntent.Confirm); settle()
        assertTrue(assertIs<RestorePhase.Preview>(m.state.value.phase).confirming)
        m.dispatch(RestorePointIntent.PutEverythingBack); settle()
        assertIs<RestorePhase.Finished>(m.state.value.phase)
        val undo = h.planner.requests.filterIsInstance<PlanRequest.UndoEverything>().last()
        assertEquals(2, undo.changes.size)
        assertFalse(undo.removeLauncher)
    }

    @Test fun saveACopyAsksForADocumentAndWritesTheBundle() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val m = RestorePointMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.effects.test {
            m.dispatch(RestorePointIntent.SaveCopy); settle()
            val doc = assertIs<ManageEffect.CreateDocument>(drain().single())
            assertTrue(doc.suggestedName.startsWith("cleanyourtv-restore-") && doc.suggestedName.endsWith(".zip"))
        }
        val out = java.io.ByteArrayOutputStream()
        assertTrue(m.writeBundle(out))
        assertEquals(1, h.bundleWriter.written.size)
    }

    @Test fun forgetThisTvAsksForTheCredentialAndDeletesItsRecords() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        val m = TvSettingsMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(TvSettingsIntent.Rename("Lounge TV", "Lounge")); settle()
        assertEquals("Lounge TV", h.tvs.tvs.value.single().nickname)
        m.dispatch(TvSettingsIntent.SetLevel(Level.CUSTOM)); settle()
        assertEquals(Level.CUSTOM, h.tvs.tvs.value.single().level)
        m.dispatch(TvSettingsIntent.Forget); settle()
        assertEquals(1, h.credential.prompts.size)
        assertTrue(h.tvs.tvs.value.isEmpty())
        assertTrue(h.documents.paths().none { it.startsWith("tvs/${tv.id.value}") })
    }

    @Test fun resetMyKeyAlwaysPromptsAndClearsPasses() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.gate.remember(h.credential.mint(tv.id))
        val m = AppSettingsMachine(h.manageDeps(), backgroundScope)
        settle()
        assertEquals("TEE", m.state.value.keyBacking)
        m.dispatch(AppSettingsIntent.Ask(AppConfirm.RESET_KEY)); settle()
        assertEquals(AppConfirm.RESET_KEY, m.state.value.confirm)
        m.dispatch(AppSettingsIntent.ResetKey); settle()
        assertEquals(1, h.keyInfo.resets)
        assertEquals("GATE_SUBTITLE_RESET_KEY", h.credential.prompts.single().subtitle)
        assertNull(h.gate.cached(tv.id))
        m.dispatch(AppSettingsIntent.CheckUpdates); settle()
        assertEquals(UpdateCheck.Available("0.2.0"), m.state.value.update)
    }

    @Test fun manageShowsTheTvAndOpensSections() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        val m = ManageMachine(h.manageDeps(), backgroundScope, null)
        settle()
        val card = assertNotNull(m.state.value.current)
        assertEquals(1, card.switchedOff)
        assertTrue(card.clean)
        assertEquals(ChipState.CONNECTED, card.chip)
        m.effects.test {
            m.dispatch(ManageIntent.Open(ManageSection.SWITCHED_OFF)); settle()
            assertEquals(ManageEffect.Navigate("switchedoff/${tv.id.value}"), drain().single())
        }
    }

    @Test fun addModuleFetchesChecksTheSignerInstallsAndMirrorsTheKey() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val apk = "retro apk bytes".encodeToByteArray()
        val signer = sha256Of("signer".encodeToByteArray())
        val ref = ArtifactRef("https://github.com/x/retro.apk", apk.size.toLong(), sha256Of(apk), signer)
        val version = ModuleVersion(1, "0.1.0", "2026-10-03", ref, listOf(PermissionStatement("INTERNET", "Uses the internet")), "Fetches game files", 21, emptyList(), emptyList())
        val retro = Module("retro", "Retro", "NES games on the TV", "A card", PackageName("io.github.example.retro"), "card", Tier.COMMUNITY,
            "example", "https://github.com/example/retro", "MIT", null, null, listOf(version))
        h.registry.onRefresh = { IndexState.Ready(VerifiedIndex(1, Long.MAX_VALUE, listOf(retro), emptyList()), stale = false, fetchedAtMs = 0) }
        h.inspector = FakeApkInspector(mapOf(sha256Of(apk) to ApkInfo(retro.pkg, 1, "0.1.0", signer, sha256Of(apk), apk.size.toLong(), "test")))
        h.respond(PlanKind.ADD_MODULE, listOf(Op.Disable(adPkg)))
        val tv = h.knownTv()
        val m = ModulesMachine(h.manageDeps(FakeArtifactFetcher(h.blobs, mapOf(ref.url to apk))), backgroundScope, tv.id, pollMs = 1_000)
        settle()
        assertEquals(listOf("retro"), m.state.value.available.map { it.id })
        m.dispatch(ModulesIntent.Open("retro")); settle()
        assertEquals("${signer.hex.substring(0, 4)} ${signer.hex.substring(4, 8)} ${signer.hex.substring(8, 12)}", m.state.value.detail?.key12)
        m.dispatch(ModulesIntent.Add("retro")); settle()
        val asking = assertIs<InstallStep.Asking>(m.state.value.install)
        assertEquals(keyDigest12(signer), asking.key12)
        assertTrue(h.planner.requests.any { it is PlanRequest.AddModule })
        h.cardAnswer = CardAnswer.SHOWN
        settle(1_100)
        assertIs<InstallStep.Shown>(m.state.value.install)
    }

    @Test fun aReSignedApkNeverReachesThePlanner() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val apk = "retro apk bytes".encodeToByteArray()
        val signer = sha256Of("signer".encodeToByteArray())
        val ref = ArtifactRef("https://github.com/x/retro.apk", apk.size.toLong(), sha256Of(apk), signer)
        val version = ModuleVersion(1, "0.1.0", "2026-10-03", ref, emptyList(), "", 21, emptyList(), emptyList())
        val retro = Module("retro", "Retro", "NES", "A card", PackageName("io.github.example.retro"), "card", Tier.COMMUNITY,
            "example", "https://github.com/example/retro", "MIT", null, null, listOf(version))
        h.registry.onRefresh = { IndexState.Ready(VerifiedIndex(1, Long.MAX_VALUE, listOf(retro), emptyList()), stale = false, fetchedAtMs = 0) }
        val other = sha256Of("someone else".encodeToByteArray())
        h.inspector = FakeApkInspector(mapOf(sha256Of(apk) to ApkInfo(retro.pkg, 1, "0.1.0", other, sha256Of(apk), apk.size.toLong(), "test")))
        val tv = h.knownTv()
        val m = ModulesMachine(h.manageDeps(FakeArtifactFetcher(h.blobs, mapOf(ref.url to apk))), backgroundScope, tv.id)
        settle()
        m.dispatch(ModulesIntent.Add("retro")); settle()
        assertEquals(FetchFailure.SIGNER_MISMATCH, assertIs<InstallStep.Failed>(m.state.value.install).fetch)
        assertTrue(h.planner.requests.none { it is PlanRequest.AddModule })
    }

    @Test fun gateKeeperCachesForTenMinutesPerTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val id = Samples.miTvId()
        assertNotNull(h.gate.pass(id, "Mi TV 4A"))
        assertNotNull(h.gate.pass(id, "Mi TV 4A"))
        assertEquals(1, h.credential.prompts.size)
        h.clock.advance(10 * 60_000 + 1)
        assertNull(h.gate.cached(id))
        assertNotNull(h.gate.pass(id, "Mi TV 4A"))
        assertEquals(2, h.credential.prompts.size)
    }

    // RFC-W8-4: the app-level pass ("Reset my key", "Delete everything") is never cached.
    @Test fun theAppLevelPassIsNeverCached() = runTest(timeout = 30.seconds) {
        val h = Harness()
        assertNotNull(h.gate.passWithSubtitle(APP_GATE_ID, "Reset my key", forcePrompt = true))
        assertNull(h.gate.cached(APP_GATE_ID))
        assertNotNull(h.gate.passWithSubtitle(APP_GATE_ID, "Delete everything"))
        assertEquals(2, h.credential.prompts.size)
        assertNull(h.gate.cached(APP_GATE_ID))
    }

    @Test fun tvAccessShowsTheDebuggingStepUntilThePortOpens() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        var result: AccessResult? = null
        backgroundScope.launchAccess(h, tv) { result = it }
        settle()
        assertIs<Reach.NeedsDebugging>(h.access.reach.value)
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertIs<AccessResult.Ok>(result)
        assertEquals(Reach.Idle, h.access.reach.value)
    }

    @Test fun tvJsonRoundTrips() {
        val record = Samples.tv().copy(wizard = WizardDraft(3, Level.CUSTOM, setOf(adPkg), setOf(promoPkg), listOf(promoPkg), "aurora", true, PlanId("p1"), 5))
        assertEquals(record, TvJson.decode(TvJson.encode(record)))
        assertEquals("0000 0000 0000", keyDigest12(Samples.zeroHash))
    }

    @Test fun operationNoticesFollowTheRun() {
        val plan = Harness().plan(listOf(Op.Disable(adPkg)), takeover = true)
        val running = tv.cleanyour.core.engine.RunState.Running(plan, 1, emptyList(), 58, null)
        assertEquals("NOTIFY_PROGRESS{percent=58,tv=Mi TV 4A}", OperationNotices.of(plan.tvId, "Mi TV 4A", running, KeyCopy)?.text)
        assertTrue(OperationNotices.needsForeground(plan))
        assertFalse(OperationNotices.needsForeground(Harness().plan(listOf(Op.Enable(adPkg)))))
    }

    // R5-W8 O2: a Manage write paused by a lost connection shows the open-run card, not the generic
    // "failed" snackbar; the next write on that TV shows the same card without a new plan or prompt;
    // "Carry on" continues the run with the pass and the card goes once it finishes.
    @Test fun aPausedWriteShowsTheOpenRunCardAndCarryOnFinishesIt() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg, promoPkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        h.engine.onStart = { p -> if (p.kind == PlanKind.PUT_BACK) emit(p.tvId, RunState.Paused(p, 0, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed)) else finish(p, RunStatus.COMPLETED) }
        val d = h.manageDeps()
        val m = SwitchedOffMachine(d, backgroundScope, tv.id)
        settle()
        m.effects.test {
            m.dispatch(SwitchedOffIntent.PutBack(adPkg)); settle()
            assertTrue(drain().none { it is ManageEffect.Snackbar }, "no generic failed snackbar")
            val card = assertNotNull(d.openWrites.current.value)
            assertEquals(PlanKind.PUT_BACK, card.kind)
            assertEquals(PauseReason.CONNECTION_LOST, card.reason)
            assertEquals(tv.nickname, card.tvName)
            val starts = h.engine.calls.count { it is FakeEngine.Call.Start }
            d.openWrites.dismiss()
            m.dispatch(SwitchedOffIntent.PutBack(promoPkg)); settle()
            assertTrue(drain().none { it is ManageEffect.Snackbar })
            assertEquals(starts, h.engine.calls.count { it is FakeEngine.Call.Start }, "no new run while one is open")
            assertNotNull(d.openWrites.current.value, "the card is back for the next write")
        }
        val paused = h.engine.runState(tv.id).value as RunState.Paused
        var result: WriteResult? = null
        backgroundScope.launch { result = TvWriter(d).resolveOpen(tv.id, OpenWriteAction.CARRY_ON) }
        settle()
        val cont = h.engine.calls.filterIsInstance<FakeEngine.Call.ContinueRun>().single()
        assertNotNull(cont.pass)
        assertNull(cont.refused)
        assertEquals(1, h.credential.prompts.size)   // one prompt for the TV (D17)
        assertTrue(assertNotNull(d.openWrites.current.value).busy)
        h.engine.emit(tv.id, RunState.Running(paused.plan, 0, emptyList(), 50, null)); settle()
        h.engine.finish(paused.plan, RunStatus.COMPLETED); settle()
        assertIs<WriteResult.Done>(result)
        assertNull(d.openWrites.current.value)
        assertTrue(h.planner.requests.any { it is PlanRequest.WriteRestorePoint })   // D44 once the run is over
    }

    // O2: "Undo what we did" undoes the open run; "Stop here" ends it; an action the engine does
    // not take (the session went away again) leaves the card with the reconnect line.
    @Test fun theOpenRunCardUndoesStopsAndSaysWhenTheTvDidNotAnswer() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        h.engine.onStart = { p -> if (p.kind == PlanKind.PUT_BACK) emit(p.tvId, RunState.Paused(p, 0, PauseReason.STEP_FAILED, null)) else finish(p, RunStatus.COMPLETED) }
        val d = h.manageDeps()
        assertIs<WriteResult.Paused>(TvWriter(d).write(tv.id, PlanRequest.PutBack(adPkg)))
        val paused = h.engine.runState(tv.id).value as RunState.Paused
        var result: WriteResult? = null
        backgroundScope.launch { result = TvWriter(d).resolveOpen(tv.id, OpenWriteAction.UNDO) }
        settle(TvWriter.MOVE_WAIT_MS + 100)
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.UndoRun })
        assertIs<WriteResult.Paused>(result)
        assertTrue(assertNotNull(d.openWrites.current.value).notTaken)
        assertEquals(paused, h.engine.runState(tv.id).value)
        result = null
        backgroundScope.launch { result = TvWriter(d).resolveOpen(tv.id, OpenWriteAction.STOP_HERE) }
        settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
        assertEquals(RunStatus.ABANDONED, assertIs<WriteResult.Done>(result).finished.status)
        assertNull(d.openWrites.current.value)
    }

    // O2 after process death: the engine has not loaded the open run, so start says RUN_OPEN; the
    // writer resumes it (safety only) and shows its card instead of the failed snackbar.
    @Test fun aRunOpenRefusalLoadsTheRunThroughResumeAndShowsItsCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        val open = h.plan(listOf(Op.Disable(promoPkg)), kind = PlanKind.LEFTOVERS, id = "01FAKEOPENRUN000000000001")
        h.engine.emit(tv.id, RunState.Running(open, 0, emptyList(), 0, null))   // the engine says RUN_OPEN
        val d = h.manageDeps()
        backgroundScope.launch { TvWriter(d).write(tv.id, PlanRequest.PutBack(adPkg)) }
        settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Resume })
        h.engine.emit(tv.id, RunState.Paused(open, 0, PauseReason.IN_DOUBT, null))
        assertNotNull(TvWriter(d).openRun(tv, h.session))
        assertEquals(PlanKind.LEFTOVERS, assertNotNull(d.openWrites.current.value).kind)
    }

    // W3 R-12: the typed name is trimmed before it is kept.
    @Test fun renameTrimsTheTypedName() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val m = TvSettingsMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(TvSettingsIntent.Rename("  Lounge TV  ", "  Lounge ")); settle()
        assertEquals("Lounge TV", h.tvs.tvs.value.single().nickname)
        assertEquals("Lounge", h.tvs.tvs.value.single().room)
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchAccess(h: Harness, tv: Tv, done: (AccessResult) -> Unit) =
    launch { done(h.access.session(tv.id)) }
