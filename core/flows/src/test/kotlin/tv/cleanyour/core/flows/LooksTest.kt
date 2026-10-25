package tv.cleanyour.core.flows

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.Picture
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.testing.fixtures.FakeThemeSource
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

// 0.4.0 looks: a dated look (Diwali, until 2026-11-10) keeps the look the person had before it, is
// offered up to its last day, and afterwards the phone counts the earlier look as the TV's.
class LooksTest {
    private val lastDay = LocalDate.of(2026, 11, 10)
    private fun ms(day: LocalDate) = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val dock = listOf(PackageName("com.netflix.ninja"), PackageName("com.google.android.youtube.tv"))

    @Test fun aDatedLookKeepsTheLookBeforeIt() {
        val cinema = HomeChoice(dock, "cinema")
        val diwali = cinema.withTheme("diwali")
        assertEquals("cinema", diwali.previousThemeId)
        assertEquals("cinema", diwali.withTheme("diwali").previousThemeId, "picked again: still the one before")
        assertNull(diwali.withTheme("console").previousThemeId, "an undated look forgets it")
        assertNull(HomeChoice(dock, null).withTheme("diwali").previousThemeId, "Default before it")
        assertEquals("diwali", diwali.lookOn(lastDay))
        assertNull(diwali.endedLook(lastDay))
        assertEquals("cinema", diwali.lookOn(lastDay.plusDays(1)))
        assertEquals("diwali", diwali.endedLook(lastDay.plusDays(1)))
        assertEquals("cinema", cinema.lookOn(lastDay.plusYears(1)))
    }

    @Test fun homeTxtKeepsTheEarlierLookAndOldFilesStillLoad() = runTest {
        val h = Harness()
        val prefs = HomePrefs(h.documents)
        val id = h.knownTv().id
        val choice = HomeChoice(dock, "diwali", cards = false, previousThemeId = "horizon")
        prefs.save(id, choice)
        assertEquals(choice, prefs.load(id))
        h.documents.write("tvs/${id.value}/home.txt", "dock=com.netflix.ninja\ntheme=aurora\n".encodeToByteArray())
        assertEquals(HomeChoice(dock.take(1), "aurora"), prefs.load(id))
    }

    private val themes = FakeThemeSource(listOf(
        Theme("cinema", "Cinema", "Curtains", LauncherConfigDoc(columns = 5, cinematicHome = true), null),
        Theme("diwali", "Diwali", "Lamps", LauncherConfigDoc(columns = 5, festival = "diwali", festivalUntil = "2026-11-10"), null),
    ))

    @Test fun afterItsLastDayTheEarlierLookIsTheTvsAndOneTapPutsItBack() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
        h.tvs.upsert(tv)
        h.snapshots.save(Samples.snapshot(takenAtMs = Samples.NOW_MS + 1))
        val deps = h.manageDeps(encoder = ConfigEncoder.Codec, themes = themes)
        HomePrefs(h.documents).save(tv.id, HomeChoice(dock, "cinema", cards = false))

        h.clock.setWall(ms(lastDay))
        val m = HomeScreenMachine(deps, backgroundScope, tv.id)
        settle()
        assertEquals(listOf("cinema", "diwali"), m.state.value.themes.map { it.id })
        m.dispatch(HomeScreenIntent.SetTheme("diwali")); settle()
        assertEquals("diwali", h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last().doc.festival)
        assertEquals(HomeChoice(dock, "diwali", cards = false, previousThemeId = "cinema"), HomePrefs(h.documents).load(tv.id))
        assertNull(m.state.value.endedLook)

