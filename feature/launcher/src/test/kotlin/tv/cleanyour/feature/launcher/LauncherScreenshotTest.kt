package tv.cleanyour.feature.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.Busy
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.ThemeOption
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Samples

// Roborazzi screenshots of every screen state of :feature:launcher (PRODUCT-SPEC 4: M2 Home screen).
// Record with recordRoborazziDebug, compare with verifyRoborazziDebug. Images land in
// src/test/screenshots. Sheets (the dock sheet S3b, the theme picker, the "Use the TV's own home
// screen" preview) are ModalBottomSheet windows that Robolectric does not capture, so they have no
// screenshot here.

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

private fun ComposeContentTestRule.shot(name: String, fontScale: Float? = null, rtl: Boolean = false, content: @Composable () -> Unit) {
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides if (fontScale != null) Density(density.density, fontScale) else density,
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LocalLayoutDirection.current,
        ) { ShotTheme(content) }
    }
    waitForIdle()
    onRoot().captureRoboImage("src/test/screenshots/$name.png")
}

private fun app(pkg: String, name: String) = AppChoice(PackageName(pkg), name)

private val defaultDock = listOf(
    app("com.netflix.ninja", "Netflix"), app("com.google.android.youtube.tv", "YouTube"),
    app("com.amazon.amazonvideo.livingroom", "Prime Video"), app("com.droidlogic.tvinput", "Live TV"),
)
private val apps = defaultDock + listOf(app("com.spotify.tv.android", "Spotify"), app("org.videolan.vlc", "VLC"))
private val themes = listOf(
    ThemeOption("default", "Default", "A black background with four apps along the bottom."),
    ThemeOption("aurora", "Aurora", "A deep blue background with a soft glow behind the apps."),
    ThemeOption("six-columns", "Six columns", "Six apps across, so more fit on the screen."),
)

private fun home(
    themeId: String? = null, drift: Boolean = false, busy: Busy? = null, stock: Boolean = true, ours: Boolean = true, dock: List<AppChoice> = defaultDock,
) = HomeScreenState(
    tvName = "Mi TV 4A", dock = dock, apps = apps, themeId = themeId, themes = themes, drift = drift,
    stock = if (stock) Samples.stockLauncher else null, busy = busy, homeScreenOurs = ours,
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LauncherScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    // M2 Home screen
    @Test fun home_normal() = compose.shot("home_normal") { HomeScreen(home(), {}, {}) }
    @Test fun home_aurora_theme() = compose.shot("home_aurora_theme") { HomeScreen(home(themeId = "aurora"), {}, {}) }
    @Test fun home_drift_banner() = compose.shot("home_drift_banner") { HomeScreen(home(drift = true), {}, {}) }
    @Test fun home_busy_connecting() = compose.shot("home_busy_connecting") { HomeScreen(home(busy = Busy.Connecting), {}, {}) }
    @Test fun home_busy_working() = compose.shot("home_busy_working") { HomeScreen(home(busy = Busy.Working(40, listOf("Updating the home screen on the TV..."))), {}, {}) }
    @Test fun home_no_stock_launcher() = compose.shot("home_no_stock_launcher") { HomeScreen(home(stock = false), {}, {}) }
    @Test fun home_stock_already_in_use() = compose.shot("home_stock_already_in_use") { HomeScreen(home(ours = false), {}, {}) }
    @Test fun home_empty_dock() = compose.shot("home_empty_dock") { HomeScreen(home(dock = emptyList()), {}, {}) }
}
