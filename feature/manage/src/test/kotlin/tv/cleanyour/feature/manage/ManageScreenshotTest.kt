package tv.cleanyour.feature.manage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.flows.AppConfirm
import tv.cleanyour.core.flows.AppPrefsValue
import tv.cleanyour.core.flows.AppSettingsState
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.LeftoversState
import tv.cleanyour.core.flows.ManageState
import tv.cleanyour.core.flows.OffGroup
import tv.cleanyour.core.flows.OffRow
import tv.cleanyour.core.flows.OpenWrite
import tv.cleanyour.core.flows.SwitchedOffState
import tv.cleanyour.core.flows.TvCard
import tv.cleanyour.core.flows.TvSettingsState
import tv.cleanyour.core.flows.UpdateCheck
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.testing.fixtures.Samples

// Every section 4 state of :feature:manage (PRODUCT-SPEC 4: M1, M1a, the open-run card, M4, M5,
// M7, M8 and their sheets), each in dark, light, 200% font and right to left, with ATF on.

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
private val shotPng by lazy { fakeShot() }

private fun card(
    clean: Boolean = true, level: Level = Level.SIMPLE, chip: ChipState = ChipState.CONNECTED, thumbnail: Boolean = true,
    switchedOff: Int = 23, room: String? = "Living room", restorePointAtMs: Long? = NOW, debugging: DebugState = DebugState.ON,
) = TvCard(tvId, "Mi TV 4A", room, clean, NOW, level, chip, if (thumbnail) shotPng else null, switchedOff, dockNames, restorePointAtMs, debugging)

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
        off("com.mitv.ota", "System updater", "Kept on so the TV keeps getting security fixes.", off = false),
        off("com.mitv.otaextra", "Update helper", "Checks for new TV software", off = true),
    )),
)

private fun switchedOff(level: Level = Level.SIMPLE, groups: List<OffGroup> = offGroups, ask: List<OffGroup> = emptyList(), busy: String? = null, count: Int = 6) =
    SwitchedOffState("Mi TV 4A", level, groups, ask, busy?.let { PackageName(it) }, loading = false, count = count)

private fun appSettings(update: UpdateCheck = UpdateCheck.Idle, backing: String = "STRONGBOX") = AppSettingsState(
    version = "0.1.0", prefs = AppPrefsValue(haptics = true, largeTextFollowsSystem = true),
    launcherPkg = "com.gathin.tvhome", launcherVersion = "0.1.0", launcherDigest = "9f2c41d07a6be8135c4d0e72ab19f5c3",
    keyBacking = backing, keyFingerprint = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:7F:2A", update = update,
)

private fun tvSettings(level: Level = Level.SIMPLE, room: String? = "Living room") = TvSettingsState("Mi TV 4A", room, level, "192.168.1.50", NOW)

private fun openWrite(kind: PlanKind = PlanKind.SWITCH_OFF, reason: PauseReason = PauseReason.CONNECTION_LOST, busy: Boolean = false, notTaken: Boolean = false) =
    OpenWrite(tvId, "Mi TV 4A", PlanId("01JAZ0000000000000000000"), kind, reason, busy, notTaken)

private val diagnostics = """
    clean-your-tv 0.1.0 diagnostics
    tv: Mi TV 4A (model MiTV-AXSO0, Android TV 9)
    address: [removed]  serial: [removed]
    run 01JAZ0: SWITCH_OFF 6 steps, paused CONNECTION_LOST at step 4
    step 1 pm disable-user com.mitv.patchwall: ok
""".trimIndent()

private val tall = 1100.dp

