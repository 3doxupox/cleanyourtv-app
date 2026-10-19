package tv.cleanyour.feature.connect

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
import tv.cleanyour.core.discovery.DiscoverySource
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.discovery.ServiceRef
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.DoorPhase
import tv.cleanyour.core.flows.Families
import tv.cleanyour.core.flows.IdentityCheck
import tv.cleanyour.core.flows.NoSetupActions
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.core.flows.StopCard
import tv.cleanyour.core.flows.StopCase

// Roborazzi screenshots of every screen state of :feature:connect (PRODUCT-SPEC 4: S1, S1b, S2a to
// S2d, S2x and the inline stop cards). Record with recordRoborazziDebug, compare with
// verifyRoborazziDebug. Images land in src/test/screenshots. Sheets (S1c address, "I don't see it
// on the TV", the pairing commands) are ModalBottomSheet windows that Robolectric does not capture,
// so they have no screenshot here.

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

private const val FINGERPRINT = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:7F:2A"

private val miTv = FoundTv("192.168.1.50", "Mi TV 4A", "MiTV-AXSO0", setOf(DiscoverySource.GOOGLE_CAST), true, null, null, 0L)
private val chromecast = FoundTv("192.168.1.61", "Bedroom Chromecast", "Chromecast", setOf(DiscoverySource.GOOGLE_CAST), false, null, null, 0L)
private val miTvTwin = miTv.copy(host = "192.168.1.77")
private val pairingTv = FoundTv("192.168.1.61", "Bedroom Chromecast", "Chromecast", setOf(DiscoverySource.ADB_TLS_PAIRING), false,
    ServiceRef("adb-tls-pairing", "192.168.1.61", 41235), null, 0L)
private val unknownTv = FoundTv("192.168.1.90", null, null, setOf(DiscoverySource.MANUAL), true, null, null, 0L)

private val tvExtras = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.WAITING)

private fun welcome(found: List<FoundTv>, looking: Boolean) = SetupState.Welcome(found, onWifi = true, looking = looking)

private fun door(family: String, phase: DoorPhase, step: Int, banner: String? = null, waitedMs: Long = 0, fingerprint: String? = null, tv: FoundTv = miTv) =
    SetupState.OpenDoor(tv, family, step, phase, fingerprint, banner, waitedMs)

private fun card(case: StopCase, title: String, body: String, primary: String, secondary: String? = null) =
    StopCard(case, title, body, primary, secondary, planId = null)

private val notFoundCard = card(StopCase.TV_NOT_FOUND, "No TV found yet",
    "Make sure the TV is switched on and on the same Wi-Fi as this phone. TVs can take a minute to show up after they wake.",
    "Keep looking", "Enter the TV's address")
private val wrongNetworkCard = card(StopCase.WRONG_NETWORK, "Phone and TV are on different networks",
    "The TV was last seen on a different network. Join the same Wi-Fi as the TV and we'll carry on.",
    "Open Wi-Fi settings", "I'm on the right one, look again")
private val wifiOffCard = card(StopCase.WRONG_NETWORK, "Phone and TV are on different networks",
    "Wi-Fi is off. The TV can only be reached over the same Wi-Fi.", "Open Wi-Fi settings", "I'm on the right one, look again")
private val identityCard = card(StopCase.IDENTITY_CHANGED, "A different TV answered at this address",
    "The TV at this address isn't the one we set up as Mi TV 4A. Its address may have changed, or another TV has taken it. We stopped before changing anything.",
    "Look for it again", "Back")
private val notSupportedCard = card(StopCase.TV_NOT_SUPPORTED, "This TV can't be set up from a phone yet",
    "This TV's debugging connection is too basic for the phone to make changes safely, so we stopped before touching anything. Nothing on the TV was changed.",
    "Back")

