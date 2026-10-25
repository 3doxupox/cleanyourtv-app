package tv.cleanyour.core.flows

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.testing.fixtures.FakeThemeSource
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
