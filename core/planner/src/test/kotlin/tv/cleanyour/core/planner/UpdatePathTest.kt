package tv.cleanyour.core.planner

import tv.cleanyour.core.model.LauncherArtifact
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.ops.Transfer
import tv.cleanyour.core.planner.PlannerFixtures.installedLauncher
import tv.cleanyour.core.planner.PlannerFixtures.launcher
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.stock
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Samples
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// WP4 item 1 (upstream #1101): "Update the home screen" restarts the updated launcher when it is
// Home, then puts the launcher's remote button helpers back exactly as the TV had them. The three
// cases below are also pinned as "Show commands" goldens under src/test/resources/golden-plans, so
// a change to any line the person can read before tapping is a reviewed diff.
class UpdatePathTest {
    private val ours = Samples.launcherArtifact()
    private val pin = LauncherArtifact(ours.pkg, 9, "3.0", ours.sha256, ours.signer, ours.blob)
    private val helpers = "com.gathin.tvhome/com.gathin.tvhome.InputKeyService:com.example.reader/.Reader"
    private fun ops(p: Plan) = p.steps.map { it.op }
    private fun names(p: Plan) = ops(p).map { it::class.simpleName }

    private fun tv(home: Boolean, helperOn: Boolean): Snapshot {
        val base = if (home) snapshot(home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher(signer = ours.signer, versionCode = 8)))
        else snapshot(extra = mapOf(installedLauncher(signer = ours.signer, versionCode = 8)))
        val a11y = if (helperOn) mapOf(SettingKey.ENABLED_ACCESSIBILITY to helpers, SettingKey.ACCESSIBILITY_ENABLED to "1")
        else mapOf(SettingKey.ACCESSIBILITY_ENABLED to "0")
        return base.withSettings(base.settings + a11y)
    }

    private val putBack = listOf(
        Op.Settle(1), Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, null), Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0"),
        Op.Settle(1), Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, helpers), Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"),
    )

    @Test fun anUpdateWithAccessibilityOffHasNoSettingsSteps() {
        val p = plan(PlanRequest.UpdateLauncher(pin), snapshot = tv(home = true, helperOn = false))
        assertEquals(listOf("InstallStream", "RestartHome"), names(p))
        val restart = p.steps[1]
        assertEquals(Op.RestartHome(launcher), restart.op)
        assertEquals(DefaultPlanner.NARRATION_RESTART_UPDATED, restart.narration)
        assertEquals(FailurePolicy.CONTINUE, restart.onFailure)
        assertEquals(DefaultPlanner.GROUP_LAUNCHER, restart.group)
        assertTrue(ops(p).none { it is Op.StartActivity }, "the restart replaces the plain start")
        golden("update-home-accessibility-off", p)
    }

    @Test fun anUpdateWithOurHelperOnPutsItBackInSixSteps() {
        val p = plan(PlanRequest.UpdateLauncher(pin), snapshot = tv(home = true, helperOn = true))
        assertEquals(listOf(Op.RestartHome(launcher)) + putBack, ops(p).drop(1))
        assertTrue(p.steps.drop(1).all { it.onFailure == FailurePolicy.CONTINUE && it.group == DefaultPlanner.GROUP_LAUNCHER })
        assertEquals(helpers, p.steps[3].prior?.settingValue)
        assertEquals("1", p.steps[4].prior?.settingValue)
        golden("update-home-helper-on", p)
    }

    @Test fun anUpdateWhileAnotherAppIsHomePutsTheHelperBackWithoutARestart() {
        val p = plan(PlanRequest.UpdateLauncher(pin), snapshot = tv(home = false, helperOn = true))
        assertEquals(listOf("InstallStream") + putBack.map { it::class.simpleName }, names(p))
        assertEquals(putBack, ops(p).drop(1))
        assertTrue(ops(p).none { it is Op.RestartHome || it is Op.StartActivity }, "not Home: nothing is started")
        golden("update-not-home-helper-on", p)
        // And with accessibility off there: the install alone.
        assertEquals(listOf("InstallStream"), names(plan(PlanRequest.UpdateLauncher(pin), snapshot = tv(home = false, helperOn = false))))
    }

    // RF-LAUNCHER-240 F7: the update never switches accessibility on. Off, unset, or someone else's
    // service only: no settings step at all. And whatever the case, every "on" step writes back a
    // value the TV already had.
    @Test fun theUpdateNeverSwitchesAHelperOn() {
        val home = tv(home = true, helperOn = false)
        val cases = listOf(
            home.withSettings(home.settings + mapOf(SettingKey.ENABLED_ACCESSIBILITY to helpers, SettingKey.ACCESSIBILITY_ENABLED to "0")),
            home.withSettings(home.settings - SettingKey.ACCESSIBILITY_ENABLED + mapOf(SettingKey.ENABLED_ACCESSIBILITY to helpers)),
            home.withSettings(home.settings + mapOf(SettingKey.ENABLED_ACCESSIBILITY to "com.example.reader/.Reader", SettingKey.ACCESSIBILITY_ENABLED to "1")),
        )
        for (s in cases) assertTrue(ops(plan(PlanRequest.UpdateLauncher(pin), snapshot = s)).none { it is Op.SettingsPut }, "${s.settings}")
        for (s in listOf(tv(home = true, helperOn = true), tv(home = false, helperOn = true))) {
            for (op in ops(plan(PlanRequest.UpdateLauncher(pin), snapshot = s)).filterIsInstance<Op.SettingsPut>()) {
                if (op.value != null && op.value != "0") assertEquals(s.settings[op.key], op.value, "only what the TV had")
            }
        }
    }

    // "Show commands" as the person sees it (SetupMachine.commandsOf: each rendered line, a transfer
    // as its sync line), with the step's group, failure policy, narration and prior around it.
    private fun golden(name: String, p: Plan) {
        val sb = StringBuilder("# ").append(name).append('\n')
        for (s in p.steps) {
            sb.append("step ").append(s.index).append(": group=").append(s.group).append(" policy=").append(s.onFailure.name)
                .append(" narration=").append(s.narration).append('\n')
            s.prior?.let { pr -> sb.append("  prior: ").append(listOfNotNull(pr.packageState?.let { "packageState=$it" }, pr.settingValue?.let { "settingValue=$it" },
                pr.home?.let { "home=${it.flat}" }, pr.present?.let { "present=$it" }).joinToString(" ").ifEmpty { "empty" }).append('\n') }
            s.rendered.lines.forEach { sb.append("  ").append(it.text).append('\n') }
            when (val t = s.rendered.transfer) {
                is Transfer.Push -> sb.append("  sync:${t.dest.absolute} ${t.src.sha256.hex}\n")
                is Transfer.Pull -> sb.append("  sync:${t.src.absolute} ${t.maxBytes}\n")
                is Transfer.StreamInstall -> sb.append("  sync:${t.src.path} ${t.size}\n")
                null -> {}
            }
        }
        val actual = sb.toString()
        val file = File(Fixtures.repoRoot, "core/planner/src/test/resources/golden-plans/$name.txt")
        val expected = if (file.isFile) file.readText() else null
        if (expected != actual) {
            // Written beside the build for review, never over the golden file.
            File(Fixtures.repoRoot, "core/planner/build/golden-actual").apply { mkdirs() }.resolve("$name.txt").writeText(actual)
            fail("golden-plans/$name.txt ${if (expected == null) "missing" else "differs"}; actual:\n$actual")
        }
    }
}
