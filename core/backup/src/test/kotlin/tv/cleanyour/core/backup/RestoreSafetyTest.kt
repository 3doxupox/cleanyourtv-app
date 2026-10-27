package tv.cleanyour.core.backup

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// WP4 G4 review: the restore files and the laptop restore for records the earlier builds and the
// 0.4.5 update path leave behind.
class RestoreSafetyTest {
    private val stock = Samples.stockLauncher
    private val launcher = Samples.launcherComponent
    private val chooser = Component(PackageName("android"), "com.android.internal.app.ResolverActivity")
    private val helpers = "com.gathin.tvhome/com.gathin.tvhome.InputKeyService"
    private fun rec(step: Int, op: Op, prior: Prior?) = ChangeRecord(PlanId("01SETUPPLAN000000000000001"), step, op, prior, Samples.NOW_MS + step)

    private val takeover = listOf(
        rec(0, Op.ArmHomeWatchdog(stock, 150, RunToken("00000000000000aa")), null),
        rec(1, Op.Disable(stock.pkg), Prior(packageState = EnabledState.ENABLED)),
        rec(2, Op.SetHome(launcher), Prior(home = chooser)),
    )

    // (3) A pre-0.4.5 journal with the chooser as the SetHome prior: homeBefore (restore-point.json,
    // restore.txt, the note) and the laptop's SetHome name the stock home the switch armed its
    // watchdog with; without that record the laptop step says why it cannot put HOME back.
    @Test fun aPlaceholderHomePriorIsNeverTheHomeToGoBackTo() {
        assertEquals(stock, ChangeSummary.of(takeover).homeBefore)
        assertEquals(null, ChangeSummary.of(takeover.drop(1)).homeBefore)
        val home = RestoreCommands(ShellRenderer()).steps(takeover).single { it.change.op is Op.SetHome }
        assertEquals(RestoreStepKind.PUT_BACK, home.kind)
        assertEquals(Op.SetHome(stock), home.inverse)
        val none = RestoreCommands(ShellRenderer()).steps(takeover.drop(1)).single { it.change.op is Op.SetHome }
        assertEquals(RestoreStepKind.NOT_FROM_LAPTOP, none.kind)
        assertTrue(none.lines.isEmpty())
        // A real home screen prior is used as it is.
        val real = takeover.map { if (it.op is Op.SetHome) it.copy(prior = Prior(home = stock)) else it }
        assertEquals(stock, ChangeSummary.of(real).homeBefore)
    }

    // (1) The helper put back's records: a laptop script cannot read the TV first, so it never
    // writes accessibility_enabled 1 or the service list back; those steps say why instead.
    @Test fun theLaptopRestoreNeverSwitchesAccessibilityOn() {
        val putBack = listOf(
            rec(0, Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, null), Prior(settingValue = helpers)),
            rec(1, Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0"), Prior(settingValue = "1")),
            rec(2, Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, helpers), Prior(settingValue = helpers)),
            rec(3, Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"), Prior(settingValue = "1")),
        )
        val steps = RestoreCommands(ShellRenderer()).steps(putBack)
        assertTrue(steps.isNotEmpty())
        assertTrue(steps.all { it.kind == RestoreStepKind.NOT_FROM_LAPTOP && it.lines.isEmpty() }, steps.toString())
        assertTrue(steps.none { "accessibility_enabled' '1'" in it.lines.joinToString() })
        // Accessibility off before us: the off write goes back as it was, which switches nothing on.
        val offBefore = listOf(rec(0, Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"), Prior(settingValue = "0")))
        assertEquals(listOf(RestoreStepKind.PUT_BACK), RestoreCommands(ShellRenderer()).steps(offBefore).map { it.kind })
    }
}
