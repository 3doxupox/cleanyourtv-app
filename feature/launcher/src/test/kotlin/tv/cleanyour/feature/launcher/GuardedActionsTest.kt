package tv.cleanyour.feature.launcher

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Samples

// R5-W8 O1 as ruled: applying the dock and switching to the TV's own home screen drop a tap
// through another app's overlay; Cancel and the dock's up, down and remove buttons never do.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp-xhdpi")
class GuardedActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun useTheTvsOwnIsGuardedAndCancelIsNot() {
        val hits = mutableListOf<HomeScreenIntent>()
        compose.showColumn { StockPreviewContent(Samples.plan(emptyList())) { hits += it } }
        compose.tap("Use the TV's own", OBSCURED)
        compose.tap("Cancel", OBSCURED)
        assertEquals(listOf<HomeScreenIntent>(HomeScreenIntent.CancelPreview), hits)
    }

    @Test fun dockDoneIsGuardedAndEditingIsNot() {
        val netflix = AppChoice(PackageName("com.netflix.ninja"), "Netflix")
        val changes = mutableListOf<List<AppChoice>>()
        var done = 0
        compose.showColumn { DockContent(listOf(netflix), listOf(netflix), { changes += it }, { done++ }) }
        compose.tap("Done", OBSCURED)
        assertEquals(0, done)
        compose.tap(compose.onNodeWithContentDescription("Take Netflix out of the dock"), OBSCURED)
        assertEquals(listOf(emptyList<AppChoice>()), changes)
        compose.tap("Done", 0)
        assertEquals(1, done)
    }
}
