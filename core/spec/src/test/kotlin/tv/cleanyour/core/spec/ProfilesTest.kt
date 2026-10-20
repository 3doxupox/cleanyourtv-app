package tv.cleanyour.core.spec

import kotlinx.coroutines.runBlocking
import org.junit.Test
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfilesTest {
    // D2: the upstream folders without a curated entry are skipped on purpose; the rest ship.
    private val unconvertedFile = File(Fixtures.repoRoot, "core/spec/upstream-feedback/unconverted.txt")
    private val unconverted = ProfileConverter.readUnconverted(unconvertedFile)
    private val upstream = Fixtures.upstreamExamples()
    private val examples = upstream.filter { it.folder !in unconverted }
    private fun p(s: String) = PackageName(s)
    private fun profile(folder: String) = DebloatTxtParser.parse(examples.first { it.folder == folder }.debloatTxt, folder)

    @Test fun everyConvertedUpstreamExampleParsesWithCuratedMetadata() {
        assertEquals(93, upstream.size)
        assertEquals(37, examples.size)
        for (e in examples) {
            val profile = DebloatTxtParser.parse(e.debloatTxt, e.folder)
            assertEquals(e.folder, profile.id)
            assertTrue(profile.groups.isNotEmpty(), e.folder)
            assertTrue(profile.groups.all { g -> g.packages.isNotEmpty() }, e.folder)
            assertNotNull(profile.match.brand, e.folder)
            assertNotNull(profile.tested, e.folder)
            assertTrue(profile.match.models.isNotEmpty() || profile.match.devices.isNotEmpty(), "${e.folder} has nothing to match on")
            // Every package line of the file is in exactly one place in the profile.
            val scanned = DebloatTxt.scan(e.debloatTxt, e.folder).groups.flatMap { g -> g.entries.map { it.pkg } }.toSet()
            val inGroups = profile.groups.flatMap { g -> g.packages.map { it.name } }
            assertEquals(inGroups.size, inGroups.toSet().size, "${e.folder}: a package is in two groups")
            val placed = inGroups.toSet() + profile.stockLaunchers.map { it.pkg } + listOfNotNull(profile.setupWraith)
            assertEquals(emptySet(), scanned - placed, "${e.folder}: packages lost")
            assertTrue(inGroups.all { it in scanned }, e.folder)
        }
    }

    @Test fun converterSeesEveryGroupTitleInTheMetadata() {
        val (bundle, problems) = ProfileConverter.convert(
            File(Fixtures.upstreamDir, "examples"),
            File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json"),
            "69c93b6",
            unconverted,
        )
        assertEquals(emptyList(), problems)
        assertEquals(examples.map { it.folder }, bundle.profiles.map { it.id })
    }

    // D2: unconverted.txt is exactly the upstream folders without a profile-meta.json entry, so a
    // new upstream folder fails here (and in the build) until it gets an entry or a line there.
    @Test fun theUnconvertedListIsPinnedToTheSubmodule() {
        val meta = ProfileMeta.decode(File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json").readText())
        assertEquals(upstream.map { it.folder }.filter { it !in meta.profiles }.toSet(), unconverted)
        assertEquals(56, unconverted.size)
        val listed = unconvertedFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listed.sorted(), listed, "one folder per line, sorted")
        assertTrue('\u2014' !in unconvertedFile.readText() && "-".repeat(2) !in unconvertedFile.readText())
        // A folder nobody lists or curates is a problem, and so is a stale or doubled line.
        val (_, missing) = ProfileConverter.convert(File(Fixtures.upstreamDir, "examples"),
            File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json"), "x", unconverted - unconverted.first())
        assertEquals(listOf("${unconverted.first()}: no entry in profile-meta.json"), missing)
        val (_, stale) = ProfileConverter.convert(File(Fixtures.upstreamDir, "examples"),
            File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json"), "x", unconverted + "gone-tv" + "xiaomi-mitv-axso0")
        assertEquals(setOf("gone-tv: in unconverted.txt but not upstream", "xiaomi-mitv-axso0: in both unconverted.txt and profile-meta.json"), stale.toSet())
    }

    // The unconverted folders still parse by header keywords alone, so curating one is a
    // metadata change only.
    @Test fun unconvertedUpstreamExamplesParseWithoutMetadata() {
        for (e in upstream.filter { it.folder in unconverted }) {
            val profile = DebloatTxt.parse(e.debloatTxt, e.folder, null)
            assertTrue(profile.groups.sumOf { it.packages.size } > 0, e.folder)
        }
    }

    // v2.4.0 changed two of the converted folders (tcl-g10-google-tv, xiaomi-mitv-mooq0) in their
    // header comments only; their package lines are those of 1bbf0c9. tcl-g10-google-tv's new note
    // says the setup wizard claims Home there too once launcherx is off, so its entry names the
    // wizard: the takeover switches it off, with the Wi-Fi guard (D3).
    @Test fun theG10TakeoverIncludesTheWizard() {
        val g10 = profile("tcl-g10-google-tv")
        assertEquals(KnownLaunchers.SETUPWRAITH, g10.setupWraith)
        assertTrue(Quirk.HOME_TAKEOVER_SETUPWRAITH in g10.quirks)
        assertTrue(g10.groups.none { g -> g.packages.any { it.name == KnownLaunchers.SETUPWRAITH } })
    }

    @Test fun theReferenceTvProfile() {
        val mi = profile("xiaomi-mitv-axso0")
        assertEquals(listOf("MiTV-AXSO0"), mi.match.models)
        assertEquals(listOf("dangal"), mi.match.devices)
        assertEquals(Platform.ANDROID_TV, mi.platform)
        assertEquals(listOf(Component(p("com.google.android.tvlauncher"), ".MainActivity")), mi.stockLaunchers)
        assertEquals(p("com.android.tv"), mi.liveTv)
        assertEquals(null, mi.setupWraith)
        assertTrue(Quirk.REBOOT_COMMAND in mi.quirks)
        val byId = mi.groups.associateBy { it.id }
        assertEquals(GroupDefault.DISABLE, byId.getValue("ads").default)
        assertTrue(p("com.mitv.tvhome.atv") in byId.getValue("ads").packages.map { it.name })
        assertEquals(p("com.glance.tv"), byId.getValue("ads").packages.first { it.name.value == "com.glance.tv" }.name)
        assertEquals("Glance lock-screen news and ads", byId.getValue("ads").packages.first { it.name.value == "com.glance.tv" }.note)
        assertEquals(listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.google.android.feedback", "com.google.android.tv.bugreportsender"),
            byId.getValue("tracking").packages.map { it.name.value })
        // D22: the updater is a Software updates row, kept in Simple.
        assertEquals(GroupDefault.ASK, byId.getValue("updates").default)
        assertEquals(listOf("com.xiaomi.mitv.updateservice"), byId.getValue("updates").packages.map { it.name.value })
        assertEquals("Software updates", byId.getValue("updates").title)
        // Personal choices are ask groups: voice, casting, unused streaming apps.
        for (id in listOf("voice", "casting", "streaming")) assertEquals(GroupDefault.ASK, byId.getValue(id).default, id)
        assertEquals(listOf("com.google.android.katniss", "com.google.android.speech.pumpkin"), byId.getValue("voice").packages.map { it.name.value })
        // Simple switches off the disable groups only; 20 packages on this TV.
        val simple = mi.groups.filter { it.default == GroupDefault.DISABLE }.flatMap { it.packages }
        assertEquals(20, simple.size)
        // Disable groups come first, in the fixed category order.
        assertEquals(listOf("ads", "tracking", "preloads", "brand", "other", "streaming", "voice", "casting", "updates"), mi.groups.map { it.id })
        assertEquals("Adverts and sponsored rows", byId.getValue("ads").title)
        assertEquals("Xiaomi extras", byId.getValue("brand").title)
        assertEquals(DebloatTxt.sha256(examples.first { it.folder == "xiaomi-mitv-axso0" }.debloatTxt), mi.sourceSha256)
    }

    @Test fun setupwraithAndStockLaunchersLeaveTheGroups() {
        val g05 = profile("tcl-g05-google-tv")
        assertEquals(Platform.GOOGLE_TV, g05.platform)
        val gtv = profile("google-chromecast-gtv-4k")
        assertTrue(Quirk.HOME_TAKEOVER_SETUPWRAITH in gtv.quirks)
        assertEquals(KnownLaunchers.SETUPWRAITH, gtv.setupWraith)
        assertTrue(KnownLaunchers.LAUNCHERX in gtv.stockLaunchers)
        val all = examples.map { DebloatTxtParser.parse(it.debloatTxt, it.folder) }
        for (pr in all) {
            val inGroups = pr.groups.flatMap { g -> g.packages.map { it.name } }.toSet()
            assertTrue(KnownLaunchers.SETUPWRAITH !in inGroups, pr.id)
            assertTrue(KnownLaunchers.byPackage.keys.none { it in inGroups }, pr.id)
            // Every updater row is an ask row (D22).
            for (g in pr.groups) for (e in g.packages) if (ProfileCategory.isUpdater(e.name)) assertEquals(GroupDefault.ASK, g.default, "${pr.id} ${e.name}")
        }
        assertEquals(Platform.FIRE_OS, profile("insignia-ns55f301na25").platform)
        assertEquals(Platform.TCL_CN, profile("tcl-ak30a5").platform)
    }

    @Test fun headerKeepListsBecomeNeverDisable() {
        val g05 = profile("tcl-g05-google-tv")
        assertTrue(p("com.tcl.tvinput") in g05.neverDisable, g05.neverDisable.toString())
        val hisense = profile("hisense-smarttv-4k-ffm")
        assertTrue(p("com.hisense.tv.smartsense") in hisense.neverDisable, hisense.neverDisable.toString())
        assertTrue(hisense.neverDisable.none { it.value == "com.mediatek" })
        assertEquals(setOf(p("com.a.b"), p("com.c.d")), DebloatTxt.keepListIn(listOf("# Keep: com.a.b and com.c.d, plus com.mediatek.* and tools/restore.sh", "#", "# Stock launcher: com.e.f")))
    }

    @Test fun malformedFilesFailWithTheLine() {
        val e = assertFailsWith<DebloatTxtException> { DebloatTxtParser.parse("# T\ncom.ok.pkg\nnot a package line!\n", "bad") }
        assertEquals(3, e.line)
        assertFailsWith<DebloatTxtException> { DebloatTxtParser.parse("# only comments\n", "empty") }
        val bom = DebloatTxt.parse("# Group\ncom.a.b   # note\n          # more note\r\ncom.c.d\n", "x", null)
        assertEquals("note more note", bom.groups.single().packages.first().note)
    }

    @Test fun bundledProfilesEqualAFreshParse() {
        val bundle = BundledProfileSource.bundled
        assertEquals("69c93b6", bundle.upstreamCommit)
        assertEquals(examples.map { it.folder }, bundle.profiles.map { it.id })
        for (e in examples) assertEquals(DebloatTxtParser.parse(e.debloatTxt, e.folder), bundle.profiles.first { it.id == e.folder }, e.folder)
        assertEquals(SureSetGenerator.generate(bundle.profiles), bundle.sureSets)
        // Codec round trip.
        assertEquals(bundle, ProfileBundleJson.decode(ProfileBundleJson.encode(bundle)))
        assertEquals(ProfileMeta.bundled.upstreamCommit, bundle.upstreamCommit)
    }

    @Test fun sureSetsFollowD26() {
        val profiles = BundledProfileSource.bundled.profiles
        val sets = SureSetGenerator.generate(profiles)
        val brands = profiles.groupBy { it.match.brand!!.lowercase() }
        assertEquals(brands.filterValues { it.size >= 3 }.keys.sorted(), sets.map { it.brand.lowercase() })
        assertTrue(sets.any { it.brand == "Xiaomi" } && sets.any { it.brand == "TCL" })
        val keep = profiles.flatMap { it.neverDisable }.toSet()
        for (s in sets) {
            val mine = profiles.filter { it.match.brand.equals(s.brand, true) }
            for (g in s.groups) {
                assertEquals(GroupDefault.DISABLE, g.default)
                for (e in g.packages) {
                    assertTrue(e.name !in keep)
                    val n = mine.count { pr -> pr.groups.any { it.default == GroupDefault.DISABLE && e.name in it.packages.map { x -> x.name } } }
                    assertTrue(n >= 3, "${s.brand} ${e.name} in $n profiles")
                }
            }
            assertTrue(KnownLaunchers.TVLAUNCHER in s.stockLaunchers)
        }
        println("sure sets: " + sets.joinToString { s -> "${s.brand}=" + s.groups.flatMap { it.packages }.joinToString("/") { it.name.value } })
    }

    @Test fun resolveExactThenBrandThenNone() = runBlocking<Unit> {
        val source = BundledProfileSource()
        val mi = Fixtures.miTv().facts()
        val exact = source.resolve(mi)
        assertTrue(exact is ProfileResolution.Exact && exact.profile.id == "xiaomi-mitv-axso0", exact.toString())
        // Same TV after a firmware update to another SDK: no exact profile, the Xiaomi sure set.
        val updated = source.resolve(mi.copy(sdk = 30))
        assertTrue(updated is ProfileResolution.BrandSureSet && updated.brand == "Xiaomi" && updated.sureSet.isNotEmpty(), updated.toString())
        val unknownXiaomi = source.resolve(mi.copy(model = "MiTV-NEW1", device = "newdevice"))
        assertTrue(unknownXiaomi is ProfileResolution.BrandSureSet)
        val other = source.resolve(mi.copy(brand = "Acme", manufacturer = "Acme", model = "X1", device = "x1"))
        assertEquals(ProfileResolution.None(KnownLaunchers.generic), other)
        // A wrong brand never takes another brand's exact profile.
        assertTrue(source.resolve(mi.copy(brand = "Acme", manufacturer = "Acme")) is ProfileResolution.None)
    }

    @Test fun dockRanking() {
        val mi = profile("xiaomi-mitv-axso0")
        val installed = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar", "com.sonyliv", "com.android.tv")
            .map(::p).toSet()
        // The reference TV: inputs, Live TV installed, India.
        assertEquals(listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "com.android.tv"),
            DockRanking.pick(mi, installed, hasInputs = true, liveTv = null, locale = "en-IN").map { it.value })
        // No inputs: four streaming apps, the regional table decides the fourth.
        assertEquals(listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar"),
            DockRanking.pick(mi, installed, hasInputs = false, liveTv = null, locale = "en_IN").map { it.value })
        // A US locale skips Hotstar for nothing installed, so only three plus nothing else.
        assertEquals(listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom"),
            DockRanking.pick(null, installed, hasInputs = false, liveTv = null, locale = "en-US").map { it.value })
        // Only installed apps, never more than four, no duplicates even with a suggested dock.
        val withSuggested = mi.copy(suggestedDock = listOf(p("com.sonyliv"), p("com.netflix.ninja"), p("not.installed")))
        assertEquals(listOf("com.sonyliv", "com.netflix.ninja", "com.google.android.youtube.tv", "com.android.tv"),
            DockRanking.pick(withSuggested, installed, hasInputs = true, liveTv = p("com.android.tv"), locale = "en-IN").map { it.value })
        assertEquals(emptyList(), DockRanking.pick(null, emptySet(), hasInputs = true, liveTv = p("com.android.tv"), locale = "en-IN"))
        assertEquals("IN", DockRankingImpl.region("hi-Deva-IN"))
        assertEquals(null, DockRankingImpl.region("en"))
    }

    @Test fun factsCoverTheReferenceProfileTheSureSetsAndTheDockTable() {
        val facts = BundledFactsTable()
        val needed = profile("xiaomi-mitv-axso0").groups.flatMap { g -> g.packages.map { it.name } } +
            SureSetGenerator.generate(BundledProfileSource.bundled.profiles).flatMap { s -> s.groups.flatMap { g -> g.packages.map { it.name } } } +
            DockRankingImpl.ranking("en-IN") + DockRankingImpl.ranking("en-US") + DockRankingImpl.ranking("en-GB") + p("com.android.tv")
        val missing = needed.distinct().filter { facts.lookup(it) == null }
        assertEquals(emptyList(), missing)
        val glance = facts.lookup(p("com.glance.tv"))!!
        assertTrue(glance.friendlyName.length <= 32 && glance.description.length <= 70)
        val raw = File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/facts.tsv").readText()
        assertTrue('\u2014' !in raw && "-".repeat(2) !in raw)
        for ((_, f) in BundledFactsTable.bundled) {
            assertTrue(f.friendlyName.isNotBlank() && f.friendlyName.length <= 32, f.toString())
            assertTrue(f.description.isNotBlank() && f.description.length <= 70, f.toString())
        }
        // Every upstream package has a name.
        val everyUpstream = examples.flatMap { e -> DebloatTxtParser.parse(e.debloatTxt, e.folder).groups.flatMap { g -> g.packages.map { it.name } } }.distinct()
        assertEquals(emptyList(), everyUpstream.filter { facts.lookup(it) == null })
    }
}
