package tv.cleanyour.feature.launcher

import androidx.compose.material3.MaterialTheme
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
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.Busy
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.model.PackageName

// M2 Home screen: Dock (S3b), Look (theme picker), "Use the TV's own home screen" with its preview,
// and the drift banner with "Restore mine" (D15).
@Composable
fun HomeScreen(state: HomeScreenState, onIntent: (HomeScreenIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var dockOpen by rememberSaveable { mutableStateOf(false) }
    var lookOpen by rememberSaveable { mutableStateOf(false) }
    PendingScreenScaffold(modifier, topBar = { PendingTopBar(onBack = onBack, backDescription = stringResource(R.string.action_back)) }) {
        PendingScreenTitle(stringResource(R.string.home_title))
        if (state.drift) PendingBanner(stringResource(R.string.home_drift_banner), PendingBannerTone.WARNING,
            actionLabel = stringResource(R.string.home_drift_restore), onAction = { onIntent(HomeScreenIntent.RestoreMine) })
        val busy = state.busy
        if (busy != null) {
            PendingLiveLine(stringResource(R.string.home_applying), found = false)
            if (busy is Busy.Working) PendingProgressBar(busy.percent / 100f)
        }
        PendingListGroup {
            PendingListRow(stringResource(R.string.home_row_dock), subtitle = state.dock.joinToString(", ") { it.name }.ifEmpty { null },
                onClick = if (busy == null) ({ dockOpen = true }) else null)
            val theme = state.themes.firstOrNull { it.id == state.themeId }?.name ?: stringResource(R.string.theme_default)
            PendingListRow(stringResource(R.string.home_row_look), subtitle = theme, onClick = if (busy == null) ({ lookOpen = true }) else null)
        }
        if (state.stock != null && state.homeScreenOurs) PendingListGroup {
            PendingListRow(stringResource(R.string.home_row_stock), subtitle = stringResource(R.string.home_row_stock_note),
                onClick = if (busy == null) ({ onIntent(HomeScreenIntent.PreviewStock) }) else null)
        }
    }
    if (dockOpen) DockSheet(state.dock, state.apps, onDone = { onIntent(HomeScreenIntent.SetDock(it)); dockOpen = false }, onDismiss = { dockOpen = false })
    if (lookOpen) PendingBottomSheet({ lookOpen = false }) {
        PendingSheetHeader(stringResource(R.string.theme_title))
        PendingListGroup {
            val descriptions = mapOf("default" to R.string.theme_default_desc, "aurora" to R.string.theme_aurora_desc, "six-columns" to R.string.theme_six_columns_desc)
            state.themes.forEach { t ->
                val selected = t.id == state.themeId || (state.themeId == null && t.id == "default")
                val desc = descriptions[t.id]?.let { stringResource(it) } ?: t.description
                val cd = msg(if (selected) R.string.theme_card_selected_cd else R.string.theme_card_cd, "name" to t.name, "description" to desc)
                PendingListRow(t.name, Modifier.semantics { contentDescription = cd }, subtitle = desc, value = if (selected) "✓" else null,
                    onClick = { onIntent(HomeScreenIntent.SetTheme(t.id)); lookOpen = false })
            }
        }
        PendingGap()
    }
    state.stockPreview?.let { plan ->
        PendingBottomSheet({ onIntent(HomeScreenIntent.CancelPreview) }) {
            PendingSheetHeader(stringResource(R.string.home_stock_preview_title))
            PendingBody(stringResource(R.string.home_stock_preview_body), color = MaterialTheme.colorScheme.onSurface)
            plan.steps.map { it.narration }.distinct().forEach { PendingCheckLine(it, done = false) }
            PendingPrimaryButton(stringResource(R.string.home_stock_confirm), { onIntent(HomeScreenIntent.UseStock) })
            PendingTertiaryButton(stringResource(R.string.action_cancel), { onIntent(HomeScreenIntent.CancelPreview) })
            PendingGap()
        }
    }
}

// S3b on Manage: up to four, ordered with up and down buttons (UX 7.1 motor rule).
@Composable
fun DockSheet(current: List<AppChoice>, apps: List<AppChoice>, onDone: (List<PackageName>) -> Unit, onDismiss: () -> Unit) {
    var dock by remember(current) { mutableStateOf(current) }
    PendingBottomSheet(onDismiss) {
        PendingSheetHeader(stringResource(R.string.dock_title), subtitle = stringResource(R.string.dock_body))
        PendingOverline(stringResource(R.string.dock_section_in))
        PendingListGroup {
            dock.forEachIndexed { i, app ->
                val inCd = msg(R.string.dock_item_in_cd, "name" to app.name, "position" to i + 1)
                val upCd = msg(R.string.dock_move_up_cd, "name" to app.name)
                val downCd = msg(R.string.dock_move_down_cd, "name" to app.name)
                val outCd = msg(R.string.dock_remove_cd, "name" to app.name)
                PendingListRow(app.name, Modifier.semantics { contentDescription = inCd }, trailing = {
                    if (i > 0) PendingTertiaryButton("▲", { dock = dock.toMutableList().apply { add(i - 1, removeAt(i)) } }, Modifier.semantics { contentDescription = upCd })
                    if (i < dock.size - 1) PendingTertiaryButton("▼", { dock = dock.toMutableList().apply { add(i + 1, removeAt(i)) } }, Modifier.semantics { contentDescription = downCd })
                    PendingTertiaryButton("−", { dock = dock - app }, Modifier.semantics { contentDescription = outCd })
                })
            }
        }
        if (dock.size >= 4) PendingBody(stringResource(R.string.dock_full_note))
        PendingOverline(stringResource(R.string.dock_section_other))
        PendingListGroup {
            apps.filter { a -> dock.none { it.pkg == a.pkg } }.forEach { app ->
                val addCd = msg(R.string.dock_add_cd, "name" to app.name)
                PendingListRow(app.name, Modifier.semantics { contentDescription = addCd }, onClick = if (dock.size < 4) ({ dock = dock + app }) else null)
            }
        }
        PendingPrimaryButton(stringResource(R.string.action_done), { onDone(dock.map { it.pkg }) })
        PendingGap()
    }
}
