package tv.cleanyour.feature.manage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.AppPrefsValue
import tv.cleanyour.core.flows.AppSettingsState
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.LeftoversState
import tv.cleanyour.core.flows.ManageState
import tv.cleanyour.core.flows.OffGroup
import tv.cleanyour.core.flows.OffRow
import tv.cleanyour.core.flows.SwitchedOffState
import tv.cleanyour.core.flows.TvCard
import tv.cleanyour.core.flows.TvSettingsState
import tv.cleanyour.core.flows.UpdateCheck
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Samples

// Roborazzi screenshots of every screen state of :feature:manage (PRODUCT-SPEC 4: M1, M4, M5, M7,
// M8). Record with recordRoborazziDebug, compare with verifyRoborazziDebug. Images land in
// src/test/screenshots. Sheets (M1a DebugStepSheet, the level explanation, "Forget this TV"
// confirmation, Licences, Privacy, diagnostics preview, Reset my key and Delete everything
// confirmations) are ModalBottomSheet windows that Robolectric does not capture, so they have no
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

private fun fakeShot(): ByteArray {
    val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(0xFF05060A.toInt())
    val paint = Paint().apply { color = 0xFF2A2F45.toInt() }
    repeat(4) { i -> canvas.drawRoundRect(RectF(70f + i * 130f, 220f, 180f + i * 130f, 320f), 16f, 16f, paint) }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

private const val NOW = Samples.NOW_MS
private val tvId = Samples.miTvId()
private val dockNames = listOf("Netflix", "YouTube", "Prime Video", "Live TV")

private fun card(
    clean: Boolean = true, level: Level = Level.SIMPLE, chip: ChipState = ChipState.CONNECTED, thumbnail: ByteArray? = fakeShot(),
    switchedOff: Int = 23, room: String? = "Living room", restorePointAtMs: Long? = NOW, debugging: DebugState = DebugState.ON,
) = TvCard(tvId, "Mi TV 4A", room, clean, NOW, level, chip, thumbnail, switchedOff, dockNames, restorePointAtMs, debugging)

private fun manage(card: TvCard = card(), offline: Boolean = false, reconnecting: Boolean = false, others: List<TvCard> = emptyList()) =
    ManageState(cards = listOf(card) + others, selected = tvId, offline = offline, reconnecting = reconnecting, loading = false)

private fun off(pkg: String, name: String, description: String?, off: Boolean = true) = OffRow(PackageName(pkg), name, description, off)

private val offGroups = listOf(
    OffGroup("ads", "Adverts and sponsored rows", listOf(
        off("com.mitv.patchwall", "PatchWall", "Xiaomi's home screen with adverts"),
        off("com.mitv.promos", "Mi TV+ promos", "Promoted content rows"),
        off("com.glance.tv", "Glance", "Adverts on the lock screen"),
    )),
    OffGroup("tracking", "Tracking and analytics", listOf(
        off("com.xiaomi.analytics", "Xiaomi Analytics", "Reports what you watch to Xiaomi"),
        off("com.alphonso.tv", "Alphonso", "Listens to what's on to sell ads"),
    )),
    OffGroup("other", "", listOf(off("com.example.legacy", "Legacy app", null))),
)
private val askGroups = listOf(
    OffGroup("voice", "Voice assistant", listOf(
        off("com.google.android.katniss", "Google Assistant", "Voice search and commands", off = false),
    )),
    OffGroup("updates", "Software updates", listOf(
        off("com.mitv.ota", "System updater", "Kept on so the TV keeps getting security fixes. Switching off stops update nags too.", off = false),
        off("com.mitv.otaextra", "Update helper", "Checks for new TV software", off = true),
    )),
)

private fun appSettings(update: UpdateCheck = UpdateCheck.Idle, backing: String = "STRONGBOX") = AppSettingsState(
    version = "0.1.0", prefs = AppPrefsValue(haptics = true, largeTextFollowsSystem = true),
    launcherPkg = "com.gathin.tvhome", launcherVersion = "0.1.0", launcherDigest = "9f2c41d07a6be8135c4d0e72ab19f5c3",
    keyBacking = backing, keyFingerprint = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:7F:2A", update = update,
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ManageScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    // M1 Manage
    @Test fun manage_clean() = compose.shot("manage_clean") { ManageScreen(manage(), {}) }
    @Test fun manage_custom_level() = compose.shot("manage_custom_level") { ManageScreen(manage(card(level = Level.CUSTOM)), {}) }
    @Test fun manage_offline_banner() = compose.shot("manage_offline_banner") {
        ManageScreen(manage(card(chip = ChipState.OFFLINE), offline = true), {})
    }
    @Test fun manage_offline_reconnecting() = compose.shot("manage_offline_reconnecting") {
        ManageScreen(manage(card(chip = ChipState.OFFLINE), offline = true, reconnecting = true), {})
    }
    @Test fun manage_original() = compose.shot("manage_original") {
        ManageScreen(manage(card(clean = false, switchedOff = 0)), {})
    }
    @Test fun manage_debugging_off() = compose.shot("manage_debugging_off") {
        ManageScreen(manage(card(chip = ChipState.DEBUGGING_OFF, debugging = DebugState.OFF)), {})
    }
    @Test fun manage_no_thumbnail() = compose.shot("manage_no_thumbnail") { ManageScreen(manage(card(thumbnail = null)), {}) }
    @Test fun manage_two_tvs() = compose.shot("manage_two_tvs") {
        val other = card().copy(id = tv.cleanyour.core.model.TvId("1111222233334444"), name = "Bedroom Chromecast", room = null)
        ManageScreen(manage(others = listOf(other)), {})
    }

    // M4 Switched off
    @Test fun switchedoff_groups() = compose.shot("switchedoff_groups") {
        SwitchedOffScreen(SwitchedOffState("Mi TV 4A", Level.SIMPLE, offGroups, loading = false, count = 6), {}, {})
    }
    @Test fun switchedoff_busy() = compose.shot("switchedoff_busy") {
        SwitchedOffScreen(SwitchedOffState("Mi TV 4A", Level.SIMPLE, offGroups, busy = PackageName("com.glance.tv"), loading = false, count = 6), {}, {})
    }
    @Test fun switchedoff_custom_ask_groups() = compose.shot("switchedoff_custom_ask_groups") {
        SwitchedOffScreen(SwitchedOffState("Mi TV 4A", Level.CUSTOM, offGroups, askGroups, loading = false, count = 6), {}, {})
    }
    @Test fun switchedoff_empty() = compose.shot("switchedoff_empty") {
        SwitchedOffScreen(SwitchedOffState("Mi TV 4A", Level.SIMPLE, emptyList(), loading = false, count = 0), {}, {})
    }
    @Test fun switchedoff_loading() = compose.shot("switchedoff_loading") {
        SwitchedOffScreen(SwitchedOffState(), {}, {})
    }

    // M5 Check for leftovers
    @Test fun leftovers_checking() = compose.shot("leftovers_checking") { LeftoversScreen(LeftoversState.Checking, {}, {}) }
    @Test fun leftovers_found() = compose.shot("leftovers_found") {
        LeftoversScreen(LeftoversState.Found(listOf("PatchWall", "Glance", "Xiaomi Analytics"), Samples.plan(emptyList())), {}, {})
    }
    @Test fun leftovers_found_one() = compose.shot("leftovers_found_one") {
        LeftoversScreen(LeftoversState.Found(listOf("PatchWall"), Samples.plan(emptyList())), {}, {})
    }
    @Test fun leftovers_nothing() = compose.shot("leftovers_nothing") { LeftoversScreen(LeftoversState.Nothing, {}, {}) }
    @Test fun leftovers_working() = compose.shot("leftovers_working") { LeftoversScreen(LeftoversState.Working(40), {}, {}) }
    @Test fun leftovers_done() = compose.shot("leftovers_done") { LeftoversScreen(LeftoversState.Done(3), {}, {}) }
    @Test fun leftovers_failed() = compose.shot("leftovers_failed") { LeftoversScreen(LeftoversState.Failed, {}, {}) }

    // M7 TV settings
    @Test fun tvsettings_simple() = compose.shot("tvsettings_simple") {
        TvSettingsScreen(TvSettingsState("Mi TV 4A", "Living room", Level.SIMPLE, "192.168.1.50", NOW), {}, {})
    }
    @Test fun tvsettings_custom() = compose.shot("tvsettings_custom") {
        TvSettingsScreen(TvSettingsState("Mi TV 4A", null, Level.CUSTOM, "192.168.1.50", NOW), {}, {})
    }

    // M8 App settings
    @Test fun appsettings_idle() = compose.shot("appsettings_idle") { AppSettingsScreen(appSettings(), {}, {}, {}) }
    @Test fun appsettings_checking() = compose.shot("appsettings_checking") { AppSettingsScreen(appSettings(UpdateCheck.Checking), {}, {}, {}) }
    @Test fun appsettings_update_available() = compose.shot("appsettings_update_available") { AppSettingsScreen(appSettings(UpdateCheck.Available("0.2")), {}, {}, {}) }
    @Test fun appsettings_up_to_date() = compose.shot("appsettings_up_to_date") { AppSettingsScreen(appSettings(UpdateCheck.Latest), {}, {}, {}) }
    @Test fun appsettings_update_failed() = compose.shot("appsettings_update_failed") { AppSettingsScreen(appSettings(UpdateCheck.Failed), {}, {}, {}) }
    @Test fun appsettings_key_tee() = compose.shot("appsettings_key_tee") { AppSettingsScreen(appSettings(backing = "TEE"), {}, {}, {}) }
    @Test fun appsettings_key_wrapped() = compose.shot("appsettings_key_wrapped") { AppSettingsScreen(appSettings(backing = "WRAPPED_SOFTWARE"), {}, {}, {}) }
}

// 200% font scale of the key screen of this module.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ManageScreenshotFontScaleTest {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    @Test fun manage_clean_font200() = compose.shot("manage_clean_font200", fontScale = 2f) { ManageScreen(manage(), {}) }
}

// Right-to-left layout direction of the key screen of this module.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ManageScreenshotRtlTest {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    @Test fun manage_clean_rtl() = compose.shot("manage_clean_rtl", rtl = true) { ManageScreen(manage(), {}) }
}
