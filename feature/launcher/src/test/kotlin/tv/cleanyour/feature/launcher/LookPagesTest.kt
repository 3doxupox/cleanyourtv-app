package tv.cleanyour.feature.launcher

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.LookDraft
import tv.cleanyour.core.flows.LookEdit
import tv.cleanyour.core.flows.LookNote
import tv.cleanyour.core.flows.LookNoteKind
import tv.cleanyour.core.flows.WayBack
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.ui.design.component.LookEditorNav
import tv.cleanyour.ui.design.component.LookPage

// look2 Look sheet behaviour (spec 4, 12.1, W12): Back walks Advanced, More options, Look, then
// asks before leaving anything unsent; while it asks, only the question can be reached; every
// control has a label; the preview's description names the style, the picture and apps in a row;
// the flows' notes show once, with their action, and are acknowledged.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp-xhdpi")
class LookPagesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun sheet(draft: LookDraft) = HomeScreenState(tvName = "Mi TV 4A", dock = LookFixtures.dock4, pictures = LookFixtures.pictureOptions, look = draft)

    @Test fun backWalksUpThePagesThenAsksBeforeLeaving() {
        val nav = LookEditorNav()
        val hits = mutableListOf<HomeScreenIntent>()
        compose.showColumn { LookContent(sheet(LookFixtures.draft(changed = true, wayBack = WayBack.UNDO, previews = false)), { hits += it }, nav = nav) }
        compose.runOnIdle { nav.open(LookPage.ADVANCED) }
        compose.onNodeWithContentDescription("Back to More options").assertIsDisplayed()
        assertFalse(compose.runOnIdle { nav.back() })
        compose.waitForIdle()
        assertEquals(LookPage.MORE, nav.page)
        compose.onNodeWithContentDescription("Back to Look").performClick()
        compose.waitForIdle()
        assertEquals(LookPage.MAIN, nav.page)
        assertFalse(compose.runOnIdle { nav.back() })
        compose.waitForIdle()
        assertTrue(nav.asking)
        compose.onNodeWithText("Leave without sending?").assertIsDisplayed()
        compose.onNodeWithText("Your changes aren't on the TV yet.").assertIsDisplayed()
        // While it asks, the sheet behind cannot be reached: one button to send, none shown.
        compose.onAllNodesWithText("Put it on the TV").assertCountEquals(0)
        compose.onNodeWithText("Keep choosing").performClick()
        compose.waitForIdle()
        assertFalse(nav.asking)
        assertTrue(hits.isEmpty())
        // Close asks too, and Leave closes.
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Leave").performClick()
        compose.waitForIdle()
        assertEquals(listOf<HomeScreenIntent>(HomeScreenIntent.CloseLook), hits)
    }

    @Test fun nothingUnsentClosesAtOnce() {
        val nav = LookEditorNav()
        compose.showColumn { LookContent(sheet(LookFixtures.draft(previews = false)), {}, nav = nav) }
        assertTrue(compose.runOnIdle { nav.back() })
    }

    @Test fun aNewPhotoHasItsOwnWords() {
        val nav = LookEditorNav()
        compose.showColumn { LookContent(sheet(LookFixtures.draft(newPhoto = true, changed = true, previews = false)), {}, nav = nav) }
        assertFalse(compose.runOnIdle { nav.close() })
        compose.onNodeWithText("Your new photo isn't on the TV yet.").assertIsDisplayed()
    }

    @Test fun everyControlHasALabelAndThePreviewNamesTheLook() {
        val nav = LookEditorNav()
        compose.showColumn { LookContent(sheet(LookFixtures.draft(StyleId.GLASS, names = true)), {}, pictures = LookFixtures.pictureFiles, nav = nav) }
        compose.onNodeWithContentDescription("Preview of the TV's home screen: Glass, the Mist picture, 5 apps in a row, names under every app.")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("More options").assertIsDisplayed()
        for (page in LookPage.entries) {
            compose.runOnIdle { nav.open(page) }
            compose.waitForIdle()
            val unlabelled = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().filter { n ->
                val c = n.config
                c.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() && c.getOrNull(SemanticsProperties.Text).isNullOrEmpty()
            }
            assertEquals("unlabelled controls on $page", 0, unlabelled.size)
        }
    }

    @Test fun aNoteShowsOnceWithItsActionAndIsAcknowledged() {
        val hits = mutableListOf<HomeScreenIntent>()
        val note = LookNote(7, LookNoteKind.RESET, action = true)
        compose.showColumn { LookContent(sheet(LookFixtures.draft(note = note, previews = false)), { hits += it }) }
        compose.onNodeWithText("Options reset.").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(listOf<HomeScreenIntent>(HomeScreenIntent.Look(LookEdit.NoteAction(7)), HomeScreenIntent.Look(LookEdit.NoteSeen(7))), hits)
    }

    @Test fun aStyleChangeNamesWhatTheNewStyleHasOfItsOwn() {
        val note = LookNote(3, LookNoteKind.STYLE_CHANGED, StyleId.SPOTLIGHT,
            own = listOf(tv.cleanyour.core.flows.DetailWord.BUTTONS, tv.cleanyour.core.flows.DetailWord.GLOW),
            dropped = listOf(tv.cleanyour.core.flows.DetailWord.DOCK_BAND), action = true)
        compose.showColumn { LookContent(sheet(LookFixtures.draft(StyleId.SPOTLIGHT, note = note, previews = false)), {}) }
        compose.onNodeWithText("Spotlight has its own buttons and glow. It has no dock band choice.").assertIsDisplayed()
        compose.onNodeWithText("Keep mine").assertIsDisplayed()
    }
}
