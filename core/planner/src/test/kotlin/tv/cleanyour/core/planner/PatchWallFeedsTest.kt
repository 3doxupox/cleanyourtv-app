package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageEntry
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.TestedInfo
import tv.cleanyour.core.planner.DefaultPlanner.Companion.PATCHWALL
import tv.cleanyour.core.planner.DefaultPlanner.Companion.REASON_PATCHWALL_FEED
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.planner
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.ProfileCategory
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// beta4, incident of 0.4.6-beta2 (issues #945, #1570): a Xiaomi MiTV-AXSO2 (dangalUHD, Android 11) on
// its candidate list kept PatchWall on (the owner's choice row of issue #111, kept on by default) while
// the list switched off PatchWall's feeds, Mi TV+ and com.xm.webcontent, and was stuck loading after a
// restart. Xiaomi's Android 11 firmware opens PatchWall at boot (#1580), and every tested list switches
// PatchWall off together with its feeds. So while PatchWall stays on its feeds stay on too, on exact
// lists, candidate lists and brand sets alike; with PatchWall switched off they may go off as well.
class PatchWallFeedsTest {
    private val mitvPlus = PackageName("com.mitv.tvhome.mitvplus")
    private val webContent = PackageName("com.xm.webcontent")
    private val feeds = setOf(mitvPlus, webContent)
    private val tvlauncher = Component(PackageName("com.google.android.tvlauncher"), ".MainActivity")
    private val fallback = Component(PackageName("com.android.tv.settings"), ".system.FallbackHome")

