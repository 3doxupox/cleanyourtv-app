package tv.cleanyour.feature.launcher

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.LookDraft
import tv.cleanyour.core.flows.LookMotion
import tv.cleanyour.core.flows.LookPicture
import tv.cleanyour.core.flows.LookPreview
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.ui.design.component.InfoChip
import tv.cleanyour.ui.design.component.Overline
import tv.cleanyour.ui.design.component.PictureChoice
import tv.cleanyour.ui.design.component.PicturePicker
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.SegmentOption
import tv.cleanyour.ui.design.component.SegmentedChoice
import tv.cleanyour.ui.design.component.SheetHeader
import tv.cleanyour.ui.design.component.ThemeOption
import tv.cleanyour.ui.design.component.ThemePicker
import tv.cleanyour.ui.design.component.TvLook
import tv.cleanyour.ui.design.component.TvPreview
import tv.cleanyour.ui.design.component.themeSwatch
import tv.cleanyour.ui.design.theme.CytTheme

// The launcher looks with their one-line descriptions; the preview tile colours come from
// :ui:design (themeSwatch), shared with the wizard's picker.
private val themeDescriptions = mapOf(
    "default" to R.string.theme_default_desc,
    "aurora" to R.string.theme_aurora_desc,
    "six-columns" to R.string.theme_six_columns_desc,
    "cinema" to R.string.theme_cinema_desc,
    "console" to R.string.theme_console_desc,
    "horizon" to R.string.theme_horizon_desc,
    "quiet" to R.string.theme_quiet_desc,
    "diwali" to R.string.theme_diwali_desc,
    "library" to R.string.theme_library_desc,
    "arcade" to R.string.theme_arcade_desc,
    "mist" to R.string.theme_mist_desc,
)

// The Picture row's keys for the look's own picture (HomeChoice.picture null) and for "Choose a
// photo" (opens the picker, never a choice itself).
private const val OWN = "own"
private const val PICK = "pick"

// 0.4.0 Look sheet: a preview of the TV's home screen with the choice (TvPreview, from the bytes
// the push would send), then Apps in a row (4 to 8, right under the preview it changes), the look
// (layout, motion, colour and its own picture) and the picture it shows (its own, one of the
// gallery, or none). A tap only chooses (HomeScreenMachine keeps the choice); nothing reaches the
// TV until "Put it on the TV", a guarded primary (R4-W8). `pictures`: the pictures by file, given
// by the screenshot tests (the app reads the files).
@Composable
internal fun ColumnScope.LookContent(state: HomeScreenState, onIntent: (HomeScreenIntent) -> Unit, pictures: Map<String, ImageBitmap>? = null,
    photos: PhotoPicker? = null) {
    val draft = state.look ?: return
    SheetHeader(stringResource(R.string.theme_title))
    val name = state.themes.firstOrNull { it.id == draft.themeId }?.name ?: stringResource(R.string.theme_default)
    draft.preview?.let { LookPreviewCard(it, name, pictures, Modifier.padding(top = 16.dp)) }
    AppsInARow(state, draft, onIntent)
    Overline(stringResource(R.string.look_list_title), Modifier.padding(start = 4.dp, top = 24.dp))
    val options = state.themes.map { t ->
        ThemeOption(t.id, t.name, themeDescriptions[t.id]?.let { stringResource(it) } ?: t.description, themeSwatch(t.id))
    }
    ThemePicker(options, draft.themeId, { onIntent(HomeScreenIntent.SelectLook(it.id)) }, Modifier.padding(top = 8.dp))
    Overline(stringResource(R.string.look_picture_title), Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp))
    if (draft.ownPictureOnly) {
        Body(stringResource(R.string.look_picture_own_only), Modifier.padding(start = 4.dp, end = 4.dp))
    } else {
        // "Choose a photo" opens the system picker, then the crop and dim (PhotoCropDialog); the
        // result is "New photo" (LookPicture.NEW_ID) until "Put it on the TV" puts it there, and
        // the photo kept for this TV is "Restore mine" (LookPicture.MINE_ID).
        var picked by remember { mutableStateOf<Uri?>(null) }
        PicturePicker(pictureChoices(state, draft, pictures, choose = photos != null), draft.picture ?: OWN, { c ->
            if (c.key == PICK) photos?.pick { picked = it } else onIntent(HomeScreenIntent.SelectPicture(c.key.takeUnless { k -> k == OWN }))
        })
        picked?.let { uri ->
            PhotoCropDialog(uri, draft.preview?.toTvLook(null), if (state.newPicture != null) state.newDim else state.ownDim, onDone = { jpeg, dim ->
                onIntent(HomeScreenIntent.SetOwnPicture(jpeg, dim)); picked = null
            }, onCancel = { picked = null })
        }
    }
    Body(stringResource(R.string.look_clears_tv_choices), Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp))
    PrimaryButton(stringResource(R.string.look_use), { onIntent(HomeScreenIntent.UseLook) }, Modifier.padding(top = 20.dp))
}