object ManageShots {
    val all: List<Shot> = listOf(
        // M1 Manage
        Shot("manage_clean") { ManageScreen(manage(), {}) },
        Shot("manage_custom_level") { ManageScreen(manage(card(level = Level.CUSTOM)), {}) },
        Shot("manage_original") { ManageScreen(manage(card(clean = false, switchedOff = 0)), {}) },
        Shot("manage_offline") { ManageScreen(manage(card(chip = ChipState.OFFLINE), offline = true), {}) },
        Shot("manage_reconnecting") { ManageScreen(manage(card(chip = ChipState.OFFLINE), offline = true, reconnecting = true), {}) },
        Shot("manage_debugging_off") { ManageScreen(manage(card(chip = ChipState.DEBUGGING_OFF, debugging = DebugState.OFF)), {}) },
        Shot("manage_working") { ManageScreen(manage(card(chip = ChipState.WORKING)), {}) },
        Shot("manage_no_thumbnail") { ManageScreen(manage(card(thumbnail = false, room = null)), {}) },
        Shot("manage_two_tvs", tall) {
            val other = card().copy(id = TvId("1111222233334444"), name = "Bedroom Chromecast", room = "Bedroom")
            ManageScreen(manage(others = listOf(other)), {})
        },

        // M1a the debugging step for a returning write
        Shot("debugstep_waiting") { SheetOver({ ManageScreen(manage(), {}) }) { DebugStepContent("Mi TV 4A", connecting = false) {} } },
        Shot("debugstep_connecting") { SheetOver({ ManageScreen(manage(), {}) }) { DebugStepContent("Mi TV 4A", connecting = true) {} } },

        // The open-run card (R5-W8 O2)
        Shot("openwrite_connection_lost") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(), {}, {}, {}) } },
        Shot("openwrite_step_failed") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(reason = PauseReason.STEP_FAILED), {}, {}, {}) } },
        Shot("openwrite_busy") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(busy = true), {}, {}, {}) } },
        Shot("openwrite_not_taken") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(notTaken = true), {}, {}, {}) } },
        Shot("openwrite_setup") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(kind = PlanKind.SETUP), {}, {}, {}) } },

        // M4 Switched off
        Shot("switchedoff_groups", tall) { SwitchedOffScreen(switchedOff(), {}, {}) },
        Shot("switchedoff_busy", tall) { SwitchedOffScreen(switchedOff(busy = "com.glance.tv"), {}, {}) },
        Shot("switchedoff_custom", tall) { SwitchedOffScreen(switchedOff(Level.CUSTOM, ask = askGroups), {}, {}) },
        Shot("switchedoff_empty") { SwitchedOffScreen(switchedOff(groups = emptyList(), count = 0), {}, {}) },
        Shot("switchedoff_loading") { SwitchedOffScreen(SwitchedOffState(), {}, {}) },

        // M5 Check for leftovers
        Shot("leftovers_checking") { LeftoversScreen(LeftoversState.Checking, {}, {}) },
        Shot("leftovers_found") { LeftoversScreen(LeftoversState.Found(listOf("PatchWall", "Glance", "Xiaomi Analytics"), Samples.plan(emptyList())), {}, {}) },
        Shot("leftovers_found_one") { LeftoversScreen(LeftoversState.Found(listOf("PatchWall"), Samples.plan(emptyList())), {}, {}) },
        Shot("leftovers_nothing") { LeftoversScreen(LeftoversState.Nothing, {}, {}) },
        Shot("leftovers_working") { LeftoversScreen(LeftoversState.Working(40), {}, {}) },
        Shot("leftovers_done") { LeftoversScreen(LeftoversState.Done(3), {}, {}) },
        Shot("leftovers_failed") { LeftoversScreen(LeftoversState.Failed, {}, {}) },

        // M7 TV settings
        Shot("tvsettings_simple", tall) { TvSettingsScreen(tvSettings(), {}, {}) },
        Shot("tvsettings_custom", tall) { TvSettingsScreen(tvSettings(Level.CUSTOM, room = null), {}, {}) },
        Shot("tvsettings_forget") { SheetOver({ TvSettingsScreen(tvSettings(), {}, {}) }) { ForgetContent("Mi TV 4A") {} } },

        // M8 App settings
        Shot("appsettings_idle", tall) { AppSettingsScreen(appSettings(), {}, {}, {}) },
        Shot("appsettings_checking", tall) { AppSettingsScreen(appSettings(UpdateCheck.Checking), {}, {}, {}) },
        Shot("appsettings_update_available", tall) { AppSettingsScreen(appSettings(UpdateCheck.Available("0.2.0")), {}, {}, {}) },
        Shot("appsettings_up_to_date", tall) { AppSettingsScreen(appSettings(UpdateCheck.Latest), {}, {}, {}) },
        Shot("appsettings_update_failed", tall) { AppSettingsScreen(appSettings(UpdateCheck.Failed), {}, {}, {}) },
        Shot("appsettings_key_tee", tall) { AppSettingsScreen(appSettings(backing = "TEE"), {}, {}, {}) },
        Shot("appsettings_key_wrapped", tall) { AppSettingsScreen(appSettings(backing = "WRAPPED_SOFTWARE"), {}, {}, {}) },
        Shot("appsettings_licences") { SheetOver({ AppSettingsScreen(appSettings(), {}, {}, {}) }) { LicencesContent() } },
        Shot("appsettings_privacy") { SheetOver({ AppSettingsScreen(appSettings(), {}, {}, {}) }) { PrivacyContent() } },
        Shot("appsettings_diagnostics") { SheetOver({ AppSettingsScreen(appSettings(), {}, {}, {}) }) { DiagnosticsContent(diagnostics) {} } },
        Shot("appsettings_reset_key") { SheetOver({ AppSettingsScreen(appSettings(), {}, {}, {}) }) { ConfirmContent(AppConfirm.RESET_KEY, working = false) {} } },
        Shot("appsettings_delete_all") { SheetOver({ AppSettingsScreen(appSettings(), {}, {}, {}) }) { ConfirmContent(AppConfirm.DELETE_EVERYTHING, working = false) {} } },
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h2400dp-xhdpi")
class ManageScreenshotTest(private val shot: Shot, private val variant: Variant) {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    @Test fun shot() = compose.shoot(shot, variant)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
        fun params(): List<Array<Any>> = ManageShots.all.flatMap { s -> Variant.entries.map { v -> arrayOf<Any>(s, v) } }
    }
}
