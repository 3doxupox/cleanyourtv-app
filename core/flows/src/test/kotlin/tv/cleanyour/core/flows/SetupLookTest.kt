package tv.cleanyour.core.flows

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.WizardDraft
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.spec.BundledThemeSource
import tv.cleanyour.core.spec.DecodeResult
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.testing.fixtures.sha256Of
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.4.0 (RFC-LOOK-SETUP-1): Setup's look step has the Look sheet's choices (apps in a row, the
// look, the picture, a new photo), sends the same bytes the Look sheet would for the same choice,
// keeps what it sent for Manage, and a Setup run again starts from the saved choice.
class SetupLookTest {
    private val looks = BundledThemeSource()
    private val glass = looks.pictures().single { it.id == "glass" }
    private val apps = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar",
        "com.spotify.tv.android", "com.plexapp.android", "org.videolan.vlc").map(::PackageName)
    private val config = RemotePath(RemoteDir.OVERLAY, listOf("config.json"))

    // Welcome to Found with the bundled looks, a plan that pushes the look's config.json, and a run
    // that completes and journals that push (so Done keeps what it sent).
    private suspend fun TestScope.found(h: Harness): SetupMachine {
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val on = PackageState(EnabledState.ENABLED, false, 0, null, false, false, null, false)
        h.prober.snapshotFor = { r -> Samples.snapshot(reason = r, packages = Samples.miTvPackages() + apps.associateWith { on }) }
        val previous = h.planner.respond
        h.planner.respond = { request, snap ->
            if (request is PlanRequest.Setup) PlanResult.Ok(h.plan(listOfNotNull(Op.Disable(adPkg), request.config?.let { Op.PushAsset(it, config, it.sha256) }), takeover = true))
            else previous(request, snap)
        }
        val m = SetupMachine(h.setupDeps(themes = looks), backgroundScope)
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
        assertIs<SetupState.Found>(m.state.value)
        return m
    }

    // "Make it clean": a run that completes and journals the config.json push, so Done keeps what it sent.
    private fun TestScope.makeItClean(h: Harness, m: SetupMachine) {
        h.engine.onStart = { p ->
            val i = p.steps.indexOfFirst { it.op is Op.PushAsset }
            if (i >= 0) {
                h.journal.append(JournalRecord.Begin(p.id, i, p.steps[i].op, listOf("fake"), null, 1))
                h.journal.append(JournalRecord.Done(p.id, i, 0, true, 5))
            }
            finish(p, RunStatus.COMPLETED, if (p.sections.isNotEmpty()) HomeOutcome.CONFIRMED else null)
        }
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
    }

    private fun Harness.lastSetup() = planner.requests.filterIsInstance<PlanRequest.Setup>().last()
    private fun Harness.sentDoc(): LauncherConfigDoc = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(blobs.bytes(assertNotNull(lastSetup().config)))).doc

    @Test fun appsInARowAndThePictureOnlyChangeTheDraftAndALookTapResetsTheCount() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = found(h)
        assertEquals("default", m.extras.value.look?.themeId)
        m.dispatch(SetupIntent.SetDock(apps)); settle()
        m.dispatch(SetupIntent.SetTheme("console")); settle()
        assertEquals(7, m.extras.value.look?.columns, "Console's own")
        assertNull(m.extras.value.draft?.columns)
        m.dispatch(SetupIntent.SelectColumns(6)); settle()
        assertEquals(6, m.extras.value.draft?.columns)
        assertEquals(6, m.extras.value.look?.columns)
        assertEquals(7, m.extras.value.look?.lookColumns)
        assertEquals(6, m.extras.value.dockColumns, "the dock sheet holds 6 a row")
        assertEquals(6, h.sentDoc().columns)
        assertEquals(1, h.sentDoc().rowsBelowDock, "7 apps at 6 in a row: the dock and one row")
        m.dispatch(SetupIntent.SelectColumns(9)); settle()
        assertEquals(6, m.extras.value.draft?.columns, "9 is not offered")
        m.dispatch(SetupIntent.SelectColumns(7)); settle()
        assertNull(m.extras.value.draft?.columns, "the look's own is not kept")
        m.dispatch(SetupIntent.SelectColumns(5)); settle()
        m.dispatch(SetupIntent.SelectPicture("glass")); settle()
        assertEquals("glass", m.extras.value.draft?.picture)
        assertEquals("glass", m.extras.value.look?.picture)
        assertEquals(glass.wallpaper, h.lastSetup().wallpaper)
        assertEquals(true, h.sentDoc().wallpaper)
        assertEquals(5, h.sentDoc().columns)
        // A look tap resets apps in a row to that look's own; the picture stays.
        m.dispatch(SetupIntent.SetTheme("mist")); settle()
        assertNull(m.extras.value.draft?.columns)
        assertEquals("glass", m.extras.value.draft?.picture)
        assertEquals(m.extras.value.look?.lookColumns, m.extras.value.look?.columns)
        assertEquals(glass.wallpaper, h.lastSetup().wallpaper)
        m.dispatch(SetupIntent.SelectPicture(LookPicture.NONE_ID)); settle()
        assertEquals(false, h.sentDoc().wallpaper)
        assertNull(h.lastSetup().wallpaper)
        m.dispatch(SetupIntent.SelectPicture(null)); settle()
        assertEquals(looks.bundled().single { it.id == "mist" }.wallpaper, h.lastSetup().wallpaper, "the look's own")
        // The draft is saved with the TV, so a Setup continued later has the same choice.
        assertEquals(m.extras.value.draft, h.tvs.tvs.value.single().wizard)
    }

    // The bytes Setup sends equal what the Look sheet sends for the same choice, and after Setup the
    // Look sheet opens on that choice.
    @Test fun setupSendsTheLookSheetsBytesAndTheLookSheetOpensOnTheSameChoice() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = found(h)
        m.dispatch(SetupIntent.SetDock(apps)); settle()
        m.dispatch(SetupIntent.SetTheme("console")); settle()
        m.dispatch(SetupIntent.SelectColumns(6)); settle()
        m.dispatch(SetupIntent.SelectPicture("glass")); settle()
        val setup = h.lastSetup()
        val setupBytes = h.blobs.bytes(assertNotNull(setup.config))
        // The emulator check pushes these bytes the way FileOverlayTransport does (LookBytesDumpTest).
        java.io.File("build/look-bytes/setup-console-6-glass").apply {
            deleteRecursively(); mkdirs()
            java.io.File(this, "config.json").writeBytes(setupBytes)
            java.io.File(this, "wallpaper.jpg").writeBytes(assertNotNull(BundledThemeSource.bytes(assertNotNull(setup.wallpaper))))
        }
        makeItClean(h, m)
        val tv = h.tvs.tvs.value.single()
        assertEquals(sha256Of(setupBytes), tv.launcher?.ourOverlayDigest)
        assertEquals(HomeChoice(apps, "console", cards = false, picture = "glass", columns = 6), HomePrefs(h.documents).load(tv.id))

        val home = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec, themes = looks), backgroundScope, tv.id)
        settle()
        assertEquals(6, home.state.value.columns)
        home.dispatch(HomeScreenIntent.OpenLook); settle()
        val draft = assertNotNull(home.state.value.look)
        assertEquals("console", draft.themeId)
        assertEquals(6, draft.columns)
        assertEquals("glass", draft.picture)
        assertEquals(m.extras.value.look?.copy(preview = null), draft.copy(preview = null), "the same draft as Setup's look step")
        home.dispatch(HomeScreenIntent.UseLook); settle()
        val push = h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last()
        assertContentEquals(setupBytes, h.blobs.bytes(push.config), "the same config.json")
        assertEquals(setup.wallpaper, push.wallpaper, "the same wallpaper.jpg")
    }

    // A photo made in the look step is sent with its dim when Setup runs, and kept for the TV (as
    // "Restore mine" with that dim) only once the TV has it.
    @Test fun aPhotoMadeInSetupIsSentThenKeptForTheTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = found(h)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(2000) { it.toByte() }
        m.dispatch(SetupIntent.SetOwnPicture(jpeg, 40)); settle()
        assertEquals(LookPicture.NEW_ID, m.extras.value.draft?.picture)
        assertEquals(sha256Of(jpeg), m.extras.value.newPicture?.sha256)
        assertEquals(40, m.extras.value.newDim)
        assertEquals(sha256Of(jpeg), h.lastSetup().wallpaper?.sha256)
        assertEquals(40, h.sentDoc().wallpaperDim)
        val id = h.tvs.tvs.value.single().id
        assertNull(h.documents.read(OwnPicture.path(id)) { it }, "nothing kept before the TV has it")
        makeItClean(h, m)
        assertContentEquals(jpeg, h.documents.read(OwnPicture.path(id)) { it })
        val saved = assertNotNull(HomePrefs(h.documents).load(id))
        assertEquals(LookPicture.MINE_ID, saved.picture)
        assertEquals(40, saved.dim)
    }

    // Running Setup again starts from the saved choice (the look, its apps in a row and picture), so
    // it no longer clears the saved count.
    @Test fun setupAgainStartsFromTheSavedChoice() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.tvs.upsert(Samples.tv())
        HomePrefs(h.documents).save(Samples.miTvId(), HomeChoice(apps, "console", cards = false, picture = "glass", columns = 6))
        val m = found(h)
        val draft = assertNotNull(m.extras.value.draft)
        assertEquals("console", draft.themeId)
        assertEquals(6, draft.columns)
        assertEquals("glass", draft.picture)
        assertEquals(6, h.sentDoc().columns)
        assertEquals(glass.wallpaper, h.lastSetup().wallpaper)
        assertTrue(m.extras.value.look?.let { it.columns == 6 && it.picture == "glass" } == true)
    }

    // The draft's look choice survives a restart with the TV record.
    @Test fun theDraftKeepsItsLookChoiceOnDisk() {
        val tv = Samples.tv().copy(wizard = WizardDraft(3, Level.SIMPLE, emptySet(), emptySet(), apps, "console", false, null, 5, columns = 6, picture = "glass"))
        assertEquals(tv, TvJson.decode(TvJson.encode(tv)))
        val old = TvJson.encode(tv.copy(wizard = tv.wizard?.copy(columns = null, picture = null))).decodeToString()
        assertEquals(tv.wizard?.copy(columns = null, picture = null), TvJson.decode(old.replace(",\"columns\":null,\"picture\":null", "").encodeToByteArray()).wizard,
            "a draft written before 0.4.0 still loads")
    }
}
