package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.cleanyour.core.model.PackageName

// The body of LauncherConfigCodec: the launcher's Config.java read path, key by key, as of
// upstream main 1bbf0c9 (which adds input_action and module_cards to the pinned 7205769, modelled
// as fields by RFC-W4-1; the pinned launcher ignores both). Decoding answers "what will the TV
// show for these bytes"; a key the launcher would ignore is left null in the doc with a warning,
// so effective() of the decoded doc equals what Config.load computes. ConfigDifferentialTest
// holds this to the real Config.java on the theme fixtures and 10,000 generated files.
internal object ConfigCodecImpl {
    const val MAX_BYTES = 64 * 1024

    // Config.java's keys in the order it reads them; encode writes this order.
    val KEY_ORDER: List<String> = listOf(
        "dock_apps", "hidden_apps", "live_tv_package", "live_tv_action", "source_panel_service",
        "source_panel_action", "input_action", "columns", "rows_below_dock", "shamsi_date",
        "module_cards", "vpn_app", "show_home_settings_shortcut", "background_color", "wallpaper",
    )
    // Keys with a LauncherConfigDoc field: all of Config.java's keys.
    val MODELLED: Set<String> = KEY_ORDER.toSet()

    // res/values/config.xml of the launcher (identical at 7205769 and 1bbf0c9 for the keys both
    // have; input_action and module_cards are 1bbf0c9's values).
    val BUILT_IN = EffectiveConfig(
        dockApps = listOf("com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv", "in.startv.hotstar"),
        hiddenApps = listOf("com.mediatek.wwtv.tvcenter", "com.mitv.gallery"),
        liveTvPackage = "com.mediatek.wwtv.tvcenter", liveTvAction = "", sourcePanelService = "", sourcePanelAction = "",
        columns = 4, rowsBelowDock = 0, shamsiDate = false, vpnApp = "", showHomeSettingsShortcut = false,
        backgroundColorArgb = 0xFF000000.toInt(), wallpaper = true, inputAction = "", moduleCards = true,
    )

