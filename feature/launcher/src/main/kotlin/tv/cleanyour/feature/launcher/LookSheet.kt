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
import tv.cleanyour.ui.design.component.LookEditor
import tv.cleanyour.ui.design.component.LookEditorState
import tv.cleanyour.ui.design.component.PhotoPicker
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.toEditor

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

// The sheet's choice as the editor shows it (LookEditorFrom): the apps are counted on the dock as
// the TV shows it, the waiting apps too.
internal fun HomeScreenState.toEditor(draft: LookDraft): LookEditorState =
    draft.toEditor(themes, pictures, ownPicture, ownDim, newPicture, newDim, dock = dock.size, waitingApps = waiting)
