package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

// LauncherKeys reads the carried 2.7 keys as launcher 2.7's Config.java does (Config.bool,
// integer, string, glowStyle), and the 25 keys of 3.0 as 3.0's does (also stringList and glassStyle). The real reader on the same bytes: ConfigDifferentialTest and
// LookDifferentialTest.
class LauncherKeysTest {
    private fun decoded(json: String): LauncherConfigDoc = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(json.encodeToByteArray()), json).doc
    private fun doc(vararg pairs: Pair<String, JsonElement>) = LauncherConfigDoc(unknown = mapOf(*pairs))

    // DEFAULTS_27 is res/values/config.xml of launcher 2.7 (3e34f40); the submodule's, parsed here,
    // keeps those values for the 2.7 keys at b06ab21.
    @Test fun defaultsAreConfigXml27() {
        val xml = UpstreamLauncher.parseConfigXml(File(Fixtures.upstreamDir, "launcher/app/src/main/res/values/config.xml"))
        fun b(k: String) = xml.getValue(k) as Boolean
        fun i(k: String) = xml.getValue(k) as Int
        val d = LauncherKeys.DEFAULTS_27
        assertEquals(
            listOf(b("ambient_glow"), b("app_glow"), b("dock_glass"), b("dock_even_padding"), b("always_show_names"), b("icon_only_buttons"),
                b("icon_only_inputs"), b("top_menu"), b("wifi_status_color"), b("focus_overshoot"), b("cinematic_dots"), b("cinematic_white_focus"),
                i("cinematic_art"), i("cinematic_scrim")),
            listOf(d.ambientGlow, d.appGlow, d.dockGlass, d.dockEvenPadding, d.alwaysShowNames, d.iconOnlyButtons, d.iconOnlyInputs, d.topMenu,
                d.wifiStatusColor, d.focusOvershoot, d.cinematicDots, d.cinematicWhiteFocus, d.cinematicArt, d.cinematicScrim),
        )
        assertEquals(LauncherKeys.glowStyle(xml.getValue("glow_style") as String), d.glowStyle)
        assertEquals("radial", xml.getValue("glow_style"))
        // dock_opacity is not in config.xml: Config.load reads it from config.json alone, default 100.
        assertEquals(false, "dock_opacity" in xml)
        assertEquals(100, d.dockOpacity)
        val reader = File(Fixtures.upstreamDir, "launcher/app/src/main/java/com/gathin/tvhome/Config.java").readText()
        assertEquals(true, "integer(json, \"dock_opacity\", 100, 30, 100)" in reader)
        // No doc, and a doc with none of the keys, read as the defaults.
        assertEquals(d, LauncherKeys.look(null))
        assertEquals(d, LauncherKeys.look(LauncherConfigDoc(columns = 5)))
    }

    @Test fun dockOpacityIs30To100Else100() {
        for ((v, want) in listOf(29 to 100, 101 to 100, 30 to 30, 100 to 100, 50 to 50, -1 to 100, 0 to 100)) {
            assertEquals(want, LauncherKeys.look(decoded("{\"dock_opacity\": $v}")).dockOpacity, "dock_opacity $v")
            assertEquals(want, LauncherKeys.look(doc("dock_opacity" to JsonPrimitive(v))).dockOpacity, "dock_opacity $v in code")
        }
        // A number's intValue(): a double is cut towards zero, a long keeps its low 32 bits.
        assertEquals(100, LauncherKeys.look(decoded("{\"dock_opacity\": 29.9}")).dockOpacity)
        assertEquals(100, LauncherKeys.look(decoded("{\"dock_opacity\": 100.9}")).dockOpacity)
        assertEquals(50, LauncherKeys.look(decoded("{\"dock_opacity\": 4294967346}")).dockOpacity)
        assertEquals(100, LauncherKeys.look(decoded("{\"dock_opacity\": \"50\"}")).dockOpacity)
    }

    // Config.integer reads a double through intValue(), so 45.7 is 45; out of range is the default.
    @Test fun integersReadAsConfigIntegerReadsThem() {
        assertEquals(45, LauncherKeys.look(decoded("{\"cinematic_scrim\": 45.7}")).cinematicScrim)
        assertEquals(45, LauncherKeys.int(doc("k" to JsonPrimitive(45.7)), "k", 0, 100, 9))
        assertEquals(0, LauncherKeys.look(decoded("{\"cinematic_art\": -0.5}")).cinematicArt)
        assertEquals(45, LauncherKeys.look(decoded("{\"cinematic_scrim\": 4294967341}")).cinematicScrim)
        assertEquals(100, LauncherKeys.look(doc("cinematic_scrim" to JsonPrimitive(Double.POSITIVE_INFINITY))).cinematicScrim, "Infinity is Integer.MAX_VALUE: out of range")
        assertEquals(0, LauncherKeys.int(doc("k" to JsonPrimitive(Double.NaN)), "k", 0, 100, 9), "(int) NaN is 0")
        assertEquals(20, LauncherKeys.look(decoded("{\"cinematic_art\": 0x14}")).cinematicArt)
        assertEquals(50, LauncherKeys.look(decoded("{\"cinematic_art\": 101}")).cinematicArt)
        for (bad in listOf(JsonPrimitive("45"), JsonPrimitive(true), JsonNull, JsonArray(listOf(JsonPrimitive(45))), JsonObject(emptyMap())))
            assertEquals(9, LauncherKeys.int(doc("k" to bad), "k", 0, 100, 9), "$bad")
        assertEquals(9, LauncherKeys.int(null, "k", 0, 100, 9))
    }

    // Config.bool takes a JSON true or false only: a string, a number or null keeps the default.
    @Test fun aStringWhereABoolIsExpectedGivesTheDefault() {
        assertEquals(true, LauncherKeys.look(decoded("{\"ambient_glow\": \"false\"}")).ambientGlow)
        assertEquals(false, LauncherKeys.look(decoded("{\"app_glow\": \"true\"}")).appGlow)
        assertEquals(true, LauncherKeys.look(decoded("{\"focus_overshoot\": 0}")).focusOvershoot)
        assertEquals(true, LauncherKeys.look(decoded("{\"cinematic_dots\": null}")).cinematicDots)
        assertEquals(false, LauncherKeys.look(decoded("{\"ambient_glow\": FALSE}")).ambientGlow, "org.json reads FALSE as false")
        assertEquals(true, LauncherKeys.look(decoded("{\"top_menu\": true}")).topMenu)
        assertEquals(false, LauncherKeys.bool(doc("k" to JsonPrimitive("true")), "k", false))
        assertEquals(true, LauncherKeys.bool(doc("k" to JsonArray(emptyList())), "k", true))
        assertEquals(true, LauncherKeys.bool(doc("k" to JsonPrimitive(true)), "k", false))
    }

    // DEFAULTS_30 is res/values/config.xml of the submodule at the pin (launcher 3.0, b06ab21),
    // parsed here; lunar_utc_offset is read from config.json alone (-1, -1 to 14).
    @Test fun defaultsAreConfigXml30() {
        val xml = UpstreamLauncher.parseConfigXml(File(Fixtures.upstreamDir, "launcher/app/src/main/res/values/config.xml"))
        fun b(k: String) = xml.getValue(k) as Boolean
        @Suppress("UNCHECKED_CAST")
        fun list(k: String) = (xml.getValue(k) as Array<String>).toList()
        val want = Keys30(
            inputSources = list("input_sources"), lunarDate = b("lunar_date"), lunarUtcOffset = -1,
            openOnStartChoice = b("open_on_start_choice"), showLibraryPill = b("show_library_pill"), libraryOkOpens = b("library_ok_opens"),
            uniformTiles = b("uniform_tiles"), consumeVolumeKeys = b("consume_volume_keys"), powerOnHome = b("power_on_home"),
            homeLongPressHome = b("home_long_press_home"), powerOnHomeAlways = b("power_on_home_always"), dockBand = b("dock_band"),
            wifiStatusWarnings = b("wifi_status_warnings"), dateBelowClock = b("date_below_clock"), vpnPill = b("vpn_pill"),
            locale = xml.getValue("locale") as String, topBarApps = list("top_bar_apps"), showGreeting = b("show_greeting"),
            headerWhite = b("header_white"), clockWallpaper = b("clock_wallpaper"), clockWallpaperDim = xml.getValue("clock_wallpaper_dim") as Int,
            clockGlow = b("clock_glow"), singleRow = b("single_row"), dockBandAll = b("dock_band_all"),
            glassStyle = LauncherKeys.glassStyle(xml.getValue("glass_style") as String),
        )
        assertEquals(want, LauncherKeys.DEFAULTS_30)
        assertEquals("glass", xml.getValue("glass_style"))
        assertEquals(false, "lunar_utc_offset" in xml)
        val reader = File(Fixtures.upstreamDir, "launcher/app/src/main/java/com/gathin/tvhome/Config.java").readText()
        assertEquals(true, "integer(json, \"lunar_utc_offset\", lunarOffset, -1, 14)" in reader)
        assertEquals(true, "int lunarOffset = -1;" in reader)
        // Every 3.0 key is in config.xml but lunar_utc_offset, and 24 plus it are the 25.
        assertEquals(ConfigCodecImpl.CARRIED_30 - "lunar_utc_offset", ConfigCodecImpl.CARRIED_30.filter { it in xml }.toSet())
        assertEquals(LauncherKeys.DEFAULTS_30, LauncherKeys.keys30(null))
        assertEquals(LauncherKeys.DEFAULTS_30, LauncherKeys.keys30(LauncherConfigDoc(columns = 5)))
        // The 2.7 look keys keep their 2.7 defaults in 3.0's config.xml.
        assertEquals(LauncherKeys.DEFAULTS_27.ambientGlow, b("ambient_glow"))
    }

    // Config.glassStyle: rim or halo in any case once trimmed, else glass; a non-string keeps glass.
    @Test fun anUnknownGlassStyleGivesGlass() {
        for ((v, want) in listOf("\"rim\"" to GlassStyle.RIM, "\" RIM \"" to GlassStyle.RIM, "\"Halo\"" to GlassStyle.HALO,
            "\"glass\"" to GlassStyle.GLASS, "\"neon\"" to GlassStyle.GLASS, "\"\"" to GlassStyle.GLASS, "5" to GlassStyle.GLASS,
            "true" to GlassStyle.GLASS, "[\"rim\"]" to GlassStyle.GLASS, "\"rim halo\"" to GlassStyle.GLASS))
            assertEquals(want, LauncherKeys.keys30(decoded("{\"glass_style\": $v}")).glassStyle, "glass_style $v")
        assertEquals(GlassStyle.GLASS, LauncherKeys.glassStyle(null))
    }

    // Config.stringList: strings only, trimmed as Java trims, blanks dropped; a non-list keeps the default.
    @Test fun listsReadAsConfigStringListReadsThem() {
        val k = LauncherKeys.keys30(decoded("{\"top_bar_apps\": [\" a.b \", \"\", \"  \", 5, null, [\"c.d\"], \"\\u0001e.f\"], \"input_sources\": \"HW5=23\"}"))
        assertEquals(listOf("a.b", "e.f"), k.topBarApps)
        assertEquals(emptyList(), k.inputSources)
        assertEquals(listOf("HW5=23"), LauncherKeys.keys30(decoded("{\"input_sources\": [\"HW5=23\"]}")).inputSources)
        val n = LauncherKeys.keys30(decoded("{\"lunar_utc_offset\": 15, \"clock_wallpaper_dim\": 90.9, \"dock_band\": \"false\", \"locale\": \" vi \"}"))
        assertEquals(listOf<Any>(-1, 90, true, "vi"), listOf(n.lunarUtcOffset, n.clockWallpaperDim, n.dockBand, n.locale))
    }

    // Config.glowStyle: horizon in any case once trimmed, else radial; a non-string keeps radial.
    @Test fun anUnknownGlowStyleGivesRadial() {
        for ((v, want) in listOf("\"horizon\"" to GlowStyle.HORIZON, "\" HORIZON \"" to GlowStyle.HORIZON, "\"Horizon\"" to GlowStyle.HORIZON,
            "\"radial\"" to GlowStyle.RADIAL, "\"top\"" to GlowStyle.RADIAL, "\"\"" to GlowStyle.RADIAL, "5" to GlowStyle.RADIAL,
            "true" to GlowStyle.RADIAL, "[\"horizon\"]" to GlowStyle.RADIAL, "\"radial horizon\"" to GlowStyle.RADIAL))
            assertEquals(want, LauncherKeys.look(decoded("{\"glow_style\": $v}")).glowStyle, "glow_style $v")
        assertEquals(GlowStyle.RADIAL, LauncherKeys.glowStyle(null))
    }

    // Config.string trims as Java does (every char up to U+0020) and keeps the default for a non-string.
    @Test fun stringsTrimAsJavaDoes() {
        assertEquals("horizon", LauncherKeys.string(doc("k" to JsonPrimitive("\u0001 horizon \t")), "k", "d"))
        assertEquals("d", LauncherKeys.string(doc("k" to JsonPrimitive(5)), "k", "d"))
        assertEquals("d", LauncherKeys.string(doc("k" to JsonNull), "k", "d"))
        assertEquals("d", LauncherKeys.string(null, "k", "d"))
    }

    // What LookKeys writes reads back to the look it means.
    @Test fun aGlassLookReadsBack() {
        val d = LookKeys.build(LookKeys.spec(StyleId.GLASS, names = true), PictureKeys(true, "#0E1022", 0, 0), 5, emptyList(), cards = false)
        assertEquals(
            LookConfig(ambientGlow = true, glowStyle = GlowStyle.HORIZON, appGlow = true, dockGlass = true, dockOpacity = 100,
                dockEvenPadding = true, alwaysShowNames = true, iconOnlyButtons = true, iconOnlyInputs = true, topMenu = false,
                wifiStatusColor = true, focusOvershoot = true, cinematicDots = true, cinematicWhiteFocus = false, cinematicArt = 50, cinematicScrim = 100),
            LauncherKeys.look(d),
        )
        val s = LookKeys.build(LookKeys.spec(StyleId.SPOTLIGHT).copy(ring = false, band = BandChoice.SEE_THROUGH, seeThrough = 30),
            PictureKeys(true, "#0E1022", 0, 0), 5, emptyList(), cards = false)
        val l = LauncherKeys.look(s)
        assertEquals(listOf(true, true, 45, 20, 100), listOf(l.ambientGlow, l.cinematicWhiteFocus, l.cinematicScrim, l.cinematicArt, l.dockOpacity))
    }
}
