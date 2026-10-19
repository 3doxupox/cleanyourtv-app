@file:OptIn(ExperimentalMaterial3Api::class)

package tv.cleanyour.feature.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.Busy
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.ui.design.component.ActionColumn
import tv.cleanyour.ui.design.component.Banner
import tv.cleanyour.ui.design.component.BannerTone
import tv.cleanyour.ui.design.component.CheckLine
import tv.cleanyour.ui.design.component.CheckState
import tv.cleanyour.ui.design.component.CytBottomSheet
import tv.cleanyour.ui.design.component.CytIcon
import tv.cleanyour.ui.design.component.CytIconButton
import tv.cleanyour.ui.design.component.CytTopBar
import tv.cleanyour.ui.design.component.DockRow
import tv.cleanyour.ui.design.component.ListDivider
import tv.cleanyour.ui.design.component.ListGroup
import tv.cleanyour.ui.design.component.ListRow
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.ProgressBar
import tv.cleanyour.ui.design.component.ScreenScaffold
import tv.cleanyour.ui.design.component.ScreenTitle
import tv.cleanyour.ui.design.component.SheetHeader
import tv.cleanyour.ui.design.component.TertiaryButton
import tv.cleanyour.ui.design.component.ThemeOption
import tv.cleanyour.ui.design.component.ThemePicker
import tv.cleanyour.ui.design.component.themeSwatch
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.theme.CytTheme
import tv.cleanyour.ui.design.theme.Glow

