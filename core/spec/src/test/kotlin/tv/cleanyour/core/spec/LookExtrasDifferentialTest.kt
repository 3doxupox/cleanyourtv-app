package tv.cleanyour.core.spec

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.UpstreamLauncher.Version
import tv.cleanyour.core.spec.upstream.UpstreamConfig
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// 0.4.5 acceptance (plan WP5 step 11): every LookExtras (2^7 x 2 x 3 = 768) on each style's own
// spec, with Green Wi-Fi on and with One menu button, with a picture and without, on a 4-app dock
// and on a 15-app dock at 4 apps in a row (three rows, so one row's rows_below_dock 0 shows),
// through LookKeys.build, kept once per distinct bytes. Each doc reads on launcher 3.0's own
// Config.java (the submodule's, vendored in com.gathin.tvhome.v30) exactly as the codec and
// LauncherKeys read it (Agreement.agree, every field and the 25 keys of 3.0), with no codec
// warning, and the real Config holds exactly what was meant: each switch where it acts
// (LookExtrasKeys.applying), the launcher's own value elsewhere, and rows_below_dock 0 with one
// row. LookExtrasKeys' own reader of the nine keys agrees with the real fields too.
class LookExtrasDifferentialTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dock4 = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar")
        .map(::PackageName)
    private val dock15 = dock4 + List(11) { PackageName("com.example.app$it") }

    private fun real(v: UpstreamConfig.V30) = Look30Config(
        singleRow = v.singleRow, dockBand = v.dockBand, dockBandAll = v.dockBandAll, showGreeting = v.showGreeting,
        dateBelowClock = v.dateBelowClock, headerWhite = v.headerWhite, wifiStatusWarnings = v.wifiStatusWarnings,
        uniformTiles = v.uniformTiles, glassStyle = v.glassStyle,
    )

    @Test fun everyExtrasDocAgreesWithLauncher30() {
        val started = System.nanoTime()
        val pictures = LookInputs.pictures.filter { it.first == "no picture" || it.first == "Mist" }
        val docs = LinkedHashMap<String, Triple<String, LauncherConfigDoc, LookExtras>>()
        var built = 0
        for ((pictureName, picture) in pictures) for (style in StyleId.entries)
            for (spec in listOf(LookKeys.spec(style), LookKeys.spec(style).copy(greenWifi = true), LookKeys.spec(style).copy(buttons = ButtonsChoice.MENU)))
                for ((columns, dock) in listOf(5 to dock4, 4 to dock15)) for (extras in LookExtrasInputs.all()) {
                    val doc = LookKeys.build(spec, picture, columns, dock, cards = false, extras = extras)
                    built++
                    val k = LauncherKeys.look(doc)
                    val meant = LookExtrasKeys.applying(extras, doc.dockRow == true, k.wifiStatusColor, k.topMenu)
                    docs.getOrPut(LauncherConfigCodec.encode(doc).decodeToString()) { Triple("$style $spec, $pictureName, ${dock.size} apps, $extras", doc, meant) }
                }
        val up = UpstreamLauncher(Version.V30, tmp.newFolder("extras"))
        val t = Agreement.Tally()
        var oneRows = 0
        for ((text, entry) in docs) {
            val (label, doc, meant) = entry
            val bytes = text.encodeToByteArray()
            val ok = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(bytes), label)
            assertEquals(emptyList(), ok.warnings, label)
            assertEquals(doc, ok.doc, "$label reads back to the doc that was built")
            assertEquals(doc, Agreement.agree(up, bytes, wallpaper = doc.wallpaper == true, label = label, t = t), label)
            val u = assertIs<UpstreamLauncher.Outcome.Loaded>(up.load(bytes, wallpaper = doc.wallpaper == true)).config
            val want = Look30Config(
                singleRow = meant.oneRow, dockBand = !meant.noBand, dockBandAll = meant.bandAll, showGreeting = !meant.hideGreeting,
                dateBelowClock = meant.dateBelow, headerWhite = meant.brightDate, wifiStatusWarnings = meant.wifiWarnings,
                uniformTiles = meant.tiles == TilesChoice.ICONS, glassStyle = meant.highlight.id,
            )
            assertEquals(want, real(u.v30), "$label: the launcher holds what was meant")
            assertEquals(real(u.v30), LookExtrasKeys.look30(doc), "$label: our reader of the nine keys")
            assertEquals(meant, LookExtrasKeys.shown(doc), label)
            if (meant.oneRow) {
                oneRows++
                assertEquals(0, u.rowsBelowDock, "$label: one row has no rows under it")
            }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        println("extras differential on launcher 3.0: $built builds, ${docs.size} distinct docs, ${t.agreed} agree with 3.0 Config.java, " +
            "$oneRows with one row, ${t.warnings} codec warnings (%.1f s)".format(seconds))
        assertEquals(2 * 5 * 3 * 2 * 768, built)
        assertEquals(docs.size, t.agreed)
        assertEquals(docs.size, t.applied)
        assertEquals(0, t.warnings)
        assertTrue(oneRows > 0)
    }

    // LookExtrasKeys.DEFAULTS_30 is config.xml at the pin, and DEFAULT extras write nothing a
    // launcher 3.0 would read differently from no key.
    @Test fun theDefaultsAreConfigXml30() {
        val up = UpstreamLauncher(Version.V30, tmp.newFolder("none"))
        val u = assertIs<UpstreamLauncher.Outcome.Loaded>(up.load(null, wallpaper = false)).config
        assertEquals(LookExtrasKeys.DEFAULTS_30, real(u.v30))
        val d = LauncherKeys.DEFAULTS_30
        assertEquals(LookExtrasKeys.DEFAULTS_30, Look30Config(d.singleRow, d.dockBand, d.dockBandAll, d.showGreeting, d.dateBelowClock,
            d.headerWhite, d.wifiStatusWarnings, d.uniformTiles, d.glassStyle.name.lowercase()))
        for (style in StyleId.entries) {
            val doc = LookKeys.build(LookKeys.spec(style), LookInputs.pictures[1].second, 5, dock4, cards = false)
            val v = assertIs<UpstreamLauncher.Outcome.Loaded>(up.load(LauncherConfigCodec.encode(doc), wallpaper = true)).config.v30
            assertEquals(LookExtrasKeys.DEFAULTS_30.copy(singleRow = false), real(v), "$style with DEFAULT extras")
        }
    }
}
