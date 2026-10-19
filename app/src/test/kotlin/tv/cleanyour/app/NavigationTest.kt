package tv.cleanyour.app

import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The emulator run: Back from "I've set up a TV before" left the app instead of returning to Welcome.
class NavigationTest {
    @Test fun backFromIveSetUpATvBeforeReturnsToWelcome() {
        val stack = mutableListOf<NavKey>(Dest.Setup())
        assertTrue(applyRoute(stack, "returning", noTvs = true))
        assertEquals(listOf<NavKey>(Dest.Setup(), Dest.Manage(null)), stack)
        assertTrue(applyRoute(stack, "back", noTvs = true))   // system Back pops the same way
        assertEquals(listOf<NavKey>(Dest.Setup()), stack)
        assertFalse(applyRoute(stack, "back", noTvs = true))  // Back on Welcome leaves the app
    }

    @Test fun manageAfterTheWizardStillReplacesTheStack() {
        val stack = mutableListOf<NavKey>(Dest.Setup(), Dest.Setup("0123456789abcdef"))
        applyRoute(stack, "manage/0123456789abcdef", noTvs = false)
        assertEquals(listOf<NavKey>(Dest.Manage("0123456789abcdef")), stack)
        applyRoute(stack, "modules/0123456789abcdef", noTvs = false)
        assertEquals(Dest.Modules("0123456789abcdef"), stack.last())
        applyRoute(stack, "welcome", noTvs = true)
        assertEquals(listOf<NavKey>(Dest.Setup()), stack)
    }
}