        h.clock.setWall(ms(lastDay.plusDays(1)))
        val later = HomeScreenMachine(deps, backgroundScope, tv.id)
        settle()
        assertEquals(listOf("cinema"), later.state.value.themes.map { it.id }, "no longer offered")
        assertEquals("cinema", later.state.value.themeId)
        assertEquals("Diwali", later.state.value.endedLook)
        val pushes = h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().size
        later.dispatch(HomeScreenIntent.SetTheme("diwali")); settle()
        assertEquals(pushes, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().size, "an ended look is never pushed")
        later.dispatch(HomeScreenIntent.RestoreMine); settle()
        val back = h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last().doc
        assertEquals(true, back.cinematicHome)
        assertNull(back.festival)
        assertEquals(HomeChoice(dock, "cinema", cards = false), HomePrefs(h.documents).load(tv.id))
        assertNull(later.state.value.endedLook)
    }

    private val glass = Picture("glass", "Glass", BlobRef(Samples.zeroHash, 1000, "/blobs/glass.jpg"))
    private val ownPicture = BlobRef(tv.cleanyour.testing.fixtures.sha256Of("cinema".encodeToByteArray()), 2000, "/blobs/cinema.jpg")
    private val gallery = FakeThemeSource(listOf(
        Theme("quiet", "Quiet black", "Black", LauncherConfigDoc(wallpaper = false, wallpaperDim = 0, wallpaperRotationMinutes = 0, reducedMotion = true), null),
        Theme("cinema", "Cinema", "Curtains", LauncherConfigDoc(wallpaper = true, wallpaperDim = 0, wallpaperRotationMinutes = 0, cinematicHome = true), ownPicture),
        Theme("diwali", "Diwali", "Lamps", LauncherConfigDoc(wallpaper = true, festival = "diwali", festivalUntil = "2026-11-10"), ownPicture),
    ), listOf(glass))

    // A picture over a look that shows none (Quiet black) forces wallpaper on, or it would be
    // pushed and not shown; "No picture" turns it off and sends no wallpaper.jpg; a dated look
    // keeps its own whatever was chosen.
    @Test fun theEncoderForcesWallpaperOnWhenAPictureIsChosen() {
        val quiet = gallery.bundled()[0]
        val (doc, wallpaper) = Look.docOf(quiet, dock, cards = false, LookPicture.of("glass", gallery))
        assertEquals(true, doc.wallpaper)
        assertEquals(0, doc.wallpaperRotationMinutes)
        assertEquals(glass.wallpaper, wallpaper)
        assertEquals(true, doc.reducedMotion, "the rest of the look stays")
        val cinema = gallery.bundled()[1]
        val (none, noFile) = Look.docOf(cinema, dock, cards = false, LookPicture.of(LookPicture.NONE_ID, gallery))
        assertEquals(false, none.wallpaper)
        assertNull(noFile)
        assertEquals(cinema.doc.copy(dockApps = dock, rowsBelowDock = 0, moduleCards = false) to ownPicture,
            Look.docOf(cinema, dock, cards = false, LookPicture.of(null, gallery)), "the look's own")
        assertEquals(ownPicture, Look.docOf(cinema, dock, cards = false, LookPicture.of("gone", gallery)).second, "a picture no longer there: the look's own")
        val (festive, festiveFile) = Look.docOf(gallery.bundled()[2], dock, cards = false, LookPicture.of("glass", gallery))
        assertEquals(true, festive.wallpaper)
        assertEquals(ownPicture, festiveFile)
    }

    // The Look sheet only chooses; "Use this look" sends the look and the picture as one ApplyTheme,
    // and both are kept for the next push (a new dock keeps the picture).
    @Test fun theLookSheetSendsNothingUntilUseThisLook() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
        h.tvs.upsert(tv)
        h.snapshots.save(Samples.snapshot(takenAtMs = Samples.NOW_MS + 1))
        val deps = h.manageDeps(encoder = ConfigEncoder.Codec, themes = gallery)
        assertEquals(gallery, deps.pictures)
        HomePrefs(h.documents).save(tv.id, HomeChoice(dock, "cinema", cards = false))
        h.clock.setWall(ms(lastDay))
        val m = HomeScreenMachine(deps, backgroundScope, tv.id)
        settle()
        assertEquals(listOf("glass"), m.state.value.pictures.map { it.id })
        m.dispatch(HomeScreenIntent.OpenLook); settle()
        assertEquals(LookDraft("cinema", null), m.state.value.look)
        m.dispatch(HomeScreenIntent.SelectLook("quiet")); settle()
        m.dispatch(HomeScreenIntent.SelectPicture("glass")); settle()
        assertEquals(LookDraft("quiet", "glass"), m.state.value.look)
        m.dispatch(HomeScreenIntent.SelectLook("diwali")); settle()
        assertEquals(LookDraft("diwali", null, ownPictureOnly = true), m.state.value.look, "a festival keeps its own picture")
        m.dispatch(HomeScreenIntent.SelectPicture("glass")); settle()
        assertNull(m.state.value.look?.picture)
        m.dispatch(HomeScreenIntent.SelectLook("quiet")); settle()
        m.dispatch(HomeScreenIntent.SelectPicture("glass")); settle()
        assertEquals(0, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().size, "nothing sent while choosing")
        m.dispatch(HomeScreenIntent.UseLook); settle()
        assertNull(m.state.value.look)
        val sent = h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().single()
        assertEquals(true, sent.doc.wallpaper)
        assertEquals(true, sent.doc.reducedMotion)
        assertEquals(glass.wallpaper, sent.wallpaper)
        val kept = assertNotNull(HomePrefs(h.documents).load(tv.id))
        assertEquals("quiet", kept.themeId); assertEquals("glass", kept.picture)
        assertEquals("glass", m.state.value.pictureId)
        // A new dock later keeps the look and its picture.
        m.dispatch(HomeScreenIntent.SetDock(dock.reversed())); settle()
        assertEquals(glass.wallpaper, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last().wallpaper)
        // Closing the sheet sends nothing.
        m.dispatch(HomeScreenIntent.OpenLook); settle()
        assertEquals(LookDraft("quiet", "glass"), m.state.value.look)
        m.dispatch(HomeScreenIntent.SelectLook("cinema")); settle()
        m.dispatch(HomeScreenIntent.CloseLook); settle()
        assertEquals(2, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().size)
        assertNull(m.state.value.look)
    }

    @Test fun homeTxtKeepsThePicture() = runTest {
        val h = Harness()
        val id = h.knownTv().id
        val choice = HomeChoice(dock, "quiet", cards = true, picture = "glass")
        HomePrefs(h.documents).save(id, choice)
        assertEquals(choice, HomePrefs(h.documents).load(id))
    }
}
