package tv.cleanyour.feature.launcher

import android.graphics.BitmapFactory
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.LookDraft
import tv.cleanyour.core.flows.LookMotion
import tv.cleanyour.core.flows.LookPicture
import tv.cleanyour.core.flows.LookPreview
import tv.cleanyour.ui.design.component.InfoChip
import tv.cleanyour.ui.design.component.Overline
import tv.cleanyour.ui.design.component.PictureChoice
import tv.cleanyour.ui.design.component.PicturePicker
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.SheetHeader
import tv.cleanyour.ui.design.component.ThemeOption
import tv.cleanyour.ui.design.component.ThemePicker
import tv.cleanyour.ui.design.component.TvLook
import tv.cleanyour.ui.design.component.TvPreview
import tv.cleanyour.ui.design.component.themeSwatch

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

// The Picture row's key for the look's own picture (HomeChoice.picture null).
private const val OWN = "own"

// 0.4.0 Look sheet: a preview of the TV's home screen with the choice (TvPreview, from the bytes
// the push would send), then the look (layout, motion, colour and its own picture) and the picture
// it shows (its own, one of the gallery, or none). A tap only chooses (HomeScreenMachine keeps the
// choice); nothing reaches the TV until "Use this look", a guarded primary (R4-W8). `pictures`:
// the pictures by file, given by the screenshot tests (the app reads the files).
@Composable
internal fun ColumnScope.LookContent(state: HomeScreenState, onIntent: (HomeScreenIntent) -> Unit, pictures: Map<String, ImageBitmap>? = null) {
    val draft = state.look ?: return
    SheetHeader(stringResource(R.string.theme_title))
    val name = state.themes.firstOrNull { it.id == draft.themeId }?.name ?: stringResource(R.string.theme_default)
    draft.preview?.let { LookPreviewCard(it, name, pictures, Modifier.padding(top = 16.dp)) }
    Overline(stringResource(R.string.look_list_title), Modifier.padding(start = 4.dp, top = 20.dp))
    val options = state.themes.map { t ->
        ThemeOption(t.id, t.name, themeDescriptions[t.id]?.let { stringResource(it) } ?: t.description, themeSwatch(t.id))
    }
    ThemePicker(options, draft.themeId, { onIntent(HomeScreenIntent.SelectLook(it.id)) }, Modifier.padding(top = 8.dp))
    Overline(stringResource(R.string.look_picture_title), Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp))
    if (draft.ownPictureOnly) {
        Body(stringResource(R.string.look_picture_own_only), Modifier.padding(start = 4.dp, end = 4.dp))
    } else {
        PicturePicker(pictureChoices(state, draft, pictures), draft.picture ?: OWN, { onIntent(HomeScreenIntent.SelectPicture(it.key.takeUnless { k -> k == OWN })) })
    }
    Body(stringResource(R.string.look_clears_tv_choices), Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp))
    PrimaryButton(stringResource(R.string.look_use), { onIntent(HomeScreenIntent.UseLook) }, Modifier.padding(top = 20.dp))
}

@Composable
private fun pictureChoices(state: HomeScreenState, draft: LookDraft, given: Map<String, ImageBitmap>?): List<PictureChoice> {
    val look = state.themes.firstOrNull { it.id == draft.themeId }
    val color = themeSwatch(draft.themeId).first()
    val own = PictureChoice(OWN, stringResource(R.string.look_picture_own), picture(look?.wallpaperPath, given), color)
    val gallery = state.pictures.map { PictureChoice(it.id, it.name, picture(it.path, given), color) }
    return listOf(own) + gallery + PictureChoice(LookPicture.NONE_ID, stringResource(R.string.look_picture_none), null, color)
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

// The launcher's numbers for the preview: focus scale 1.12 with advanced effects, 1.10 otherwise,
// none with animations off; the accent focus only in a festival.
@Composable
private fun LookPreview.toTvLook(picture: ImageBitmap?): TvLook {
    val (top, bottom) = skyColors(minuteOfDay)
    return TvLook(
        background = Color(backgroundArgb), picture = picture, dim = dim, sky = sky, skyTop = top, skyBottom = bottom,
        columns = columns, rowsBelowDock = rowsBelowDock, dockRow = dockRow, dockTitle = dockTitle, cinematic = cinematic,
        accent = if (festive) Color(accentArgb) else null, greeting = greeting,
        focusScale = when (motion) { LookMotion.OFF -> 1f; LookMotion.CRISP -> 1.12f; else -> 1.10f },
        dock = dock, clock = "${minuteOfDay / 60}:${(minuteOfDay % 60).toString().padStart(2, '0')}",
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

// What the picture cannot show: tiles a row, the motion, the sky.
@Composable
private fun PreviewFacts(p: LookPreview, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoChip(msg(R.string.look_fact_columns, "count" to p.columns))
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
