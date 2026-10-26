package tv.cleanyour.ui.design.component

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.ui.design.R
import tv.cleanyour.ui.design.theme.CytTheme

// 0.4.0 Look editor (RFC-LOOK-SETUP-1): the Look sheet's choices, shared by the Look sheet after
// Setup (Manage, Home screen, Look) and Setup's look step, so both show the same thing in the same
// order with the same words: the preview of the TV's home screen (TvPreview, drawn from the bytes
// the push would send), Apps in a row (4 to 8, right under the preview it changes, with its helper
// lines), the looks, and the Picture row (the look's own, the gallery, the person's photo, a new
// photo, "Choose a photo" with its crop and dim, or none). A tap only chooses; what sends it to the
// TV is the caller's own button under it.

// How the TV moves (LookPreview.motion).
enum class LookEditorMotion { OFF, CALM, CRISP, BOUNCY }

// The preview as the flows read it back from the bytes (LookPreview), in plain values.
@Immutable
data class LookEditorPreview(
    val backgroundArgb: Int,
    val picturePath: String?,
    val dim: Int,
    val sky: Int,
    val columns: Int,
    val rowsBelowDock: Int,
    val dockRow: Boolean,
    val dockTitle: Boolean,
    val fillDock: Boolean,
    val cinematic: Boolean,
    val festive: Boolean,
    val accentArgb: Int,
    val greeting: String,
    val motion: LookEditorMotion,
    val dock: List<String>,
    val minuteOfDay: Int,
)

// A look of the list: its own picture as a file on this phone, when it has one.
@Immutable
data class LookEditorLook(val id: String, val name: String, val description: String, val picturePath: String? = null)

// A gallery picture of the Picture row, with its file (null when it has none on this phone).
@Immutable
data class LookEditorPicture(val id: String, val name: String, val path: String?)

// What the editor shows. `picture`: null for the look's own, NONE, MINE, NEW or a gallery id.
// `apps`: the dock as the TV shows it plus the apps waiting for more apps in a row; `waiting`: how
// many of them wait at `columns`. `minePath`: the person's photo kept for this TV ("Restore mine"),
// `newPath`: a photo just made here, not on the TV yet; `photoDim`: the dim the crop starts from.
@Immutable
data class LookEditorState(
    val looks: List<LookEditorLook>,
    val lookId: String,
    val preview: LookEditorPreview?,
    val columns: Int,
    val apps: Int,
    val waiting: Int,
    val picture: String?,
    val ownPictureOnly: Boolean = false,
    val pictures: List<LookEditorPicture> = emptyList(),
    val minePath: String? = null,
    val newPath: String? = null,
    val photoDim: Int = 30,
    val minColumns: Int = 4,
    val maxColumns: Int = 8,
) {
    companion object {
        // The picture keys the flows use (LookPicture.NONE_ID, MINE_ID, NEW_ID).
        const val NONE = "none"
        const val MINE = "mine"
        const val NEW = "new"
    }
}

// The Picture row's keys for the look's own picture (picture null) and for "Choose a photo" (opens
// the picker, never a choice itself).
private const val OWN = "own"
private const val PICK = "pick"

// The editor, from the sheet header to the Picture row. `onPicture` gets null for the look's own;
// `onPhoto` the JPEG the crop made and its dim. `photos`: the system photo picker ("Choose a photo"
// is offered only with one). `pictures`: the pictures by file, given by the screenshot tests (the
// app reads the files).
@Composable
fun ColumnScope.LookEditor(
    state: LookEditorState,
    onLook: (String) -> Unit,
    onColumns: (Int) -> Unit,
    onPicture: (String?) -> Unit,
    onPhoto: (ByteArray, Int) -> Unit,
    photos: PhotoPicker? = null,
    pictures: Map<String, ImageBitmap>? = null,
) {
    SheetHeader(stringResource(R.string.cyt_look_title))
    val name = state.looks.firstOrNull { it.id == state.lookId }?.name ?: stringResource(R.string.cyt_look_default)
    state.preview?.let { LookPreviewCard(it, name, pictures, Modifier.padding(top = 16.dp)) }
    AppsInARow(state, onColumns)
    Overline(stringResource(R.string.cyt_look_list_title), Modifier.padding(start = 4.dp, top = 24.dp))
    val options = state.looks.map { t -> ThemeOption(t.id, t.name, lookDescription(t.id) ?: t.description, themeSwatch(t.id)) }
    ThemePicker(options, state.lookId, { onLook(it.id) }, Modifier.padding(top = 8.dp))
    Overline(stringResource(R.string.cyt_look_picture_title), Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp))
    if (state.ownPictureOnly) {
        LookBody(stringResource(R.string.cyt_look_picture_own_only), Modifier.padding(start = 4.dp, end = 4.dp))
    } else {
        // "Choose a photo" opens the system picker, then the crop and dim (PhotoCropDialog); the
        // result is "New photo" (NEW) until the caller sends it, and the photo kept for this TV is
        // "Restore mine" (MINE).
        var picked by remember { mutableStateOf<Uri?>(null) }
        PicturePicker(pictureChoices(state, pictures, choose = photos != null), state.picture ?: OWN, { c ->
            if (c.key == PICK) photos?.pick { picked = it } else onPicture(c.key.takeUnless { k -> k == OWN })
        })
        picked?.let { uri ->
            PhotoCropDialog(uri, state.preview?.toTvLook(null), state.photoDim, onDone = { jpeg, dim -> onPhoto(jpeg, dim); picked = null },
                onCancel = { picked = null })
        }
    }
}

