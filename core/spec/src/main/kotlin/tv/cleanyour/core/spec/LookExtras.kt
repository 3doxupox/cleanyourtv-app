package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonPrimitive

// Home screen 3.0's look switches (plan 0.4.5 WP5 step 3, RFC-UP300-1). Kept apart from LookSpec and
// StyleDetails on purpose, so the look2 cross product and its 2.7 differential stay as they are; a
// TV on 2.7 or older gets DEFAULT from the rules (LookGates.reads30). Every default is the
// launcher's own (config.xml at b06ab21), so DEFAULT writes no key and a look keeps its 0.4.0 bytes.
enum class TilesChoice(val id: String) { ART("art"), ICONS("icons") }
enum class HighlightChoice(val id: String) { GLASS("glass"), RIM("rim"), HALO("halo") }
data class LookExtras(
    val oneRow: Boolean = false,        // single_row
    val bandAll: Boolean = false,       // dock_band_all
    val noBand: Boolean = false,        // dock_band false
    val hideGreeting: Boolean = false,  // show_greeting false
    val dateBelow: Boolean = false,     // date_below_clock
    val brightDate: Boolean = false,    // header_white
    val wifiWarnings: Boolean = false,  // wifi_status_warnings
    val tiles: TilesChoice = TilesChoice.ART,              // uniform_tiles (T2)
    val highlight: HighlightChoice = HighlightChoice.GLASS, // glass_style (T2)
) { companion object { val DEFAULT = LookExtras() } }

// The nine 3.0 keys behind LookExtras as launcher 3.0's Config.load reads them from a config.json
// (b06ab21): bool, string and glassStyle on the carried values in doc.unknown, config.xml's
// defaults otherwise. `singleRow` is Config's own field, single_row and dock_row together (Config
// passes singleRow && dockRow); the rest are the keys as read. LookExtrasDifferentialTest holds it
// to the real 3.0 Config.java.
data class Look30Config(
    val singleRow: Boolean,
    val dockBand: Boolean,
    val dockBandAll: Boolean,
    val showGreeting: Boolean,
    val dateBelowClock: Boolean,
    val headerWhite: Boolean,
    val wifiStatusWarnings: Boolean,
    val uniformTiles: Boolean,
    val glassStyle: String,
)

object LookExtrasKeys {
    // The keys LookKeys.build may write for the extras, in LookExtras order.
    val KEYS: List<String> = listOf(
        "single_row", "dock_band_all", "dock_band", "show_greeting", "date_below_clock", "header_white",
        "wifi_status_warnings", "uniform_tiles", "glass_style",
    )

    // Config.GLASS_DEFAULT, GLASS_RIM and GLASS_HALO.
    const val GLASS_DEFAULT = "glass"

    // res/values/config.xml at b06ab21 for the nine keys (LookExtrasDifferentialTest parses it).
    val DEFAULTS_30: Look30Config = Look30Config(
        singleRow = false, dockBand = true, dockBandAll = false, showGreeting = true, dateBelowClock = false, headerWhite = false,
        wifiStatusWarnings = false, uniformTiles = false, glassStyle = GLASS_DEFAULT,
    )

    // Config.glassStyle: "rim" or "halo" in any case, once trimmed, else glass.
    fun glassStyle(value: String?): String {
        val v = value?.javaTrim() ?: return GLASS_DEFAULT
        return HighlightChoice.entries.firstOrNull { it != HighlightChoice.GLASS && it.id.equals(v, ignoreCase = true) }?.id ?: GLASS_DEFAULT
    }

