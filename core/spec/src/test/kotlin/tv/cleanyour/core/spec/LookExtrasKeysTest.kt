package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import tv.cleanyour.core.model.PackageName
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The 3.0 extras in LookKeys.build (plan WP5 step 4), rule by rule, without a launcher: DEFAULT adds
// nothing, each switch writes its one key only when it is not the launcher's default and only where
// it takes effect, and the reader (LookExtrasKeys.shown) gives back what took effect. The real 3.0
// reader of the same docs is LookExtrasDifferentialTest's.
class LookExtrasKeysTest {
    private val dock4 = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar")
        .map(::PackageName)
    private val mist = PictureKeys(wallpaper = true, backgroundColor = "#0E1022", dim = 0, skyIntensity = 0)

    private fun build(spec: LookSpec, extras: LookExtras = LookExtras.DEFAULT, dock: List<PackageName> = dock4) =
        LookKeys.build(spec, mist, 5, dock, cards = false, extras = extras)

    private fun extraKeys(d: LauncherConfigDoc) = d.unknown.filterKeys { it in LookExtrasKeys.KEYS }

    @Test fun defaultAddsNothing() {
        for (style in StyleId.entries) {
            val spec = LookKeys.spec(style)
            assertEquals(LookKeys.build(spec, mist, 5, dock4, cards = false), build(spec), "$style")
            assertEquals(emptyMap(), extraKeys(build(spec)), "$style")
        }
        assertEquals(LookExtrasKeys.DEFAULTS_30, LookExtrasKeys.look30(null))
        assertEquals(LookExtras.DEFAULT, LookExtrasKeys.shown(null))
    }

    @Test fun eachSwitchWritesItsOneKey() {
        val classic = LookKeys.spec(StyleId.CLASSIC)
        val glass = LookKeys.spec(StyleId.GLASS)   // Green Wi-Fi is Glass's own
        fun p(v: Any) = when (v) { is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
        for ((spec, extras, want) in listOf(
            Triple(classic, LookExtras(oneRow = true), mapOf("single_row" to p(true))),
            Triple(classic, LookExtras(oneRow = true, bandAll = true), mapOf("single_row" to p(true), "dock_band_all" to p(true))),
            Triple(classic, LookExtras(noBand = true), mapOf("dock_band" to p(false))),
            Triple(classic, LookExtras(hideGreeting = true), mapOf("show_greeting" to p(false))),
            Triple(classic, LookExtras(dateBelow = true), mapOf("date_below_clock" to p(true))),
            Triple(classic, LookExtras(brightDate = true), mapOf("header_white" to p(true))),
            Triple(glass, LookExtras(wifiWarnings = true), mapOf("wifi_status_warnings" to p(true))),
            Triple(classic, LookExtras(tiles = TilesChoice.ICONS), mapOf("uniform_tiles" to p(true))),
            Triple(classic, LookExtras(highlight = HighlightChoice.RIM), mapOf("glass_style" to p("rim"))),
            Triple(classic, LookExtras(highlight = HighlightChoice.HALO), mapOf("glass_style" to p("halo"))),
        )) {
            val d = build(spec, extras)
            assertEquals(want, extraKeys(d), "$extras")
            assertEquals(build(spec).unknown + want, d.unknown, "$extras changes nothing else")
            assertEquals(build(spec).copy(unknown = d.unknown), d, "$extras changes no modelled key")
        }
    }

    // A switch that does nothing in this look never reaches the bytes.
    @Test fun aSwitchWithoutEffectIsNotWritten() {
        val all = LookKeys.spec(StyleId.ALL_APPS)
        assertEquals(emptyMap(), extraKeys(build(all, LookExtras(oneRow = true, bandAll = true, noBand = true))), "All apps has no dock row")
        val classic = LookKeys.spec(StyleId.CLASSIC)
        assertEquals(emptyMap(), extraKeys(build(classic, LookExtras(bandAll = true))), "the band behind every app needs one row")
        assertEquals(emptyMap(), extraKeys(build(classic, LookExtras(wifiWarnings = true))), "Classic's Wi-Fi is not green")
        val menu = LookKeys.spec(StyleId.GLASS).copy(buttons = ButtonsChoice.MENU)
        assertEquals(emptyMap(), extraKeys(build(menu, LookExtras(wifiWarnings = true))), "one menu button hides the Wi-Fi pill")
        // Spotlight: the cinematic shelf has the dock row, so one row, its band and no band all act there
        // (MainActivity and DockDecoration at b06ab21 draw them on the shelf too).
        val spot = LookKeys.spec(StyleId.SPOTLIGHT)
        assertEquals(setOf("single_row", "dock_band_all"), extraKeys(build(spot, LookExtras(oneRow = true, bandAll = true))).keys)
        assertEquals(setOf("dock_band"), extraKeys(build(spot, LookExtras(noBand = true))).keys)
    }

    // Every combination on every style, with and without Green Wi-Fi and with one menu button: the
    // codec carries every key with no warning, the doc reads back, and the reader shows exactly what
    // took effect. 2^7 x 2 x 3 = 768 extras.
    @Test fun everyCombinationReadsBackAsItTookEffect() {
        var n = 0
        for (style in StyleId.entries) for (buttons in ButtonsChoice.entries) for (wifi in listOf(false, true)) {
            val spec = LookKeys.spec(style).copy(buttons = buttons, greenWifi = wifi)
            for (extras in LookExtrasInputs.all()) {
                val d = build(spec, extras)
                for (k in d.unknown.keys) assertTrue(k in ConfigCodecImpl.CARRIED_27 || k in ConfigCodecImpl.CARRIED_30, "$extras writes $k")
                val ok = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(LauncherConfigCodec.encode(d)))
                assertEquals(emptyList(), ok.warnings, "$style $buttons $wifi $extras")
                assertEquals(d, ok.doc)
                val k = LauncherKeys.look(d)
                val want = LookExtrasKeys.applying(extras, d.dockRow == true, k.wifiStatusColor, k.topMenu)
                assertEquals(want, LookExtrasKeys.shown(ok.doc), "$style $buttons $wifi $extras")
                n++
            }
        }
        assertEquals(5 * 3 * 2 * 768, n)
        assertEquals(768, LookExtrasInputs.all().count())
    }

    @Test fun glassStyleIsReadAsConfigReadsIt() {
        for ((v, want) in listOf("rim" to "rim", " HALO " to "halo", "Rim" to "rim", "glass" to "glass", "frost" to "glass", "" to "glass", null to "glass")) {
            assertEquals(want, LookExtrasKeys.glassStyle(v), "$v")
        }
    }
}

// Every LookExtras: the seven switches on and off, tiles 2, highlight 3 (768).
object LookExtrasInputs {
    fun all(): Sequence<LookExtras> = sequence {
        val tf = listOf(false, true)
        for (oneRow in tf) for (bandAll in tf) for (noBand in tf) for (hide in tf) for (below in tf) for (bright in tf) for (warn in tf)
            for (tiles in TilesChoice.entries) for (highlight in HighlightChoice.entries)
                yield(LookExtras(oneRow, bandAll, noBand, hide, below, bright, warn, tiles, highlight))
    }
}
