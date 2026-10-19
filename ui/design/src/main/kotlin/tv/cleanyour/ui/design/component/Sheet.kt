package tv.cleanyour.ui.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import tv.cleanyour.ui.design.theme.CytTheme

// DESIGN-SYSTEM 8.8. The drag handle: 32 x 4 dp, white 36% (outline.strong).
@Composable
fun SheetDragHandle(modifier: Modifier = Modifier) {
    Box(modifier.padding(top = 12.dp, bottom = 6.dp).size(width = 32.dp, height = 4.dp).background(CytTheme.colors.outlineStrong, CytTheme.shapes.pill))
}

// The Material 3 modal sheet in the brand: surface.2, shape.large top corners, the 64% scrim, the
// handle, 20 dp side padding. Used for Customise and See the list (D3), the dock picker, the
// address entry, the TV details and the confirmations. No true blur in 0.1.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CytBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = CytTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = CytTheme.shapes.sheet,
        containerColor = c.surface2,
        contentColor = c.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = c.scrim,
        dragHandle = { SheetDragHandle() },
    ) {
        Column(Modifier.fillMaxWidth().sheetTopRim().padding(horizontal = 20.dp).padding(bottom = 16.dp), content = content)
    }
}

// The sheet's look without the modal window, for inline use and screenshot tests: the same
// surface, corners, rim and handle.
@Composable
fun SheetSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = CytTheme.colors
    Column(
        modifier.fillMaxWidth().clip(CytTheme.shapes.sheet).background(c.surface2).sheetTopRim(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetDragHandle()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), content = content)
    }
}

// Sheet title (title.large) with an optional live total on the end ("22 of 23 off", with the
// number in text.primary) and one line of reassurance under it.
@Composable
fun SheetHeader(title: String, modifier: Modifier = Modifier, trailing: AnnotatedString? = null, subtitle: String? = null) {
    val c = CytTheme.colors
    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f).semantics { heading() }, color = c.textPrimary, style = CytTheme.type.titleLarge)
            if (trailing != null) Text(trailing, color = c.textSecondary, style = CytTheme.type.bodyMedium.copy(fontFeatureSettings = "tnum"))
        }
        if (subtitle != null) Text(subtitle, Modifier.padding(top = 4.dp), color = c.textSecondary, style = CytTheme.type.bodyMedium)
    }
}

// The 1 dp highlight line along the top edge of the sheet (inset box-shadow in the mockup).
private fun Modifier.sheetTopRim(): Modifier = this.drawBehind {
    drawLine(Color.White.copy(alpha = 0.2f), Offset(28.dp.toPx(), 0.5f), Offset(size.width - 28.dp.toPx(), 0.5f), strokeWidth = 1f)
}

