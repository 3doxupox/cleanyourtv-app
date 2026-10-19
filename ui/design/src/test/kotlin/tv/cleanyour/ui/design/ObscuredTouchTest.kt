package tv.cleanyour.ui.design

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.ui.design.component.CountdownConfirm
import tv.cleanyour.ui.design.component.CytIconButton
import tv.cleanyour.ui.design.component.DestructiveButton
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.SecondaryButton
import tv.cleanyour.ui.design.component.SmallGlassButton
import tv.cleanyour.ui.design.component.StopCard
import tv.cleanyour.ui.design.component.TertiaryButton
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.theme.CytTheme

// SECURITY 4.3 and P8, spec 7.3 item 6, R5-W8 O1 as ruled: a tap that reaches the window through
// another app's overlay never fires a confirming action (primary, destructive, the small card
// action, the CountdownConfirm "Yes"), shows the hint, and never blocks Back, Cancel, the
// secondary or tertiary buttons. Real MotionEvents with the window flags are sent through the
// activity's decor view, the path a touch takes on a device.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class ObscuredTouchTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val hint get() = compose.activity.getString(R.string.cyt_obscured_hint)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { CytTheme(reducedMotion = true) { Column { content() } } }
        compose.waitForIdle()
    }

    // One tap (down, up) at the centre of the node, with the given MotionEvent flags.
    private fun dispatch(node: SemanticsNodeInteraction, flags: Int) {
        val b = node.fetchSemanticsNode().boundsInWindow
        val x = (b.left + b.right) / 2
        val y = (b.top + b.bottom) / 2
        val decor = compose.activity.window.decorView
        val t = SystemClock.uptimeMillis()
        compose.runOnUiThread {
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
                val e = event(t, t + i * 50L, action, x, y, flags)
                assertEquals(flags, e.flags and flags)
                decor.dispatchTouchEvent(e)
                e.recycle()
            }
        }
    }

    private fun tap(node: SemanticsNodeInteraction, flags: Int) {
        dispatch(node, flags)
        compose.waitForIdle()
    }

    private fun tap(text: String, flags: Int) = tap(compose.onNodeWithText(text), flags)

    private fun event(down: Long, at: Long, action: Int, x: Float, y: Float, flags: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        return MotionEvent.obtain(down, at, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
    }

    @Test
    fun anObscuredTapDoesNotFireThePrimaryAndAClearTapDoes() {
        var clicks = 0
        show { PrimaryButton("Make it clean", { clicks++ }) }
        tap("Make it clean", MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(0, clicks)
        tap("Make it clean", MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        assertEquals(0, clicks)
        tap("Make it clean", 0)
        assertEquals(1, clicks)
    }

    @Test
    fun destructiveSmallActionAndStopCardPrimaryAreGuarded() {
        var clicks = 0
        show {
            DestructiveButton("Put everything back", { clicks++ })
            SmallGlassButton("Restart", { clicks++ })
            StopCard("The TV stopped answering", "Usually it's back in a few seconds.", primaryLabel = "Carry on with the rest", onPrimary = { clicks++ })
        }
        listOf("Put everything back", "Restart", "Carry on with the rest").forEach { tap(it, MotionEvent.FLAG_WINDOW_IS_OBSCURED) }
        assertEquals(0, clicks)
        listOf("Put everything back", "Restart", "Carry on with the rest").forEach { tap(it, 0) }
        assertEquals(3, clicks)
    }

    @Test
    fun nonConfirmingButtonsStillWorkWhenObscured() {
        val hits = mutableListOf<String>()
        show {
            SecondaryButton("No, put it back", { hits += "secondary" })
            TertiaryButton("Stop and undo", { hits += "tertiary" }, quiet = true)
            CytIconButton(CytIcons.Back, "Back", { hits += "back" })
            PrimaryButton("Back to the start", { hits += "unguarded primary" }, guardObscured = false)
            StopCard("This TV can't be set up from a phone yet", "We stopped before touching anything.", primaryLabel = "OK, go back",
                onPrimary = { hits += "unguarded card" }, secondaryLabel = "Cancel", onSecondary = { hits += "card secondary" }, primaryGuarded = false)
        }
        tap("No, put it back", MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        tap("Stop and undo", MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        tap("Back to the start", MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        tap("OK, go back", MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        tap("Cancel", MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        tap(compose.onNodeWithContentDescription("Back"), MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(listOf("secondary", "tertiary", "unguarded primary", "unguarded card", "card secondary", "back"), hits)
        compose.onNodeWithText(hint).assertDoesNotExist()
    }

    @Test
    fun theCountdownYesIsGuardedAndNoIsNot() {
        val hits = mutableListOf<String>()
        show {
            CountdownConfirm(
                overline = "4 of 5 · Look at the TV", title = "Can you see it?", thumbnail = null, thumbnailDescription = "The TV",
                sentence = "The new home screen.", instruction = "Press Home on the remote once.", liveCheck = "Home opens the new screen",
                liveCheckPassed = false, remainingMs = 84_000, totalMs = 90_000, timeoutSentence = "If we don't hear from you, we put it back.",
                yesLabel = "Yes, I see it", noLabel = "No, put it back", onYes = { hits += "yes" }, onNo = { hits += "no" },
            )
        }
        tap("Yes, I see it", MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        tap("No, put it back", MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(listOf("no"), hits)
    }

    @Test
    fun aDroppedTapShowsTheHintForAWhile() {
        show { PrimaryButton("Yes, that's mine", {}) }
        compose.onNodeWithText(hint).assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        dispatch(compose.onNodeWithText("Yes, that's mine"), MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        compose.waitForIdle()   // runs the main looper, where the state write is applied
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText(hint).assertExists()
        compose.mainClock.advanceTimeBy(7_000)
        compose.onNodeWithText(hint).assertDoesNotExist()
    }
}
