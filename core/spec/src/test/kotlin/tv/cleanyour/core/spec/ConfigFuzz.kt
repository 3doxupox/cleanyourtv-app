package tv.cleanyour.core.spec

import kotlin.random.Random

// Generates launcher config files the way people and broken tools write them: strict JSON and
// the lenient forms Android's org.json also reads, every key with right and wrong types, values
// in and out of range, padded strings, bad colours, BOMs, comments, duplicates, truncation,
// trailing garbage, invalid UTF-8 and sizes around the 64 KB cap. Deterministic per seed.
class ConfigFuzz(seed: Long) {
    private val r = Random(seed)

    private val listKeys = listOf("dock_apps", "hidden_apps")
    private val stringKeys = listOf("live_tv_package", "live_tv_action", "source_panel_service", "source_panel_action", "vpn_app", "input_action")
    private val intKeys = listOf("columns", "rows_below_dock")
    private val boolKeys = listOf("shamsi_date", "show_home_settings_shortcut", "module_cards", "wallpaper")
    private val knownKeys = listKeys + stringKeys + intKeys + boolKeys + "background_color"
    private val packages = listOf(
        "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar",
        "com.mediatek.wwtv.tvcenter", "com.android.tv", "a.b", "com.x_y.z9", "android",
    )

    private fun pick(vararg xs: String) = xs[r.nextInt(xs.size)]
    private fun <T> pickL(xs: List<T>) = xs[r.nextInt(xs.size)]

    fun next(): ByteArray {
        val roll = r.nextInt(100)
        return when {
            roll < 52 -> doc(lenient = r.nextInt(4) == 0).encodeToByteArray()
            roll < 62 -> doc(lenient = true).encodeToByteArray()
            roll < 66 -> pick("[1, 2]", "\"just a string\"", "42", "true", "null", "", "   \n", "{", "}", "{}", "{} {}", "nope", "[{\"columns\": 6}]")
                .encodeToByteArray()
            roll < 72 -> doc(false).let { it.substring(0, r.nextInt(it.length + 1)) }.encodeToByteArray()
            roll < 76 -> withBadBytes(doc(false).encodeToByteArray())
            roll < 82 -> ("﻿".repeat(1 + r.nextInt(3)) + doc(r.nextBoolean())).encodeToByteArray()
            roll < 87 -> nearCap()
            roll < 92 -> (doc(false) + pick(" trailing", "}", ",{\"columns\": 2}", "\u0000", " // done", "]]]")).encodeToByteArray()
            roll < 96 -> duplicates().encodeToByteArray()
            else -> nested().encodeToByteArray()
        }
    }

    private fun withBadBytes(b: ByteArray): ByteArray {
        if (b.isEmpty()) return byteArrayOf(0xC3.toByte())
        val out = b.toMutableList()
        repeat(1 + r.nextInt(3)) {
            val bad = pickL(listOf(byteArrayOf(0xFF.toByte()), byteArrayOf(0xC3.toByte()), byteArrayOf(0xE2.toByte(), 0x82.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())))
            out.addAll(r.nextInt(out.size + 1), bad.toList())
        }
        return out.toByteArray()
    }

    private fun nearCap(): ByteArray {
        val base = doc(false)
        val target = 64 * 1024 + r.nextInt(-8, 9) + if (r.nextInt(5) == 0) r.nextInt(-3000, 6000) else 0
        val pad = (target - base.encodeToByteArray().size).coerceAtLeast(0)
        val text = if (r.nextBoolean()) base.dropLast(1) + ", \"pad\": \"" + "x".repeat((pad - 11).coerceAtLeast(0)) + "\"}"
        else " ".repeat(pad) + base
        return text.encodeToByteArray()
    }

    private fun duplicates(): String {
        val k = pickL(knownKeys)
        return "{" + (0 until 2 + r.nextInt(3)).joinToString(", ") { "\"$k\": ${value(k, false)}" } + "}"
    }

    private fun nested(): String {
        val depth = 1 + r.nextInt(40)
        val open = (0 until depth).joinToString("") { if (r.nextBoolean()) "[" else "{\"n\": " }
        // Close in reverse; objects close with }, arrays with ].
        val closes = open.split(Regex("(?<=[\\[ ])")).filter { it.isNotEmpty() }.reversed().joinToString("") { if (it == "[") "]" else "}" }
        return "{\"columns\": 5, \"deep\": $open 1 $closes}"
    }

