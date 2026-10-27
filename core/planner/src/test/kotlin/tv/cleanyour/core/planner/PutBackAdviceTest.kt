package tv.cleanyour.core.planner

import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.planner.PlannerFixtures.change
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.spec.BundledProfileSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// WP4 item 2 (C6): the "Better back on" table and its offer rule.
class PutBackAdviceTest {
    private val mic = PackageName("com.google.android.tv.remote.service")
    private val g10 = "tcl-g10-google-tv"
    private fun tv(state: EnabledState?) = snapshot(extra = if (state == null) emptyMap() else
        mapOf(mic to PackageState(state, true, 1, null, false, false, "Remote service", false)))

    @Test fun theTableIsTheFiveReviewedEntries() {
        assertEquals(
            listOf("tcl-g10-google-tv" to "com.google.android.tv.remote.service", "tcl-g03-google-tv" to "com.tcl.xian.StartandroidService",
                "tcl-ak30a5" to "com.tcl.versionUpdateApp", "kalley-r10g" to "com.google.android.apps.tv.launcherx",
                "kalley-r10g" to "com.google.android.tungsten.setupwraith"),
            PutBackAdvice.entries.map { it.profileId to it.pkg.value },
        )
        // Every profile id is a shipped profile, and every reason is plain text.
        val shipped = BundledProfileSource.bundled.profiles.map { it.id }.toSet()
        for (e in PutBackAdvice.entries) {
            assertTrue(e.profileId in shipped, e.profileId)
            assertTrue(e.reason.endsWith(".") && '\u2014' !in e.reason && "-".repeat(2) !in e.reason, e.reason)
        }
    }

    @Test fun offeredOnlyWhenOurJournalSwitchedItOffAndItIsOffNow() {
        val ours = listOf(change(0, Op.Disable(mic), Prior(packageState = EnabledState.ENABLED)))
        assertEquals(listOf(mic), PutBackAdvice.offers(g10, tv(EnabledState.DISABLED_USER), ours).map { it.pkg })
        assertEquals(listOf(mic), PutBackAdvice.offers(g10, tv(EnabledState.DISABLED), ours).map { it.pkg })
        // The factory or the owner switched it off: no journal entry of ours.
        assertTrue(PutBackAdvice.offers(g10, tv(EnabledState.DISABLED_USER), emptyList()).isEmpty())
        // On now, or not on the TV, or no snapshot.
        assertTrue(PutBackAdvice.offers(g10, tv(EnabledState.ENABLED), ours).isEmpty())
        assertTrue(PutBackAdvice.offers(g10, tv(null), ours).isEmpty())
        assertTrue(PutBackAdvice.offers(g10, null, ours).isEmpty())
        // Another TV's profile, or none.
        assertTrue(PutBackAdvice.offers("tcl-g03-google-tv", tv(EnabledState.DISABLED_USER), ours).isEmpty())
        assertTrue(PutBackAdvice.offers(null, tv(EnabledState.DISABLED_USER), ours).isEmpty())
        // We put it back since, and the owner switched it off again: not ours any more.
        val since = ours + change(1, Op.Enable(mic), Prior(packageState = EnabledState.DISABLED_USER))
        assertTrue(PutBackAdvice.offers(g10, tv(EnabledState.DISABLED_USER), since).isEmpty())
        // We switched it off again after a put back: ours.
        assertEquals(1, PutBackAdvice.offers(g10, tv(EnabledState.DISABLED_USER), since + change(2, Op.Disable(mic), Prior(packageState = EnabledState.ENABLED))).size)
    }

    // The tap is the ordinary PutBack: exactly one step, the inverse of our switch-off (Enable, or
    // DefaultState for a package that was DEFAULT before us), with its prior. The remote mic service
    // is on the static denylist now, so the renderer refuses a DefaultState for it: it goes back on
    // with Enable, and an updater that is not protected keeps its DefaultState.
    @Test fun thePutBackIsExactlyOneEnableOrInverse() {
        val updater = PackageName("com.tcl.versionUpdateApp")
        val s = tv(EnabledState.DISABLED_USER).let { t ->
            t.copy(packages = t.packages + (updater to PackageState(EnabledState.DISABLED_USER, true, 1, null, false, false, null, false)))
                .let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
        }
        val cases = listOf(
            Triple(mic, EnabledState.ENABLED, Op.Enable(mic)), Triple(mic, EnabledState.DEFAULT, Op.Enable(mic)),
            Triple(updater, EnabledState.ENABLED, Op.Enable(updater)), Triple(updater, EnabledState.DEFAULT, Op.DefaultState(updater)),
        )
        for ((pkg, before, expected) in cases) {
            val journal = listOf(change(0, Op.Disable(pkg), Prior(packageState = before)))
            val p = plan(PlanRequest.PutBack(pkg), snapshot = s, journals = journal)
            assertEquals(listOf<Op>(expected), p.steps.map { it.op }, "${pkg.value} $before")
            assertEquals(EnabledState.DISABLED_USER, p.steps.single().prior?.packageState)
        }
    }
}
