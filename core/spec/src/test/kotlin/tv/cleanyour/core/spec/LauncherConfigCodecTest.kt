package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import org.junit.Test
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Fixtures
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LauncherConfigCodecTest {
    private fun ok(s: String) = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(s.encodeToByteArray()), s)
    private fun rejected(b: ByteArray) = assertIs<DecodeResult.Rejected>(LauncherConfigCodec.decode(b))

    @Test fun decodesTheUpstreamThemes() {
        val t = Fixtures.upstreamThemes().associate { it.name to LauncherConfigCodec.decode(it.configJson) }
        val aurora = assertIs<DecodeResult.Ok>(t.getValue("aurora")).doc
        assertEquals(LauncherConfigDoc(backgroundColor = "#070A14", wallpaper = true), aurora)
        assertEquals(0xFF070A14.toInt(), LauncherConfigCodec.effective(aurora, LauncherConfigCodec.builtInDefaults).backgroundColorArgb)
        val six = assertIs<DecodeResult.Ok>(t.getValue("six-columns")).doc
        assertEquals(6, six.columns); assertEquals(1, six.rowsBelowDock); assertEquals(false, six.wallpaper)
        val def = assertIs<DecodeResult.Ok>(t.getValue("default"))
        assertEquals(emptyList(), def.warnings)
        // The default theme is the built-in look written out.
        val e = LauncherConfigCodec.effective(def.doc, LauncherConfigCodec.builtInDefaults)
        assertEquals(LauncherConfigCodec.builtInDefaults.copy(wallpaper = false), e)
        assertEquals(false, def.doc.reducedMotion)
        // v2.4.0's festive theme: a festival with an end date and an accent.
        val dussehra = assertIs<DecodeResult.Ok>(t.getValue("dussehra"))
        assertEquals(emptyList(), dussehra.warnings)
        val d = LauncherConfigCodec.effective(dussehra.doc, LauncherConfigCodec.builtInDefaults)
        assertEquals(listOf("dussehra", "2026-10-21", "", 0xFFFFB23C.toInt()), listOf(d.festival, d.festivalUntil, d.greeting, d.accentColorArgb))
    }

    // RFC-W4-2: the seventeen keys launcher v2.4.0 added are fields, decoded in Config.java's
    // order, written back in that order, and previewed by effective(); none lands in `unknown`.
    @Test fun theV24KeysAreModelled() {
        val all = "{\n  \"dock_apps\": [\"a.b\"],\n  \"fill_dock\": false,\n  \"columns\": 5,\n  \"animations\": false,\n" +
            "  \"advanced_effects\": true,\n  \"greeting_name\": \"Mr. Trịnh\",\n  \"module_cards\": false,\n  \"reduced_motion\": true,\n" +
            "  \"dock_title\": false,\n  \"dock_row\": false,\n  \"reorder_button\": true,\n  \"show_stats\": true,\n" +
            "  \"app_library\": true,\n  \"sky_intensity\": 60,\n  \"settings_key_keycodes\": [83, 384],\n  \"settings_key_panel\": true,\n" +
            "  \"wallpaper\": true,\n  \"festival\": \"Diwali\",\n  \"festival_until\": \"2026-11-09\",\n  \"greeting\": \"Shubh  Deepavali\",\n" +
            "  \"accent_color\": \"#ffb23c\"\n}\n"
        val r = ok(all)
        assertEquals(emptyList(), r.warnings)
        assertEquals(emptyMap(), r.doc.unknown)
        assertEquals(all, LauncherConfigCodec.encode(r.doc).decodeToString())
        val e = LauncherConfigCodec.effective(r.doc, LauncherConfigCodec.builtInDefaults)
        assertEquals(
            listOf(false, false, true, "Mr. Trịnh", true, false, false, true, true, true, 60, listOf(83, 384), true),
            listOf(e.fillDock, e.animations, e.advancedEffects, e.greetingName, e.reducedMotion, e.dockTitle, e.dockRow,
                e.reorderButton, e.showStats, e.appLibrary, e.skyIntensity, e.settingsKeyKeycodes, e.settingsKeyPanel),
        )
        assertEquals(listOf("diwali", "2026-11-09", "Shubh Deepavali", 0xFFFFB23C.toInt()), listOf(e.festival, e.festivalUntil, e.greeting, e.accentColorArgb))
        // Unordered and padded input reads to the same doc.
        val shuffled = ok("{\"accent_color\": \" #ffb23c \", \"sky_intensity\": 60.7, \"festival\": \" Diwali\", \"greeting_name\": \" Mr. Trịnh \"}")
        assertEquals(LauncherConfigDoc(accentColor = "#ffb23c", skyIntensity = 60, festival = "Diwali", greetingName = "Mr. Trịnh"), shuffled.doc)
        // The built-in values are config.xml's.
        val b = LauncherConfigCodec.builtInDefaults
        assertEquals(listOf(true, true, false, "", false, true, true, false, false, false, 0, emptyList<Int>(), false, "", "", "", 0),
            listOf(b.fillDock, b.animations, b.advancedEffects, b.greetingName, b.reducedMotion, b.dockTitle, b.dockRow, b.reorderButton,
                b.showStats, b.appLibrary, b.skyIntensity, b.settingsKeyKeycodes, b.settingsKeyPanel, b.festival, b.festivalUntil, b.greeting, b.accentColorArgb))
    }

    // settings_key_keycodes is all or nothing, as Config.intArray reads it.
    @Test fun keycodesAreAllOrNothing() {
        assertEquals(listOf(83, 83, Int.MAX_VALUE), ok("{\"settings_key_keycodes\": [83.9, 4294967379, 1e300]}").doc.settingsKeyKeycodes)
        assertEquals(emptyList(), ok("{\"settings_key_keycodes\": []}").doc.settingsKeyKeycodes)
        for (bad in listOf("[83, 0]", "[83, \"84\"]", "[83, null]", "[83,,84]", "[0.5]", "83", "\"83\"")) {
            val r = ok("{\"settings_key_keycodes\": $bad}")
            assertEquals(null, r.doc.settingsKeyKeycodes, bad)
            assertEquals(1, r.warnings.size, "$bad: ${r.warnings}")
        }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(settingsKeyKeycodes = listOf(83, 0))) }
        assertEquals("{\n  \"settings_key_keycodes\": [83]\n}\n", LauncherConfigCodec.encode(LauncherConfigDoc(settingsKeyKeycodes = listOf(83))).decodeToString())
    }

    // A list that takes Back, the arrows, OK, Enter or Menu would leave Home without those buttons:
    // decode keeps it (the launcher reads it) and warns (SECURITY.md V1).
    @Test fun navigationKeycodesWarn() {
        for (k in listOf(4, 19, 20, 21, 22, 23, 66, 82)) {
            val r = ok("{\"settings_key_keycodes\": [83, $k]}")
            assertEquals(listOf(83, k), r.doc.settingsKeyKeycodes)
            assertEquals(1, r.warnings.size, r.warnings.toString())
            assertTrue("keycode $k" in r.warnings.single() && "Settings" in r.warnings.single(), r.warnings.single())
        }
        assertEquals(emptyList(), ok("{\"settings_key_keycodes\": [83, 384, 176]}").warnings)
        assertTrue("keycode 19, 66" in ok("{\"settings_key_keycodes\": [19, 66, 19]}").warnings.single())
    }

    // RFC-W4-3: the nine keys launcher v2.5.0 added, read as Config.java reads them.
    @Test fun theV25KeysAreModelled() {
        val r = ok("{\"launch_actions\": [\" com.telekom.onetv.tv.pl=com.telekom.onetv.tv.pl.action.PROGRAM_GUIDE \"], " +
            "\"app_labels\": [\"com.telekom.onetv.tv.pl=TV\", \"com.telekom.onetv.tv.pl=Live\"], \"app_banners\": [\"com.telekom.onetv.tv.pl=banner_tv\"], " +
            "\"input_key_keycodes\": [192], \"app_keys\": [\"190:com.google.android.youtube.tv\"], \"cinematic_home\": true, " +
            "\"wallpaper_rotation_minutes\": 30, \"wallpaper_dim\": 40, \"race_calendar\": [{\"round\": 18, \"name\": \"United States Grand Prix\", " +
            "\"place\": \"Austin\", \"sessions\": [{\"name\": \"Race\", \"start\": \"2026-10-25T19:00:00Z\"}, {\"name\": \"fp1\", \"start\": \"2026-10-23T17:30:00Z\"}]}]}")
        assertEquals(emptyList(), r.warnings)
        val e = LauncherConfigCodec.effective(r.doc, LauncherConfigCodec.builtInDefaults)
        assertEquals(mapOf("com.telekom.onetv.tv.pl" to "com.telekom.onetv.tv.pl.action.PROGRAM_GUIDE"), e.launchActions)
        assertEquals(mapOf("com.telekom.onetv.tv.pl" to "Live"), e.appLabels, "a later entry for a package wins")
        assertEquals(mapOf("com.telekom.onetv.tv.pl" to "banner_tv"), e.appBanners)
        assertEquals(listOf(192) to listOf("190:com.google.android.youtube.tv"), e.inputKeyKeycodes to e.appKeys)
        assertEquals(Triple(true, 30, 40), Triple(e.cinematicHome, e.wallpaperRotationMinutes, e.wallpaperDim))
        assertEquals(listOf(LauncherRace(18, "United States Grand Prix", "Austin", listOf(
            LauncherRaceSession("FP1", "2026-10-23T17:30:00Z"), LauncherRaceSession("RACE", "2026-10-25T19:00:00Z")))), e.raceCalendar)
        // Our own encoding reads back the same, and an older push (no new keys) is unchanged.
        assertEquals(r.doc, ok(LauncherConfigCodec.encode(r.doc).decodeToString()).doc)
        assertEquals("{\n  \"columns\": 6\n}\n", LauncherConfigCodec.encode(LauncherConfigDoc(columns = 6)).decodeToString())
        // What the launcher skips, it says so.
        val bad = ok("{\"launch_actions\": [\"no equals\", \"=x\", \"Not A Package=x\"], \"app_banners\": [\"a.b=banner_none\"], " +
            "\"app_keys\": [\"x:a.b\", \"190:\", \"0:a.b\"], \"wallpaper_dim\": 91, \"race_calendar\": [{\"sessions\": [{\"name\": \"FP1\", \"start\": \"tomorrow\"}]}]}")
        assertEquals(10, bad.warnings.size, bad.warnings.joinToString("\n"))
        assertEquals(emptyList(), LauncherConfigCodec.effective(bad.doc, LauncherConfigCodec.builtInDefaults).raceCalendar)
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(appKeys = listOf(" 190:a.b"))) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(wallpaperDim = 91)) }
        assertFailsWith<IllegalArgumentException> {
            LauncherConfigCodec.encode(LauncherConfigDoc(raceCalendar = listOf(LauncherRace(1, "x", "y", listOf(LauncherRaceSession("fp1", "2026-10-23T17:30:00Z"))))))
        }
    }

    // SECURITY.md V1: with launcher 2.5's key services on (InputKeyService, HomeKeyService), a
    // navigation button in input_key_keycodes or app_keys is taken in every app.
    @Test fun navigationKeysInTheKeyServicesWarn() {
        val r = ok("{\"input_key_keycodes\": [192, 23], \"app_keys\": [\"KEYCODE_DPAD_CENTER:a.b\", \"4:a.b\", \"190:a.b\"]}")
        assertEquals(2, r.warnings.size, r.warnings.toString())
        assertTrue("keycode 23" in r.warnings[0] && "input switcher" in r.warnings[0], r.warnings[0])
        assertTrue("keycode 23, 4" in r.warnings[1] && "any app" in r.warnings[1], r.warnings[1])
        assertEquals(emptyList(), ok("{\"input_key_keycodes\": [192], \"app_keys\": [\"KEYCODE_BUTTON_3:a.b\"]}").warnings)
    }

    @Test fun festivalLikeFestivalJava() {
        fun e(json: String) = LauncherConfigCodec.effective(ok(json).doc, LauncherConfigCodec.builtInDefaults)
        assertEquals("", e("{\"festival\": \"x\", \"festival_until\": \"2026-02-30\"}").festival)
        assertEquals("", e("{\"festival\": \"" + "x".repeat(41) + "\"}").festival)
        assertEquals("x".repeat(40), e("{\"festival\": \"" + "X".repeat(40) + "\"}").festival)
        assertEquals("", e("{\"festival\": \"" + "\u0130".repeat(21) + "\"}").festival, "lower-casing grows it past 40")
        assertEquals("" to "", e("{\"festival\": \"x\"}").let { it.festivalUntil to it.greeting })
        assertEquals(0, e("{\"festival\": \"x\", \"accent_color\": \"#FFF\"}").accentColorArgb)
        assertEquals("g".repeat(39), e("{\"festival\": \"x\", \"greeting\": \"" + "g".repeat(39) + "\uD83D\uDE00\"}").greeting, "a pair is never split")
        assertEquals("" to 0, e("{\"greeting\": \"hi\", \"accent_color\": \"#123456\"}").let { it.greeting to it.accentColorArgb })
        // Warnings name what the launcher drops.
        assertEquals(1, ok("{\"festival\": \"x\", \"festival_until\": \"soon\"}").warnings.size)
        assertEquals(1, ok("{\"festival\": \"x\", \"accent_color\": \"gold\"}").warnings.size)
        assertEquals(2, ok("{\"greeting\": \"hi\", \"accent_color\": \"#123456\"}").warnings.size)
        assertEquals(0, ok("{\"festival\": \"x\", \"accent_color\": \"\"}").warnings.size)
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(festival = " x")) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(skyIntensity = 101)) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(greetingName = "x ")) }
    }

    @Test fun perKeyTypeAndRangeLikeConfigJava() {
        val r = ok("{\"columns\": 9, \"rows_below_dock\": -1, \"shamsi_date\": \"true\", \"dock_apps\": [\" a.b \", \"\", 3, \"not a pkg\"], \"vpn_app\": 7, \"zzz\": 1}")
        assertEquals(null, r.doc.columns); assertEquals(null, r.doc.rowsBelowDock); assertEquals(null, r.doc.shamsiDate); assertEquals(null, r.doc.vpnApp)
        assertEquals(listOf(PackageName("a.b")), r.doc.dockApps)
        assertEquals(setOf("zzz"), r.doc.unknown.keys)
        assertEquals(8, r.warnings.size, r.warnings.toString())
        assertEquals(4, ok("{\"columns\": 4294967300}").doc.columns)            // Number.intValue() of a long
        assertEquals(7, ok("{\"columns\": 07.9}").doc.columns)                  // 07.9 is a double: 7
        assertEquals(8, ok("{\"columns\": 010}").doc.columns)                   // octal
        assertEquals(6, ok("{columns: 0x6; rows_below_dock: 1}").doc.columns)
        assertEquals(6, ok("{columns = 0x6} trailing text").doc.columns)
        assertEquals("x", ok("{'live_tv_action' => ' x ' /* c */ # hash\n}").doc.liveTvAction)
    }

    // The keys launcher 2.7 reads that LauncherConfigDoc has no field for (the 0.4.0 looks' three
    // and look2's) are carried as is with no warning (the bundled launcher does read them), and
    // written back after the modelled keys, sorted. Each is in the submodule's config.xml (but
    // dock_opacity, which Config.load reads from config.json alone) and in its Config.load.
    @Test fun launcher27KeysTheLooksUseAreCarriedWithoutAWarning() {
        val r = ok("{\"ambient_glow\": false, \"cinematic_scrim\": 45, \"cinematic_art\": 20, \"columns\": 5, \"zz\": 1}")
        assertEquals(setOf("ambient_glow", "cinematic_scrim", "cinematic_art", "zz"), r.doc.unknown.keys)
        assertEquals(listOf("unknown key zz; the launcher ignores it (carried as is)"), r.warnings)
        assertEquals("{\n  \"columns\": 5,\n  \"ambient_glow\": false,\n  \"cinematic_art\": 20,\n  \"cinematic_scrim\": 45,\n  \"zz\": 1\n}\n",
            LauncherConfigCodec.encode(r.doc).decodeToString())
        val xml = java.io.File(Fixtures.upstreamDir, "launcher/app/src/main/res/values/config.xml").readText()
        // dock_opacity is read from config.json alone (Config.load: 30 to 100, else 100).
        for (k in ConfigCodecImpl.CARRIED_27 - "dock_opacity") assertTrue(Regex("<(bool|integer|string) name=\"$k\"").containsMatchIn(xml), k)
        val reader = java.io.File(Fixtures.upstreamDir, "launcher/app/src/main/java/com/gathin/tvhome/Config.java").readText()
        for (k in ConfigCodecImpl.CARRIED_27) assertTrue("(json, \"$k\"" in reader, "Config.load reads $k from config.json")
    }

    // Every carried key round trips with no warning, in every value LookKeys writes.
    @Test fun everyCarriedKeyRoundTripsWithoutAWarning() {
        assertEquals(16, ConfigCodecImpl.CARRIED_27.size)
        for (k in ConfigCodecImpl.CARRIED_27) assertTrue(k !in ConfigCodecImpl.MODELLED, k)
        val values = mapOf(
            "glow_style" to listOf(JsonPrimitive("radial"), JsonPrimitive("horizon")),
            "dock_opacity" to listOf(30, 50, 70, 100).map { JsonPrimitive(it) },
            "cinematic_scrim" to listOf(0, 45, 100).map { JsonPrimitive(it) },
            "cinematic_art" to listOf(0, 20, 50, 100).map { JsonPrimitive(it) },
        )
        for (k in ConfigCodecImpl.CARRIED_27) for (v in values[k] ?: listOf(JsonPrimitive(true), JsonPrimitive(false))) {
            val doc = LauncherConfigDoc(columns = 5, unknown = mapOf(k to v))
            val text = LauncherConfigCodec.encode(doc).decodeToString()
            assertEquals("{\n  \"columns\": 5,\n  \"$k\": $v\n}\n", text)
            val back = ok(text)
            assertEquals(emptyList(), back.warnings, "$k $v")
            assertEquals(doc, back.doc, "$k $v")
        }
        // All of them at once, as a look2 push writes them: sorted after the modelled keys.
        val all = LauncherConfigDoc(dockRow = true, unknown = ConfigCodecImpl.CARRIED_27.associateWith { k -> values[k]?.first() ?: JsonPrimitive(false) })
        val text = LauncherConfigCodec.encode(all).decodeToString()
        assertEquals(listOf("dock_row") + ConfigCodecImpl.CARRIED_27.sorted(), Regex("\"([a-z_0-9]+)\":").findAll(text).map { it.groupValues[1] }.toList())
        assertEquals(emptyList(), ok(text).warnings)
        assertEquals(all, ok(text).doc)
    }

    // RFC-UP300-1: the 25 keys launcher 3.0 added are carried as is with no warning, apart from the
    // modelled keys and the 2.7 carried ones, and written back after the modelled keys, sorted.
    @Test fun launcher30KeysAreCarriedWithoutAWarning() {
        assertEquals(25, ConfigCodecImpl.CARRIED_30.size)
        for (k in ConfigCodecImpl.CARRIED_30) assertTrue(k !in ConfigCodecImpl.MODELLED && k !in ConfigCodecImpl.CARRIED_27, k)
        val values = mapOf(
            "input_sources" to JsonArray(listOf(JsonPrimitive("HW5=23"), JsonPrimitive("HW8=-"))),
            "top_bar_apps" to JsonArray(listOf(JsonPrimitive("com.google.android.youtube.tv"))),
            "lunar_utc_offset" to JsonPrimitive(7),
            "clock_wallpaper_dim" to JsonPrimitive(40),
            "locale" to JsonPrimitive("pt-BR"),
            "glass_style" to JsonPrimitive("rim"),
        )
        val all = LauncherConfigDoc(dockRow = true, unknown = ConfigCodecImpl.CARRIED_30.associateWith { k -> values[k] ?: JsonPrimitive(true) })
        val text = LauncherConfigCodec.encode(all).decodeToString()
        assertEquals(listOf("dock_row") + ConfigCodecImpl.CARRIED_30.sorted(), Regex("^  \"([a-z_0-9]+)\":", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList())
        assertEquals(emptyList(), ok(text).warnings)
        assertEquals(all, ok(text).doc)
        // Next to an unknown key, only that one warns.
        val r = ok("{\"single_row\": true, \"dock_band\": false, \"zz\": 1}")
        assertEquals(listOf("unknown key zz; the launcher ignores it (carried as is)"), r.warnings)
    }

    // CARRIED_30 is exactly what launcher 3.0's Config.load (the submodule at the pin) reads from a
    // config.json beyond 2.7's (the frozen v27 copy), and each is in 3.0's config.xml but
    // lunar_utc_offset, which Config.load reads from config.json alone.
    @Test fun carried30IsWhatLauncher30ReadsBeyond27() {
        fun jsonKeys(f: java.io.File) = Regex("\\((?:json|o), \"([a-z_0-9]+)\"").findAll(f.readText()).map { it.groupValues[1] }.toSet()
        val v30 = jsonKeys(java.io.File(Fixtures.upstreamDir, "launcher/app/src/main/java/com/gathin/tvhome/Config.java"))
        val v27 = jsonKeys(java.io.File(Fixtures.repoRoot, "core/spec/src/test/java/com/gathin/tvhome/v27/Config.java"))
        assertEquals(ConfigCodecImpl.CARRIED_30, v30 - v27)
        assertEquals(emptySet(), v27 - v30, "3.0 still reads every key 2.7 reads")
        val xml = java.io.File(Fixtures.upstreamDir, "launcher/app/src/main/res/values/config.xml").readText()
        for (k in ConfigCodecImpl.CARRIED_30 - "lunar_utc_offset") assertTrue(Regex("<(bool|integer|string|string-array) name=\"$k\"").containsMatchIn(xml), k)
        assertTrue("name=\"lunar_utc_offset\"" !in xml)
    }

    // RFC-W4-1: the two keys upstream 1bbf0c9 added are fields, decoded in Config.java's order and
    // never carried in `unknown`.
    @Test fun inputActionAndModuleCardsAreModelled() {
        val text = "{\n  \"live_tv_action\": \"x\",\n  \"input_action\": \"com.xgimi.action.hdmiPlayer\",\n  \"columns\": 6,\n" +
            "  \"module_cards\": false,\n  \"zz\": 1\n}\n"
        val r = ok("{\"zz\": 1, \"module_cards\": false, \"input_action\": \" com.xgimi.action.hdmiPlayer \", \"columns\": 6, \"live_tv_action\": \"x\"}")
        assertEquals("com.xgimi.action.hdmiPlayer", r.doc.inputAction)
        assertEquals(false, r.doc.moduleCards)
        assertEquals(setOf("zz"), r.doc.unknown.keys)
        assertEquals(text, LauncherConfigCodec.encode(r.doc).decodeToString())
        assertEquals(r.doc, assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(text.encodeToByteArray())).doc)
        val e = LauncherConfigCodec.effective(r.doc, LauncherConfigCodec.builtInDefaults)
        assertEquals("com.xgimi.action.hdmiPlayer" to false, e.inputAction to e.moduleCards)
        // Wrong types: the launcher keeps its defaults, so the doc leaves them null with a warning.
        val wrong = ok("{\"input_action\": 3, \"module_cards\": \"false\"}")
        assertEquals(null, wrong.doc.inputAction); assertEquals(null, wrong.doc.moduleCards)
        assertEquals(emptyMap(), wrong.doc.unknown)
        assertEquals(2, wrong.warnings.size, wrong.warnings.toString())
        val d = LauncherConfigCodec.effective(wrong.doc, LauncherConfigCodec.builtInDefaults)
        assertEquals("" to true, d.inputAction to d.moduleCards)
        assertEquals("" to true, LauncherConfigCodec.builtInDefaults.let { it.inputAction to it.moduleCards })
    }

    // Launcher 2.7 keeps a copy of config.json through JSONObject.toString(), which is null for a
    // NaN or Infinity in a list: Home would crash on every start, so the file is refused.
    @Test fun aNanOrInfinityInAListCrashesLauncher27() {
        for (json in listOf("{\"dock_apps\": [NaN, \"a.b\"]}", "{\"settings_key_keycodes\": [83, 1e400]}", "{\"x\": {\"y\": [-1e400]}}",
            "{\"race_calendar\": [{\"sessions\": [], \"n\": [NaN]}]}"))
            assertEquals(ConfigCodecImpl.CRASH_27_NON_FINITE, rejected(json.encodeToByteArray()).reason, json)
        assertTrue("crash" in ConfigCodecImpl.CRASH_27_NON_FINITE)
        assertEquals(listOf(4), ok("{\"settings_key_keycodes\": [4.9]}").doc.settingsKeyKeycodes)
    }

    @Test fun refusesWhatTheLauncherIgnoresOrCrashesOn() {
        assertTrue(rejected(ByteArray(64 * 1024 + 1) { ' '.code.toByte() }).reason.contains("64 KB"))
        rejected("[1]".encodeToByteArray()); rejected("".encodeToByteArray()); rejected("{\"columns\": NaN}".encodeToByteArray())
        assertTrue(rejected("{\"background_color\": \"  \"}".encodeToByteArray()).reason.contains("crash"))
        assertTrue(rejected(("{\"a\": " + "[".repeat(600) + "]".repeat(600) + "}").encodeToByteArray()).reason.contains("nested"))
        ok("{\"background_color\": \"#12\"}").let { assertEquals(null, it.doc.backgroundColor); assertEquals(1, it.warnings.size) }
        assertEquals(0xFFFFFFFF.toInt(), LauncherConfigCodec.effective(ok("{\"background_color\": \"#-0000001\"}").doc, LauncherConfigCodec.builtInDefaults).backgroundColorArgb)
    }

    @Test fun encodeIsCanonicalAndStrict() {
        val doc = LauncherConfigDoc(dockApps = listOf(PackageName("com.netflix.ninja")), columns = 5, backgroundColor = "#000000", wallpaper = false,
            unknown = mapOf("zz_custom" to JsonArray(listOf(JsonPrimitive(1)))), inputAction = "com.xgimi.action.hdmiPlayer", moduleCards = false)
        val text = LauncherConfigCodec.encode(doc).decodeToString()
        assertEquals(
            "{\n  \"dock_apps\": [\"com.netflix.ninja\"],\n  \"input_action\": \"com.xgimi.action.hdmiPlayer\",\n  \"columns\": 5,\n" +
                "  \"module_cards\": false,\n  \"background_color\": \"#000000\",\n  \"wallpaper\": false,\n  \"zz_custom\": [1]\n}\n",
            text,
        )
        val e = LauncherConfigCodec.effective(doc, LauncherConfigCodec.builtInDefaults)
        assertEquals("com.xgimi.action.hdmiPlayer" to false, e.inputAction to e.moduleCards)
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(inputAction = " padded")) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(unknown = mapOf("input_action" to JsonPrimitive("x")))) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(unknown = mapOf("module_cards" to JsonPrimitive(false)))) }
        assertContentEquals("{}\n".encodeToByteArray(), LauncherConfigCodec.encode(LauncherConfigDoc()))
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(columns = 9)) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(backgroundColor = "")) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(vpnApp = " padded")) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(unknown = mapOf("columns" to JsonPrimitive(3)))) }
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val raw = JsonUnquotedLiteral("1}, \"columns\": 8, {")
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(unknown = mapOf("x" to raw))) }
        // Over the cap.
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(vpnApp = "v".repeat(70_000))) }
    }

    @Test fun bundledThemes() {
        val themes = BundledThemeSource().bundled()
        // dussehra (v2.4.0) is not bundled: its festival ends on 2026-10-21. The app's own looks follow.
        assertEquals(listOf("default", "aurora", "six-columns", "cinema", "console", "horizon", "quiet", "diwali", "library", "arcade", "mist"), themes.map { it.id })
        assertEquals(false, themes[0].doc.reducedMotion, "the bundled default is v2.4.0's")
        assertEquals(listOf("Default", "Aurora", "Six columns", "Cinema", "Console", "Horizon", "Quiet black", "Diwali", "Library", "Arcade", "Mist"), themes.map { it.name })
        val aurora = themes[1]
        val wp = aurora.wallpaper!!
        val bytes = BundledThemeSource.bytes(wp)!!
        assertContentEquals(java.io.File(Fixtures.upstreamDir, "themes/aurora/wallpaper.jpg").readBytes(), bytes)
        assertEquals(bytes.size.toLong(), wp.size)
        assertEquals(null, themes[0].wallpaper)
        for (t in themes) {
            assertTrue('\u2014' !in t.description && "-".repeat(2) !in t.description)
            // A bundled theme survives our own encoder unchanged.
            assertEquals(t.doc, assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(LauncherConfigCodec.encode(t.doc))).doc)
        }
    }

    // 0.4.0: every look of the app's own sets each layout and motion key outright, so switching from
    // any look to it leaves nothing of the last one behind; it decodes without a warning, and its
    // picture is a JPEG under the transport's 300 KB cap that the launcher shows (wallpaper true).
    @Test fun ownLooksSetEveryLayoutAndMotionKey() {
        val own = BundledThemeSource().bundled().drop(3)
        assertEquals(listOf("cinema", "console", "horizon", "quiet", "diwali", "library", "arcade", "mist"), own.map { it.id })
        for (t in own) {
            val d = t.doc
            val keys = listOf(d.backgroundColor, d.wallpaper, d.wallpaperDim, d.wallpaperRotationMinutes, d.skyIntensity, d.cinematicHome,
                d.dockTitle, d.dockRow, d.columns, d.rowsBelowDock, d.animations, d.advancedEffects, d.reducedMotion)
            assertTrue(keys.all { it != null }, "${t.id} leaves a key to the launcher")
            assertEquals(null, d.dockApps, "${t.id} names no apps")
            val bytes = LauncherConfigCodec.encode(d)
            assertEquals(emptyList(), assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(bytes)).warnings, t.id)
            val w = t.wallpaper
            assertEquals(d.wallpaper == true, w != null, "${t.id}: a picture exactly when the look shows one")
            if (w != null) {
                val b = BundledThemeSource.bytes(w)!!
                assertTrue(b.size in 1..300_000, "${t.id}: ${b.size} bytes")
                assertTrue(b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte(), "${t.id}: a JPEG")
            }
        }
        assertEquals(55, own.first { it.id == "horizon" }.doc.skyIntensity)
        assertEquals(false, own.first { it.id == "library" }.doc.dockRow, "Library: one plain grid from the top")
        val diwali = own.first { it.id == "diwali" }.doc
        assertEquals("diwali", diwali.festival); assertEquals("2026-11-10", diwali.festivalUntil)
        // rows_below_dock is the phone's to write: DockLayers sets it from the dock's length on every
        // push (as every version since 0.1.0 does), so a look file saying 1 promised a row a 4-app dock never shows.
        for (t in own) assertEquals(0, t.doc.rowsBelowDock, "${t.id}: the rows under the dock follow the dock")
    }

    @Test fun datedLooksAreOfferedUpToTheirLastDay() {
        val last = java.time.LocalDate.of(2026, 11, 10)
        assertEquals(last, Looks.info("diwali")?.until)
        assertEquals(BundledThemeSource().bundled().first { it.id == "diwali" }.doc.festivalUntil, last.toString(), "the side table and the festival end together")
        assertTrue(Looks.offered("diwali", last))
        assertTrue(!Looks.offered("diwali", last.plusDays(1)))
        assertTrue(Looks.offered("cinema", last.plusYears(5)))
        assertTrue(Looks.offered(null, last.plusYears(5)))
        assertTrue(Looks.info("horizon")!!.sky)
    }
}
