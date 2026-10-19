package tv.cleanyour.feature.setup

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
import tv.cleanyour.core.engine.NarrationLine
import tv.cleanyour.core.engine.NarrationState
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.DoneSummary
import tv.cleanyour.core.flows.Finishing
import tv.cleanyour.core.flows.NoSetupActions
import tv.cleanyour.core.flows.Reconnect
import tv.cleanyour.core.flows.RestartState
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.core.flows.StopCard
import tv.cleanyour.core.flows.StopCase
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.planner.GroupSummary
import tv.cleanyour.core.planner.GuardedSection
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanSummary
import tv.cleanyour.core.planner.RowSummary
import tv.cleanyour.core.probe.ProbeStage
import tv.cleanyour.testing.fixtures.Samples

// Roborazzi screenshots of every screen state of :feature:setup (PRODUCT-SPEC 4: probing, S3, S4a,
// S4b, S4c to S4e, S5). Record with recordRoborazziDebug, compare with verifyRoborazziDebug. Images
// land in src/test/screenshots. Sheets (S3a Customise and "See the list", S3b dock, the theme
// picker, "Show details") are ModalBottomSheet windows that Robolectric does not capture, so they
// have no screenshot here.

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

// Sample data: the Mi TV 4A plan of PRODUCT-SPEC 4 S3.

private val tvId = Samples.miTvId()
private const val NOW = Samples.NOW_MS

private fun row(pkg: String, name: String, description: String, off: Boolean = true, protectedRow: Boolean = false, why: String? = null) =
    RowSummary(PackageName(pkg), name, description, off, protectedRow, why)

private val adsGroup = GroupSummary("ads", "Adverts and sponsored rows", off = 6, total = 6, rows = listOf(
    row("com.mitv.patchwall", "PatchWall", "Xiaomi's home screen with adverts"),
    row("com.mitv.promos", "Mi TV+ promos", "Promoted content rows"),
    row("com.glance.tv", "Glance", "Adverts on the lock screen"),
))
private val trackingGroup = GroupSummary("tracking", "Tracking and analytics", off = 7, total = 7, rows = listOf(
    row("com.xiaomi.analytics", "Xiaomi Analytics", "Reports what you watch to Xiaomi"),
    row("com.alphonso.tv", "Alphonso", "Listens to what's on to sell ads"),
))
private val nobodyGroup = GroupSummary("nobody", "Apps nobody asked for", off = 8, total = 8, rows = listOf(
    row("com.example.demo", "Demo mode", "Shop display mode"),
))
private val extrasGroup = GroupSummary("brand", "Xiaomi extras", off = 2, total = 2, rows = emptyList())
private val protectedGroup = GroupSummary("protected", "We keep these", off = 0, total = 3, rows = listOf(
    row("com.droidlogic.tvinput", "Live TV and HDMI", "Plays your HDMI set-top box", off = false, protectedRow = true,
        why = "Live TV plays your HDMI set-top box; switching it off would lose the Inputs button."),
))
private val keepNames = listOf("Netflix", "YouTube", "Prime Video")
private val dock = listOf("Netflix", "YouTube", "Prime Video", "Live TV")

private fun summary(groups: List<GroupSummary>, home: Boolean = true) =
    PlanSummary(groups, keepNames, dock, homeScreenPossible = home, replacesLauncher = false)

private fun plan(summary: PlanSummary): Plan = Samples.plan(emptyList()).copy(summary = summary)

private val fullPlan = plan(summary(listOf(adsGroup, trackingGroup, nobodyGroup, extrasGroup, protectedGroup)))
private val zeroPlan = plan(summary(listOf(GroupSummary("ads", "Adverts and sponsored rows", 0, 0, emptyList()), protectedGroup)))
private val unknownPlan = plan(summary(listOf(
    GroupSummary("ads", "Adverts and sponsored rows", off = 3, total = 3, rows = emptyList()),
    GroupSummary("tracking", "Tracking and analytics", off = 4, total = 4, rows = emptyList()),
    GroupSummary("nobody", "Apps nobody asked for", off = 2, total = 2, rows = emptyList()),
    protectedGroup)))
private val noHomePlan = plan(summary(listOf(
    GroupSummary("ads", "Adverts and sponsored rows", off = 6, total = 6, rows = emptyList()),
    GroupSummary("tracking", "Tracking and analytics", off = 7, total = 7, rows = emptyList()),
    GroupSummary("nobody", "Apps nobody asked for", off = 3, total = 3, rows = emptyList()),
    protectedGroup), home = false))

