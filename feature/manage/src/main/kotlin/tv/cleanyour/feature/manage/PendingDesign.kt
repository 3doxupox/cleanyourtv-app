package tv.cleanyour.feature.manage

// Thin local stand-ins for W7's :ui:design components (ui/design/API.md). Each Pending<Component>
// has the parameters of the W7 component of the same name, so the swap at merge is mechanical:
// delete this file, drop the "Pending" prefix, import tv.cleanyour.ui.design.component.*.
// TODO(W7 merge): replace every Pending* below with the :ui:design component named in its comment.

import android.graphics.BitmapFactory
import android.os.Build
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.cleanyour.core.flows.ChipState

// ---- copy: ICU MessageFormat with named arguments (strings.xml rule) ----

@Composable
fun msg(id: Int, vararg args: Pair<String, Any>): String {
    val pattern = stringResource(id)
    if (args.isEmpty()) return pattern
    val locale = LocalConfiguration.current.locales[0]
    return remember(pattern, locale, args.toList()) {
        android.icu.text.MessageFormat(pattern, locale).format(args.toMap())
    }
}

// ---- tokens (TODO(W7 merge): CytTheme.colors) ----

object PendingTokens {
    val success = Color(0xFF30D158)
    val warning = Color(0xFFFFB340)
    val danger = Color(0xFFFF6961)
    val gutter = 20.dp
}

// ---- obscured-touch guard (SECURITY 4.3, D35): a confirming button ignores taps that reach it
// through another app's overlay. TODO(W7 merge): keep in :ui:design or here, W7 has no equivalent.
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.rejectObscuredTouches(): Modifier = pointerInteropFilter { e ->
    val obscured = (e.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0 ||
        (Build.VERSION.SDK_INT >= 29 && (e.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0)
    obscured   // true consumes (drops) the event, false lets the button see it
}

// ---- layout (TODO(W7 merge): ScreenScaffold, CytTopBar, ScreenTitle, Lead, Overline, StepCounter) ----

@Composable
fun PendingScreenScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    overlay: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            topBar()
            Column(
                Modifier.weight(1f).fillMaxWidth().let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
                    .padding(horizontal = PendingTokens.gutter),
                content = content,
            )
            if (bottomBar != null) Column(
                Modifier.fillMaxWidth().padding(horizontal = PendingTokens.gutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = bottomBar,
            )
        }
        overlay()
    }
}

@Composable
fun PendingTopBar(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, backDescription: String = "", center: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp)) {
        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).semantics { contentDescription = backDescription }) { Text("<") }
        if (center != null) Box(Modifier.align(Alignment.Center)) { center() }
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

@Composable
fun PendingScreenTitle(text: String, modifier: Modifier = Modifier, display: Boolean = false) {
    Text(text, modifier.padding(top = 8.dp, bottom = 8.dp).semantics { heading() },
        style = if (display) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground)
}

@Composable
fun PendingLead(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun PendingBody(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
fun PendingOverline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(LocalConfiguration.current.locales[0]), modifier.padding(top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun PendingStepCounter(text: String, modifier: Modifier = Modifier) = PendingOverline(text, modifier)

@Composable
fun PendingGap(height: Int = 16) = Spacer(Modifier.height(height.dp))

// ---- actions (TODO(W7 merge): PrimaryButton, SecondaryButton, TertiaryButton, DestructiveButton) ----

@Composable
fun PendingPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(onClick = onClick, enabled = enabled && !loading, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).rejectObscuredTouches(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
        if (loading) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PendingSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).rejectObscuredTouches()) { Text(text) }
}

@Composable
fun PendingTertiaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, quiet: Boolean = false) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp).rejectObscuredTouches()) {
        Text(text, color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
fun PendingDestructiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, border = BorderStroke(1.5.dp, PendingTokens.danger),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).rejectObscuredTouches()) { Text(text, color = PendingTokens.danger) }
}

// ---- status (TODO(W7 merge): TvStatusChip, DiscoveryChip, Banner, StopCard, Spinner, ProgressBar) ----

