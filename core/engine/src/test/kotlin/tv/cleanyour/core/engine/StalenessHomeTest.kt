package tv.cleanyour.core.engine

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.Step
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals

// WP4 item 4 (B5): the planner records the stock home as the SetHome prior while HOME answers with
// the chooser, a placeholder or nothing (StockHome.recorded). The staleness check before step 0
// then takes that same answer as no change, and still sees a real home screen that took over.
class StalenessHomeTest {
    private val stock = Samples.stockLauncher
    private val launcher = Samples.launcherComponent
    private val step = Step(0, Op.SetHome(launcher), ShellRenderer().render(Op.SetHome(launcher)), Prior(home = stock), "home", "", FailurePolicy.STOP_AND_ROLLBACK)
    private fun home(resolved: Component?) = Samples.miTvSnapshot().let { it.copy(home = HomeResolution(resolved, it.home.claimants)) }

    @Test fun aPlaceholderAnswerIsNoChangeAndARealOneIs() {
        for (placeholder in listOf(
            Component(PackageName("android"), "com.android.internal.app.ResolverActivity"),
            Component(PackageName("com.android.tv.settings"), ".system.FallbackHome"),
            Component(PackageName("com.google.android.tungsten.setupwraith"), ".RecoveryActivity"),
            null,
        )) assertEquals(emptyList(), Staleness.diff(listOf(step), home(placeholder)), "${placeholder?.flat}")
        assertEquals(emptyList(), Staleness.diff(listOf(step), home(stock)))
        val other = Component(PackageName("com.spocky.projengmenu"), ".ui.home.MainActivity")
        assertEquals(listOf("home: was ${stock.flat}, now ${other.flat}"), Staleness.diff(listOf(step), home(other)))
    }
}
