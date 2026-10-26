package tv.cleanyour.core.spec

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.UpstreamLauncher.Version
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// look2 acceptance: every doc LookKeys.build can make reads on launcher 2.7's own Config.java (the
// submodule's, vendored in com.gathin.tvhome.v27) exactly as the codec and LauncherKeys read it,
// field by field (Agreement.agree), with no codec warning, and reads back to the doc that was built.
//
// Inputs: the full LookSpec cross product (5 styles, glow 3, App colour across the top 2, ring 2,
// band with its amounts 5, buttons 3, green Wi-Fi 2, big name 2, names 2, movement 4, Lift and zoom
// 2: 57,600 specs) times 5 pictures, through build at 5 apps in a row with a 4-app dock (288,000
// builds), kept once per distinct bytes, since the rules of spec 7.3 fold most of the cross onto the
// same keys; then each style's own spec at 4 to 8 apps in a row with docks of 0, 4, 15 and 24 apps,
// for each picture. The full cross runs well inside the 4 minute budget on forge, so it is not cut.
class LookDifferentialTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dock4 = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar")
        .map(::PackageName)
    private fun dock(n: Int) = (dock4 + List(20) { PackageName("com.example.app$it") }).take(n)

    @Test fun everyLookDocAgreesWithLauncher27() {
        val started = System.nanoTime()
        val docs = LinkedHashMap<String, Pair<String, LauncherConfigDoc>>()   // by the bytes' text
        val pictures = LookInputs.pictures
        var built = 0
        for ((pictureName, picture) in pictures) {
            for (spec in LookInputs.cross()) {
                val doc = LookKeys.build(spec, picture, columns = 5, dock = dock4, cards = false)
                built++
                val text = LauncherConfigCodec.encode(doc).decodeToString()
                docs.getOrPut(text) { "$spec with $pictureName" to doc }
            }
            for (style in StyleId.entries) for (columns in 4..8) for (n in listOf(0, 4, 15, 24)) {
                val doc = LookKeys.build(LookKeys.spec(style), picture, columns, dock(n), cards = true)
                built++
                val text = LauncherConfigCodec.encode(doc).decodeToString()
                docs.getOrPut(text) { "$style own at $columns with $n apps and $pictureName" to doc }
            }
        }
        val up = UpstreamLauncher(Version.V27, tmp.newFolder("looks"))
        val t = Agreement.Tally()
        for ((text, entry) in docs) {
            val (label, doc) = entry
            val bytes = text.encodeToByteArray()
            assertTrue(bytes.size <= ConfigCodecImpl.MAX_BYTES, "$label: ${bytes.size} bytes")
            val ok = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(bytes), label)
            assertEquals(emptyList(), ok.warnings, label)
            assertEquals(doc, ok.doc, "$label reads back to the doc that was built")
            val read = Agreement.agree(up, bytes, wallpaper = doc.wallpaper == true, label = label, t = t)
            assertEquals(doc, read, label)
        }
        val seconds = (System.nanoTime() - started) / 1e9
        println("look2 differential on launcher 2.7: $built builds, ${docs.size} distinct look docs, ${t.agreed} agree with 2.7 Config.java, " +
            "${t.warnings} codec warnings (%.1f s)".format(seconds))
        assertEquals(docs.size, t.agreed)
        assertEquals(docs.size, t.applied)
        assertEquals(0, t.warnings)
        assertEquals(0, t.withWarnings)
    }

    // The vendored 2.7 classes are the submodule's own files with only the package line changed,
    // and the Cards stub's cleanLabel is the submodule's method, unchanged.
    @Test fun vendoredLauncher27ClassesAreTheSubmodules() {
        val upstream = File(Fixtures.upstreamDir, "launcher/app/src/main/java/com/gathin/tvhome")
        val vendored = File(Fixtures.repoRoot, "core/spec/src/test/java/com/gathin/tvhome/v27")
        val copies = listOf("Config", "Festival", "WallpaperRotation", "RaceWeekend", "WallpaperMood", "Sun", "ThemeFiles", "WallpaperCaptions")
        for (name in copies) {
            val theirs = File(upstream, "$name.java").readLines()
            val ours = File(vendored, "$name.java").readLines()
            assertEquals("package com.gathin.tvhome;", theirs.first(), name)
            assertEquals("package com.gathin.tvhome.v27;", ours.first(), name)
            assertEquals(theirs.drop(1), ours.drop(1), "$name.java differs from the submodule's beyond the package line")
        }
        // Every file in the package is a copy, the R, Cards or bridge stub: nothing else slips in.
        assertEquals((copies + listOf("R", "Cards", "V27ConfigBridge")).map { "$it.java" }.toSet(), vendored.list()!!.toSet())
        fun cleanLabel(f: File): String {
            val lines = f.readLines()
            val start = lines.indexOfFirst { it.trim().startsWith("static String cleanLabel(") }
            val end = (start until lines.size).first { lines[it] == "    }" }
            return lines.subList(start, end + 1).joinToString("\n")
        }
        assertEquals(cleanLabel(File(upstream, "Cards.java")), cleanLabel(File(vendored, "Cards.java")))
        // R holds every id the copies read, and the bridge answers each one config.xml has.
        val ids = copies.flatMap { Regex("\\bR\\.(bool|integer|string|array)\\.([a-z_0-9]+)").findAll(File(vendored, "$it.java").readText()).map { m -> m.value }.toList() }.toSet()
        val r = File(vendored, "R.java").readText()
        for (id in ids) {
            val (type, name) = id.removePrefix("R.").split('.')
            assertTrue(Regex("class $type \\{[^}]*\\b$name =").containsMatchIn(r), "R.java has $id")
        }
    }

    // The 2.7 reader's own defaults (no config.json) are config.xml's and LauncherKeys.DEFAULTS_27.
    @Test fun withNoFileLauncher27ShowsTheDefaults() {
        val up = UpstreamLauncher(Version.V27, tmp.newFolder("none"))
        val u = assertIs<UpstreamLauncher.Outcome.Loaded>(up.load(null, wallpaper = false)).config
        assertEquals(LauncherKeys.DEFAULTS_27, UpstreamLauncher.look(u.v27))
        assertEquals(LauncherKeys.DEFAULTS_27, up.lookDefaults())
        assertEquals(LauncherKeys.DEFAULTS_27, LauncherKeys.look(null))
    }
}