@Composable
fun PendingTvStatusChip(label: String, status: ChipState, modifier: Modifier = Modifier) {
    val dot = when (status) {
        ChipState.CONNECTED -> PendingTokens.success
        ChipState.WAITING -> PendingTokens.warning
        else -> Color.Transparent
    }
    Row(modifier.clip28().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        if (status == ChipState.WORKING) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
        else Box(Modifier.size(8.dp).background(dot, CircleShape).border(1.dp, if (dot == Color.Transparent) MaterialTheme.colorScheme.onSurfaceVariant else dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun Modifier.clip28(): Modifier = this

enum class PendingBannerTone { SUCCESS, WARNING, NEUTRAL, INFO }

@Composable
fun PendingBanner(text: String, tone: PendingBannerTone, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) {
    val c = when (tone) { PendingBannerTone.SUCCESS -> PendingTokens.success; PendingBannerTone.WARNING -> PendingTokens.warning; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Surface(modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, c.copy(alpha = 0.5f))) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (actionLabel != null) PendingTertiaryButton(actionLabel, onAction)
        }
    }
}

@Composable
fun PendingStopCard(
    title: String, body: String, modifier: Modifier = Modifier,
    primaryLabel: String? = null, onPrimary: () -> Unit = {},
    secondaryLabel: String? = null, onSecondary: () -> Unit = {},
    tertiaryLabel: String? = null, onTertiary: () -> Unit = {},
    detailsLabel: String? = null, onDetails: () -> Unit = {},
    busyText: String? = null,
) {
    Surface(modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busyText != null) Row(verticalAlignment = Alignment.CenterVertically) { PendingSpinner(); Spacer(Modifier.width(8.dp)); Text(busyText, style = MaterialTheme.typography.bodyMedium) }
            if (primaryLabel != null) PendingPrimaryButton(primaryLabel, onPrimary)
            if (secondaryLabel != null) PendingSecondaryButton(secondaryLabel, onSecondary)
            if (tertiaryLabel != null) PendingTertiaryButton(tertiaryLabel, onTertiary)
            if (detailsLabel != null) PendingTertiaryButton(detailsLabel, onDetails, quiet = true)
        }
    }
}

@Composable
fun PendingSpinner(modifier: Modifier = Modifier) = CircularProgressIndicator(modifier.size(16.dp), strokeWidth = 2.dp)

@Composable
fun PendingProgressBar(progress: Float, modifier: Modifier = Modifier) =
    LinearProgressIndicator(progress = { progress }, modifier = modifier.fillMaxWidth().height(4.dp))

@Composable
fun PendingLiveLine(text: String, found: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        if (found) Box(Modifier.size(8.dp).background(PendingTokens.success, CircleShape)) else PendingSpinner()
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---- cards and rows (TODO(W7 merge): StepCard, ListGroup, ListRow, SwitchRow, CheckLine, InfoChip, SafetyLine) ----

@Composable
fun PendingStepCard(title: String, body: String, modifier: Modifier = Modifier, overline: String? = null, current: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    Surface(modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (current) MaterialTheme.colorScheme.outline else Color.Transparent)) {
        Column(Modifier.padding(20.dp)) {
            if (overline != null) PendingOverline(overline)
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (trailing != null) trailing()
        }
    }
}

@Composable
fun PendingListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
fun PendingListRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, value: String? = null, onClick: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) trailing()
        else if (onClick != null) Text(" >", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PendingSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, warning: Boolean = false, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled) { onCheckedChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (warning) PendingTokens.warning else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun PendingCheckLine(text: String, modifier: Modifier = Modifier, done: Boolean = true) {
    Row(modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (done) "✓" else "○", color = if (done) PendingTokens.success else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun PendingInfoChip(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun PendingSafetyLine(text: String, modifier: Modifier = Modifier) = PendingBody(text, modifier)

// D2: the Off / Kept pill states the outcome in a word. TODO(W7 merge): OffKeptPill, DiffRow.
@Composable
fun PendingOffKeptPill(off: Boolean, offLabel: String, keptLabel: String, modifier: Modifier = Modifier) {
    Text(if (off) offLabel else keptLabel,
        modifier.border(1.dp, if (off) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.tertiary, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
        color = if (off) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
}

@Composable
fun PendingDiffRow(name: String, description: String?, packageName: String?, off: Boolean, offLabel: String, keptLabel: String, onToggle: (() -> Unit)?, modifier: Modifier = Modifier, stateDescription: String = "") {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
        .semantics { if (stateDescription.isNotEmpty()) contentDescription = stateDescription }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (packageName != null) PendingLtrText(packageName)
        }
        if (onToggle != null) PendingOffKeptPill(off, offLabel, keptLabel)
    }
}

@Composable
fun PendingLtrText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- sheet and field (TODO(W7 merge): CytBottomSheet, SheetHeader, CytTextField, MonospaceBlock) ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingBottomSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = PendingTokens.gutter, vertical = 8.dp), content = content)
    }
}