// M2 Home screen: Dock (S3b), Look (theme picker), "Use the TV's own home screen" with its preview,
// and the drift banner with "Restore mine" (D15). Each sheet keeps its body in an internal *Content
// composable, so the screenshot tests can draw it on SheetSurface.
@Composable
fun HomeScreen(state: HomeScreenState, onIntent: (HomeScreenIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var dockOpen by rememberSaveable { mutableStateOf(false) }
    var lookOpen by rememberSaveable { mutableStateOf(false) }
    val busy = state.busy
    ScreenScaffold(Glow.Tv, modifier, topBar = { CytTopBar(onBack = onBack) }) {
        ScreenTitle(stringResource(R.string.home_title), Modifier.padding(top = 20.dp))
        if (state.drift) {
            Banner(stringResource(R.string.home_drift_banner), BannerTone.WARNING, Modifier.padding(top = 16.dp),
                actionLabel = stringResource(R.string.home_drift_restore), onAction = { onIntent(HomeScreenIntent.RestoreMine) }, guardAction = true)
        }
        if (busy != null) {
            LiveLine(stringResource(R.string.home_applying))
            if (busy is Busy.Working) ProgressBar(busy.percent / 100f, Modifier.padding(top = 16.dp))
        }
        ListGroup(Modifier.padding(top = 20.dp)) {
            ListRow(stringResource(R.string.home_row_dock), subtitle = state.dock.joinToString(", ") { it.name }.ifEmpty { null },
                onClick = if (busy == null) ({ dockOpen = true }) else null)
            ListDivider()
            val theme = state.themes.firstOrNull { it.id == state.themeId }?.name ?: stringResource(R.string.theme_default)
            ListRow(stringResource(R.string.home_row_look), subtitle = theme, onClick = if (busy == null) ({ lookOpen = true }) else null)
        }
        if (state.stock != null && state.homeScreenOurs) {
            ListGroup(Modifier.padding(top = 12.dp)) {
                ListRow(stringResource(R.string.home_row_stock), subtitle = stringResource(R.string.home_row_stock_note),
                    onClick = if (busy == null) ({ onIntent(HomeScreenIntent.PreviewStock) }) else null)
            }
        }
    }
    if (dockOpen) DockSheet(state.dock, state.apps, onDone = { onIntent(HomeScreenIntent.SetDock(it)); dockOpen = false }, onDismiss = { dockOpen = false })
    if (lookOpen) CytBottomSheet({ lookOpen = false }) {
        LookContent(state) { onIntent(HomeScreenIntent.SetTheme(it)); lookOpen = false }
    }
    state.stockPreview?.let { plan ->
        CytBottomSheet({ onIntent(HomeScreenIntent.CancelPreview) }) { StockPreviewContent(plan, onIntent) }
    }
}

// The three launcher themes with their one-line descriptions; the preview tile colours come from
// :ui:design (themeSwatch), shared with the wizard's picker.
private val themeDescriptions = mapOf(
    "default" to R.string.theme_default_desc,
    "aurora" to R.string.theme_aurora_desc,
    "six-columns" to R.string.theme_six_columns_desc,
)

// A tap here applies the look to the TV, so the rows are guarded like a primary (R4-W8).
@Composable
internal fun ColumnScope.LookContent(state: HomeScreenState, onSelect: (String) -> Unit) {
    SheetHeader(stringResource(R.string.theme_title))
    val options = state.themes.map { t ->
        ThemeOption(t.id, t.name, themeDescriptions[t.id]?.let { stringResource(it) } ?: t.description, themeSwatch(t.id))
    }
    ThemePicker(options, state.themeId ?: "default", { onSelect(it.id) }, Modifier.padding(top = 16.dp), guardObscured = true)
}

@Composable
internal fun ColumnScope.StockPreviewContent(plan: Plan, onIntent: (HomeScreenIntent) -> Unit) {
    SheetHeader(stringResource(R.string.home_stock_preview_title))
    Body(stringResource(R.string.home_stock_preview_body), Modifier.padding(top = 8.dp))
    Column(Modifier.padding(top = 16.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.steps.map { it.narration }.distinct().forEach { CheckLine(it, state = CheckState.NONE) }
    }
    ActionColumn(Modifier.padding(top = 20.dp)) {
        PrimaryButton(stringResource(R.string.home_stock_confirm), { onIntent(HomeScreenIntent.UseStock) })
        TertiaryButton(stringResource(R.string.action_cancel), { onIntent(HomeScreenIntent.CancelPreview) }, quiet = true)
    }
}

// S3b on Manage: up to four, ordered with up and down buttons (UX 7.1 motor rule).
@Composable
fun DockSheet(current: List<AppChoice>, apps: List<AppChoice>, onDone: (List<PackageName>) -> Unit, onDismiss: () -> Unit) {
    var dock by remember(current) { mutableStateOf(current) }
    CytBottomSheet(onDismiss) { DockContent(dock, apps, { dock = it }, { onDone(dock.map { it.pkg }) }) }
}

@Composable
internal fun ColumnScope.DockContent(dock: List<AppChoice>, apps: List<AppChoice>, onChange: (List<AppChoice>) -> Unit, onDone: () -> Unit) {
    SheetHeader(stringResource(R.string.dock_title), subtitle = stringResource(R.string.dock_body))
    SectionLabel(stringResource(R.string.dock_section_in))
    ListGroup {
        dock.forEachIndexed { i, app ->
            if (i > 0) ListDivider()
            val inCd = msg(R.string.dock_item_in_cd, "name" to app.name, "position" to i + 1)
            DockRow(app.name, Modifier.semantics { contentDescription = inCd }, controls = {
                Row {
                    if (i > 0) CytIconButton(CytIcons.ArrowUp, msg(R.string.dock_move_up_cd, "name" to app.name),
                        { onChange(dock.toMutableList().apply { add(i - 1, removeAt(i)) }) })
                    if (i < dock.size - 1) CytIconButton(CytIcons.ArrowDown, msg(R.string.dock_move_down_cd, "name" to app.name),
                        { onChange(dock.toMutableList().apply { add(i + 1, removeAt(i)) }) })
                    CytIconButton(CytIcons.Minus, msg(R.string.dock_remove_cd, "name" to app.name), { onChange(dock - app) })
                }
            })
        }
    }
    if (dock.size >= 4) Body(stringResource(R.string.dock_full_note), Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp))
    SectionLabel(stringResource(R.string.dock_section_other))
    ListGroup {
        apps.filter { a -> dock.none { it.pkg == a.pkg } }.forEachIndexed { i, app ->
            if (i > 0) ListDivider()
            val full = dock.size >= 4
            val cd = if (full) msg(R.string.dock_item_out_cd, "name" to app.name) else msg(R.string.dock_add_cd, "name" to app.name)
            DockRow(app.name, Modifier.semantics { contentDescription = cd },
                controls = if (full) null else ({ CytIcon(CytIcons.Plus, null, tint = CytTheme.colors.accent) }),
                onClick = if (full) null else ({ onChange(dock + app) }))
        }
    }
    PrimaryButton(stringResource(R.string.action_done), onDone, Modifier.padding(top = 20.dp))
}
