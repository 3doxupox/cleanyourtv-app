package tv.cleanyour.feature.connect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.Families
import tv.cleanyour.core.flows.SetupActions
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupIntent
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.core.flows.Sheet
import tv.cleanyour.core.flows.StopAction
import tv.cleanyour.core.flows.DoorPhase
import tv.cleanyour.core.flows.fingerprintTail

// Screens S1, S1b, S1c, S2a to S2d and S2x (PRODUCT-SPEC 4, UX 2 screen 1 and 2, UX 4.1 to 4.5).
// Stateless: they render a SetupState plus its extras and send everything back through
// SetupActions. Returns false from `isConnectState` for the states :feature:setup draws.
fun isConnectState(state: SetupState): Boolean =
    state is SetupState.Welcome || state is SetupState.Picker || state is SetupState.OpenDoor || state is SetupState.PairingNeeded

@Composable
fun ConnectFlow(state: SetupState, extras: SetupExtras, actions: SetupActions, onReturning: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        is SetupState.Welcome -> WelcomeScreen(state, extras, actions, onReturning, modifier)
        is SetupState.Picker -> PickerScreen(state, extras, actions, modifier)
        is SetupState.OpenDoor -> DoorScreen(state, extras, actions, modifier)
        is SetupState.PairingNeeded -> PairingScreen(state, extras, actions, modifier)
        else -> {}
    }
    if (extras.sheet == Sheet.ADDRESS) AddressSheet(extras.addressError, actions)
}

@Composable
private fun chipLabel(name: String, status: ChipState): String = msg(R.string.chip_label, "tv" to name,
    "state" to chipWord(status, R.string.chip_connected, R.string.chip_waiting, R.string.chip_working, R.string.chip_debugging_off, R.string.chip_offline))

@Composable
private fun InlineCard(extras: SetupExtras, actions: SetupActions) {
    val c = extras.card ?: return
    PendingStopCard(c.title, c.body, primaryLabel = c.primary, onPrimary = { actions.intent(SetupIntent.StopAction(StopAction.PRIMARY)) },
        secondaryLabel = c.secondary, onSecondary = { actions.intent(SetupIntent.StopAction(StopAction.SECONDARY)) })
}

// S1 Welcome (D8, D19, D5): discovery is already running; the live line is the chip.
@Composable
fun WelcomeScreen(state: SetupState.Welcome, extras: SetupExtras, actions: SetupActions, onReturning: () -> Unit, modifier: Modifier = Modifier) {
    PendingScreenScaffold(modifier, bottomBar = {
        PendingPrimaryButton(stringResource(R.string.welcome_start), { actions.intent(SetupIntent.Start) })
        PendingTertiaryButton(stringResource(R.string.welcome_returning), onReturning, Modifier.align(Alignment.CenterHorizontally))
    }) {
        PendingGap(48)
        // TODO(W7 merge): WelcomeIllustration(play = true, contentDescription = welcome_hero_cd)
        val heroCd = stringResource(R.string.welcome_hero_cd)
        Spacer(Modifier.height(120.dp).semantics { contentDescription = heroCd })
        PendingScreenTitle(stringResource(R.string.welcome_title), display = true)
        PendingLead(stringResource(R.string.welcome_body))
        val found = state.found
        val line = when {
            found.size >= 2 -> msg(R.string.welcome_found_many, "count" to found.size)
            found.size == 1 -> msg(R.string.welcome_found_one_noroom, "name" to displayName(found[0]))
            state.looking -> stringResource(R.string.welcome_looking)
            else -> stringResource(R.string.welcome_none_yet)
        }
        PendingLiveLine(line, found = found.isNotEmpty())
        InlineCard(extras, actions)
    }
}

@Composable
fun displayName(tv: FoundTv): String = tv.friendlyName ?: tv.model ?: tv.host