private val section = GuardedSection("home", 0, 0, RunToken("0123456789abcdef"), confirmWindowMs = 90_000,
    expectedHome = Samples.launcherComponent, rollbackIfFocus = emptySet(), rollback = emptyList())

private val connected = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.CONNECTED)
private val working = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.WORKING)

private fun found(plan: Plan = fullPlan) = SetupState.Found(tvId, plan, Level.SIMPLE, sheet = null)

private fun running(lines: List<NarrationLine>, percent: Int, thumbnail: ByteArray? = fakeShot()) =
    SetupState.Working(tvId, RunState.Running(fullPlan, step = 3, narration = lines, percent = percent, thumbnail = thumbnail), thumbnail)

private fun checkState(deadlineAheadMs: Long = 84_000, homeCheck: Boolean? = null, thumbnail: ByteArray? = fakeShot()) =
    SetupState.Check(tvId, RunState.AwaitingConfirm(fullPlan, section, NOW + deadlineAheadMs, homeCheck, thumbnail), thumbnail, dock)

private fun done(
    off: Int = 23, home: Boolean = true, skipped: List<String> = emptyList(), recommended: Boolean = false, restart: RestartState? = null,
) = SetupState.Done(tvId, DoneSummary(off, home, NOW, skipped, recommended), restart)

private fun stop(case: StopCase, title: String, body: String, primary: String, secondary: String? = null, tertiary: String? = null) =
    SetupState.Stopped(tvId, StopCard(case, title, body, primary, secondary, planId = null, tertiary = tertiary))

private val commands = listOf("fake enable com.google.android.tvlauncher", "fake set-home com.google.android.tvlauncher/.MainActivity")

private val narration = listOf(
    NarrationLine("Restore point saved", NarrationState.DONE),
    NarrationLine("Adverts switched off (6)", NarrationState.DONE),
    NarrationLine("Tracking: 4 of 7...", NarrationState.DOING),
    NarrationLine("Apps nobody asked for", NarrationState.UPCOMING),
    NarrationLine("Installing the new home screen", NarrationState.UPCOMING),
    NarrationLine("Switching Home over", NarrationState.UPCOMING),
)
private val narrationAllStates = listOf(
    NarrationLine("Restore point saved", NarrationState.DONE),
    NarrationLine("Adverts switched off (6)", NarrationState.DONE),
    NarrationLine("Play Games wouldn't switch off", NarrationState.SKIPPED),
    NarrationLine("Tracking: 4 of 7...", NarrationState.DOING),
    NarrationLine("Apps nobody asked for", NarrationState.UPCOMING),
    NarrationLine("Installing the new home screen", NarrationState.FAILED),
)

private const val SETUPWRAITH_BODY = "This TV's first-run setup wizard holds on to the Home button. On this model the new home screen only works if the wizard is switched off too. That's safe for everyday use; we switch it back on if you undo or before a factory reset."
private const val UNKNOWN_BODY = "We couldn't make the new home screen take over on this TV, so we put things back. Everything else we did is kept. You can try again from Manage, or share diagnostics so this model can be looked at."
private const val CONNECTION_LOST_BODY = "The TV stopped answering after we switched off 14 of 23 things. Usually it's back in a few seconds."
private const val INSTALL_FAILED_BODY = "The TV is out of space: 180 MB free, 600 MB needed. We cleared caches and tried again. Still no. The clean-up is done; try the home screen later from Manage after freeing space in Settings > Storage."
private const val NOT_BACK_BODY = "If the TV is stuck on its logo: hold the power button on the TV itself (not the remote) for 10 seconds, then turn it on again. If it shows a screen that says \"Can't load Android system\", choose Try again. Don't choose factory reset; it isn't needed.\n\nAs soon as the TV is back on Wi-Fi we'll reconnect and put the old home screen back automatically, so you can decide what to do calmly."

private fun connectionLost() = stop(StopCase.CONNECTION_LOST, "The TV stopped answering", CONNECTION_LOST_BODY,
    "Carry on with the rest", "Stop here", "Undo what we did")

