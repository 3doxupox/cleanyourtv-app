package tv.cleanyour.core.backup

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.testing.fixtures.sha256Of
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestoreCommandsTest {
    private val steps = RestoreCommands(ReferenceRenderer()).steps(Scenario.changes())

    @Test fun newestFirstAndOnlyLastingChanges() {
        val ops = steps.map { it.inverse }
        // the put back is undone first, the debloat last; reads, the watchdog, restarts, mkdir,
        // settle, debugging off and the restore point files leave no step
        assertEquals(Op.Disable(Scenario.putBackPkg), ops.first())
        assertEquals(Op.Enable(Scenario.disabledAfter.first()), ops.last())
        assertTrue(steps.none { it.change.op is Op.Read || it.change.op is Op.Mkdir || it.change.op is Op.KillWatchdog || it.change.op is Op.DebugOff })
        assertTrue(steps.none { RestoreCommands.isRestorePointFile(it.change.op) })
        val stockOn = ops.indexOf(Op.Enable(Scenario.stock.pkg))
        val homeBack = ops.indexOf(Op.SetHome(Scenario.stock))
        val removeOurs = ops.indexOf(Op.Uninstall(Scenario.launcher.pkg))
        assertTrue(stockOn in 0 until homeBack && homeBack < removeOurs, "stock on, then home back, then our launcher removed: $ops")
    }

    @Test fun sameMillisecondFallsBackToThePlanStep() {
        // two steps journaled in the same millisecond, listed out of order: the later step is undone first
        val plan = PlanId("p")
        val a = PackageName("com.example.first")
        val b = PackageName("com.example.second")
        val changes = listOf(
            ChangeRecord(plan, 1, Op.Disable(b), Prior(packageState = EnabledState.ENABLED), 5),
            ChangeRecord(plan, 0, Op.Disable(a), Prior(packageState = EnabledState.ENABLED), 5),
        )
        assertEquals(listOf(Op.Enable(b), Op.Enable(a)), RestoreCommands(ReferenceRenderer()).steps(changes).map { it.inverse })
    }

    @Test fun kindsAndUndoRules() {
        assertEquals(RestoreStepKind.REMOVE_APP, steps.single { it.inverse is Op.Uninstall }.kind)
        assertEquals(Op.DefaultState(Scenario.defaultStatePkg), steps.single { it.change.op == Op.Disable(Scenario.defaultStatePkg) }.inverse)
        assertEquals(Op.SettingsPut(SettingKey.SCREENSAVER_COMPONENTS, null), steps.single { (it.change.op as? Op.SettingsPut)?.key == SettingKey.SCREENSAVER_COMPONENTS }.inverse)
        assertTrue(steps.filter { it.kind != RestoreStepKind.NOT_FROM_LAPTOP }.all { it.lines.isNotEmpty() && it.note == null })
        assertEquals(listOf("pm enable --user 0 'com.xiaomi.statistic'"), steps.single { it.inverse == Op.Enable(PackageName("com.xiaomi.statistic")) }.lines)
    }

    @Test fun whatCannotBeDoneFromALaptopSaysWhy() {
        val pkg = PackageName("com.example.app")
        val plan = PlanId("p")
        val changes = listOf(
            ChangeRecord(plan, 0, Op.Disable(pkg), null, 1),                                                    // no prior
            ChangeRecord(plan, 1, Op.Uninstall(pkg), Prior(present = true), 2),                                  // no inverse
            ChangeRecord(plan, 2, Op.SettingsPut(SettingKey.ADB_ENABLED, "0"), Prior(settingValue = "1"), 3),   // refused: adb_enabled only takes 0
        )
        val s = RestoreCommands(ReferenceRenderer()).steps(changes)
        assertEquals(3, s.size)
        assertTrue(s.all { it.kind == RestoreStepKind.NOT_FROM_LAPTOP && it.lines.isEmpty() && !it.note.isNullOrEmpty() }, s.toString())
        assertTrue(s[0].note!!.contains("refused"), s[0].note)
    }

    @Test fun anUndoThatNeedsAFileFromThePhoneIsNotALaptopLine() {
        // a renderer whose undo of an uninstall is a reinstall from the phone's cache (a transfer)
        val blob = BlobRef(sha256Of("apk"), 3, "mem:apk")
        val pkg = PackageName("com.example.app")
        val reinstalling = object : OpRenderer by ReferenceRenderer() {
            override fun inverse(op: Op, prior: Prior): Op? =
                if (op is Op.Uninstall) Op.InstallStream(blob, blob.sha256, op.pkg, 1, Samples.zeroHash) else null
        }
        val s = RestoreCommands(reinstalling).steps(listOf(ChangeRecord(PlanId("p"), 0, Op.Uninstall(pkg), Prior(present = true), 1)))
        assertEquals(RestoreStepKind.NOT_FROM_LAPTOP, s.single().kind)
        assertTrue(s.single().note!!.contains("file from the phone"), s.single().note)
    }
}