    // A debloat.txt as the bundle builder reads it, for a test: a "# Title" line after a blank line
    // starts a group, each package line is a default-off row, and an owner's-choice package (issue
    // #111) is an ask row of its own. The candidate lists of the test builds are not on this branch.
    private fun fixtureProfile(id: String, text: String): Profile {
        val groups = mutableListOf<PackageGroup>()
        var title: String? = null
        val rows = mutableListOf<PackageEntry>()
        val ask = mutableListOf<PackageEntry>()
        fun flush() { title?.let { t -> if (rows.isNotEmpty()) groups += PackageGroup("g${groups.size}", t, GroupDefault.DISABLE, rows.toList()) }; rows.clear() }
        var blank = true
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> blank = true
                line.startsWith("#") -> { if (blank) { flush(); title = line.removePrefix("#").trim() }; blank = false }
                else -> {
                    val pkg = PackageName(line.substringBefore('#').trim())
                    if (ProfileCategory.isOwnersChoice(pkg)) ask += PackageEntry(pkg, null) else rows += PackageEntry(pkg, null)
                    blank = false
                }
            }
        }
        flush()
        if (ask.isNotEmpty()) groups += PackageGroup("ask", "Your choice", GroupDefault.ASK, ask)
        return PlannerFixtures.profile().copy(id = id, groups = groups, stockLaunchers = listOf(tvlauncher),
            tested = TestedInfo("candidate", null, null, rebootTested = false, candidate = true))
    }

    private val axso2: Profile = fixtureProfile("xiaomi-mitv-axso2-android11",
        checkNotNull(javaClass.getResourceAsStream("/candidates/xiaomi-mitv-axso2-android11/debloat.txt")).readBytes().decodeToString())
    private val miTv4i: Profile = fixtureProfile("xiaomi-mitv4i-magnolia",
        File(Fixtures.repoRoot, "core/spec/candidates/xiaomi-mitv4i-magnolia/debloat.txt").readText())

    // The TV: every package of the list installed and on (PatchWall and both feeds among them).
    private fun tv(profile: Profile?, off: Set<PackageName> = emptySet(), sdk: Int = 30, device: String = "dangalUHD"): Snapshot {
        val listed = (profile?.groups.orEmpty().flatMap { g -> g.packages.map { it.name } } + feeds + PATCHWALL).toSet()
        val base = PlannerFixtures.snapshot()
        val packages = base.packages + listed.associateWith { p ->
            PackageState(if (p in off) EnabledState.DISABLED_USER else EnabledState.ENABLED, true, 1, null, false, false, null, false)
        }
        return base.copy(packages = packages, facts = base.facts.copy(brand = "Xiaomi", manufacturer = "Xiaomi", model = "MiTV-AXSO2", device = device, sdk = sdk, release = "11"),
            home = HomeResolution(tvlauncher, listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(fallback, -1000)))).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
    }

    // The clean-up alone (the switch has its own rules, HomeSwitchRisk), as the candidate list ran it.
    private fun setup(resolution: ProfileResolution, snapshot: Snapshot, kept: Set<PackageName> = emptySet(), extraOff: Set<PackageName> = emptySet()): Plan =
        plan(PlanRequest.Setup(resolution, choices(kept = kept, extraOff = extraOff, installLauncher = false, takeover = false), PlannerFixtures.artifact(), Level.SIMPLE), snapshot = snapshot)

    private fun Plan.off(): Set<PackageName> = steps.mapNotNull { (it.op as? Op.Disable)?.pkg }.toSet()

    private fun checkTheRule(resolution: ProfileResolution, snapshot: Snapshot, name: String) {
        // PatchWall kept on (the default): its feeds stay on, said so on their rows; the rest of the list goes off.
        val kept = setup(resolution, snapshot)
        assertTrue(PATCHWALL !in kept.off() && feeds.none { it in kept.off() }, "$name: ${kept.off()}")
        val rows = kept.summary.groups.flatMap { it.rows }.filter { it.pkg in feeds && it.pkg in snapshot.packages }
        assertTrue(rows.isNotEmpty() && rows.all { it.protected && !it.off && it.whyProtected == REASON_PATCHWALL_FEED }, "$name: $rows")
        assertTrue(kept.off().isNotEmpty(), "$name: the rest of the list still goes off")
        // Kept on by the owner's own choice too.
        assertTrue(feeds.none { it in setup(resolution, snapshot, kept = setOf(PATCHWALL)).off() }, name)
        // PatchWall switched off by the same plan (Customise): its feeds go off with it.
        val both = setup(resolution, snapshot, extraOff = setOf(PATCHWALL))
        assertTrue(PATCHWALL in both.off(), "$name: ${both.off()}")
        assertTrue(feeds.filter { f -> resolution.groups().any { g -> g.packages.any { it.name == f } } }.all { it in both.off() }, "$name: ${both.off()}")
        // PatchWall off on the TV already: its feeds may go off.
        val already = setup(resolution, tv(resolution.profileOrNull(), off = setOf(PATCHWALL)))
        assertTrue(feeds.filter { f -> resolution.groups().any { g -> g.packages.any { it.name == f } } }.all { it in already.off() }, "$name: ${already.off()}")
        // A feed off already, PatchWall on: no row and no step (the plan leaves it as it is).
        val webOff = setup(resolution, tv(resolution.profileOrNull(), off = setOf(webContent)))
        assertTrue(webContent !in webOff.off() && webOff.summary.groups.flatMap { it.rows }.none { it.pkg == webContent }, name)
    }

    private fun ProfileResolution.groups(): List<PackageGroup> = when (this) {
        is ProfileResolution.Exact -> profile.groups
        is ProfileResolution.BrandSureSet -> sureSet
        is ProfileResolution.None -> emptyList()
    }
    private fun ProfileResolution.profileOrNull(): Profile? = (this as? ProfileResolution.Exact)?.profile

    @Test fun theAxso2CandidateKeepsPatchWallsFeedsWhilePatchWallStaysOn() {
        // The list as it ran: PatchWall an ask row, both feeds default-off rows.
        assertEquals(GroupDefault.ASK, axso2.groups.single { g -> g.packages.any { it.name == PATCHWALL } }.default)
        for (f in feeds) assertEquals(GroupDefault.DISABLE, axso2.groups.single { g -> g.packages.any { it.name == f } }.default, f.value)
        checkTheRule(ProfileResolution.Exact(axso2), tv(axso2), "AXSO2 candidate")
    }

    @Test fun theMiTv4iListKeepsWebContentWhilePatchWallStaysOn() {
        assertTrue(miTv4i.groups.any { g -> g.packages.any { it.name == webContent } })
        checkTheRule(ProfileResolution.Exact(miTv4i), tv(miTv4i, sdk = 28, device = "magnolia"), "Mi TV 4i list")
    }

    @Test fun theXiaomiBrandSetAndTheShippedListsKeepTheRule() {
        val source = BundledProfileSource()
        val s = tv(null, sdk = 28, device = "magnolia")
        val set = source.resolveNow(s.facts.copy(model = "MiTV4I", fingerprint = "Xiaomi/magnolia/magnolia:9/PI/3698:user/release-keys"))
        assertTrue(set is ProfileResolution.BrandSureSet && set.brand == "Xiaomi", "$set")
        if (set.groups().any { g -> g.packages.any { it.name in feeds } }) checkTheRule(set, s, "Xiaomi brand set")
        // Every shipped list that offers PatchWall: never a feed off while PatchWall stays on.
        val lists = BundledProfileSource.bundled.profiles.filter { p -> p.groups.any { g -> g.packages.any { it.name == PATCHWALL } } }
        assertTrue(lists.isNotEmpty())
        for (p in lists) {
            val off = setup(ProfileResolution.Exact(p), tv(p)).off()
            assertTrue(PATCHWALL in off || feeds.none { it in off }, "${p.id}: $off")
        }
    }

    @Test fun customiseCannotSwitchAFeedOffAloneWhilePatchWallStaysOn() {
        // A feed no group lists, asked for in Custom, is refused while PatchWall stays on.
        val none = ProfileResolution.None(listOf(tvlauncher))
        val s = tv(null)
        val refused = planner().plan(PlanRequest.Setup(none, choices(extraOff = setOf(mitvPlus), installLauncher = false, takeover = false),
            PlannerFixtures.artifact(), Level.SIMPLE), s, PlannerFixtures.facts, emptyList())
        assertTrue(refused is PlanResult.Refused && refused.reasons.single().endsWith(REASON_PATCHWALL_FEED), "$refused")
        assertEquals(setOf(PATCHWALL, mitvPlus), setup(none, s, extraOff = setOf(PATCHWALL, mitvPlus)).off())
    }

    @Test fun manageSwitchesAFeedOffOnlyWithPatchWall() {
        val s = tv(axso2)
        val alone = planner().plan(PlanRequest.SwitchOff(setOf(mitvPlus)), s, PlannerFixtures.facts, emptyList())
        assertTrue(alone is PlanResult.Refused && alone.reasons.single().endsWith(REASON_PATCHWALL_FEED), "$alone")
        assertEquals(setOf(PATCHWALL, mitvPlus), plan(PlanRequest.SwitchOff(setOf(PATCHWALL, mitvPlus)), snapshot = s).off())
        assertEquals(setOf(mitvPlus), plan(PlanRequest.SwitchOff(setOf(mitvPlus)), snapshot = tv(axso2, off = setOf(PATCHWALL))).off())
        // Leftovers: a feed that came back on stays on while PatchWall is on and stays on.
        assertEquals(emptySet(), plan(PlanRequest.Leftovers(feeds), snapshot = s).off())
        assertEquals(feeds + PATCHWALL, plan(PlanRequest.Leftovers(feeds + PATCHWALL), snapshot = s).off())
    }
}
