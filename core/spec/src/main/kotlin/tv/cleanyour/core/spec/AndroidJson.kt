package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The launcher reads config.json with Android's org.json (libcore, AOSP). That parser is lenient:
// comments (slash-star, slash-slash, hash), single quotes, unquoted names and values, '=' and '=>'
// as name separators, ';' as a value separator, hex and octal integers, case-insensitive literals,
// an optional BOM, last-duplicate-wins, nulls for empty array slots and anything after the
// top-level object ignored. This is a line-by-line port of the parsing half of JSONTokener plus
// the checks JSONObject.put and JSONArray.put apply, so the phone reads a file exactly as the TV
// does. Source: platform/libcore json/src/main/java/org/json, pie-release (identical in main apart
// from annotations), Apache-2.0. The differential test runs the AOSP classes against this port.
internal sealed interface AValue {
    data class Str(val v: String) : AValue
    data class IntV(val v: Int) : AValue
    data class LongV(val v: Long) : AValue
    data class Dbl(val v: Double) : AValue
    data class Bool(val v: Boolean) : AValue
    data object Null : AValue                 // JSONObject.NULL
    data object Absent : AValue               // the Java null an empty array slot holds
    class Obj(val map: LinkedHashMap<String, AValue>) : AValue
    class Arr(val items: List<AValue>) : AValue
}

internal open class AndroidJsonException(message: String) : Exception(message)

// Raised instead of recursing further; the TV would recurse until its stack runs out.
internal class AndroidJsonTooDeep(depth: Int) : AndroidJsonException("nested deeper than $depth levels")

internal class AndroidJsonReader(input: String, private val maxDepth: Int = MAX_DEPTH) {
    // JSONTokener's constructor consumes one optional byte order mark.
    private val s: String = if (input.startsWith('﻿')) input.substring(1) else input
    private var pos = 0
    private var depth = 0

    // new JSONObject(String): the first value must be an object; the rest of the text is ignored.
    fun readObjectDocument(): AValue.Obj {
        val v = nextValue()
        return v as? AValue.Obj ?: throw AndroidJsonException("top-level value is not an object")
    }

    private fun err(message: String) = AndroidJsonException("$message at character $pos")

    fun nextValue(): AValue {
        val c = nextCleanInternal()
        return when (c) {
            -1 -> throw err("End of input")
            '{'.code -> readObject()
            '['.code -> readArray()
            '\''.code, '"'.code -> AValue.Str(nextString(c.toChar()))
            else -> { pos--; readLiteral() }
        }
    }

    private fun nextCleanInternal(): Int {
        while (pos < s.length) {
            val c = s[pos++]
            when (c) {
                '\t', ' ', '\n', '\r' -> continue
                '/' -> {
                    if (pos == s.length) return c.code
                    when (s[pos]) {
                        '*' -> {
                            pos++
                            val end = s.indexOf("*/", pos)
                            if (end == -1) throw err("Unterminated comment")
                            pos = end + 2
                        }
                        '/' -> { pos++; skipToEndOfLine() }
                        else -> return c.code
                    }
                }
                '#' -> skipToEndOfLine()
                else -> return c.code
            }
        }
        return -1
    }

    private fun skipToEndOfLine() {
        while (pos < s.length) {
            val c = s[pos]
            if (c == '\r' || c == '\n') { pos++; return }
            pos++
        }
    }

    private fun nextString(quote: Char): String {
        var builder: StringBuilder? = null
        var start = pos
        while (pos < s.length) {
            val c = s[pos++]
            if (c == quote) {
                return if (builder == null) s.substring(start, pos - 1)
                else builder.append(s, start, pos - 1).toString()
            }
            if (c == '\\') {
                if (pos == s.length) throw err("Unterminated escape sequence")
                if (builder == null) builder = StringBuilder()
                builder.append(s, start, pos - 1)
                builder.append(readEscapeCharacter())
                start = pos
            }
        }
        throw err("Unterminated string")
    }

    private fun readEscapeCharacter(): Char {
        val escaped = s[pos++]
        return when (escaped) {
            'u' -> {
                if (pos + 4 > s.length) throw err("Unterminated escape sequence")
                val hex = s.substring(pos, pos + 4)
                pos += 4
                try {
                    java.lang.Integer.parseInt(hex, 16).toChar()
                } catch (_: NumberFormatException) {
                    throw err("Invalid escape sequence: $hex")
                }
            }
            't' -> '\t'
            'b' -> '\b'
            'n' -> '\n'
            'r' -> '\r'
            'f' -> '\u000C'
            else -> escaped
        }
    }

