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
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.LookDraft
import tv.cleanyour.core.flows.ThemeOption
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.component.DockApp
import tv.cleanyour.ui.design.component.DockEditorState

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

    @Test fun updateIsGuardedAndCancelIsNot() {
        val hits = mutableListOf<HomeScreenIntent>()
        compose.showColumn { UpdatePreviewContent(Samples.plan(emptyList()), "2.4") { hits += it } }
        compose.tap("Update", OBSCURED)
        compose.tap("Cancel", OBSCURED)
        assertEquals(listOf<HomeScreenIntent>(HomeScreenIntent.CancelPreview), hits)
        compose.tap("Update", 0)
        assertEquals(listOf(HomeScreenIntent.CancelPreview, HomeScreenIntent.UpdateHomeScreen), hits)
    }

    @Test fun dockDoneIsGuardedAndEditingIsNot() {
        val netflix = AppChoice(PackageName("com.netflix.ninja"), "Netflix")
        val state = DockEditorState(listOf(netflix.toDockApp()), 4)
        var done = 0
        compose.showColumn { DockContent(state, listOf(netflix), { done++ }) }
        compose.tap("Done", OBSCURED)
        assertEquals(0, done)
        compose.tap(compose.onNodeWithContentDescription("Take Netflix out"), OBSCURED)
        assertEquals(emptyList<DockApp>(), state.apps)
        compose.tap("Done", 0)
        assertEquals(1, done)
    }

    // 0.4.0 Look sheet: a tap on an Apps in a row segment only chooses (SelectColumns, also through
    // an overlay, like any choice), and "Put it on the TV" is still the one guarded write.
    @Test fun appsInARowOnlyChoosesAndPutItOnTheTvIsGuarded() {
        val hits = mutableListOf<HomeScreenIntent>()
        val state = HomeScreenState(themes = listOf(ThemeOption("default", "Default", "Black")), look = LookDraft("default", null, columns = 4, lookColumns = 4))
        compose.showColumn { LookContent(state, { hits += it }) }
        compose.tap(compose.onNodeWithContentDescription("5 apps in a row"), 0)
        compose.tap(compose.onNodeWithContentDescription("8 apps in a row, more apps"), OBSCURED)
        assertEquals(listOf<HomeScreenIntent>(HomeScreenIntent.SelectColumns(5), HomeScreenIntent.SelectColumns(8)), hits)
        compose.tap("Put it on the TV", OBSCURED)
        assertEquals(2, hits.size)
        compose.tap("Put it on the TV", 0)
        assertEquals(HomeScreenIntent.UseLook, hits.last())
        assertEquals(1, hits.count { it == HomeScreenIntent.UseLook })
    }
}