// S1b Which TV? (2 or more found; UX 4.12).
@Composable
fun PickerScreen(state: SetupState.Picker, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableStateOf(state.found.firstOrNull()?.host) }
    val chosen = state.found.firstOrNull { it.host == selected } ?: state.found.firstOrNull()
    PendingScreenScaffold(modifier,
        topBar = { PendingTopBar(onBack = { actions.back() }, backDescription = stringResource(R.string.action_back)) },
        bottomBar = {
            PendingPrimaryButton(msg(R.string.picker_primary, "name" to (chosen?.let { displayName(it) } ?: "")), { chosen?.let { actions.intent(SetupIntent.PickTv(it.host)) } }, enabled = chosen != null)
            PendingTertiaryButton(stringResource(R.string.picker_tertiary), { actions.intent(SetupIntent.Open(Sheet.ADDRESS)) }, Modifier.align(Alignment.CenterHorizontally))
        }) {
        PendingStepCounter(msg(R.string.step_counter, "step" to 1))
        PendingScreenTitle(stringResource(R.string.picker_title))
        PendingLead(stringResource(R.string.picker_body))
        // Two TVs with the same name get the last part of the address in grey (UX 4.12).
        val dupes = state.found.groupBy { displayName(it) }.filterValues { it.size > 1 }.keys
        PendingListGroup {
            state.found.forEach { tv ->
                val name = if (displayName(tv) in dupes) msg(R.string.picker_name_with_suffix, "name" to displayName(tv), "suffix" to tv.host.substringAfterLast('.')) else displayName(tv)
                Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { selected = tv.host }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = tv.host == chosen?.host, onClick = null)
                    PendingListRow(name, subtitle = tv.model?.takeIf { it != name })
                }
            }
        }
        PendingBody(stringResource(R.string.picker_footer))
        InlineCard(extras, actions)
    }
}

// S2a to S2d: one screen, four states; the phone advances on its own (UX 2 screen 2).
@Composable
fun DoorScreen(state: SetupState.OpenDoor, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    val name = extras.tvName ?: displayName(state.tv).ifEmpty { null }
    var showNotSeen by rememberSaveable { mutableStateOf(false) }
    var brandsOpen by rememberSaveable { mutableStateOf(state.family == Families.UNKNOWN) }
    PendingScreenScaffold(modifier,
        topBar = {
            PendingTopBar(onBack = { actions.back() }, backDescription = stringResource(R.string.action_back),
                center = { if (name != null) PendingTvStatusChip(chipLabel(name, extras.chip ?: ChipState.WAITING), extras.chip ?: ChipState.WAITING) })
        }) {
        PendingStepCounter(msg(R.string.step_counter, "step" to 2))
        when (state.phase) {
            DoorPhase.ALLOW -> AllowContent(state, extras, onNotSeen = { showNotSeen = true })
            DoorPhase.IDENTITY -> IdentityContent(extras, actions)
            DoorPhase.CONNECTED -> {
                PendingScreenTitle(msg(R.string.connected_title, "tv" to (name ?: "")))
                PendingCheckLine(stringResource(R.string.allow_trusted))
            }
            DoorPhase.STEPS, DoorPhase.CONNECTING -> StepsContent(state, extras, actions, name, brandsOpen, onBrands = { brandsOpen = !brandsOpen })
        }
        InlineCard(extras, actions)
    }
    if (showNotSeen) NotSeenSheet(extras.allowRetries, onAsk = { actions.intent(SetupIntent.AskTvAgain); showNotSeen = false }, onDismiss = { showNotSeen = false })
}

