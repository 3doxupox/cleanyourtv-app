package tv.cleanyour.core.planner

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.LauncherArtifact
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.planner.PlannerFixtures.facts
import tv.cleanyour.core.planner.PlannerFixtures.installedLauncher
import tv.cleanyour.core.planner.PlannerFixtures.launcher
import tv.cleanyour.core.planner.PlannerFixtures.planner
import tv.cleanyour.core.planner.PlannerFixtures.setup
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.stock
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.testing.fixtures.Samples
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

// SECURITY.md (Launcher 3.0) and RF-LAUNCHER-240 F7: the app never switches an accessibility service
// on; it only puts back what the TV had. Over seeded random TVs (accessibility on, off or unknown;
// lists with the launcher's helpers, someone else's service, both or none) and every kind of plan
// that can touch accessibility, each SettingsPut of enabled_accessibility_services or
// accessibility_enabled writes null, "0" or the snapshot's own value; Undo everything may also
// write the value a change record says the TV had before the app changed it.
class AccessibilityPutBackPropertyTest {
    private val rnd = Random(0x5EED_0A11)
    private val accessibility = setOf(SettingKey.ENABLED_ACCESSIBILITY, SettingKey.ACCESSIBILITY_ENABLED)
    private val helpers = listOf("com.gathin.tvhome/com.gathin.tvhome.InputKeyService", "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService",
        "com.gathin.tvhome/.HomeKeyService")
    private val others = listOf("com.example.reader/.Reader", "com.vendor.voice/com.vendor.voice.Assist")
    private val config = BlobRef(Sha256("c".repeat(64)), 100, "blob:c")
    private val ours = Samples.launcherArtifact()

    private fun randomTv(): Snapshot {
        val home = if (rnd.nextBoolean()) launcher else stock
        val base = snapshot(home = home, claimants = listOf(launcher, stock).shuffled(rnd),
            extra = if (rnd.nextInt(5) != 0) mapOf(installedLauncher(versionCode = rnd.nextLong(1, 4))) else emptyMap())
        val list = (helpers.shuffled(rnd).take(rnd.nextInt(0, 3)) + others.shuffled(rnd).take(rnd.nextInt(0, 2))).shuffled(rnd)
        val settings = base.settings.toMutableMap()
        if (list.isNotEmpty() || rnd.nextBoolean()) settings[SettingKey.ENABLED_ACCESSIBILITY] = list.joinToString(":")
        when (rnd.nextInt(3)) {
            0 -> settings[SettingKey.ACCESSIBILITY_ENABLED] = "1"
            1 -> settings[SettingKey.ACCESSIBILITY_ENABLED] = "0"
            else -> {}
        }
        return base.withSettings(settings)
    }

    private fun requests(): List<PlanRequest> = listOf(
        setup(choices = choices(takeover = rnd.nextBoolean(), installLauncher = rnd.nextBoolean())),
        PlanRequest.ApplyTheme(LauncherConfigDoc(columns = rnd.nextInt(4, 8)), config, null),
        PlanRequest.UpdateLauncher(LauncherArtifact(ours.pkg, rnd.nextLong(2, 10), "3.0", ours.sha256, ours.signer, ours.blob)),
        PlanRequest.UseStockHome(stock),
        PlanRequest.BeforeFactoryReset,
        PlanRequest.DebugOff,
        PlanRequest.Reboot,
    )

    private fun check(plan: Plan, snap: Snapshot, journals: List<ChangeRecord>, label: String): Int {
        var puts = 0
        val had = journals.filter { (it.op as? Op.SettingsPut)?.key in accessibility }.map { it.prior?.settingValue }.toSet()
        for (s in plan.steps) {
            val op = s.op as? Op.SettingsPut ?: continue
            if (op.key !in accessibility) continue
            puts++
            val ok = op.value == null || op.value == "0" || op.value == snap.settings[op.key] ||
                (plan.kind == PlanKind.UNDO_EVERYTHING && op.value in had)
            assertTrue(ok, "$label writes ${op.key.key} = ${op.value}; the TV had ${snap.settings[op.key]}")
        }
        return puts
    }

    @Test fun everyAccessibilityWriteIsOffOrWhatTheTvHad() {
        var puts = 0; var plans = 0
        repeat(300) { i ->
            val snap = randomTv()
            for (req in requests()) {
                val r = planner(OverlayTransport()).plan(req, snap, facts, emptyList())
                val plan = (r as? PlanResult.Ok)?.plan ?: continue
                plans++
                puts += check(plan, snap, emptyList(), "#$i ${req::class.simpleName}")
                // Apply it to a model TV, then undo everything from its change records.
                val tv = ModelTv(snap)
                tv.apply(plan)
                val applied = tv.snapshot()
                val undo = planner(OverlayTransport()).plan(PlanRequest.UndoEverything(tv.changes, removeLauncher = rnd.nextBoolean()), applied, facts, tv.changes)
                (undo as? PlanResult.Ok)?.plan?.let { puts += check(it, applied, tv.changes, "#$i undo after ${req::class.simpleName}") }
            }
        }
        assertTrue(plans > 1000 && puts > 150, "generator lost coverage: plans=$plans, accessibility writes=$puts")
    }
}
