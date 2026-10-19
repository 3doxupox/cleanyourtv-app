package tv.cleanyour.core.flows

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.Choices
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.planner.disabledPackages
import tv.cleanyour.core.planner.switchOffCount
import tv.cleanyour.core.planner.switchOffPackages
import tv.cleanyour.core.probe.AdbProber
import tv.cleanyour.core.spec.AppNames
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.DockRanking
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.FakeClock
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Emulator run, round 3: on the emulator TV (an unknown model, Simple) S3 said nothing was found to
// switch off while the plan log said "9 steps, 1 to switch off": the home screen switch switched the
// TV's own home screen off and no screen named it. Here S3's numbers (the numeral, the group rows,
// "n of m off" in the list), the list's Off rows, "Show commands" and the plan "Make it clean" runs
// must agree exactly, with the bundled profiles and facts the app ships, for the reference Mi TV,
// an unknown model and the emulator capture.
class WhatWeFoundMatchesThePlanTest {
    private val facts = BundledFactsTable()
    private val profiles = BundledProfileSource()
    private val planner = DefaultPlanner(FakeClock(), FakeIdGen(), ShellRenderer(), FakeConfigTransport(), "0.1.0")
    private val launcher = Samples.launcherArtifact()

    // The Setup request SetupMachine.replan sends for a first visit in Simple: nothing kept, nothing
    // extra, the auto-picked dock, the launcher and the switch when the TV allows them.
    private fun plan(snapshot: Snapshot): Pair<Plan, ProfileResolution> {
        val res = profiles.resolveNow(snapshot.facts)
        val profile = (res as? ProfileResolution.Exact)?.profile
        val dock = DockRanking.pick(profile, snapshot.packages.keys, snapshot.hdmi.isNotEmpty(), profile?.liveTv, "en")
        val choices = Choices(emptySet(), emptySet(), dock, null, installLauncher = true, takeover = true, replaceLauncherSigner = false, keepDebuggingOn = false)
        val r = planner.plan(PlanRequest.Setup(res, choices, launcher, Level.SIMPLE), snapshot, facts, emptyList())
        return assertIs<PlanResult.Ok>(r, r.toString()).plan to res
    }

    // Every rule the screen and the plan share, in one place.
    private fun assertS3AgreesWithThePlan(plan: Plan) {
        val summary = plan.summary
        val disabled = plan.disabledPackages
        // S3's numeral and headline.
        assertEquals(disabled.size, summary.switchOffCount, "S3 counts every switch-off of the plan")
        // The summary card's group rows add up to the same number.
        assertEquals(disabled.size, summary.groups.filter { it.off > 0 }.sumOf { it.off })
        // "See the list": the Off rows are exactly the packages the plan switches off, once each.
        assertEquals(disabled.sortedBy { it.value }, summary.switchOffPackages.sortedBy { it.value })
        assertEquals(disabled.size, disabled.toSet().size)
        // Each group's count is its own Off rows.
        for (g in summary.groups) {
            assertEquals(g.rows.count { it.off }, g.off, g.id)
            assertEquals(g.rows.size, g.total, g.id)
        }
        // A row the list shows as kept is never switched off; nothing protected and kept is either.
        val keptRows = summary.groups.flatMap { it.rows }.filter { !it.off }.map { it.pkg }
        assertTrue(keptRows.none { it in disabled }, "a kept row is switched off: ${keptRows.filter { it in disabled }}")
        // "Show commands": every step of the plan, in order, nothing else, so every write is shown.
        val commands = SetupMachine.commandsOf(plan)
        val expected = plan.steps.flatMap { s -> s.rendered.lines.map { it.text } + listOfNotNull(s.rendered.transfer?.let { "" }) }
        assertEquals(expected.size, commands.size, "one line per rendered line and transfer")
        for (step in plan.steps) for (line in step.rendered.lines) assertTrue(line.text in commands, line.text)
        for (pkg in disabled) assertTrue(commands.any { pkg.value in it && "disable" in it }, "$pkg missing from Show commands")
    }

    @Test fun referenceMiTv() {
        val (plan, res) = plan(Samples.snapshot(packages = Samples.miTvPackages(emptySet())))
        assertIs<ProfileResolution.Exact>(res)
        assertS3AgreesWithThePlan(plan)
        // The debloat and the TV's own home screen, inside the switch.
        val home = plan.summary.groups.single { it.id == DefaultPlanner.GROUP_HOME }
        assertTrue(home.rows.any { it.pkg == Samples.stockLauncher.pkg && it.off && it.protected })
        assertTrue(plan.summary.switchOffCount > 1)
    }