// The look's own description, in the app's words (the bundled one otherwise).
@Composable
fun lookDescription(id: String): String? = when (id) {
    "default" -> R.string.cyt_look_desc_default
    "aurora" -> R.string.cyt_look_desc_aurora
    "six-columns" -> R.string.cyt_look_desc_six_columns
    "cinema" -> R.string.cyt_look_desc_cinema
    "console" -> R.string.cyt_look_desc_console
    "horizon" -> R.string.cyt_look_desc_horizon
    "quiet" -> R.string.cyt_look_desc_quiet
    "diwali" -> R.string.cyt_look_desc_diwali
    "library" -> R.string.cyt_look_desc_library
    "arcade" -> R.string.cyt_look_desc_arcade
    "mist" -> R.string.cyt_look_desc_mist
    else -> null
}?.let { stringResource(it) }

// Apps in a row: 4 to 8 as one segmented choice, "Bigger" under the 4 end and "More apps" under the
// 8 end, then the helper lines (a polite live region, so TalkBack reads a new line after a change):
// always the first, the line for 8 when it is chosen, then at most one of the waiting, the empty
// space and the 7 line. A tap only chooses.
@Composable
private fun ColumnScope.AppsInARow(state: LookEditorState, onColumns: (Int) -> Unit) {
    Overline(stringResource(R.string.cyt_look_columns_title), Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
    val first = state.minColumns
    val last = state.maxColumns
    val options = (first..last).map { n ->
        val cd = when (n) { first -> R.string.cyt_look_columns_cd_big; last -> R.string.cyt_look_columns_cd_many; else -> R.string.cyt_look_columns_cd }
        SegmentOption(n.toString(), n.toString(), lookMsg(cd, "count" to n))
    }
    SegmentedChoice(options, state.columns.toString(), { onColumns(it.key.toInt()) })
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.cyt_look_columns_end_big), color = CytTheme.colors.textSecondary, style = CytTheme.type.bodySmall)
        Text(stringResource(R.string.cyt_look_columns_end_many), color = CytTheme.colors.textSecondary, style = CytTheme.type.bodySmall)
    }
    Column(Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in columnsHelp(state)) LookBody(line)
    }
}

// The helper lines for apps in a row, counted on the dock as the TV shows it (the waiting apps
// too). A look without the dock band (Library) has no dock spaces and no waiting apps: one grid.
@Composable
private fun columnsHelp(state: LookEditorState): List<String> {
    val c = state.columns
    val apps = state.apps
    val band = state.preview?.dockRow ?: true
    val lines = mutableListOf(stringResource(if (c == state.minColumns) R.string.cyt_look_columns_biggest else R.string.cyt_look_columns_help))
    if (c == state.maxColumns) lines += stringResource(R.string.cyt_look_columns_eight)
    when {
        band && state.waiting > 0 -> lines += lookMsg(R.string.cyt_look_columns_waiting, "count" to state.waiting)
        band && apps in 1 until c -> lines += lookMsg(R.string.cyt_look_columns_empty, "apps" to apps, "free" to c - apps)
        c == 7 -> lines += stringResource(R.string.cyt_look_columns_seven)
    }
    return lines
}

@Composable
private fun pictureChoices(state: LookEditorState, given: Map<String, ImageBitmap>?, choose: Boolean): List<PictureChoice> {
    val look = state.looks.firstOrNull { it.id == state.lookId }
    val color = themeSwatch(state.lookId).first()
    val own = PictureChoice(OWN, stringResource(R.string.cyt_look_picture_own), lookPicture(look?.picturePath, given), color)
    val gallery = state.pictures.map { PictureChoice(it.id, it.name, lookPicture(it.path, given), color) }
    val mine = state.minePath?.let { PictureChoice(LookEditorState.MINE, stringResource(R.string.cyt_look_picture_restore_mine), lookPicture(it, given), color) }
    val new = state.newPath?.let { PictureChoice(LookEditorState.NEW, stringResource(R.string.cyt_look_picture_new), lookPicture(it, given), color) }
    val pick = PictureChoice(PICK, stringResource(R.string.cyt_look_picture_choose), null, CytTheme.colors.surface3).takeIf { choose }
    return listOf(own) + gallery + listOfNotNull(mine, new, pick) + PictureChoice(LookEditorState.NONE, stringResource(R.string.cyt_look_picture_none), null, color)
}

