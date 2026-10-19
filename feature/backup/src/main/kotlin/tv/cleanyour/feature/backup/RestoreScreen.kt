package tv.cleanyour.feature.backup

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import tv.cleanyour.core.flows.RestorePhase
import tv.cleanyour.core.flows.RestorePointIntent
import tv.cleanyour.core.flows.RestorePointState

fun restoreDate(ms: Long, locale: java.util.Locale): String = SimpleDateFormat("d MMM, HH:mm", locale).format(Date(ms))

// M6 Restore point (UX 4.13, F10, DESIGN-SYSTEM 8.1): the preview always comes first; the one
// outlined danger button sits only inside its confirmation sheet; the export is a SAF zip.
@Composable
fun RestoreScreen(state: RestorePointState, onIntent: (RestorePointIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val baseline = state.baselineAtMs?.let { restoreDate(it, locale) }
    var factory by rememberSaveable { mutableStateOf(false) }
    PendingScreenScaffold(modifier, topBar = { PendingTopBar(onBack = onBack, backDescription = stringResource(R.string.action_back)) }) {
        PendingScreenTitle(stringResource(R.string.restore_title))
        when (val p = state.phase) {
            is RestorePhase.Working -> {
                PendingScreenTitle(stringResource(if (p.kind == "factory") R.string.restore_factory_working else R.string.restore_working_title))
                PendingProgressBar(p.percent / 100f)
                p.lines.takeLast(6).forEach { PendingCheckLine(it, done = false) }
                return@PendingScreenScaffold
            }
            is RestorePhase.Finished -> {
                if (p.kind == "factory") PendingBanner(stringResource(R.string.restore_factory_done), PendingBannerTone.SUCCESS)
                else {
                    PendingBanner(stringResource(R.string.restore_done_title), PendingBannerTone.SUCCESS)
                    PendingBody(msg(R.string.restore_done_body, "date" to restoreDate(p.atMs, locale)), color = MaterialTheme.colorScheme.onSurface)
                    PendingPrimaryButton(stringResource(R.string.restore_clean_again), { onIntent(RestorePointIntent.CleanAgain) })
                }
            }
            RestorePhase.Failed -> PendingBanner(stringResource(R.string.restore_read_failed), PendingBannerTone.WARNING)
            else -> {}
        }
        PendingLead(if (baseline != null) msg(R.string.restore_current, "date" to baseline) else stringResource(R.string.restore_none))
        PendingListGroup {
            PendingListRow(stringResource(R.string.restore_row_undo), subtitle = baseline?.let { msg(R.string.restore_row_undo_sub, "date" to it) },
                onClick = if (baseline != null) ({ onIntent(RestorePointIntent.PreviewUndo) }) else null)
            PendingListRow(stringResource(R.string.restore_row_save), subtitle = stringResource(R.string.restore_row_save_sub),
                onClick = if (baseline != null && !state.exporting) ({ onIntent(RestorePointIntent.SaveCopy) }) else null)
            if (state.googleTv) PendingListRow(stringResource(R.string.restore_row_factory), subtitle = stringResource(R.string.restore_row_factory_sub), onClick = { factory = true })
        }
        if (state.exporting) PendingSpinner()
    }
    val phase = state.phase
    if (phase is RestorePhase.Previewing) PendingBottomSheet({ onIntent(RestorePointIntent.Cancel) }) { PendingSpinner(); PendingGap() }
    if (phase is RestorePhase.Preview) {
        val pv = phase.preview
        PendingBottomSheet({ onIntent(RestorePointIntent.Cancel) }) {
            if (!phase.confirming) {
                PendingSheetHeader(msg(R.string.restore_preview_title, "date" to (baseline ?: "")))
                PendingCheckLine(msg(R.string.restore_preview_things, "count" to pv.thingsBack), done = false)
                pv.stockName?.let { PendingCheckLine(msg(R.string.restore_preview_home, "name" to it), done = false) }
                if (pv.wizardBack) PendingCheckLine(stringResource(R.string.restore_preview_wizard), done = false)
                PendingOverline(stringResource(R.string.restore_launcher_title))
                PendingListGroup {
                    PendingListRow(stringResource(R.string.restore_launcher_keep), value = if (!pv.removeLauncher) "✓" else null, onClick = { onIntent(RestorePointIntent.RemoveLauncher(false)) })
                    PendingListRow(stringResource(R.string.restore_launcher_remove), subtitle = stringResource(R.string.restore_launcher_remove_note),
                        value = if (pv.removeLauncher) "✓" else null, onClick = { onIntent(RestorePointIntent.RemoveLauncher(true)) })
                }
                PendingSecondaryButton(stringResource(R.string.restore_preview_button), { onIntent(RestorePointIntent.Confirm) })
            } else {
                // The one red button in the app (UX 4.13): outlined, inside its own sheet.
                PendingSheetHeader(stringResource(R.string.restore_confirm_title))
                PendingBody(stringResource(R.string.restore_confirm_body), color = PendingTokens.danger)
                PendingDestructiveButton(stringResource(R.string.restore_confirm_button), { onIntent(RestorePointIntent.PutEverythingBack) })
                PendingTertiaryButton(stringResource(R.string.action_cancel), { onIntent(RestorePointIntent.Cancel) })
            }
            PendingGap()
        }
    }
    if (factory) PendingBottomSheet({ factory = false }) {
        PendingSheetHeader(stringResource(R.string.restore_factory_title))
        PendingBody(stringResource(R.string.restore_factory_body), color = MaterialTheme.colorScheme.onSurface)
        PendingPrimaryButton(stringResource(R.string.restore_factory_button), { onIntent(RestorePointIntent.BeforeFactoryReset); factory = false })
        PendingTertiaryButton(stringResource(R.string.action_cancel), { factory = false })
        PendingGap()
    }
}