    private fun readLiteral(): AValue {
        val literal = nextToInternal("{}[]/\\:,=;# \t\u000C")
        if (literal.isEmpty()) throw err("Expected literal value")
        if (literal.equals("null", ignoreCase = true)) return AValue.Null
        if (literal.equals("true", ignoreCase = true)) return AValue.Bool(true)
        if (literal.equals("false", ignoreCase = true)) return AValue.Bool(false)

        if (literal.indexOf('.') == -1) {
            var base = 10
            var number = literal
            if (number.startsWith("0x") || number.startsWith("0X")) {
                number = number.substring(2); base = 16
            } else if (number.startsWith("0") && number.length > 1) {
                number = number.substring(1); base = 8
            }
            try {
                val longValue = java.lang.Long.parseLong(number, base)
                return if (longValue <= Int.MAX_VALUE && longValue >= Int.MIN_VALUE) AValue.IntV(longValue.toInt())
                else AValue.LongV(longValue)
            } catch (_: NumberFormatException) {
                // Not an integer in that base, so fall through to floating point.
            }
        }
        try {
            return AValue.Dbl(java.lang.Double.valueOf(literal))
        } catch (_: NumberFormatException) {
            // Not a number either.
        }
        return AValue.Str(literal)
    }

    private fun nextToInternal(excluded: String): String {
        val start = pos
        while (pos < s.length) {
            val c = s[pos]
            if (c == '\r' || c == '\n' || excluded.indexOf(c) != -1) return s.substring(start, pos)
            pos++
        }
        return s.substring(start)
    }

    private fun enter() { if (++depth > maxDepth) throw AndroidJsonTooDeep(maxDepth) }

    private fun readObject(): AValue.Obj {
        enter()
        val result = LinkedHashMap<String, AValue>()
        val first = nextCleanInternal()
        if (first == '}'.code) { depth--; return AValue.Obj(result) }
        if (first != -1) pos--
        while (true) {
            val name = nextValue()
            if (name !is AValue.Str) throw err("Names must be strings")
            val separator = nextCleanInternal()
            if (separator != ':'.code && separator != '='.code) throw err("Expected ':' after ${name.v}")
            if (pos < s.length && s[pos] == '>') pos++
            val value = nextValue()
            // JSONObject.put(String, Object): every Number goes through JSON.checkDouble.
            if (value is AValue.Dbl && (value.v.isNaN() || value.v.isInfinite())) throw err("Forbidden numeric value: ${value.v}")
            result[name.v] = value
            when (nextCleanInternal()) {
                '}'.code -> { depth--; return AValue.Obj(result) }
                ';'.code, ','.code -> continue
                else -> throw err("Unterminated object")
            }
        }
    }

    private fun readArray(): AValue.Arr {
        enter()
        val result = ArrayList<AValue>()
        var hasTrailingSeparator = false
        while (true) {
            when (nextCleanInternal()) {
                -1 -> throw err("Unterminated array")
                ']'.code -> {
                    if (hasTrailingSeparator) result.add(AValue.Absent)
                    depth--
                    return AValue.Arr(result)
                }
                ','.code, ';'.code -> {
                    result.add(AValue.Absent)
                    hasTrailingSeparator = true
                    continue
                }
                else -> pos--
            }
            // JSONArray.put(Object) does not check numbers, so NaN and infinities are allowed here.
            result.add(nextValue())
            when (nextCleanInternal()) {
                ']'.code -> { depth--; return AValue.Arr(result) }
                ','.code, ';'.code -> { hasTrailingSeparator = true; continue }
                else -> throw err("Unterminated array")
            }
        }
    }

    companion object {
        const val MAX_DEPTH = 512
    }
}

// Unknown keys are carried in LauncherConfigDoc.unknown as kotlinx JsonElements. Integers keep
// their decimal spelling, so writing them back gives the TV the same Number type; a Java null
// array slot becomes JSON null, which the launcher reads the same way for every key it knows.
internal fun AValue.toJsonElement(): JsonElement = when (this) {
    is AValue.Str -> JsonPrimitive(v)
    is AValue.IntV -> JsonPrimitive(v)
    is AValue.LongV -> JsonPrimitive(v)
    is AValue.Dbl -> JsonPrimitive(v)
    is AValue.Bool -> JsonPrimitive(v)
    AValue.Null, AValue.Absent -> JsonNull
    is AValue.Obj -> JsonObject(map.mapValues { it.value.toJsonElement() })
    is AValue.Arr -> JsonArray(items.map { it.toJsonElement() })
}

// java.lang.String.trim(): strips every char up to U+0020, unlike Kotlin's whitespace trim.
internal fun String.javaTrim(): String = trim { it <= ' ' }