    fun isPackageName(s: String): Boolean = runCatching { PackageName(s) }.isSuccess

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size > MAX_BYTES) return DecodeResult.Rejected("larger than 64 KB: the launcher ignores the whole file")
        var text = String(bytes, Charsets.UTF_8)
        if (text.startsWith('﻿')) text = text.substring(1)   // Config.read strips one BOM, JSONTokener another
        val o = try {
            AndroidJsonReader(text).readObjectDocument().map
        } catch (e: AndroidJsonTooDeep) {
            return DecodeResult.Rejected("${e.message}: refused here; the launcher would recurse that deep")
        } catch (e: AndroidJsonException) {
            return DecodeResult.Rejected("not readable by the launcher, so it ignores the whole file (${e.message})")
        }
        val w = mutableListOf<String>()

        fun stringList(key: String): List<PackageName>? {
            val v = o[key] ?: return null
            if (v !is AValue.Arr) { w += "$key is not a list; the launcher keeps its default"; return null }
            val out = ArrayList<PackageName>()
            v.items.forEachIndexed { i, item ->
                if (item is AValue.Str) {
                    val t = item.v.javaTrim()
                    when {
                        t.isEmpty() -> w += "$key[$i] is blank; the launcher skips it"
                        isPackageName(t) -> out += PackageName(t)
                        else -> w += "$key[$i] is not a package name; it can never match an app, so it is dropped here"
                    }
                } else {
                    w += "$key[$i] is not a string; the launcher skips it"
                }
            }
            return out
        }
        fun string(key: String): String? {
            val v = o[key] ?: return null
            if (v is AValue.Str) return v.v.javaTrim()
            w += "$key is not a string; the launcher keeps its default"
            return null
        }
        fun bool(key: String): Boolean? {
            val v = o[key] ?: return null
            if (v is AValue.Bool) return v.v
            w += "$key is not true or false; the launcher keeps its default"
            return null
        }
        fun integer(key: String, min: Int, max: Int): Int? {
            val v = o[key] ?: return null
            val i = when (v) {
                is AValue.IntV -> v.v
                is AValue.LongV -> v.v.toInt()          // Number.intValue() keeps the low 32 bits
                is AValue.Dbl -> v.v.toInt()            // and truncates a double towards zero
                else -> { w += "$key is not a number; the launcher keeps its default"; return null }
            }
            if (i < min || i > max) { w += "$key is $i, outside $min to $max; the launcher keeps its default"; return null }
            return i
        }

        val dock = stringList("dock_apps")
        val hidden = stringList("hidden_apps")
        val liveTv = string("live_tv_package")
        val liveTvAction = string("live_tv_action")
        val panelService = string("source_panel_service")
        val panelAction = string("source_panel_action")
        val inputAction = string("input_action")
        val columns = integer("columns", 1, 8)
        val rows = integer("rows_below_dock", 0, 3)
        val shamsi = bool("shamsi_date")
        val moduleCards = bool("module_cards")
        val vpn = string("vpn_app")
        val homeSettings = bool("show_home_settings_shortcut")
        var background: String? = null
        o["background_color"]?.let { v ->
            if (v !is AValue.Str) { w += "background_color is not a string; the launcher keeps black"; return@let }
            val t = v.v.javaTrim()
            when (AndroidColor.parseColor(t)) {
                is AndroidColor.Parse.Ok -> background = t
                AndroidColor.Parse.Invalid -> w += "background_color is not a colour the launcher knows; it keeps black"
                AndroidColor.Parse.Crash -> return DecodeResult.Rejected("background_color is empty: the launcher would crash when Home starts")
            }
        }
        var wallpaper: Boolean? = null
        o["wallpaper"]?.let { v ->
            if (v is AValue.Bool) wallpaper = v.v
            else w += "wallpaper is not true or false; the launcher shows wallpaper.jpg if it is there"
        }

        val unknown = LinkedHashMap<String, JsonElement>()
        for ((k, v) in o) {
            if (k in MODELLED) continue
            unknown[k] = v.toJsonElement()
            w += "unknown key $k; the launcher ignores it (carried as is)"
        }
        val doc = LauncherConfigDoc(
            dockApps = dock, hiddenApps = hidden, liveTvPackage = liveTv, liveTvAction = liveTvAction,
            sourcePanelService = panelService, sourcePanelAction = panelAction, columns = columns, rowsBelowDock = rows,
            shamsiDate = shamsi, vpnApp = vpn, showHomeSettingsShortcut = homeSettings, backgroundColor = background,
            wallpaper = wallpaper, unknown = unknown, inputAction = inputAction, moduleCards = moduleCards,
        )
        return DecodeResult.Ok(doc, w)
    }

    // What Config.load would compute from this doc. Values the launcher would ignore (out of
    // range, a colour it cannot parse) fall back to `defaults` here too, so a doc built in code
    // previews the way the TV will show it.
    fun effective(doc: LauncherConfigDoc?, defaults: EffectiveConfig): EffectiveConfig {
        if (doc == null) return defaults.copy(columns = maxOf(1, defaults.columns), rowsBelowDock = maxOf(0, defaults.rowsBelowDock))
        val bg = doc.backgroundColor?.let { (AndroidColor.parseColor(it.javaTrim()) as? AndroidColor.Parse.Ok)?.argb?.or(0xFF000000.toInt()) }
        return EffectiveConfig(
            dockApps = doc.dockApps?.map { it.value } ?: defaults.dockApps,
            hiddenApps = doc.hiddenApps?.map { it.value } ?: defaults.hiddenApps,
            liveTvPackage = doc.liveTvPackage?.javaTrim() ?: defaults.liveTvPackage,
            liveTvAction = doc.liveTvAction?.javaTrim() ?: defaults.liveTvAction,
            sourcePanelService = doc.sourcePanelService?.javaTrim() ?: defaults.sourcePanelService,
            sourcePanelAction = doc.sourcePanelAction?.javaTrim() ?: defaults.sourcePanelAction,
            columns = maxOf(1, doc.columns?.takeIf { it in 1..8 } ?: defaults.columns),
            rowsBelowDock = maxOf(0, doc.rowsBelowDock?.takeIf { it in 0..3 } ?: defaults.rowsBelowDock),
            shamsiDate = doc.shamsiDate ?: defaults.shamsiDate,
            vpnApp = doc.vpnApp?.javaTrim() ?: defaults.vpnApp,
            showHomeSettingsShortcut = doc.showHomeSettingsShortcut ?: defaults.showHomeSettingsShortcut,
            backgroundColorArgb = bg ?: defaults.backgroundColorArgb,
            wallpaper = doc.wallpaper ?: defaults.wallpaper,
            inputAction = doc.inputAction?.javaTrim() ?: defaults.inputAction,
            moduleCards = doc.moduleCards ?: defaults.moduleCards,
        )
    }

    // Canonical form: Config.java's key order, two-space indent, lists on one line, UTF-8 without
    // BOM, a final newline. Refuses anything the launcher would read differently from the doc.
    fun encode(doc: LauncherConfigDoc): ByteArray {
        for (k in doc.unknown.keys) require(k !in MODELLED) { "unknown carries the modelled key $k" }
        fun canonicalString(key: String, v: String?) { if (v != null) require(v == v.javaTrim()) { "$key has leading or trailing whitespace the launcher would trim" } }
        canonicalString("live_tv_package", doc.liveTvPackage)
        canonicalString("live_tv_action", doc.liveTvAction)
        canonicalString("source_panel_service", doc.sourcePanelService)
        canonicalString("source_panel_action", doc.sourcePanelAction)
        canonicalString("input_action", doc.inputAction)
        canonicalString("vpn_app", doc.vpnApp)
        doc.columns?.let { require(it in 1..8) { "columns $it is outside 1 to 8" } }
        doc.rowsBelowDock?.let { require(it in 0..3) { "rows_below_dock $it is outside 0 to 3" } }
        doc.backgroundColor?.let {
            require(it == it.javaTrim() && AndroidColor.parseColor(it) is AndroidColor.Parse.Ok) { "background_color '$it' is not a colour the launcher reads" }
        }

        val entries = ArrayList<Pair<String, String>>()
        fun list(v: List<PackageName>) = v.joinToString(", ", "[", "]") { quote(it.value) }
        for (key in KEY_ORDER) {
            val value: String? = when (key) {
                "dock_apps" -> doc.dockApps?.let(::list)
                "hidden_apps" -> doc.hiddenApps?.let(::list)
                "live_tv_package" -> doc.liveTvPackage?.let(::quote)
                "live_tv_action" -> doc.liveTvAction?.let(::quote)
                "source_panel_service" -> doc.sourcePanelService?.let(::quote)
                "source_panel_action" -> doc.sourcePanelAction?.let(::quote)
                "input_action" -> doc.inputAction?.let(::quote)
                "columns" -> doc.columns?.toString()
                "rows_below_dock" -> doc.rowsBelowDock?.toString()
                "shamsi_date" -> doc.shamsiDate?.toString()
                "module_cards" -> doc.moduleCards?.toString()
                "vpn_app" -> doc.vpnApp?.let(::quote)
                "show_home_settings_shortcut" -> doc.showHomeSettingsShortcut?.toString()
                "background_color" -> doc.backgroundColor?.let(::quote)
                "wallpaper" -> doc.wallpaper?.toString()
                else -> error("no field for $key")
            }
            if (value != null) entries += key to value
        }
        for (k in doc.unknown.keys.filter { it !in KEY_ORDER }.sorted()) entries += k to element(doc.unknown.getValue(k))
        val text = if (entries.isEmpty()) "{}\n"
        else entries.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> "  ${quote(k)}: $v" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "encoded config is ${bytes.size} bytes, over the launcher's 64 KB cap" }
        return bytes
    }

    private val NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")

    private fun element(e: JsonElement): String = when (e) {
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            e.isString -> quote(e.content)
            e.content == "true" || e.content == "false" -> e.content
            NUMBER.matches(e.content) -> e.content
            else -> throw IllegalArgumentException("carried value ${e.content} is not a finite JSON number, boolean or null")
        }
        is JsonArray -> e.joinToString(", ", "[", "]") { element(it) }
        is JsonObject -> e.entries.joinToString(", ", "{", "}") { (k, v) -> "${quote(k)}: ${element(v)}" }
    }

    // JSON string syntax JSONTokener reads back to the same chars: quote, backslash and control
    // chars escaped, and lone surrogates escaped so the UTF-8 encoder cannot replace them.
    private fun hex4(c: Char): String = "\\u" + c.code.toString(16).padStart(4, '0')

    fun quote(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' -> b.append(hex4(c))
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { b.append(c).append(s[i + 1]); i++ }
                c.isSurrogate() -> b.append(hex4(c))
                else -> b.append(c)
            }
            i++
        }
        return b.append('"').toString()
    }
}
