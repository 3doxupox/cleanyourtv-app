package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Fixtures
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

// F8 acceptance: upstream Config.java and LauncherConfigCodec agree on every theme fixture and on
// 10,000 generated files, so the phone's preview can never disagree with the TV. Upstream runs
// for real: its Config.java compiled unchanged on the JVM against AOSP's own org.json and
// Color.parseColor (test sources) and stubs for Context, Resources and Log.
class ConfigDifferentialTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun validPkg(s: String) = runCatching { PackageName(s) }.isSuccess

    private class Tally { var applied = 0; var ignored = 0; var crashed = 0; var withWarnings = 0 }

    // One file through both sides; returns which way the launcher went.
    private fun agree(up: UpstreamLauncher, bytes: ByteArray?, wallpaper: Boolean, latest: Boolean, label: String, t: Tally) {
        val upstream = up.load(bytes, wallpaper)
        val ours = bytes?.let { LauncherConfigCodec.decode(it) }
        fun ctx() = "$label: ${bytes?.let { String(it, Charsets.UTF_8).take(400) }}"
        when (upstream) {
            is UpstreamLauncher.Outcome.Crashed -> {
                t.crashed++
                assertTrue(upstream.error is StringIndexOutOfBoundsException, "unexpected upstream crash ${upstream.error} for ${ctx()}")
                assertTrue(ours is DecodeResult.Rejected && ours.reason.contains("crash"), "upstream crashes but we said $ours for ${ctx()}")
            }
            is UpstreamLauncher.Outcome.Loaded -> {
                val u = upstream.config
                if (bytes != null && !u.overlayApplied) {
                    t.ignored++
                    assertTrue(ours is DecodeResult.Rejected && !ours.reason.contains("crash"), "upstream ignores the file but we said $ours for ${ctx()}")
                }
                val doc = when (ours) {
                    null -> null
                    is DecodeResult.Ok -> { t.applied++; if (ours.warnings.isNotEmpty()) t.withWarnings++; ours.doc }
                    is DecodeResult.Rejected -> {
                        assertTrue(!u.overlayApplied, "we rejected (${ours.reason}) a file upstream applied: ${ctx()}")
                        null
                    }
                }
                val e = LauncherConfigCodec.effective(doc, up.defaults())
                // input_action and module_cards exist only in latest main; the pinned launcher ignores them.
                val mine = listOf(
                    e.dockApps, e.hiddenApps, e.liveTvPackage, e.liveTvAction, e.sourcePanelService, e.sourcePanelAction,
                    e.columns, e.rowsBelowDock, e.shamsiDate, e.vpnApp, e.showHomeSettingsShortcut, e.backgroundColorArgb,
                    e.wallpaper && wallpaper,
                ) + if (latest) listOf(e.inputAction, e.moduleCards) else emptyList()
                // Entries that are not package names can never match an app; the codec drops them.
                val theirs = listOf(
                    u.dockApps.filter(::validPkg), u.hiddenApps.filter(::validPkg), u.liveTvPackage, u.liveTvAction,
                    u.sourcePanelService, u.sourcePanelAction, u.columns, u.rowsBelowDock, u.shamsiDate, u.vpnApp,
                    u.showHomeSettingsShortcut, u.backgroundColor, u.wallpaperShown,
                ) + if (latest) listOf(u.inputAction, u.moduleCards) else emptyList()
                if (mine != theirs) fail("effective config differs for ${ctx()}\n ours:     $mine\n upstream: $theirs")
            }
        }
    }

    private fun bothLaunchers() = listOf(
        UpstreamLauncher(latest = true, dir = tmp.newFolder("latest")),
        UpstreamLauncher(latest = false, dir = tmp.newFolder("pinned")),
    )

    @Test fun builtInDefaultsEqualTheLaunchersConfigXml() {
        for (up in bothLaunchers()) assertEquals(up.defaults(), LauncherConfigCodec.builtInDefaults)
        val latest = UpstreamLauncher.parseConfigXml(java.io.File(javaClass.getResource("/upstream/config-1bbf0c9.xml")!!.toURI()))
        val b = LauncherConfigCodec.builtInDefaults
        assertEquals(b.inputAction to b.moduleCards, latest.getValue("input_action") to latest.getValue("module_cards"))
    }

    @Test fun themeFixturesAndHandWrittenCasesAgree() {
        val cases = Fixtures.upstreamThemes().map { "theme ${it.name}" to it.configJson } + listOf(
            "no file" to null,
            "empty" to ByteArray(0),
            "bom" to "﻿{\"columns\": 6}".encodeToByteArray(),
            "two boms" to "﻿﻿{\"columns\": 6}".encodeToByteArray(),
            "three boms" to "﻿﻿﻿{\"columns\": 6}".encodeToByteArray(),
            "empty colour crashes Home" to "{\"background_color\": \"\"}".encodeToByteArray(),
            "blank colour crashes Home" to "{\"background_color\": \"  \"}".encodeToByteArray(),
            "long truncates" to "{\"columns\": 4294967302}".encodeToByteArray(),
            "octal" to "{\"columns\": 07, rows_below_dock = 03}".encodeToByteArray(),
            "nan forbidden" to "{\"columns\": NaN}".encodeToByteArray(),
            "nan in a list is fine" to "{\"dock_apps\": [NaN, \"a.b\"]}".encodeToByteArray(),
            "trailing garbage" to "{\"columns\": 3} and more".encodeToByteArray(),
            "64 KB exactly" to ("{\"columns\": 2}" + " ".repeat(65536 - 14)).encodeToByteArray(),
            "64 KB plus one" to ("{\"columns\": 2}" + " ".repeat(65537 - 14)).encodeToByteArray(),
            "extras" to "{\"input_action\": \" com.xgimi.action.hdmiPlayer \", \"module_cards\": false}".encodeToByteArray(),
            "extras wrong types" to "{\"input_action\": 3, \"module_cards\": \"false\"}".encodeToByteArray(),
            "wallpaper off" to "{\"wallpaper\": false}".encodeToByteArray(),
            "wallpaper not a bool" to "{\"wallpaper\": \"no\"}".encodeToByteArray(),
            "invalid utf8" to byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte(), ':'.code.toByte(), '1'.code.toByte(), '}'.code.toByte()),
        )
        for ((i, up) in bothLaunchers().withIndex()) {
            val t = Tally()
            for ((label, bytes) in cases) for (wp in listOf(true, false)) agree(up, bytes, wp, latest = i == 0, label = label, t = t)
            assertTrue(t.crashed > 0 && t.ignored > 0 && t.applied > 0)
        }
    }

    @Test fun tenThousandGeneratedFilesAgreeWithLatestMain() {
        val up = UpstreamLauncher(latest = true, dir = tmp.newFolder("gen"))
        val fuzz = ConfigFuzz(seed = 0x5EED_0001)
        val t = Tally()
        repeat(10_000) { i -> agree(up, fuzz.next(), wallpaper = i % 3 != 0, latest = true, label = "generated #$i", t = t) }
        println("latest main: ${t.applied} applied (${t.withWarnings} with warnings), ${t.ignored} ignored, ${t.crashed} would crash")
        assertTrue(t.applied > 4_000 && t.ignored > 1_000 && t.crashed > 10 && t.withWarnings > 1_000, "generator lost coverage: applied ${t.applied}, ignored ${t.ignored}, crashed ${t.crashed}")
    }

    @Test fun generatedFilesAgreeWithThePinnedLauncher() {
        val up = UpstreamLauncher(latest = false, dir = tmp.newFolder("gen-pinned"))
        val fuzz = ConfigFuzz(seed = 0x5EED_0002)
        val t = Tally()
        repeat(3_000) { i -> agree(up, fuzz.next(), wallpaper = i % 2 == 0, latest = false, label = "pinned #$i", t = t) }
    }

    // Our own encoder's output reads back to the same doc and the TV sees what effective() says.
    @Test fun encodedDocsReadBackIdenticallyOnBothSides() {
        val up = UpstreamLauncher(latest = true, dir = tmp.newFolder("enc"))
        val r = Random(0x5EED_0003)
        val pkgs = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "in.startv.hotstar", "com.android.tv", "a.b")
        val strings = listOf("", "com.android.tv", "with \"quotes\" and \\ slash", "tab\tinside", "ünï", "\u0001", "emoji 😀", "lone \uD800 surrogate")
        val t = Tally()
        repeat(2_000) { i ->
            fun <T> maybe(v: () -> T): T? = if (r.nextBoolean()) v() else null
            var doc = LauncherConfigDoc(
                dockApps = maybe { List(r.nextInt(6)) { PackageName(pkgs.random(r)) } },
                hiddenApps = maybe { List(r.nextInt(3)) { PackageName(pkgs.random(r)) } },
                liveTvPackage = maybe { strings.random(r) }, liveTvAction = maybe { strings.random(r) },
                sourcePanelService = maybe { strings.random(r) }, sourcePanelAction = maybe { strings.random(r) },
                columns = maybe { r.nextInt(1, 9) }, rowsBelowDock = maybe { r.nextInt(0, 4) }, shamsiDate = maybe { r.nextBoolean() },
                vpnApp = maybe { strings.random(r) }, showHomeSettingsShortcut = maybe { r.nextBoolean() },
                backgroundColor = maybe { listOf("#070A14", "#80FF0000", "navy", "Teal", "#000000").random(r) }, wallpaper = maybe { r.nextBoolean() },
                unknown = if (r.nextInt(4) == 0) mapOf("x_carried" to JsonPrimitive(r.nextInt()), "a_list" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("v")))) else emptyMap(),
            ).let { d -> d.copy(liveTvPackage = d.liveTvPackage?.javaTrim()) }
            if (r.nextBoolean()) doc = doc.copy(inputAction = strings.random(r).javaTrim())
            if (r.nextBoolean()) doc = doc.copy(moduleCards = r.nextBoolean())
            fun trimmed(s: String?) = s?.javaTrim()
            doc = doc.copy(liveTvAction = trimmed(doc.liveTvAction), sourcePanelService = trimmed(doc.sourcePanelService),
                sourcePanelAction = trimmed(doc.sourcePanelAction), vpnApp = trimmed(doc.vpnApp))
            val bytes = LauncherConfigCodec.encode(doc)
            val back = LauncherConfigCodec.decode(bytes)
            assertTrue(back is DecodeResult.Ok && back.doc == doc, "round trip changed the doc #$i\n$doc\n$back\n${bytes.decodeToString()}")
            agree(up, bytes, wallpaper = true, latest = true, label = "encoded #$i", t = t)
            // And the AOSP parser reads our output as strict JSON with the same keys.
            assertEquals(JSONObject(bytes.decodeToString()).length(), (back as DecodeResult.Ok).doc.let { countKeys(it) })
        }
        assertEquals(2_000, t.applied)
    }

    // The dock sheet's rows reach the TV as the sheet shows them. Our doc goes through the real
    // Config.java; the grid is then built the way AppAdapter.reload sorts (dock_apps index first,
    // the rest by label) and GridLayoutManager cuts it (`columns` per row, the first row the dock,
    // rows_below_dock rows in view under it). The rows in view must start with exactly the sheet's
    // rows, also when some dock apps are not on the TV (they are skipped, the later ones move up).
    @Test fun dockRowsShowOnTheTvAsTheSheetShowsThem() {
        val up = UpstreamLauncher(latest = true, dir = tmp.newFolder("dock"))
        val r = Random(0x5EED_0005)
        val pool = List(20) { PackageName("com.example.app$it") }
        repeat(500) { i ->
            val columns = listOf(4, 4, 6, 3).random(r)
            val theme = LauncherConfigDoc(columns = columns.takeIf { it != 4 }, rowsBelowDock = listOf(null, 0, 1).random(r))
            val chosen = pool.shuffled(r).take(r.nextInt(1, 16))
            val onTv = (pool.filter { r.nextInt(5) != 0 } + PackageName("com.example.other")).toSet()
            val doc = DockLayers.applyTo(theme, chosen)
            val u = assertIs<UpstreamLauncher.Outcome.Loaded>(up.load(LauncherConfigCodec.encode(doc), wallpaper = false)).config
            val kept = DockLayers.trim(chosen, columns)
            assertEquals(kept.map { it.value }, u.dockApps, "#$i dock_apps")
            assertEquals(DockLayers.rowsBelowDock(kept.size, columns), u.rowsBelowDock, "#$i rows_below_dock")
            assertEquals(columns, u.columns)
            // AppAdapter.reload's comparator, on the apps the TV has (labels: the package names).
            val grid = onTv.map { it.value }.sortedWith { a, b ->
                val fa = u.dockApps.indexOf(a)
                val fb = u.dockApps.indexOf(b)
                if (fa >= 0 || fb >= 0) { if (fa < 0) 1 else if (fb < 0) -1 else fa.compareTo(fb) } else a.compareTo(b, ignoreCase = true)
            }
            val inView = grid.chunked(u.columns).take(1 + u.rowsBelowDock)
            val sheet = DockLayers.rows(DockLayers.decode(kept, { it in onTv }, columns), columns).map { row -> row.map { it.value } }
            assertTrue(sheet.size <= inView.size, "#$i the TV keeps every sheet row in view")
            // With every app on the TV the rows match exactly; with some missing, the TV fills the
            // freed slots after the sheet's apps with the next apps by name.
            sheet.forEachIndexed { k, row -> assertEquals(row, inView[k].take(row.size), "#$i row ${k + 1}") }
            if (kept.all { it in onTv }) assertEquals(sheet.dropLast(1), inView.take(sheet.size - 1), "#$i full rows")
        }
    }

    private fun countKeys(d: LauncherConfigDoc) = listOfNotNull(d.dockApps, d.hiddenApps, d.liveTvPackage, d.liveTvAction, d.sourcePanelService,
        d.sourcePanelAction, d.columns, d.rowsBelowDock, d.shamsiDate, d.vpnApp, d.showHomeSettingsShortcut, d.backgroundColor, d.wallpaper,
        d.inputAction, d.moduleCards).size + d.unknown.size

    // The lenient reader is a port of JSONTokener; check it value for value, including the
    // Integer, Long and Double split, null slots, duplicate keys and what is refused.
    @Test fun androidJsonPortMatchesAospOrgJson() {
        val fuzz = ConfigFuzz(seed = 0x5EED_0004)
        var accepted = 0
        repeat(10_000) { i ->
            val text = String(fuzz.next(), Charsets.UTF_8)
            val theirs = runCatching { toAValue(JSONObject(text)) }
            val ours = runCatching { AndroidJsonReader(text).readObjectDocument() }
            assertEquals(theirs.isSuccess, ours.isSuccess, "accept differs for #$i ${text.take(300)}: ${theirs.exceptionOrNull()} vs ${ours.exceptionOrNull()}")
            if (ours.isSuccess) {
                accepted++
                assertEquals(theirs.getOrThrow().dump(), ours.getOrThrow().dump(), "value differs for #$i ${text.take(300)}")
            }
        }
        assertTrue(accepted > 5_000)
    }

    private fun toAValue(v: Any?): AValue = when (v) {
        null -> AValue.Absent
        JSONObject.NULL -> AValue.Null
        is String -> AValue.Str(v)
        is Int -> AValue.IntV(v)
        is Long -> AValue.LongV(v)
        is Double -> AValue.Dbl(v)
        is Boolean -> AValue.Bool(v)
        is JSONObject -> AValue.Obj(LinkedHashMap<String, AValue>().also { m -> v.keys().forEach { k -> m[k] = toAValue(v.opt(k)) } })
        is JSONArray -> AValue.Arr((0 until v.length()).map { toAValue(v.opt(it)) })
        else -> error("unexpected ${v::class}")
    }

    private fun AValue.dump(): String = when (this) {
        is AValue.Obj -> map.entries.joinToString(",", "{", "}") { "${it.key}=${it.value.dump()}" }
        is AValue.Arr -> items.joinToString(",", "[", "]") { it.dump() }
        is AValue.Dbl -> "D(${java.lang.Double.doubleToRawLongBits(v)})"
        else -> toString()
    }
}