private fun resume() = stop(StopCase.RESUME_AFTER_DEATH, "We were in the middle of setting up Mi TV 4A",
    "14 of 23 things are switched off; the home screen hasn't been changed yet. Carry on?", "Carry on", "Undo what we did", "Leave it as is")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SetupScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    private fun setup(name: String, state: SetupState, extras: SetupExtras = connected) =
        compose.shot(name) { SetupFlow(state, extras, NoSetupActions, nowMs = { NOW }) }

    // S2d to S3 probing
    @Test fun probing_start() = setup("probing_start", SetupState.Probing(tvId, ProbeStage.FACTS, null))
    @Test fun probing_packages() = setup("probing_packages", SetupState.Probing(tvId, ProbeStage.PACKAGES, null))

    // S3 What we found
    @Test fun found_simple() = setup("found_simple", found())
    @Test fun found_zero() = setup("found_zero", found(zeroPlan))
    @Test fun found_unknown_model() = setup("found_unknown_model", found(unknownPlan), connected.copy(unknownModel = true))
    @Test fun found_no_screen_lock() = setup("found_no_screen_lock", found(), connected.copy(noScreenLock = true))
    @Test fun found_tv_refuses_home() = setup("found_tv_refuses_home", found(noHomePlan), connected.copy(tvName = "TCL C635", homePossible = false))
    @Test fun found_starting() = setup("found_starting", found(), connected.copy(starting = true))

    // S4a Making it clean
    @Test fun working_58() = setup("working_58", running(narration, 58), working)
    @Test fun working_all_narration_states() = setup("working_all_narration_states", running(narrationAllStates, 72), working)
    @Test fun working_no_thumbnail() = setup("working_no_thumbnail", running(narration, 12, thumbnail = null), working)
    @Test fun working_rolling_back() = setup("working_rolling_back", running(narration, 58), working.copy(rollingBack = true, highlightStop = true))

    // S4b Can you see it?
    @Test fun check_84s() = setup("check_84s", checkState(), connected)
    @Test fun check_home_checked() = setup("check_home_checked", checkState(homeCheck = true, deadlineAheadMs = 60_000), connected)
    @Test fun check_no_thumbnail() = setup("check_no_thumbnail", checkState(thumbnail = null), connected)
    @Test fun check_two_minute_window() = setup("check_two_minute_window", checkState(deadlineAheadMs = 120_000, thumbnail = null), connected)

    // S4c Stopped: the old home screen is back (UX 4.7)
    @Test fun stopped_home_setupwraith() = setup("stopped_home_setupwraith", stop(StopCase.HOME_SETUPWRAITH, "The old home screen is back", SETUPWRAITH_BODY,
        "Try with the wizard off", "Leave the old home screen"), connected.copy(commands = commands))
    @Test fun stopped_home_rolling_back() = setup("stopped_home_rolling_back", stop(StopCase.HOME_SETUPWRAITH, "The old home screen is back", SETUPWRAITH_BODY,
        "Try with the wizard off", "Leave the old home screen"), connected.copy(rollingBack = true))
    @Test fun stopped_home_other_launcher() = setup("stopped_home_other_launcher", stop(StopCase.HOME_OTHER_LAUNCHER, "The old home screen is back",
        "Another home screen app, Projectivy, is in charge of Home. We don't switch off apps we don't know. Choose which one you want.",
        "Use the new home screen", "Keep Projectivy"), connected.copy(commands = commands))
    @Test fun stopped_home_unknown() = setup("stopped_home_unknown", stop(StopCase.HOME_UNKNOWN, "The old home screen is back", UNKNOWN_BODY,
        "Share diagnostics", "OK"), connected.copy(commands = commands))
    @Test fun stopped_home_user_said_no() = setup("stopped_home_user_said_no", stop(StopCase.HOME_USER_SAID_NO, "The old home screen is back",
        "Put back. Was something wrong, or did you just want to look first?", "Try again", "Something looked wrong"), connected.copy(commands = commands))
    @Test fun stopped_home_timed_out() = setup("stopped_home_timed_out", stop(StopCase.HOME_TIMED_OUT, "The old home screen is back",
        "Putting the old home screen back... Done. Your TV is as it was. You can try again from Manage.", "OK", "Try again"), connected.copy(commands = commands))

    // S4d Stopped: connection lost mid-plan (UX 4.14)
    @Test fun stopped_connection_lost_reconnecting() = setup("stopped_connection_lost_reconnecting", connectionLost(),
        SetupExtras(tvName = "Mi TV 4A", chip = ChipState.OFFLINE, reconnect = Reconnect.TRYING))
    @Test fun stopped_connection_lost_back() = setup("stopped_connection_lost_back", connectionLost(),
        SetupExtras(tvName = "Mi TV 4A", chip = ChipState.CONNECTED, reconnect = Reconnect.BACK))
    @Test fun stopped_connection_lost_gave_up() = setup("stopped_connection_lost_gave_up", connectionLost(),
        SetupExtras(tvName = "Mi TV 4A", chip = ChipState.OFFLINE, reconnect = Reconnect.GAVE_UP))
    // The step-failed copy is not in strings.xml yet; this is the sentence the card would carry.
    @Test fun stopped_step_failed() = setup("stopped_step_failed", stop(StopCase.STEP_FAILED, "We stopped after one step failed",
        "The TV refused a change after we switched off 14 of 23 things. Everything done so far can be undone.",
        "Carry on with the rest", "Stop here", "Undo what we did"), connected)

    // S4e Resume after process death
    @Test fun stopped_resume() = setup("stopped_resume", resume(), SetupExtras(tvName = "Mi TV 4A", chip = ChipState.WAITING))
    @Test fun stopped_resume_with_home() = setup("stopped_resume_with_home", stop(StopCase.RESUME_AFTER_DEATH, "We put the old home screen back",
        "We've put the old home screen back to be safe. Try the switch again?", "Try the switch again", "Leave it as is"),
        SetupExtras(tvName = "Mi TV 4A", chip = ChipState.WAITING))

    // Install failure, undone, probe failure
    @Test fun stopped_install_failed() = setup("stopped_install_failed", stop(StopCase.INSTALL_FAILED, "The new home screen couldn't be installed",
        INSTALL_FAILED_BODY, "OK", "Show details"), connected)
    @Test fun stopped_undone() = setup("stopped_undone", stop(StopCase.UNDONE, "We undid what we did",
        "Your TV is as it was. You can try again from Manage.", "OK", "Show details"), connected.copy(commands = commands))
    @Test fun stopped_probe_failed() = setup("stopped_probe_failed", stop(StopCase.PROBE_FAILED, "We couldn't read what's on the TV",
        "The TV stopped answering while we were reading it. Nothing was changed. Check that it's still on, then try again.",
        "Try again", "Back", "Show details"), connected)

    // S5 Done
    @Test fun done_home_on() = setup("done_home_on", done())
    @Test fun done_home_on_restart_recommended() = setup("done_home_on_restart_recommended", done(recommended = true))
    @Test fun done_without_home() = setup("done_without_home", done(off = 16, home = false))
    @Test fun done_skipped_one() = setup("done_skipped_one", done(off = 22, skipped = listOf("Play Games")))
    @Test fun done_skipped_many() = setup("done_skipped_many", done(off = 20, skipped = listOf("Play Games", "Mi Cast", "Mi Home")))
    @Test fun done_restarting() = setup("done_restarting", done(restart = RestartState.Restarting(NOW)))
    @Test fun done_restarting_slow() = setup("done_restarting_slow", done(restart = RestartState.Restarting(NOW)), connected.copy(restartSlow = true))
    @Test fun done_back_after_restart() = setup("done_back_after_restart", done(restart = RestartState.Back(72_000)), connected.copy(restartReenabled = 2))
    @Test fun done_not_back() = setup("done_not_back", done(restart = RestartState.NotBack(300_000)), connected.copy(card = StopCard(StopCase.NOT_BACK_AFTER_RESTART,
        "The TV hasn't come back", NOT_BACK_BODY, "It's on again", "Help me", planId = null)))
    @Test fun done_finishing() = setup("done_finishing", done(), connected.copy(finishing = Finishing.SWITCHING))
    @Test fun done_finished() = setup("done_finished", done(), SetupExtras(tvName = "Mi TV 4A", chip = ChipState.DEBUGGING_OFF, finishing = Finishing.DONE))
}

// 200% font scale of the key screens of this module.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SetupScreenshotFontScaleTest {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    private fun setup(name: String, state: SetupState) =
        compose.shot(name, fontScale = 2f) { SetupFlow(state, connected, NoSetupActions, nowMs = { NOW }) }

    @Test fun found_simple_font200() = setup("found_simple_font200", found())
    @Test fun check_font200() = setup("check_font200", checkState())
    @Test fun done_home_on_font200() = setup("done_home_on_font200", done())
}

// Right-to-left layout direction of the key screens of this module.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SetupScreenshotRtlTest {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    private fun setup(name: String, state: SetupState) =
        compose.shot(name, rtl = true) { SetupFlow(state, connected, NoSetupActions, nowMs = { NOW }) }

    @Test fun found_simple_rtl() = setup("found_simple_rtl", found())
    @Test fun check_rtl() = setup("check_rtl", checkState())
    @Test fun done_home_on_rtl() = setup("done_home_on_rtl", done())
}
