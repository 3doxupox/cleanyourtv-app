package tv.cleanyour.feature.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.text.SimpleDateFormat
import java.util.Date
import tv.cleanyour.core.flows.AppConfirm
import tv.cleanyour.core.flows.AppSettingsIntent
import tv.cleanyour.core.flows.AppSettingsState
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.LeftoversIntent
import tv.cleanyour.core.flows.LeftoversState
import tv.cleanyour.core.flows.ManageIntent
import tv.cleanyour.core.flows.ManageSection
import tv.cleanyour.core.flows.ManageState
import tv.cleanyour.core.flows.Reach
import tv.cleanyour.core.flows.SwitchedOffIntent
import tv.cleanyour.core.flows.SwitchedOffState
import tv.cleanyour.core.flows.TvSettingsIntent
import tv.cleanyour.core.flows.TvSettingsState
import tv.cleanyour.core.flows.UpdateCheck
import tv.cleanyour.core.model.Level

// Screens M1, M1a, M4, M5, M7 and M8 (PRODUCT-SPEC 4, UX 4.3, 4.11, 4.13, 6.1).

fun shortDate(ms: Long, locale: java.util.Locale): String = SimpleDateFormat("d MMM", locale).format(Date(ms))
fun shortDateTime(ms: Long, locale: java.util.Locale): String = SimpleDateFormat("d MMM, HH:mm", locale).format(Date(ms))

@Composable
private fun chip(name: String, status: ChipState): String = msg(R.string.chip_label, "tv" to name, "state" to chipWord(status,
    R.string.chip_connected, R.string.chip_waiting, R.string.chip_working, R.string.chip_debugging_off, R.string.chip_offline))

// M1 Manage: the app's home once a TV exists. Nothing is more than two taps away.
@Composable
fun ManageScreen(state: ManageState, onIntent: (ManageIntent) -> Unit, modifier: Modifier = Modifier) {
    val tv = state.current
    val locale = LocalConfiguration.current.locales[0]
    var levels by rememberSaveable { mutableStateOf(false) }
    PendingScreenScaffold(modifier, topBar = {
        PendingTopBar(center = { if (tv != null) PendingTvStatusChip(chip(tv.name, tv.chip), tv.chip) },
            trailing = {
                val cd = stringResource(R.string.manage_settings_cd)
                PendingTertiaryButton("⚙", { onIntent(ManageIntent.AppSettings) }, Modifier.semantics { contentDescription = cd })
            })
    }) {
        PendingScreenTitle(stringResource(R.string.manage_title))
        if (state.offline) PendingBanner(stringResource(R.string.manage_offline_banner), PendingBannerTone.WARNING,
            actionLabel = stringResource(if (state.reconnecting) R.string.manage_reconnecting else R.string.manage_reconnect), onAction = { onIntent(ManageIntent.Reconnect) })
        state.cards.filter { it.id != tv?.id }.forEach { other ->
            PendingListRow(other.name, subtitle = other.room, onClick = { onIntent(ManageIntent.Select(other.id)) })
        }
        if (tv != null) {
            val stateWord = stringResource(if (tv.clean) R.string.manage_state_clean else R.string.manage_state_original)
            val checked = msg(R.string.manage_checked, "date" to shortDate(tv.checkedAtMs, locale))
            PendingListGroup {
                val room = tv.room
                PendingListRow(if (room != null) msg(R.string.manage_tv_title, "tv" to tv.name, "room" to room) else msg(R.string.manage_tv_title_noroom, "tv" to tv.name),
                    subtitle = "$stateWord. $checked", trailing = {
                        PendingInfoChip(stringResource(if (tv.level == Level.CUSTOM) R.string.manage_level_custom else R.string.manage_level_simple),
                            Modifier.clickable(role = Role.Button) { levels = true })
                    })
                PendingTvThumbnail(tv.thumbnail, msg(R.string.manage_thumbnail_cd, "tv" to tv.name), Modifier.clickable { onIntent(ManageIntent.RefreshThumbnail) })
            }
            if (!tv.clean) PendingPrimaryButton(stringResource(R.string.manage_clean_again), { onIntent(ManageIntent.CleanAgain) })
            PendingListGroup {
                PendingListRow(stringResource(R.string.manage_row_home), subtitle = tv.dockNames.joinToString(", ").ifEmpty { null }, onClick = { onIntent(ManageIntent.Open(ManageSection.HOME_SCREEN)) })
                PendingListRow(stringResource(R.string.manage_row_cards), onClick = { onIntent(ManageIntent.Open(ManageSection.MODULES)) })
                PendingListRow(msg(R.string.manage_row_switched_off, "count" to tv.switchedOff), subtitle = stringResource(R.string.manage_row_switched_off_sub),
                    onClick = { onIntent(ManageIntent.Open(ManageSection.SWITCHED_OFF)) })
                PendingListRow(stringResource(R.string.manage_row_leftovers), subtitle = stringResource(R.string.manage_row_leftovers_sub), onClick = { onIntent(ManageIntent.Open(ManageSection.LEFTOVERS)) })
                PendingListRow(stringResource(R.string.manage_row_restore), subtitle = tv.restorePointAtMs?.let { msg(R.string.manage_row_restore_sub, "date" to shortDateTime(it, locale)) },
                    onClick = { onIntent(ManageIntent.Open(ManageSection.RESTORE_POINT)) })
                PendingListRow(stringResource(R.string.manage_row_tvsettings), onClick = { onIntent(ManageIntent.Open(ManageSection.TV_SETTINGS)) })
            }
        }
        PendingTertiaryButton(stringResource(R.string.manage_add_tv), { onIntent(ManageIntent.SetUpAnother) })
    }
    if (levels) PendingBottomSheet({ levels = false }) {
        PendingSheetHeader(stringResource(R.string.manage_level_sheet_title))
        PendingListRow(stringResource(R.string.manage_level_simple), subtitle = stringResource(R.string.manage_level_simple_note))
        PendingListRow(stringResource(R.string.manage_level_custom), subtitle = stringResource(R.string.manage_level_custom_note))
        PendingGap()
    }
}

