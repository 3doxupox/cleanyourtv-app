package tv.cleanyour.feature.launcher

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.cleanyour.ui.design.component.ActionColumn
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.SheetHeader
import tv.cleanyour.ui.design.component.TertiaryButton
import tv.cleanyour.ui.design.component.TvLook
import tv.cleanyour.ui.design.component.TvPreview
import tv.cleanyour.ui.design.theme.CytTheme

// The system photo picker, which the app opens (Android 13 and later, and 11 and 12 with the
// picker module; the document picker before that; no Play services backport). Neither needs a
// storage or media permission. `pick` hands the chosen photo to `onPicked`, or nothing when the
// person backs out.
fun interface PhotoPicker { fun pick(onPicked: (Uri) -> Unit) }

// "Your photo": the picked photo in a 16:9 frame to pinch and drag, with the look's clock, buttons
// and dock drawn faintly over it (so faces stay clear of them) and the dim, live. "Use this crop"
// makes the TV's JPEG (OwnPhoto) and hands it on; nothing reaches the TV until "Put it on the TV".
@Composable
internal fun PhotoCropDialog(uri: Uri, outline: TvLook?, startDim: Int, onDone: (ByteArray, Int) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var photo by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val b = withContext(Dispatchers.IO) { OwnPhoto.decode(context.contentResolver, uri) }
        if (b == null) failed = true else photo = b
    }
    val scope = rememberCoroutineScope()
    Dialog(onCancel, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(CytTheme.colors.bg).verticalScroll(rememberScrollState()).padding(20.dp)) {
            val p = photo
            when {
                failed -> {
                    SheetHeader(stringResource(R.string.photo_title))
                    Body(stringResource(R.string.photo_unreadable), Modifier.padding(top = 12.dp))
                    TertiaryButton(stringResource(R.string.action_cancel), onCancel, Modifier.padding(top = 20.dp))
                }
                p != null -> {
                    val image = remember(p) { p.asImageBitmap() }
                    var tooBig by remember { mutableStateOf(false) }
                    PhotoCropContent(image, outline, startDim, if (tooBig) stringResource(R.string.photo_too_big) else null, onUse = { zoom, panX, panY, dim ->
                        scope.launch {
                            val bytes = withContext(Dispatchers.Default) { OwnPhoto.make(p, OwnPhoto.crop(p.width, p.height, zoom, panX, panY)) }
                            if (bytes == null) tooBig = true else onDone(bytes, dim)
                        }
                    }, onCancel = onCancel)
                }
                else -> SheetHeader(stringResource(R.string.photo_title))
            }
        }
    }
}

// The frame, the dim slider and the buttons; `problem` is a line to show when the photo could not
// be made. Zoom 1 to 4 over the cover crop, pan as fractions of the room the zoom leaves.
@Composable
internal fun PhotoCropContent(
    photo: ImageBitmap,
    outline: TvLook?,
    startDim: Int,
    problem: String?,
    onUse: (zoom: Float, panX: Float, panY: Float, dim: Int) -> Unit,
    onCancel: () -> Unit,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var dim by remember { mutableIntStateOf(startDim.coerceIn(0, 60)) }
    SheetHeader(stringResource(R.string.photo_title), subtitle = stringResource(R.string.photo_body))
    val frameDescription = stringResource(R.string.photo_frame_cd)
    BoxWithConstraints(
        Modifier.padding(top = 16.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(CytTheme.shapes.medium).background(Color.Black)
            .semantics { contentDescription = frameDescription },
    ) {
        val frameW = constraints.maxWidth.toFloat()
        Canvas(
            Modifier.fillMaxSize().pointerInput(photo) {
                detectTransformGestures { _, pan, z, _ ->
                    zoom = (zoom * z).coerceIn(1f, 4f)
                    val r = OwnPhoto.crop(photo.width, photo.height, zoom, 0f, 0f)
                    val k = frameW / r.width().coerceAtLeast(1)   // frame px per photo px
                    val roomX = (photo.width - r.width()) / 2f
                    val roomY = (photo.height - r.height()) / 2f
                    if (roomX > 0f) panX = (panX - pan.x / k / roomX).coerceIn(-1f, 1f)
                    if (roomY > 0f) panY = (panY - pan.y / k / roomY).coerceIn(-1f, 1f)
                }
            },
        ) {
            val r = OwnPhoto.crop(photo.width, photo.height, zoom, panX, panY)
            drawImage(photo, IntOffset(r.left, r.top), IntSize(r.width(), r.height()), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        }
        // Where the look puts the clock, the buttons and the dock, and the dim, over the photo.
        if (outline != null) {
            TvPreview(outline.copy(background = Color.Transparent, picture = null, dim = dim, sky = 0), "", Modifier.alpha(0.65f))
        }
    }
    val dimLine = msg(R.string.photo_dim, "dim" to dim)
    Body(dimLine, Modifier.padding(top = 16.dp, start = 4.dp))
    Slider(dim.toFloat(), { dim = (it / 10f).roundToInt() * 10 }, Modifier.fillMaxWidth().semantics { contentDescription = dimLine },
        valueRange = 0f..60f, steps = 5)
    problem?.let { Body(it, Modifier.padding(top = 8.dp, start = 4.dp), color = CytTheme.colors.warning) }
    ActionColumn(Modifier.padding(top = 16.dp)) {
        PrimaryButton(stringResource(R.string.photo_use), { onUse(zoom, panX, panY, dim) }, guardObscured = false)
        TertiaryButton(stringResource(R.string.action_cancel), onCancel, quiet = true)
    }
}