@Composable
fun PendingSheetHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null, subtitle: String? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
    }
    if (subtitle != null) PendingBody(subtitle)
}

@Composable
fun PendingTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: String? = null, numeric: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, isError = error != null, singleLine = true,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text, autoCorrectEnabled = false),
        modifier = modifier.fillMaxWidth())
}

@Composable
fun PendingMonospaceBlock(lines: List<String>, modifier: Modifier = Modifier, copyLabel: String? = null, onCopy: (() -> Unit)? = null) {
    Surface(modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(12.dp)) {
            SelectionContainer {
                Column { lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.onSurface) } }
            }
            if (copyLabel != null && onCopy != null) PendingTertiaryButton(copyLabel, onCopy)
        }
    }
}

// ---- the TV on the phone (TODO(W7 merge): TvThumbnail, CountdownRing, AdbAllowMirror, CardPromptMirror, CodeMatchCard) ----

@Composable
fun PendingTvThumbnail(png: ByteArray?, contentDescription: String, modifier: Modifier = Modifier, label: String? = null) {
    val image: ImageBitmap? = remember(png) { png?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF111114), RoundedCornerShape(20.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .semantics { this.contentDescription = contentDescription }, contentAlignment = Alignment.Center) {
            if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else PendingSpinner()
        }
        if (label != null) Text(label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun pendingFormatCountdown(remainingMs: Long): String {
    val s = ((remainingMs.coerceAtLeast(0) + 999) / 1000).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
fun PendingCountdownRing(remainingMs: Long, totalMs: Long, modifier: Modifier = Modifier, description: String = "") {
    Box(modifier.size(64.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { if (totalMs <= 0) 0f else (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(), strokeWidth = 5.dp,
            color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        Text(pendingFormatCountdown(remainingMs), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun PendingAdbAllowMirror(title: String, fingerprintLabel: String, fingerprint: String, alwaysLabel: String, cancelLabel: String, okLabel: String, modifier: Modifier = Modifier, contentDescription: String = "") {
    Surface(modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { this.contentDescription = contentDescription }, shape = RoundedCornerShape(16.dp), color = Color(0xFF263238)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(fingerprintLabel, color = Color(0xFFCFD8DC), style = MaterialTheme.typography.bodySmall)
            Text(fingerprint, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
            Text("☑ $alwaysLabel", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                Text(cancelLabel, color = Color(0xFF80CBC4)); Spacer(Modifier.width(24.dp)); Text(okLabel, color = Color(0xFF80CBC4))
            }
        }
    }
}

@Composable
fun PendingCardPromptMirror(title: String, packageName: String, keyLine: String, keepLabel: String, showLabel: String, modifier: Modifier = Modifier, contentDescription: String = "") {
    Surface(modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { this.contentDescription = contentDescription }, shape = RoundedCornerShape(16.dp), color = Color(0xFF15151A)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(packageName, color = Color(0xFFB0B0B8), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
            Text(keyLine, color = Color.White, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(keepLabel, color = Color.White); Text(showLabel, color = Color(0xFF8C9BFF))
            }
        }
    }
}

// Haptics come from the app (effects); this is only the chip-word helper shared by every screen.
@Composable
fun chipWord(status: ChipState, connected: Int, waiting: Int, working: Int, debuggingOff: Int, offline: Int): String = stringResource(
    when (status) {
        ChipState.CONNECTED -> connected
        ChipState.WAITING -> waiting
        ChipState.WORKING -> working
        ChipState.DEBUGGING_OFF -> debuggingOff
        ChipState.OFFLINE -> offline
    },
)

@Composable
fun rememberPng(bytes: ByteArray?): ImageBitmap? = remember(bytes) { bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }

@Composable
fun isRtlPseudo(): Boolean = LocalContext.current.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