// A picture file as a small bitmap for a thumbnail, read off the main thread; null while it loads
// or when there is none. Screenshot tests hand theirs in by path.
@Composable
fun lookPicture(path: String?, given: Map<String, ImageBitmap>? = null, sample: Int = 8): ImageBitmap? {
    if (given != null) return path?.let { given[it] }
    val bitmap by produceState<ImageBitmap?>(null, path, sample) {
        value = path?.let { p ->
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return bitmap
}

// The preview and, under it, what a picture cannot show.
@Composable
fun LookPreviewCard(p: LookEditorPreview, name: String, pictures: Map<String, ImageBitmap>? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        TvPreview(p.toTvLook(lookPicture(p.picturePath, pictures, sample = 4)), previewDescription(p, name))
        PreviewFacts(p, Modifier.padding(top = 10.dp))
    }
}

// The launcher's numbers for the preview (and for the outlines over the photo frame): focus scale
// 1.12 with advanced effects, 1.10 otherwise, none with animations off; the accent focus only in a
// festival.
fun LookEditorPreview.toTvLook(picture: ImageBitmap?): TvLook {
    val (top, bottom) = skyColors(minuteOfDay)
    return TvLook(
        background = Color(backgroundArgb), picture = picture, dim = dim, sky = sky, skyTop = top, skyBottom = bottom,
        columns = columns, rowsBelowDock = rowsBelowDock, dockRow = dockRow, dockTitle = dockTitle, cinematic = cinematic,
        accent = if (festive) Color(accentArgb) else null, greeting = greeting,
        focusScale = when (motion) { LookEditorMotion.OFF -> 1f; LookEditorMotion.CRISP -> 1.12f; else -> 1.10f },
        dock = dock, clock = "${minuteOfDay / 60}:${(minuteOfDay % 60).toString().padStart(2, '0')}",
        emptyDockSlots = !fillDock,
    )
}

// The time of day sky the launcher draws over the picture, in four broad stops (night, sunrise,
// day, the warm evening peak); the TV blends between them by the minute.
private fun skyColors(minute: Int): Pair<Color, Color> = when (minute / 60) {
    in 5..7 -> Color(0xFF6C2E52) to Color(0xFFE2704A)
    in 8..16 -> Color(0xFF3A6EA5) to Color(0xFF9CC3E6)
    in 17..20 -> Color(0xFFE2704A) to Color(0xFF6C2E52)
    else -> Color(0xFF0B1030) to Color(0xFF1B1030)
}

@Composable
private fun previewDescription(p: LookEditorPreview, name: String): String =
    lookMsg(if (p.picturePath != null) R.string.cyt_look_preview_cd_picture else R.string.cyt_look_preview_cd, "look" to name, "count" to p.columns)

// What the picture cannot show: the motion, the sky, one page of apps. Apps in a row is the
// control under the preview, so it has no chip; the chips wrap to a new line instead of scrolling,
// so none is cut at 200% text.
@Composable
private fun PreviewFacts(p: LookEditorPreview, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoChip(stringResource(when (p.motion) {
            LookEditorMotion.OFF -> R.string.cyt_look_fact_motion_off
            LookEditorMotion.CALM -> R.string.cyt_look_fact_motion_calm
            LookEditorMotion.CRISP -> R.string.cyt_look_fact_motion_crisp
            LookEditorMotion.BOUNCY -> R.string.cyt_look_fact_motion_bouncy
        }))
        if (p.sky > 0) InfoChip(stringResource(R.string.cyt_look_fact_sky))
        if (!p.dockRow) InfoChip(stringResource(R.string.cyt_look_fact_grid))
    }
}

// A supporting paragraph in body.medium, text.secondary unless the caller says otherwise.
@Composable
internal fun LookBody(text: String, modifier: Modifier = Modifier, color: Color = CytTheme.colors.textSecondary) {
    Text(text, modifier, color = color, style = CytTheme.type.bodyMedium)
}

// The look editor's copy is ICU MessageFormat with named arguments, as the screens' copy is.
@Composable
internal fun lookMsg(id: Int, vararg args: Pair<String, Any>): String {
    val pattern = stringResource(id)
    if (args.isEmpty()) return pattern
    val locale = LocalConfiguration.current.locales[0]
    return remember(pattern, locale, args.toList()) {
        android.icu.text.MessageFormat(pattern, locale).format(args.toMap())
    }
}
