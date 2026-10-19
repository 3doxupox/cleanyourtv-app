package tv.cleanyour.feature.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.RestorePhase
import tv.cleanyour.core.flows.RestorePointState
import tv.cleanyour.testing.fixtures.Samples

// Roborazzi screenshots of every screen state of :feature:backup (PRODUCT-SPEC 4: M6 Restore point).
// Record with recordRoborazziDebug, compare with verifyRoborazziDebug. Images land in
// src/test/screenshots. Sheets (the "Undo everything" preview, its "Put everything back"
// confirmation, the "Before a factory reset" explanation) are ModalBottomSheet windows that
// Robolectric does not capture, so they have no screenshot here.

@Composable
private fun ShotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF000000), onBackground = Color(0xFFF5F5F7),
            surface = Color(0xFF1C1C1E), onSurface = Color(0xFFF5F5F7),
            surfaceVariant = Color(0xFF2A2A2E), onSurfaceVariant = Color(0xFFA1A1A6),
            primary = Color(0xFFF5F5F7), onPrimary = Color(0xFF000000),
            tertiary = Color(0xFF8C9BFF), outline = Color(0x33FFFFFF),
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF000000))) { content() }
    }
}

private fun ComposeContentTestRule.shot(name: String, content: @Composable () -> Unit) {
    setContent { ShotTheme(content) }
    waitForIdle()
    onRoot().captureRoboImage("src/test/screenshots/$name.png")
}

private const val NOW = Samples.NOW_MS

private fun restore(phase: RestorePhase = RestorePhase.Idle, baseline: Long? = NOW, googleTv: Boolean = false, exporting: Boolean = false) =
    RestorePointState(tvName = "Mi TV 4A", baselineAtMs = baseline, latestAtMs = baseline, googleTv = googleTv, phase = phase, exporting = exporting)

private val undoLines = listOf("Turning things back on: 14 of 23...", "Putting the old home screen back...")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    // M6 Restore point
    @Test fun restore_idle() = compose.shot("restore_idle") { RestoreScreen(restore(), {}, {}) }
    @Test fun restore_idle_google_tv() = compose.shot("restore_idle_google_tv") { RestoreScreen(restore(googleTv = true), {}, {}) }
    @Test fun restore_no_restore_point() = compose.shot("restore_no_restore_point") { RestoreScreen(restore(baseline = null), {}, {}) }
    @Test fun restore_exporting() = compose.shot("restore_exporting") { RestoreScreen(restore(exporting = true), {}, {}) }
    @Test fun restore_working_undo() = compose.shot("restore_working_undo") {
        RestoreScreen(restore(RestorePhase.Working("undo", 58, undoLines)), {}, {})
    }
    @Test fun restore_working_factory() = compose.shot("restore_working_factory") {
        RestoreScreen(restore(RestorePhase.Working("factory", 40, listOf("Switching the setup wizard back on..."))), {}, {})
    }
    @Test fun restore_finished_undo() = compose.shot("restore_finished_undo") {
        RestoreScreen(restore(RestorePhase.Finished("undo", NOW)), {}, {})
    }
    @Test fun restore_finished_factory() = compose.shot("restore_finished_factory") {
        RestoreScreen(restore(RestorePhase.Finished("factory", NOW), googleTv = true), {}, {})
    }
    @Test fun restore_failed() = compose.shot("restore_failed") { RestoreScreen(restore(RestorePhase.Failed), {}, {}) }
}