    // The nine keys as launcher 3.0 reads these bytes' doc (null: no config.json).
    fun look30(doc: LauncherConfigDoc?): Look30Config {
        val d = DEFAULTS_30
        val dockRow = doc?.dockRow ?: ConfigCodecImpl.BUILT_IN.dockRow
        return Look30Config(
            singleRow = LauncherKeys.bool(doc, "single_row", d.singleRow) && dockRow,
            dockBand = LauncherKeys.bool(doc, "dock_band", d.dockBand),
            dockBandAll = LauncherKeys.bool(doc, "dock_band_all", d.dockBandAll),
            showGreeting = LauncherKeys.bool(doc, "show_greeting", d.showGreeting),
            dateBelowClock = LauncherKeys.bool(doc, "date_below_clock", d.dateBelowClock),
            headerWhite = LauncherKeys.bool(doc, "header_white", d.headerWhite),
            wifiStatusWarnings = LauncherKeys.bool(doc, "wifi_status_warnings", d.wifiStatusWarnings),
            uniformTiles = LauncherKeys.bool(doc, "uniform_tiles", d.uniformTiles),
            glassStyle = glassStyle(LauncherKeys.string(doc, "glass_style", d.glassStyle)),
        )
    }

    // The extras a 3.0 home screen shows for these bytes, each only where it takes effect (the
    // launcher's own rules, MainActivity and DockDecoration at b06ab21): one row needs the dock row
    // (Config); the band behind every app needs one row and a band (DockDecoration bandAll and
    // bandVisible); no band needs the
    // dock row (the band is a decoration of the dock row); the internet warnings tint the Wi-Fi pill,
    // so they need wifi_status_color and the pill on screen (top_menu hides it). The preview reads
    // these, and the extras differential reads back what build wrote.
    fun shown(doc: LauncherConfigDoc?): LookExtras {
        val c = look30(doc)
        val k = LauncherKeys.look(doc)
        val dockRow = doc?.dockRow ?: ConfigCodecImpl.BUILT_IN.dockRow
        return LookExtras(
            oneRow = c.singleRow,
            bandAll = c.singleRow && c.dockBandAll && c.dockBand,
            noBand = dockRow && !c.dockBand,
            hideGreeting = !c.showGreeting,
            dateBelow = c.dateBelowClock,
            brightDate = c.headerWhite,
            wifiWarnings = c.wifiStatusWarnings && k.wifiStatusColor && !k.topMenu,
            tiles = if (c.uniformTiles) TilesChoice.ICONS else TilesChoice.ART,
            highlight = HighlightChoice.entries.first { it.id == c.glassStyle },
        )
    }

    // What of `extras` takes effect on a doc with these keys (plan WP5 step 4, and the band behind
    // every app needs a band): the writer drops the rest, so a switch that does nothing never
    // reaches the bytes.
    fun applying(extras: LookExtras, dockRow: Boolean, wifiStatusColor: Boolean, topMenu: Boolean): LookExtras {
        val oneRow = extras.oneRow && dockRow
        val noBand = extras.noBand && dockRow
        return extras.copy(
            oneRow = oneRow,
            bandAll = extras.bandAll && oneRow && !noBand,
            noBand = noBand,
            wifiWarnings = extras.wifiWarnings && wifiStatusColor && !topMenu,
        )
    }

    // The keys of `extras` that differ from the launcher's own, as build writes them in doc.unknown.
    fun keys(extras: LookExtras): Map<String, JsonPrimitive> = buildMap {
        if (extras.oneRow) put("single_row", JsonPrimitive(true))
        if (extras.bandAll) put("dock_band_all", JsonPrimitive(true))
        if (extras.noBand) put("dock_band", JsonPrimitive(false))
        if (extras.hideGreeting) put("show_greeting", JsonPrimitive(false))
        if (extras.dateBelow) put("date_below_clock", JsonPrimitive(true))
        if (extras.brightDate) put("header_white", JsonPrimitive(true))
        if (extras.wifiWarnings) put("wifi_status_warnings", JsonPrimitive(true))
        if (extras.tiles == TilesChoice.ICONS) put("uniform_tiles", JsonPrimitive(true))
        if (extras.highlight != HighlightChoice.GLASS) put("glass_style", JsonPrimitive(extras.highlight.id))
    }
}