@Composable
private fun StepsContent(state: SetupState.OpenDoor, extras: SetupExtras, actions: SetupActions, name: String?, brandsOpen: Boolean, onBrands: () -> Unit) {
    PendingScreenTitle(stringResource(R.string.door_title))
    PendingLead(stringResource(R.string.door_body))
    state.banner?.let { PendingBanner(it, PendingBannerTone.INFO) }
    if (state.family == Families.UNKNOWN) PendingBody(stringResource(R.string.door_unknown_note))
    if (brandsOpen) BrandChips(onPick = { actions.intent(SetupIntent.ChangeFamily(it)) })
    val steps = familySteps(state.family)
    var page by rememberSaveable { mutableIntStateOf(state.step.coerceIn(0, 2)) }
    // The card follows the TV: when the port opens it moves to step 3 on its own.
    LaunchedEffect(state.step) { page = state.step.coerceIn(0, 2) }
    PendingStepCard(
        title = msg(R.string.door_step_label, "current" to page + 1, "total" to 3), body = steps[page],
        trailing = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (page < 2) PendingTertiaryButton(stringResource(R.string.door_next), { page++ })
            }
        },
    )
    if (state.family == Families.ATV && listOfNotNull(state.tv.model, state.tv.friendlyName).any { "shield" in it.lowercase() }) PendingBody(stringResource(R.string.family_atv_extra_shield))
    PendingBody(stringResource(R.string.door_menu_names_vary))
    val stillWaiting = state.phase == DoorPhase.STEPS && state.waitedMs >= 180_000
    val status = when {
        state.phase == DoorPhase.CONNECTING -> stringResource(R.string.door_connecting)
        stillWaiting -> stringResource(R.string.door_still_waiting)
        else -> stringResource(R.string.door_waiting)
    }
    PendingLiveLine(status, found = false)
    if (stillWaiting) PendingTertiaryButton(stringResource(R.string.door_still_waiting_show_me), { page = 1 })
    PendingBody(stringResource(R.string.door_same_wifi))
    PendingTertiaryButton(if (name != null) msg(R.string.door_change_model, "model" to name) else stringResource(R.string.door_change_model_generic), onBrands)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrandChips(onPick: (String) -> Unit) {
    PendingOverline(stringResource(R.string.door_brand_prompt))
    val brands = listOf(
        "Xiaomi" to R.string.door_brand_xiaomi, "TCL" to R.string.door_brand_tcl, "Sony" to R.string.door_brand_sony,
        "Hisense" to R.string.door_brand_hisense, "Philips" to R.string.door_brand_philips, "Google" to R.string.door_brand_google,
        "Amazon" to R.string.door_brand_amazon, "Other" to R.string.door_brand_other,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        brands.forEach { (key, res) -> PendingInfoChip(stringResource(res), Modifier.clickable(role = Role.Button) { onPick(key) }.padding(vertical = 4.dp)) }
    }
}

@Composable
private fun familySteps(family: String): List<String> = when (family) {
    Families.GTV -> listOf(stringResource(R.string.family_gtv_step1), stringResource(R.string.family_gtv_step2), stringResource(R.string.family_gtv_step3))
    Families.FIRE -> listOf(stringResource(R.string.family_fire_step1), stringResource(R.string.family_fire_step2), stringResource(R.string.family_fire_step3))
    Families.TCL -> listOf(stringResource(R.string.family_tcl_step1), stringResource(R.string.family_tcl_step2), stringResource(R.string.family_tcl_step3))
    Families.ATV -> listOf(stringResource(R.string.family_atv_step1), stringResource(R.string.family_atv_step2), stringResource(R.string.family_atv_step3))
    else -> listOf(stringResource(R.string.family_unknown_step1), stringResource(R.string.family_unknown_step2), stringResource(R.string.family_unknown_step3))
}

// S2c Look at the TV: the dialog mirror with the full fingerprint; the screen is FLAG_SECURE (D35,
// set by the app for this phase).
@Composable
private fun AllowContent(state: SetupState.OpenDoor, extras: SetupExtras, onNotSeen: () -> Unit) {
    val fp = state.fingerprint.orEmpty()
    val tail = fingerprintTail(fp)
    PendingScreenTitle(stringResource(R.string.allow_title))
    PendingAdbAllowMirror(stringResource(R.string.allow_art_title), stringResource(R.string.allow_art_fingerprint_label), fp,
        stringResource(R.string.allow_art_always), stringResource(R.string.allow_art_cancel), stringResource(R.string.allow_art_ok),
        contentDescription = msg(R.string.allow_art_cd, "tail" to tail))
    PendingLead(stringResource(R.string.allow_body))
    if (tail.isNotEmpty()) PendingBody(msg(R.string.allow_code_line, "tail" to tail), color = MaterialTheme.colorScheme.onSurface)
    PendingLiveLine(stringResource(if (state.waitedMs >= 45_000 || extras.allowDenied) R.string.allow_still_waiting else R.string.allow_waiting), found = false)
    PendingTertiaryButton(stringResource(R.string.allow_dont_see), onNotSeen)
}