// The inputs of the look2 differential and of LookKeysTest.
object LookInputs {
    // The five pictures of spec 6.2 the differential uses: none, Mist, a photo darkened 30, Mountains
    // with its sky at 55, and Diwali with its festival keys.
    val pictures: List<Pair<String, PictureKeys>> = listOf(
        "no picture" to PictureKeys(wallpaper = false, backgroundColor = "#000000", dim = 0, skyIntensity = 0),
        "Mist" to PictureKeys(wallpaper = true, backgroundColor = "#0E1022", dim = 0, skyIntensity = 0),
        "a photo at dim 30" to PictureKeys(wallpaper = true, backgroundColor = "#000000", dim = 30, skyIntensity = 0),
        "Mountains" to PictureKeys(wallpaper = true, backgroundColor = "#000000", dim = 0, skyIntensity = 55),
        "Diwali" to PictureKeys(wallpaper = true, backgroundColor = "#120809", dim = 0, skyIntensity = 0,
            festival = "diwali", festivalUntil = "2026-11-10", accentColor = "#FFB23C"),
    )

    // Band with its amounts: solid, see-through at 70, 50 and 30, glass.
    val bands: List<Pair<BandChoice, Int>> = listOf(BandChoice.SOLID to LookKeys.SEE_THROUGH_DEFAULT) +
        LookKeys.SEE_THROUGH.map { BandChoice.SEE_THROUGH to it } + listOf(BandChoice.GLASS to LookKeys.SEE_THROUGH_DEFAULT)

    fun cross(): Sequence<LookSpec> = sequence {
        val tf = listOf(false, true)
        for (style in StyleId.entries) for (glow in GlowChoice.entries) for (spot in tf) for (ring in tf)
            for ((band, see) in bands) for (buttons in ButtonsChoice.entries) for (wifi in tf) for (big in tf)
                for (names in tf) for (movement in Movement.entries) for (lift in tf)
                    yield(LookSpec(style, big, glow, spot, ring, band, see, buttons, wifi, names, movement, lift))
    }
}