    fun doc(lenient: Boolean): String {
        val n = r.nextInt(0, 12)
        val entries = (0 until n).map {
            val key = when (r.nextInt(10)) {
                in 0..6 -> pickL(knownKeys)
                7 -> pick("Columns", "dock_apps ", " columns", "columns\u0000", "backgroundColor", "dock-apps")
                else -> "x_" + r.nextInt(1000)
            }
            key to value(key.trim(), lenient)
        }
        val sep = { if (lenient && r.nextInt(4) == 0) ";" else "," }
        val colon = { if (lenient && r.nextInt(4) == 0) pick("=", "=>", " = ") else ":" }
        val ws = { if (lenient && r.nextInt(5) == 0) pick(" /* c */ ", "\n// line\n", "\n# hash\n", "\t", "\r\n") else pick("", " ", "\n  ") }
        val body = entries.joinToString(sep()) { (k, v) -> ws() + name(k, lenient) + ws() + colon() + ws() + v + ws() }
        return "{" + body + (if (r.nextInt(20) == 0) "," else "") + "}"
    }

    private fun name(k: String, lenient: Boolean): String = when {
        lenient && r.nextInt(3) == 0 && k.matches(Regex("[a-z_]+")) -> k
        lenient && r.nextInt(3) == 0 -> "'" + k.replace("'", "\\'") + "'"
        else -> str(k)
    }

    private fun str(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun lenientStr(s: String, lenient: Boolean): String = when {
        lenient && r.nextInt(3) == 0 && s.matches(Regex("[A-Za-z][A-Za-z0-9_.]*")) -> s
        lenient && r.nextInt(3) == 0 -> "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
        else -> str(s)
    }

    private fun anyValue(lenient: Boolean): String = when (r.nextInt(12)) {
        0 -> str(pickL(packages))
        1 -> number()
        2 -> pick("true", "false", "TRUE", "False", "null", "NULL")
        3 -> "[" + (0 until r.nextInt(4)).joinToString(", ") { str(pickL(packages)) } + "]"
        4 -> "{\"a\": 1}"
        5 -> "[]"
        6 -> "{}"
        7 -> str("")
        8 -> if (lenient) pick("bare", "0x1G", "-0x5", "4px") else str("bare")
        9 -> str(" " + pickL(packages) + "\t")
        10 -> pick("[1, 2, 3]", "[null]", "[[\"a.b\"]]")
        else -> number()
    }

    private fun number(): String = pick(
        "0", "1", "4", "6", "8", "9", "-1", "3", "12", "4.0", "4.9", "-0.0", "1e1", "8E0", "0x4", "0X6", "04", "010", "08",
        "4294967300", "-4294967292", "9223372036854775807", "9223372036854775808", "1e400", "-1e400", "1e-400",
        "NaN", "Infinity", "-Infinity", "2147483648", "0.5", ".5", "5.", "1f", "2d", "0x1p2", "+3", "00", "0x",
    )

    private fun value(key: String, lenient: Boolean): String {
        if (r.nextInt(4) == 0) return anyValue(lenient)
        return when (key) {
            in listKeys -> {
                val items = (0 until r.nextInt(0, 6)).map {
                    when (r.nextInt(10)) {
                        in 0..4 -> lenientStr(pickL(packages), lenient)
                        5 -> str(pick("  com.netflix.ninja  ", "\tin.startv.hotstar\n", "", "   ", "foo bar", "com.", ".x", "Com.Example.App", "ñ.ü", "a".repeat(70) + ".b"))
                        6 -> pick("1", "null", "true", "{}", "[\"a.b\"]")
                        7 -> if (lenient) "" else str("a.b")   // empty slot: [,"a.b"] holds a Java null
                        else -> str(pickL(packages))
                    }
                }
                "[" + items.joinToString(if (lenient && r.nextBoolean()) ";" else ", ") + (if (lenient && r.nextInt(5) == 0) "," else "") + "]"
            }
            in stringKeys -> lenientStr(pick("com.android.tv", "  padded.value  ", "", " ", "\u0001ctl", "a\\b", "quote\"in", "line\nbreak", "ünïcode", "com.xgimi.action.hdmiPlayer", " nbsp "), lenient)
            in intKeys -> number()
            in boolKeys -> pick("true", "false", "true", "false", "TRUE", "\"true\"", "1", "0", "null", "\"false\"")
            "background_color" -> lenientStr(
                pick(
                    "#070A14", "#000000", "#ffffff", "#80FF0000", "#FFF", "#", "#12345", "#1234567", "#-12345", "#+12345", "#0x1234",
                    "#-0000001", "#GGGGGG", "navy", "NAVY", "Teal", "darkgrey", "rebeccapurple", "", " ", "  #00ff00  ", "\t", "rgb(1,2,3)",
                    "#ffffffffff", "#7fffffff", "#ffffffff",
                ),
                lenient,
            ).let { if (r.nextInt(8) == 0) pick("0", "null", "true", "[\"#000000\"]") else it }
            else -> anyValue(lenient)
        }
    }
}