    @Test fun referenceMiTvAfterItsHandDebloat() {
        // The capture's own state: most of the list already off. Only what is still on is counted.
        val (plan, _) = plan(Samples.snapshot())
        assertS3AgreesWithThePlan(plan)
        val alreadyOff = Fixtures.miTv().disabledAfter.map(::PackageName).toSet()
        assertTrue(plan.summary.switchOffPackages.none { it in alreadyOff })
    }

    @Test fun unknownModel() {
        val facts = Samples.miTvFacts().copy(brand = "Acme", manufacturer = "Acme", model = "ACME-55", device = "acme55", fingerprint = "acme/acme55:9/x")
        val snapshot = Samples.snapshot(facts = facts, packages = Samples.miTvPackages(emptySet()))
        val (plan, res) = plan(snapshot)
        assertIs<ProfileResolution.None>(res)
        assertS3AgreesWithThePlan(plan)
        // Nothing is sure on an unknown model: the one switch-off is the TV's own home screen.
        assertEquals(listOf(Samples.stockLauncher.pkg), plan.disabledPackages)
        assertEquals(1, plan.summary.switchOffCount)
    }

    @Test fun unknownXiaomiModelWithTheBrandSureSet() {
        val facts = Samples.miTvFacts().copy(model = "MiTV-UNKNOWN", device = "unknown")
        val (plan, res) = plan(Samples.snapshot(facts = facts, packages = Samples.miTvPackages(emptySet())))
        assertIs<ProfileResolution.BrandSureSet>(res)
        assertS3AgreesWithThePlan(plan)
    }

    @Test fun emulatorCapture() = runTest {
        val tv = Fixtures.emulatorTv()
        val snapshot = AdbProber(FakeClock()).snapshot(tv.session(), SnapshotReason.BASELINE) {}
        // What the probe read: Android TV home is HOME, FallbackHome the other claimant, nothing off.
        assertEquals(Component(PackageName("com.google.android.tvlauncher"), ".HomeScreen"), snapshot.home.resolved)
        assertTrue(snapshot.packages.values.none { it.enabled == EnabledState.DISABLED_USER })
        val (plan, res) = plan(snapshot)
        assertTrue(res !is ProfileResolution.Exact, "the emulator is an unknown model")
        assertS3AgreesWithThePlan(plan)

        // The plan log's "9 steps, 1 to switch off": the streamed launcher install, then the eight
        // steps of the home screen switch, whose one switch-off is the TV's own home screen.
        assertEquals(9, plan.steps.size)
        assertEquals(
            listOf("InstallStream", "StartActivity", "Mkdir", "ArmHomeWatchdog", "Disable", "SetHome", "VerifyHome", "ConfirmHome", "KillWatchdog"),
            plan.steps.map { it.op::class.simpleName },
        )
        assertEquals(listOf(PackageName("com.google.android.tvlauncher")), plan.disabledPackages)
        assertTrue(plan.steps.none { it.op is Op.Settle }, "no debloat, so no settle")
        // S3 now says one thing to switch off, under "Needed for the new home screen", by its name.
        assertEquals(1, plan.summary.switchOffCount)
        val row = plan.summary.groups.single { it.off > 0 }.let { g ->
            assertEquals(DefaultPlanner.TITLE_HOME, g.title)
            g.rows.single()
        }
        assertEquals("Android TV home", row.friendlyName)
        assertEquals(DefaultPlanner.DESCRIPTION_OLD_HOME, row.description)
        // The rollback puts it back, and the section guards the whole switch.
        assertTrue(Op.Enable(row.pkg) in plan.sections.single().rollback)
    }

    @Test fun emulatorCaptureProbeStaysUnderTenShellCallsAndNamesTheDockApps() = runTest {
        val session = Fixtures.emulatorTv().session()
        val prober = AdbProber(FakeClock())
        val snapshot = prober.snapshot(session, SnapshotReason.BASELINE) {}
        val launchable = prober.launchable(session)
        // The names come without a new shell call: the probe and the launchable line, under ten.
        assertTrue(session.execLines.size < 10, "${session.execLines.size} shell calls: ${session.execLines}")
        // The dock sheet's candidates on the emulator: readable names, no home screen among them.
        val apps = DockCandidates.of(snapshot, launchable, null, facts)
        assertEquals(setOf("com.android.traceur", "com.lego.android.tvleanback"), apps.map { it.value }.toSet())
        assertEquals(setOf("Traceur", "Lego"), apps.map { AppNames.of(it, facts, snapshot.packages[it]?.label) }.toSet())
        assertTrue(snapshot.home.claimants.none { it.component.pkg in apps })
    }
}
