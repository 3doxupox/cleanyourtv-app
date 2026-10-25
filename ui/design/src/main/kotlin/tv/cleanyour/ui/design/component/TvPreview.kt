package tv.cleanyour.ui.design.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import tv.cleanyour.ui.design.theme.CytTheme
import tv.cleanyour.ui.design.theme.Outfit

// 0.4.0: what the TV's home screen will look like, for the Look sheet. Drawn from the launcher's
// keys (the caller reads them back from the config.json the push would send), in the launcher's
// own layer order: the background colour, the picture (centre crop), the dim, the sky at its
// strength, the cinematic wash, the header (clock, pills, a festival's greeting), the big name, the
// dock band (solid, see-through on the cinematic shelf, or none with one plain grid from the top),
// the tiles with the person's own app names on plain cards (never logos), the first one focused
// (white glass, or the festival's accent), and the rows under the dock. Launcher 2.5 numbers: a
// 960 x 540 dp screen, tiles min(180 dp, fit) wide in 16:9, 16 dp corners, the band #141416 with a
// 12% white edge and 26 dp corners, the cinematic band #121014 at 55%. It is a picture, not the
// TV: always left to right like the TV, text at a fixed size, one description for TalkBack.
@Immutable
data class TvLook(
    val background: Color,
    val picture: ImageBitmap?,
    val dim: Int,
    val sky: Int,
    val skyTop: Color,
    val skyBottom: Color,
    val columns: Int,
    val rowsBelowDock: Int,
    val dockRow: Boolean,
    val dockTitle: Boolean,
    val cinematic: Boolean,
    val accent: Color?,
    val greeting: String,
    val focusScale: Float,
    val dock: List<String>,
    val clock: String,
)

private const val TV_W = 960f
private const val TV_H = 540f
private const val MARGIN = 96f
private const val GAP = 13f

// The launcher's tile width for `columns` tiles a row on a 960 dp screen (tileWidthPx).
fun tvTileWidth(columns: Int): Float {
    val n = columns.coerceIn(1, 8)
    return min(180f, (TV_W - 2 * MARGIN - (n - 1) * GAP) / n)
}

@Composable
fun TvPreview(look: TvLook, contentDescription: String, modifier: Modifier = Modifier) {
    val shape = CytTheme.shapes.medium
    BoxWithConstraints(
        modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(look.background)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .clearAndSetSemantics { this.contentDescription = contentDescription; role = Role.Image },
    ) {
        val s = maxWidth.value / TV_W
        fun u(v: Float): Dp = (v * s).dp
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1f), LocalLayoutDirection provides LayoutDirection.Ltr) {
            if (look.picture != null) Image(look.picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (look.dim > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = look.dim.coerceIn(0, 90) / 100f)))
            if (look.sky > 0) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(look.skyTop, look.skyBottom)), alpha = look.sky.coerceIn(0, 100) / 100f))
            }
            if (look.cinematic) {
                Box(Modifier.fillMaxWidth().size(u(TV_W), u(TV_H * 0.55f)).background(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f), Color.Transparent)),
                ))
            }
            Header(look, s, ::u)
            val tileW = tvTileWidth(look.columns)
            val tileH = tileW * 9f / 16f
            val rowGap = 18f
            val rowsWide = look.columns.coerceIn(1, 8)
            val rowW = rowsWide * tileW + (rowsWide - 1) * GAP
            val left = (TV_W - rowW) / 2f
            val names = look.dock
            if (look.dockRow) {
                val rowsBelow = look.rowsBelowDock.coerceIn(0, 3)
                val dockTop = TV_H - 44f - rowsBelow * (tileH + rowGap) - tileH
                if (look.dockTitle || look.cinematic) {
                    val size = if (look.cinematic) 52f else 44f
                    Text(
                        names.firstOrNull() ?: "", Modifier.offset(u(left), u(dockTop - 34f - size)), color = Color.White,
                        fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = (size * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (look.cinematic) {
                        Text("Last opened", Modifier.offset(u(left), u(dockTop - 34f)), color = Color.White.copy(alpha = 0.7f), fontFamily = Outfit, fontSize = (16 * s).sp)
                    }
                }
                val bandShape = RoundedCornerShape(u(26f))
                val bandColor = if (look.cinematic) Color(0xFF121014).copy(alpha = 0.55f) else Color(0xFF141416)
                Box(
                    Modifier.offset(u(left - 14f), u(dockTop - 14f)).size(u(rowW + 28f), u(tileH + 28f))
                        .background(bandColor, bandShape).border(1.dp, Color.White.copy(alpha = if (look.cinematic) 0.15f else 0.12f), bandShape),
                )
                for (i in 0 until rowsWide) Tile(names.getOrNull(i), i == 0, look, u(left + i * (tileW + GAP)), u(dockTop), u(tileW), u(tileH), s)
                for (r in 1..rowsBelow) {
                    val top = dockTop + tileH + 14f + rowGap + (r - 1) * (tileH + rowGap)
                    for (i in 0 until rowsWide) Tile(names.getOrNull(r * rowsWide + i), false, look, u(left + i * (tileW + GAP)), u(top), u(tileW), u(tileH), s)
                }
            } else {
                // dock_row false: no band, one plain grid from just under the header, compact.
                var top = 108f
                var r = 0
                while (top + tileH <= TV_H - 12f) {
                    for (i in 0 until rowsWide) Tile(names.getOrNull(r * rowsWide + i), r == 0 && i == 0, look, u(left + i * (tileW + GAP)), u(top), u(tileW), u(tileH), s)
                    top += tileH + 12f
                    r++
                }
            }
        }
    }
}

@Composable
private fun Header(look: TvLook, s: Float, u: (Float) -> Dp) {
    Text(look.clock, Modifier.offset(u(48f), u(26f)), color = Color.White, fontFamily = Outfit, fontWeight = FontWeight.Medium, fontSize = (34 * s).sp)
    if (look.greeting.isNotEmpty()) {
        Text(look.greeting.uppercase(), Modifier.offset(u(50f), u(74f)), color = look.accent ?: Color.White, fontFamily = Outfit,
            fontWeight = FontWeight.SemiBold, fontSize = (14 * s).sp)
    }
    // The pills on the right: Wi-Fi, settings and the app library (the diya, bell and marigold in a festival).
    for (k in 0 until 3) {
        val pill = RoundedCornerShape(u(21f))
        Box(
            Modifier.offset(u(TV_W - 48f - (k + 1) * 50f + 8f), u(30f)).size(u(42f)).background(Color.White.copy(alpha = 0.10f), pill)
                .border(1.dp, Color.White.copy(alpha = 0.12f), pill),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(u(14f)).background(look.accent ?: Color.White.copy(alpha = 0.75f), RoundedCornerShape(u(7f))))
        }
    }
}

@Composable
private fun Tile(name: String?, focused: Boolean, look: TvLook, x: Dp, y: Dp, w: Dp, h: Dp, s: Float) {
    val corner = RoundedCornerShape((16 * s).dp)
    val ring = look.accent ?: Color.White
    Box(
        Modifier.offset(x, y).size(w, h).scale(if (focused) look.focusScale else 1f)
            .background(Color(0xFF1C1C1E), corner)
            .then(if (focused) Modifier.border((3 * s).coerceAtLeast(1f).dp, ring.copy(alpha = 0.9f), corner) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (name != null) {
            Text(name, color = Color.White.copy(alpha = if (focused) 1f else 0.8f), fontFamily = Outfit, fontSize = (15 * s).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
