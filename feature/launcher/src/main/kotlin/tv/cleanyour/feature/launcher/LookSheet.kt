package tv.cleanyour.feature.launcher

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.LookDraft
import tv.cleanyour.core.flows.LookPreview
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.ui.design.component.LookEditor
import tv.cleanyour.ui.design.component.LookEditorLook
import tv.cleanyour.ui.design.component.LookEditorMotion
import tv.cleanyour.ui.design.component.LookEditorPicture
import tv.cleanyour.ui.design.component.LookEditorPreview
import tv.cleanyour.ui.design.component.LookEditorState
import tv.cleanyour.ui.design.component.PhotoPicker
import tv.cleanyour.ui.design.component.PrimaryButton

// 0.4.0 Look sheet: the Look editor of :ui:design (the preview of the TV's home screen from the
// bytes the push would send, Apps in a row, the looks and the Picture row, the same as Setup's look
// step), then what a new look clears on the TV and "Put it on the TV". A tap only chooses
// (HomeScreenMachine keeps the choice); nothing reaches the TV until "Put it on the TV", a guarded
// primary (R4-W8). `pictures`: the pictures by file, given by the screenshot tests (the app reads
// the files).
@Composable
internal fun ColumnScope.LookContent(state: HomeScreenState, onIntent: (HomeScreenIntent) -> Unit, pictures: Map<String, ImageBitmap>? = null,
    photos: PhotoPicker? = null) {
    val draft = state.look ?: return
    LookEditor(
        state.toEditor(draft),
        onLook = { onIntent(HomeScreenIntent.SelectLook(it)) },
        onColumns = { onIntent(HomeScreenIntent.SelectColumns(it)) },
        onPicture = { onIntent(HomeScreenIntent.SelectPicture(it)) },
        onPhoto = { jpeg, dim -> onIntent(HomeScreenIntent.SetOwnPicture(jpeg, dim)) },
        photos = photos, pictures = pictures,
    )
    Body(stringResource(R.string.look_clears_tv_choices), Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp))
    PrimaryButton(stringResource(R.string.look_use), { onIntent(HomeScreenIntent.UseLook) }, Modifier.padding(top = 20.dp))
}

// The sheet's choice as the editor shows it. The apps are counted on the dock as the TV shows it,
// the waiting apps too.
internal fun HomeScreenState.toEditor(draft: LookDraft): LookEditorState {
    val count = dock.size + waiting
    return LookEditorState(
        looks = themes.map { LookEditorLook(it.id, it.name, it.description, it.wallpaperPath) },
        lookId = draft.themeId, preview = draft.preview?.toEditor(), columns = draft.columns,
        apps = count, waiting = DockLayers.waiting(count, draft.columns),
        picture = draft.picture, ownPictureOnly = draft.ownPictureOnly,
        pictures = pictures.map { LookEditorPicture(it.id, it.name, it.path) },
        minePath = ownPicture?.path, newPath = newPicture?.path, photoDim = if (newPicture != null) newDim else ownDim,
        minColumns = DockLayers.MIN_OFFERED_COLUMNS, maxColumns = DockLayers.MAX_OFFERED_COLUMNS,
    )
}

// The preview as the flows read it back from the bytes, in the editor's plain values.
internal fun LookPreview.toEditor() = LookEditorPreview(
    backgroundArgb = backgroundArgb, picturePath = picturePath, dim = dim, sky = sky, columns = columns, rowsBelowDock = rowsBelowDock,
    dockRow = dockRow, dockTitle = dockTitle, fillDock = fillDock, cinematic = cinematic, festive = festive, accentArgb = accentArgb,
    greeting = greeting, motion = LookEditorMotion.valueOf(motion.name), dock = dock, minuteOfDay = minuteOfDay,
)