// 0.4.0 Apps in a row: 4 to 8 as one segmented choice, "Bigger" under the 4 end and "More apps"
// under the 8 end, then the helper lines (a polite live region, so TalkBack reads a new line after a
// change): always the first, the line for 8 when it is chosen, then at most one of the waiting, the
// empty space and the 7 line. A tap only chooses (SelectColumns).
@Composable
private fun ColumnScope.AppsInARow(state: HomeScreenState, draft: LookDraft, onIntent: (HomeScreenIntent) -> Unit) {
    Overline(stringResource(R.string.look_columns_title), Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
    val first = DockLayers.MIN_OFFERED_COLUMNS
    val last = DockLayers.MAX_OFFERED_COLUMNS
    val options = (first..last).map { n ->
        val cd = when (n) { first -> R.string.look_columns_cd_big; last -> R.string.look_columns_cd_many; else -> R.string.look_columns_cd }
        SegmentOption(n.toString(), n.toString(), msg(cd, "count" to n))
    }
    SegmentedChoice(options, draft.columns.toString(), { onIntent(HomeScreenIntent.SelectColumns(it.key.toInt())) })
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.look_columns_end_big), color = CytTheme.colors.textSecondary, style = CytTheme.type.bodySmall)
        Text(stringResource(R.string.look_columns_end_many), color = CytTheme.colors.textSecondary, style = CytTheme.type.bodySmall)
    }
    Column(Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in columnsHelp(state, draft)) Body(line)
    }
}

// The helper lines for apps in a row, counted on the dock as the TV shows it (the waiting apps
// too). A look without the dock band (Library) has no dock spaces and no waiting apps: one grid.
@Composable
private fun columnsHelp(state: HomeScreenState, draft: LookDraft): List<String> {
    val c = draft.columns
    val apps = state.dock.size + state.waiting
    val band = draft.preview?.dockRow ?: true
    val lines = mutableListOf(stringResource(if (c == DockLayers.MIN_OFFERED_COLUMNS) R.string.look_columns_biggest else R.string.look_columns_help))
    if (c == DockLayers.MAX_OFFERED_COLUMNS) lines += stringResource(R.string.look_columns_eight)
    val waiting = DockLayers.waiting(apps, c)
    when {
        band && waiting > 0 -> lines += msg(R.string.look_columns_waiting, "count" to waiting)
        band && apps in 1 until c -> lines += msg(R.string.look_columns_empty, "apps" to apps, "free" to c - apps)
        c == 7 -> lines += stringResource(R.string.look_columns_seven)
    }
    return lines
}

@Composable
private fun pictureChoices(state: HomeScreenState, draft: LookDraft, given: Map<String, ImageBitmap>?, choose: Boolean): List<PictureChoice> {
    val look = state.themes.firstOrNull { it.id == draft.themeId }
    val color = themeSwatch(draft.themeId).first()
    val own = PictureChoice(OWN, stringResource(R.string.look_picture_own), picture(look?.wallpaperPath, given), color)
    val gallery = state.pictures.map { PictureChoice(it.id, it.name, picture(it.path, given), color) }
    val mine = state.ownPicture?.let { PictureChoice(LookPicture.MINE_ID, stringResource(R.string.look_picture_restore_mine), picture(it.path, given), color) }
    val new = state.newPicture?.let { PictureChoice(LookPicture.NEW_ID, stringResource(R.string.look_picture_new), picture(it.path, given), color) }
    val pick = PictureChoice(PICK, stringResource(R.string.look_picture_choose), null, CytTheme.colors.surface3).takeIf { choose }
    return listOf(own) + gallery + listOfNotNull(mine, new, pick) + PictureChoice(LookPicture.NONE_ID, stringResource(R.string.look_picture_none), null, color)
}

// A picture file as a small bitmap for a thumbnail, read off the main thread; null while it loads
// or when there is none. Screenshot tests hand theirs in by path.
@Composable
internal fun picture(path: String?, given: Map<String, ImageBitmap>? = null, sample: Int = 8): ImageBitmap? {
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
internal fun LookPreviewCard(p: LookPreview, name: String, pictures: Map<String, ImageBitmap>? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        TvPreview(p.toTvLook(picture(p.picturePath, pictures, sample = 4)), previewDescription(p, name))
        PreviewFacts(p, Modifier.padding(top = 10.dp))
    }
}

// The launcher's numbers for the preview (and for the outlines over the photo frame): focus scale 1.12 with advanced effects, 1.10 otherwise,
// none with animations off; the accent focus only in a festival.
@Composable
internal fun LookPreview.toTvLook(picture: ImageBitmap?): TvLook {
    val (top, bottom) = skyColors(minuteOfDay)
    return TvLook(
        background = Color(backgroundArgb), picture = picture, dim = dim, sky = sky, skyTop = top, skyBottom = bottom,
        columns = columns, rowsBelowDock = rowsBelowDock, dockRow = dockRow, dockTitle = dockTitle, cinematic = cinematic,
        accent = if (festive) Color(accentArgb) else null, greeting = greeting,
        focusScale = when (motion) { LookMotion.OFF -> 1f; LookMotion.CRISP -> 1.12f; else -> 1.10f },
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
private fun previewDescription(p: LookPreview, name: String): String =
    msg(if (p.picturePath != null) R.string.look_preview_cd_picture else R.string.look_preview_cd, "look" to name, "count" to p.columns)

// What the picture cannot show: the motion, the sky, one page of apps. 0.4.0: apps in a row is the
// control under the preview, so it has no chip; the chips wrap to a new line instead of scrolling,
// so none is cut at 200% text.
@Composable
private fun PreviewFacts(p: LookPreview, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoChip(stringResource(when (p.motion) {
            LookMotion.OFF -> R.string.look_fact_motion_off
            LookMotion.CALM -> R.string.look_fact_motion_calm
            LookMotion.CRISP -> R.string.look_fact_motion_crisp
            LookMotion.BOUNCY -> R.string.look_fact_motion_bouncy
        }))
        if (p.sky > 0) InfoChip(stringResource(R.string.look_fact_sky))
        if (!p.dockRow) InfoChip(stringResource(R.string.look_fact_grid))
    }
}
