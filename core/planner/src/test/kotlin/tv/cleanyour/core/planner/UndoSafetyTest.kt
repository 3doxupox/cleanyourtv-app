package tv.cleanyour.core.planner

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.planner.PlannerFixtures.change
import tv.cleanyour.core.planner.PlannerFixtures.installedLauncher
import tv.cleanyour.core.planner.PlannerFixtures.launcher
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.stock
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.store.SnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// WP4 G4 review: Undo everything (and Before a factory reset) on TVs the earlier builds and the
// 0.4.5 update path leave behind.
class UndoSafetyTest {
    private val mic = PackageName("com.google.android.tv.remote.service")
    private val chooser = Component(PackageName("android"), "com.android.internal.app.ResolverActivity")
    private val helpers = "com.gathin.tvhome/com.gathin.tvhome.InputKeyService"

    private fun Snapshot.with(pkg: PackageName, state: EnabledState) =
        copy(packages = packages + (pkg to PackageState(state, true, 1, null, false, false, null, false))).let { it.copy(contentHash = SnapshotCodec.hash(it)) }

    private fun undo(s: Snapshot, changes: List<ChangeRecord>, planner: DefaultPlanner = PlannerFixtures.planner()): Plan {
        val r = planner.plan(PlanRequest.UndoEverything(changes, removeLauncher = false), s, PlannerFixtures.facts, changes)
        return assertIs<PlanResult.Ok>(r, r.toString()).plan
    }

    // BLOCKING: an earlier build switched off TCL G10's remote mic service, which the static
    // denylist protects now. Its DefaultState inverse is a write the renderer refuses, which used
    // to refuse the whole Undo everything. Off now: one Enable. On now (put back since): no step.
    // An inverse that would switch it off is never planned.
    @Test fun aProtectedPackageOnlyEverGoesBackOn() {
        val off = listOf(change(0, Op.Disable(mic), Prior(packageState = EnabledState.DEFAULT)))
        assertEquals(listOf<Op>(Op.Enable(mic)), undo(snapshot().with(mic, EnabledState.DISABLED_USER), off).steps.map { it.op })
        assertTrue(undo(snapshot().with(mic, EnabledState.ENABLED), off).steps.none { DefaultPlanner.packageOf(it.op) == mic })
        // Our record that switched it on from off: the inverse would be a Disable; never planned.
        val on = listOf(change(0, Op.Enable(mic), Prior(packageState = EnabledState.DISABLED_USER)))
        assertTrue(undo(snapshot().with(mic, EnabledState.ENABLED), on).steps.isEmpty())
        // A package that is not protected keeps its exact inverse.
        val feedback = PackageName("com.google.android.feedback")
        val plain = listOf(change(0, Op.Disable(feedback), Prior(packageState = EnabledState.DEFAULT)))
        assertEquals(listOf<Op>(Op.DefaultState(feedback)), undo(snapshot().with(feedback, EnabledState.DISABLED_USER), plain).steps.map { it.op })
    }
}