// M1a: the debugging step for a returning write; it advances on its own when the port opens
// (UX 4.3). The app lays it over whatever screen asked (TvAccess.reach).
@Composable
fun DebugStepSheet(reach: Reach, onCancel: () -> Unit) {
    val name = when (reach) {
        is Reach.NeedsDebugging -> reach.tvName
        is Reach.Connecting -> reach.tvName
        is Reach.AwaitingAllow -> reach.tvName
        is Reach.WrongNetwork -> reach.tvName
        Reach.Idle -> return
    }
    PendingBottomSheet(onCancel) {
        PendingTvStatusChip(chip(name, if (reach is Reach.Connecting) ChipState.CONNECTED else ChipState.WAITING), ChipState.WAITING)
        PendingStepCard(stringResource(R.string.debugstep_title), stringResource(R.string.debugstep_body))
        PendingBody(stringResource(R.string.debugstep_note))
        PendingLiveLine(stringResource(if (reach is Reach.Connecting) R.string.debugstep_connecting else R.string.debugstep_waiting), found = false)
        PendingTertiaryButton(stringResource(R.string.action_cancel), onCancel, Modifier.align(Alignment.CenterHorizontally), quiet = true)
        PendingGap()
    }
}

// M4 Switched off: "Put back" is one op with a 5 s undo snackbar (the app shows the snackbar).
@Composable
fun SwitchedOffScreen(state: SwitchedOffState, onIntent: (SwitchedOffIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PendingScreenScaffold(modifier, topBar = { PendingTopBar(onBack = onBack, backDescription = stringResource(R.string.action_back)) }) {
        PendingScreenTitle(msg(R.string.switchedoff_title, "count" to state.count))
        if (state.loading) { PendingSpinner(); return@PendingScreenScaffold }
        if (state.groups.isEmpty()) {
            PendingLead(stringResource(R.string.switchedoff_empty))
            PendingBody(stringResource(R.string.switchedoff_empty_sub))
        } else PendingLead(stringResource(R.string.switchedoff_intro))
        state.groups.forEach { g ->
            PendingOverline(g.title.ifEmpty { stringResource(R.string.switchedoff_group_other) })
            PendingListGroup {
                g.rows.forEach { r ->
                    val cd = msg(R.string.switchedoff_put_back_cd, "name" to r.name)
                    PendingListRow(r.name, subtitle = r.description, trailing = {
                        if (state.busy == r.pkg) PendingSpinner()
                        else PendingTertiaryButton(stringResource(R.string.switchedoff_put_back), { onIntent(SwitchedOffIntent.PutBack(r.pkg)) },
                            Modifier.semantics { contentDescription = cd }, enabled = state.busy == null)
                    })
                }
            }
        }
        // Custom adds the ask groups with Off / Kept pills (D2).
        state.askGroups.forEach { g ->
            PendingOverline(g.title)
            PendingListGroup {
                g.rows.forEach { r ->
                    PendingDiffRow(r.name, r.description, r.pkg.value, r.off, stringResource(R.string.switchedoff_pill_off), stringResource(R.string.switchedoff_pill_kept),
                        onToggle = if (state.busy == null) ({ onIntent(SwitchedOffIntent.SetOff(r.pkg, !r.off)) }) else null)
                }
            }
        }
    }
}

// M5 Check for leftovers (D22).
@Composable
fun LeftoversScreen(state: LeftoversState, onIntent: (LeftoversIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PendingScreenScaffold(modifier, topBar = { PendingTopBar(onBack = onBack, backDescription = stringResource(R.string.action_back)) }, bottomBar = {
        when (state) {
            is LeftoversState.Found -> {
                PendingPrimaryButton(stringResource(R.string.leftovers_switch_off), { onIntent(LeftoversIntent.SwitchOff) })
                PendingTertiaryButton(stringResource(R.string.leftovers_not_now), { onIntent(LeftoversIntent.NotNow) }, Modifier.align(Alignment.CenterHorizontally))
            }
            LeftoversState.Failed -> PendingSecondaryButton(stringResource(R.string.leftovers_title), { onIntent(LeftoversIntent.CheckAgain) })
            else -> {}
        }
    }) {
        PendingScreenTitle(stringResource(R.string.leftovers_title))
        when (state) {
            LeftoversState.Checking -> PendingLiveLine(stringResource(R.string.leftovers_checking), found = false)
            is LeftoversState.Found -> {
                PendingLead(msg(R.string.leftovers_found, "count" to state.names.size))
                PendingListGroup { state.names.forEach { PendingListRow(it) } }
            }
            LeftoversState.Nothing -> PendingLead(stringResource(R.string.leftovers_none))
            is LeftoversState.Working -> { PendingLiveLine(stringResource(R.string.leftovers_checking), found = false); PendingProgressBar(state.percent / 100f) }
            is LeftoversState.Done -> PendingCheckLine(stringResource(R.string.leftovers_done))
            LeftoversState.Failed -> PendingLead(stringResource(R.string.leftovers_error))
        }
    }
}

// M7 TV settings (D46; "Keep debugging on" is Expert, 0.2, and absent).
@Composable
fun TvSettingsScreen(state: TvSettingsState, onIntent: (TvSettingsIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    var name by rememberSaveable(state.name) { mutableStateOf(state.name) }
    var room by rememberSaveable(state.room) { mutableStateOf(state.room.orEmpty()) }
    PendingScreenScaffold(modifier, topBar = { PendingTopBar(onBack = onBack, backDescription = stringResource(R.string.action_back)) }) {
        PendingScreenTitle(stringResource(R.string.tvsettings_title))
        PendingOverline(stringResource(R.string.tvsettings_name_room))
        PendingTextField(name, { name = it.take(40) }, stringResource(R.string.tvsettings_name_label))
        PendingTextField(room, { room = it.take(40) }, stringResource(R.string.tvsettings_room_label))
        if (name != state.name || room != state.room.orEmpty()) PendingSecondaryButton(stringResource(R.string.action_done), { onIntent(TvSettingsIntent.Rename(name, room)) }, enabled = name.isNotBlank())
        PendingOverline(stringResource(R.string.tvsettings_level_label))
        PendingListGroup {
            PendingListRow(stringResource(R.string.tvsettings_level_simple), value = if (state.level == Level.SIMPLE) "✓" else null, onClick = { onIntent(TvSettingsIntent.SetLevel(Level.SIMPLE)) })
            PendingListRow(stringResource(R.string.tvsettings_level_custom), value = if (state.level == Level.CUSTOM) "✓" else null, onClick = { onIntent(TvSettingsIntent.SetLevel(Level.CUSTOM)) })
        }
        PendingBody(stringResource(R.string.tvsettings_level_note))
        PendingListGroup {
            PendingListRow(stringResource(R.string.tvsettings_address), subtitle = state.lastSeenAtMs?.let { msg(R.string.tvsettings_last_seen, "date" to shortDateTime(it, locale)) },
                value = state.address)
        }
        PendingGap()
        PendingSecondaryButton(stringResource(R.string.tvsettings_forget), { onIntent(TvSettingsIntent.AskForget) }, enabled = !state.forgetting)
        PendingBody(stringResource(R.string.tvsettings_forget_explain))
    }
    if (state.confirmingForget) PendingBottomSheet({ onIntent(TvSettingsIntent.CancelForget) }) {
        PendingSheetHeader(msg(R.string.tvsettings_forget_title, "tv" to state.name))
        PendingBody(stringResource(R.string.tvsettings_forget_explain))
        PendingSecondaryButton(stringResource(R.string.tvsettings_forget_confirm), { onIntent(TvSettingsIntent.Forget) })
        PendingTertiaryButton(stringResource(R.string.action_cancel), { onIntent(TvSettingsIntent.CancelForget) })
        PendingGap()
    }
}

// M8 App settings (D12, F2, F13). The Reset my key confirmation is FLAG_SECURE (D35, set by the app).
@Composable
fun AppSettingsScreen(state: AppSettingsState, onIntent: (AppSettingsIntent) -> Unit, onBack: () -> Unit, onOpenRelease: () -> Unit, modifier: Modifier = Modifier) {
    var licences by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    PendingScreenScaffold(modifier, topBar = { PendingTopBar(onBack = onBack, backDescription = stringResource(R.string.action_back)) }) {
        PendingScreenTitle(stringResource(R.string.appsettings_title))
        PendingListGroup {
            PendingSwitchRow(stringResource(R.string.appsettings_large_text), state.prefs.largeTextFollowsSystem, { onIntent(AppSettingsIntent.SetLargeText(it)) },
                subtitle = stringResource(R.string.appsettings_large_text_note))
            PendingSwitchRow(stringResource(R.string.appsettings_haptics), state.prefs.haptics, { onIntent(AppSettingsIntent.SetHaptics(it)) },
                subtitle = stringResource(R.string.appsettings_haptics_note))
        }
        PendingOverline(stringResource(R.string.appsettings_about))
        PendingListGroup {
            PendingListRow(msg(R.string.appsettings_version, "version" to state.version))
            PendingListRow(stringResource(R.string.appsettings_launcher_pin_label))
            PendingMonospaceBlock(listOf(msg(R.string.appsettings_launcher_pin_line, "package" to state.launcherPkg, "version" to state.launcherVersion, "digest" to state.launcherDigest)))
            PendingListRow(stringResource(R.string.appsettings_key_label), subtitle = stringResource(when (state.keyBacking) {
                "STRONGBOX" -> R.string.appsettings_key_strongbox
                "TEE" -> R.string.appsettings_key_tee
                else -> R.string.appsettings_key_wrapped
            }))
            if (state.keyFingerprint.isNotEmpty()) PendingMonospaceBlock(listOf(state.keyFingerprint))
            val update = state.update
            PendingListRow(stringResource(R.string.appsettings_check_updates), subtitle = when (update) {
                UpdateCheck.Checking -> stringResource(R.string.appsettings_checking)
                is UpdateCheck.Available -> msg(R.string.appsettings_update_available, "version" to update.version)
                UpdateCheck.Latest -> stringResource(R.string.appsettings_up_to_date)
                UpdateCheck.Failed -> stringResource(R.string.appsettings_update_error)
                UpdateCheck.Idle -> null
            }, onClick = { if (update is UpdateCheck.Available) onOpenRelease() else onIntent(AppSettingsIntent.CheckUpdates) })
        }
        PendingListGroup {
            PendingListRow(stringResource(R.string.appsettings_licences), onClick = { licences = true })
            PendingListRow(stringResource(R.string.appsettings_privacy), onClick = { privacy = true })
            PendingListRow(stringResource(R.string.appsettings_diagnostics), onClick = { onIntent(AppSettingsIntent.PreviewDiagnostics) })
        }
        PendingListGroup {
            PendingListRow(stringResource(R.string.appsettings_reset_key), subtitle = stringResource(R.string.appsettings_reset_key_note), onClick = { onIntent(AppSettingsIntent.Ask(AppConfirm.RESET_KEY)) })
            PendingListRow(stringResource(R.string.appsettings_delete_all), subtitle = stringResource(R.string.appsettings_delete_all_note), onClick = { onIntent(AppSettingsIntent.Ask(AppConfirm.DELETE_EVERYTHING)) })
        }
    }
    if (licences) PendingBottomSheet({ licences = false }) {
        PendingSheetHeader(stringResource(R.string.appsettings_licences))
        listOf(R.string.appsettings_licence_app, R.string.appsettings_licence_font, R.string.appsettings_licence_apache, R.string.appsettings_licence_bouncycastle)
            .forEach { PendingListRow(stringResource(it)) }
        PendingGap()
    }
    if (privacy) PendingBottomSheet({ privacy = false }) {
        PendingSheetHeader(stringResource(R.string.appsettings_privacy))
        PendingBody(stringResource(R.string.appsettings_privacy_text), color = MaterialTheme.colorScheme.onSurface)
        PendingGap()
    }
    state.diagnostics?.let { text ->
        PendingBottomSheet({ onIntent(AppSettingsIntent.CloseDiagnostics) }) {
            PendingSheetHeader(stringResource(R.string.appsettings_diagnostics_preview_title), subtitle = stringResource(R.string.appsettings_diagnostics_preview_body))
            PendingMonospaceBlock(text.lines().take(200))
            PendingPrimaryButton(stringResource(R.string.appsettings_diagnostics_share), { onIntent(AppSettingsIntent.ShareDiagnostics) })
            PendingGap()
        }
    }
    state.confirm?.let { what ->
        PendingBottomSheet({ onIntent(AppSettingsIntent.CancelConfirm) }) {
            val reset = what == AppConfirm.RESET_KEY
            PendingSheetHeader(stringResource(if (reset) R.string.appsettings_reset_key_title else R.string.appsettings_delete_all_title))
            PendingBody(stringResource(if (reset) R.string.appsettings_reset_key_note else R.string.appsettings_delete_all_note), color = MaterialTheme.colorScheme.onSurface)
            PendingSecondaryButton(stringResource(if (reset) R.string.appsettings_reset_key_confirm else R.string.appsettings_delete_all_confirm),
                { onIntent(if (reset) AppSettingsIntent.ResetKey else AppSettingsIntent.DeleteEverything) }, enabled = !state.working)
            PendingTertiaryButton(stringResource(R.string.action_cancel), { onIntent(AppSettingsIntent.CancelConfirm) })
            PendingGap()
        }
    }
}
