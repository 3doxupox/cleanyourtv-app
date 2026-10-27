package tv.cleanyour.ui.design.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import tv.cleanyour.core.flows.LookEdit
import tv.cleanyour.core.spec.ButtonsChoice
import tv.cleanyour.core.spec.HighlightChoice
import tv.cleanyour.core.spec.LookExtras
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.core.spec.TilesChoice
import tv.cleanyour.ui.design.R

// Home screen 3.0's rows (plan WP6), from the editor state: each shows only on a TV whose home
// screen reads 3.0 (LookGates.reads30), and only where its key takes effect on the TV, so a row
// never offers what the TV would not do:
// - Every app in one row: a style with a dock (single_row needs dock_row, so not All apps);
// - None for the dock band: where a band is offered (not All apps or Spotlight);
// - Band behind every app (dock_band_all): not offered in 0.4.5. At rest the launcher's band already
//   runs to the grid's edge in one row (DockDecoration.java:113-140 at b06ab21), so the TV and the
//   preview look the same with it on or off until the row moves; a row the preview cannot show is
//   not offered;
// - the header rows, the tiles and the highlight: always;
// - Warn when the internet is down: shown always, changeable with Green Wi-Fi on and not One menu
//   button (wifi_status_warnings needs wifi_status_color; One menu button has no Wi-Fi button).
// A row that cannot be changed shows the value the TV will use (off), with its reason.
@Immutable
internal class ExtraRows(state: LookEditorState) {
    val extras: LookExtras = state.extras
    private val on = state.reads30
    private val r = state.rows
    val oneRow: Boolean = on && state.style != StyleId.ALL_APPS
    val oneRowOn: Boolean = oneRow && extras.oneRow
    val bandOffered: Boolean = on && r != null && r.band.visible && r.band.enabled
    val noBandOn: Boolean = bandOffered && extras.noBand
    val header: Boolean = on
    val wifiWarn: Boolean = on && r != null && r.greenWifi.visible
    val menu: Boolean = r?.buttons?.value == ButtonsChoice.MENU
    val wifiWarnEnabled: Boolean = wifiWarn && !menu && r?.greenWifi?.value == true
    val tiles: Boolean = on
    val highlight: Boolean = on

    // The whole set with one change, as LookEdit.SetExtras sends it.
    fun edit(onEdit: (LookEdit) -> Unit, change: LookExtras.() -> LookExtras) = onEdit(LookEdit.SetExtras(extras.change()))

    companion object {
        const val BAND_NONE = "none"
    }
}

// The 3.0 rows the person changed, in page order, for More options' summary ("One row",
// "Rim highlight"); only rows that show and can be changed.
@Composable
internal fun extraWords(state: LookEditorState): List<String> {
    val x = ExtraRows(state)
    val e = x.extras
    val out = mutableListOf<String>()
    if (x.oneRowOn) out += stringResource(R.string.cyt_look_word_one_row)
    if (x.noBandOn) out += stringResource(R.string.cyt_look_word_no_band)
    if (x.header && e.hideGreeting) out += stringResource(R.string.cyt_look_word_no_greeting)
    if (x.header && e.dateBelow) out += stringResource(R.string.cyt_look_word_date_below)
    if (x.header && e.brightDate) out += stringResource(R.string.cyt_look_word_bright_date)
    if (x.wifiWarnEnabled && e.wifiWarnings) out += stringResource(R.string.cyt_look_word_wifi_warn)
    if (x.tiles && e.tiles == TilesChoice.ICONS) out += stringResource(R.string.cyt_look_word_icon_tiles)
    if (x.highlight && e.highlight == HighlightChoice.RIM) out += stringResource(R.string.cyt_look_word_rim)
    if (x.highlight && e.highlight == HighlightChoice.HALO) out += stringResource(R.string.cyt_look_word_halo)
    return out
}