private const val FIRE_BANNER = "Fire TV lets us switch off adverts and tracking but keeps its own home screen. You still get the clean-up."
private const val TCL_BANNER = "This TV keeps its own home screen (TCL does not allow others). You still get the clean-up."

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ConnectScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    private fun connect(name: String, state: SetupState, extras: SetupExtras = SetupExtras()) =
        compose.shot(name) { ConnectFlow(state, extras, NoSetupActions, onReturning = {}) }

    // S1 Welcome
    @Test fun welcome_looking() = connect("welcome_looking", welcome(emptyList(), looking = true))
    @Test fun welcome_found_one() = connect("welcome_found_one", welcome(listOf(miTv), looking = false))
    @Test fun welcome_found_two() = connect("welcome_found_two", welcome(listOf(miTv, chromecast), looking = false))
    @Test fun welcome_none_yet() = connect("welcome_none_yet", welcome(emptyList(), looking = false))
    @Test fun welcome_none_yet_wrong_network() = connect("welcome_none_yet_wrong_network", welcome(emptyList(), looking = false), SetupExtras(card = wrongNetworkCard))
    @Test fun welcome_wifi_off() = connect("welcome_wifi_off", welcome(emptyList(), looking = false), SetupExtras(card = wifiOffCard))
    @Test fun welcome_tv_not_found() = connect("welcome_tv_not_found", welcome(emptyList(), looking = false), SetupExtras(card = notFoundCard))
    @Test fun welcome_identity_changed() = connect("welcome_identity_changed", welcome(listOf(miTv), looking = false), SetupExtras(card = identityCard))
    @Test fun welcome_tv_not_supported() = connect("welcome_tv_not_supported", welcome(listOf(miTv), looking = false), SetupExtras(card = notSupportedCard))

    // S1b Which TV?
    @Test fun picker_two_tvs() = connect("picker_two_tvs", SetupState.Picker(listOf(miTv, chromecast)))
    @Test fun picker_same_names() = connect("picker_same_names", SetupState.Picker(listOf(miTv, miTvTwin)))

    // S2a Let the phone in: the step cards per platform family
    @Test fun door_steps_atv() = connect("door_steps_atv", door(Families.ATV, DoorPhase.STEPS, 0), tvExtras)
    @Test fun door_steps_gtv() = connect("door_steps_gtv", door(Families.GTV, DoorPhase.STEPS, 0, tv = chromecast), tvExtras.copy(tvName = "Bedroom Chromecast"))
    @Test fun door_steps_fire() = connect("door_steps_fire", door(Families.FIRE, DoorPhase.STEPS, 0, banner = FIRE_BANNER), tvExtras.copy(tvName = "Fire TV Stick"))
    @Test fun door_steps_tcl() = connect("door_steps_tcl", door(Families.TCL, DoorPhase.STEPS, 0, banner = TCL_BANNER), tvExtras.copy(tvName = "TCL C635"))
    @Test fun door_steps_unknown_brand_chips() = connect("door_steps_unknown_brand_chips", door(Families.UNKNOWN, DoorPhase.STEPS, 0, tv = unknownTv),
        SetupExtras(tvName = null, chip = null))
    @Test fun door_steps_still_waiting() = connect("door_steps_still_waiting", door(Families.ATV, DoorPhase.STEPS, 2, waitedMs = 181_000), tvExtras)

    // S2b Connecting
    @Test fun door_connecting() = connect("door_connecting", door(Families.ATV, DoorPhase.CONNECTING, 2), tvExtras.copy(chip = ChipState.WAITING))

    // S2c Look at the TV (Allow)
    @Test fun door_allow() = connect("door_allow", door(Families.ATV, DoorPhase.ALLOW, 2, waitedMs = 5_000, fingerprint = FINGERPRINT), tvExtras)
    @Test fun door_allow_still_waiting() = connect("door_allow_still_waiting", door(Families.ATV, DoorPhase.ALLOW, 2, waitedMs = 46_000, fingerprint = FINGERPRINT), tvExtras)

    // S2d identity check and Connected
    @Test fun door_identity() = connect("door_identity", door(Families.ATV, DoorPhase.IDENTITY, 2),
        tvExtras.copy(chip = ChipState.CONNECTED, identity = IdentityCheck("Mi TV 4A", "7K2Q", fakeShot())))
    @Test fun door_connected() = connect("door_connected", door(Families.ATV, DoorPhase.CONNECTED, 2), tvExtras.copy(chip = ChipState.CONNECTED))

    // S2x Pairing needed
    @Test fun pairing_needed() = connect("pairing_needed", SetupState.PairingNeeded(pairingTv))
}

// 200% font scale of the key screen of this module.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ConnectScreenshotFontScaleTest {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    @Test fun welcome_found_one_font200() = compose.shot("welcome_found_one_font200", fontScale = 2f) {
        ConnectFlow(welcome(listOf(miTv), looking = false), SetupExtras(), NoSetupActions, onReturning = {})
    }
}

// Right-to-left layout direction of the key screen of this module.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ConnectScreenshotRtlTest {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    @Test fun welcome_found_one_rtl() = compose.shot("welcome_found_one_rtl", rtl = true) {
        ConnectFlow(welcome(listOf(miTv), looking = false), SetupExtras(), NoSetupActions, onReturning = {})
    }
}
