package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

enum class GlowStyle { RADIAL, HORIZON }

// The 2.7 look keys as launcher 2.7's Config.load reads them from a config.json: the carried
// keys in doc.unknown, with config.xml 2.7's defaults, a wrong type or out of range value giving
// the default, an unknown glow_style giving RADIAL (Config.java bool, integer, string, glowStyle).
data class LookConfig(
    val ambientGlow: Boolean,
    val glowStyle: GlowStyle,
    val appGlow: Boolean,
    val dockGlass: Boolean,
    val dockOpacity: Int,
    val dockEvenPadding: Boolean,
    val alwaysShowNames: Boolean,
    val iconOnlyButtons: Boolean,
    val iconOnlyInputs: Boolean,
    val topMenu: Boolean,
    val wifiStatusColor: Boolean,
    val focusOvershoot: Boolean,
    val cinematicDots: Boolean,
    val cinematicWhiteFocus: Boolean,
    val cinematicArt: Int,
    val cinematicScrim: Int,
)

// The reader of the carried keys (ConfigCodecImpl.CARRIED_27), for the preview and the tests.
// A value in `unknown` is what the codec read (Int, Long or Double as JSONTokener made it, a
// string, a boolean, null, a list or an object) or what code put there; each reader takes it the
// way Config.java's helper of the same name does. Only `unknown` is read: the modelled keys have
// their LauncherConfigDoc fields and LauncherConfigCodec.effective. Not modelled: the dock band
// opacity chosen on the TV (Launcher settings), which wins until the next push.
object LauncherKeys {
    // res/values/config.xml at 3e34f40 (launcher 2.7), checked by LauncherKeysTest, which parses it.
    // dock_opacity is not in config.xml: Config.load reads it from config.json alone, default 100.
    val DEFAULTS_27: LookConfig = LookConfig(
        ambientGlow = true, glowStyle = GlowStyle.RADIAL, appGlow = false, dockGlass = false, dockOpacity = 100,
        dockEvenPadding = false, alwaysShowNames = false, iconOnlyButtons = false, iconOnlyInputs = false, topMenu = false,
        wifiStatusColor = false, focusOvershoot = true, cinematicDots = true, cinematicWhiteFocus = false,
        cinematicArt = 50, cinematicScrim = 100,
    )

    fun look(doc: LauncherConfigDoc?): LookConfig {
        val d = DEFAULTS_27
        return LookConfig(
            ambientGlow = bool(doc, "ambient_glow", d.ambientGlow),
            glowStyle = glowStyle(string(doc, "glow_style", if (d.glowStyle == GlowStyle.HORIZON) "horizon" else "radial")),
            appGlow = bool(doc, "app_glow", d.appGlow),
            dockGlass = bool(doc, "dock_glass", d.dockGlass),
            dockOpacity = int(doc, "dock_opacity", 30, 100, d.dockOpacity),
            dockEvenPadding = bool(doc, "dock_even_padding", d.dockEvenPadding),
            alwaysShowNames = bool(doc, "always_show_names", d.alwaysShowNames),
            iconOnlyButtons = bool(doc, "icon_only_buttons", d.iconOnlyButtons),
            iconOnlyInputs = bool(doc, "icon_only_inputs", d.iconOnlyInputs),
            topMenu = bool(doc, "top_menu", d.topMenu),
            wifiStatusColor = bool(doc, "wifi_status_color", d.wifiStatusColor),
            focusOvershoot = bool(doc, "focus_overshoot", d.focusOvershoot),
            cinematicDots = bool(doc, "cinematic_dots", d.cinematicDots),
            cinematicWhiteFocus = bool(doc, "cinematic_white_focus", d.cinematicWhiteFocus),
            cinematicArt = int(doc, "cinematic_art", 0, 100, d.cinematicArt),
            cinematicScrim = int(doc, "cinematic_scrim", 0, 100, d.cinematicScrim),
        )
    }

    // Config.glowStyle: "horizon" in any case, once trimmed, else radial.
    fun glowStyle(value: String?): GlowStyle =
        if (value != null && value.javaTrim().equals("horizon", ignoreCase = true)) GlowStyle.HORIZON else GlowStyle.RADIAL

    private fun value(doc: LauncherConfigDoc?, key: String): JsonPrimitive? = doc?.unknown?.get(key) as? JsonPrimitive

    // Config.bool: a JSON true or false, else the default (a string "true" is not a boolean).
    fun bool(doc: LauncherConfigDoc?, key: String, default: Boolean): Boolean {
        val v = value(doc, key) ?: return default
        if (v.isString) return default
        return when (v.content) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }

    // Config.integer: a number's intValue() (a long keeps its low 32 bits, a double is cut towards
    // zero), the default outside min to max or for anything else.
    fun int(doc: LauncherConfigDoc?, key: String, min: Int, max: Int, default: Int): Int {
        val i = intValue(doc?.unknown?.get(key)) ?: return default
        return if (i < min || i > max) default else i
    }

    // Config.string: a string, trimmed as Java trims, else the default.
    fun string(doc: LauncherConfigDoc?, key: String, default: String): String {
        val v = value(doc, key) ?: return default
        return if (v.isString) v.content.javaTrim() else default
    }

    // Number.intValue() of a JSON number as the codec or code put it in `unknown`; null for what
    // is not a number. An integer text is a Java Integer or Long (both give the low 32 bits); any
    // other number text (a decimal point, an exponent, Infinity or NaN for a double JSONTokener
    // made) is a Double, which (int) cuts towards zero and saturates.
    internal fun intValue(e: JsonElement?): Int? {
        val v = e as? JsonPrimitive ?: return null
        if (v.isString) return null
        val c = v.content
        if (c == "true" || c == "false" || c == "null") return null
        c.toLongOrNull()?.let { return it.toInt() }
        return c.toDoubleOrNull()?.toInt()
    }
}