// S2d first-time identity line for a TV with no Allow dialog (D38).
@Composable
private fun IdentityContent(extras: SetupExtras, actions: SetupActions) {
    val id = extras.identity
    PendingScreenTitle(stringResource(R.string.identity_question))
    if (id != null) PendingLead(msg(R.string.identity_detail, "name" to (extras.tvName ?: id.model), "serial" to id.serialLast4))
    PendingTvThumbnail(id?.screenshot, stringResource(R.string.identity_screenshot_cd))
    PendingBody(stringResource(R.string.identity_body))
    PendingPrimaryButton(stringResource(R.string.identity_yes), { actions.intent(SetupIntent.ConfirmIdentity) })
    PendingSecondaryButton(stringResource(R.string.identity_no), { actions.intent(SetupIntent.RejectIdentity) })
}

// UX 4.4: three paragraphs and "Ask the TV again"; after 3 retries, the restart paragraph.
@Composable
private fun NotSeenSheet(retries: Int, onAsk: () -> Unit, onDismiss: () -> Unit) {
    PendingBottomSheet(onDismiss) {
        PendingSheetHeader(stringResource(R.string.notseen_title))
        if (retries >= 3) {
            PendingBody(stringResource(R.string.notseen_restart))
            PendingPrimaryButton(stringResource(R.string.notseen_restarted), onAsk)
        } else {
            PendingBody(stringResource(R.string.notseen_hidden))
            PendingBody(stringResource(R.string.notseen_cancelled))
            PendingBody(stringResource(R.string.notseen_greyed))
            PendingPrimaryButton(stringResource(R.string.notseen_ask_again), onAsk)
        }
        PendingGap()
    }
}

// S2x: the 0.1 dead end for pairing-only TVs (D9).
@Composable
fun PairingScreen(state: SetupState.PairingNeeded, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    var steps by rememberSaveable { mutableStateOf(false) }
    PendingScreenScaffold(modifier, topBar = { PendingTopBar(onBack = { actions.back() }, backDescription = stringResource(R.string.action_back)) }) {
        PendingStepCounter(msg(R.string.step_counter, "step" to 2))
        PendingStopCard(stringResource(R.string.pairing_title), stringResource(R.string.pairing_body),
            primaryLabel = stringResource(R.string.pairing_show_steps), onPrimary = { steps = true },
            secondaryLabel = stringResource(R.string.action_back), onSecondary = { actions.back() })
    }
    if (steps) PendingBottomSheet({ steps = false }) {
        PendingSheetHeader(stringResource(R.string.pairing_steps_title))
        PendingBody(stringResource(R.string.pairing_steps_intro))
        PendingMonospaceBlock(listOf(stringResource(R.string.pairing_steps_cmd_pair), stringResource(R.string.pairing_steps_cmd_connect), stringResource(R.string.pairing_steps_cmd_tcpip)))
        PendingBody(stringResource(R.string.pairing_steps_note))
        PendingGap()
    }
}

// S1c: numeric field, RFC 1918 only.
@Composable
fun AddressSheet(error: Boolean, actions: SetupActions) {
    var text by rememberSaveable { mutableStateOf("") }
    PendingBottomSheet({ actions.back() }) {
        PendingSheetHeader(stringResource(R.string.address_title))
        PendingBody(stringResource(R.string.address_body))
        PendingTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' }.take(15) }, stringResource(R.string.address_field_label),
            error = if (error) stringResource(R.string.address_error) else null, numeric = true)
        PendingGap(12)
        PendingPrimaryButton(stringResource(R.string.address_connect), { actions.intent(SetupIntent.EnterAddress(text)) }, enabled = text.count { it == '.' } == 3)
        PendingGap()
    }
}
