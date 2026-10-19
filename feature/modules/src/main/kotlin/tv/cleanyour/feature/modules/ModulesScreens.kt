package tv.cleanyour.feature.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.flows.CardAnswer
import tv.cleanyour.core.flows.IndexView
import tv.cleanyour.core.flows.InstallStep
import tv.cleanyour.core.flows.ModuleDetail
import tv.cleanyour.core.flows.ModuleRow
import tv.cleanyour.core.flows.ModulesIntent
import tv.cleanyour.core.flows.ModulesState
import tv.cleanyour.core.model.Tier
import tv.cleanyour.core.registry.FetchFailure
import tv.cleanyour.core.trust.IndexFailure

// M3 Cards and modules, M3a Add, M3b the module page, M3c Look at the TV (UX 5, D1, D6, D45, F9).
// Version strings come from the signed index only, never from copy (D6).
@Composable
fun ModulesFlow(state: ModulesState, onIntent: (ModulesIntent) -> Unit, onBack: () -> Unit, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    var adding by rememberSaveable { mutableStateOf(false) }
    val install = state.install
    val detail = state.detail
    when {
        install != null -> InstallScreen(install, state, onIntent, modifier)
        detail != null -> DetailScreen(detail, state.tvName, onIntent, onOpenUrl, modifier)
        adding -> AddScreen(state, onIntent, onBack = { adding = false }, modifier)
        else -> InstalledScreen(state, onIntent, onAdd = { adding = true }, onBack, modifier)
    }
    state.consent?.let { c ->
        PendingBottomSheet({ onIntent(ModulesIntent.SkipUpdate) }) {
            PendingBody(msg(R.string.modules_update_consent, "name" to c.name, "version" to c.version, "permissions" to c.added.joinToString(", ") { it.plainWords }),
                color = MaterialTheme.colorScheme.onSurface)
            PendingPrimaryButton(stringResource(R.string.modules_update), { onIntent(ModulesIntent.ConfirmUpdate) })
            PendingTertiaryButton(stringResource(R.string.modules_update_skip), { onIntent(ModulesIntent.SkipUpdate) })
            PendingGap()
        }
    }
}

fun megabytes(bytes: Long?): Int = if (bytes == null) 0 else maxOf(1, ((bytes + 512 * 1024) / (1024 * 1024)).toInt())

@Composable
private fun IndexNotice(index: IndexView, onIntent: (ModulesIntent) -> Unit) {
    when (index) {
        is IndexView.Ready -> if (index.stale) PendingBanner(stringResource(R.string.index_stale_warning), PendingBannerTone.WARNING,
            actionLabel = stringResource(R.string.addmodule_refresh), onAction = { onIntent(ModulesIntent.Refresh) })
        is IndexView.Failed -> PendingBanner(indexFailureText(index), PendingBannerTone.WARNING,
            actionLabel = stringResource(R.string.addmodule_refresh), onAction = { onIntent(ModulesIntent.Refresh) })
        IndexView.Loading -> PendingLiveLine(stringResource(R.string.addmodule_loading), found = false)
    }
}

// F9: one copy line per failure layer.
@Composable
fun indexFailureText(f: IndexView.Failed): String = when {
    f.verdict != null -> stringResource(when (f.verdict!!) {
        IndexFailure.BAD_SIGNATURE -> R.string.index_failure_bad_signature
        IndexFailure.UNKNOWN_KEY -> R.string.index_failure_unknown_key
        IndexFailure.REVOKED_KEY -> R.string.index_failure_revoked_key
        IndexFailure.BAD_KEYS_FILE -> R.string.index_failure_bad_keys_file
        IndexFailure.EXPIRED -> R.string.index_failure_expired
        IndexFailure.ROLLBACK -> R.string.index_failure_rollback
        IndexFailure.MALFORMED -> R.string.index_failure_malformed
    })
    f.fetch != null -> fetchFailureText(f.fetch!!)
    else -> stringResource(R.string.index_stale_warning)
}

@Composable
fun fetchFailureText(f: FetchFailure): String = when (f) {
    FetchFailure.OFFLINE -> stringResource(R.string.fetch_failure_offline)
    FetchFailure.HOST_NOT_ALLOWED -> stringResource(R.string.fetch_failure_host_not_allowed)
    FetchFailure.HTTP_ERROR -> msg(R.string.fetch_failure_http_error, "code" to "")
    FetchFailure.TOO_LARGE -> stringResource(R.string.fetch_failure_too_large)
    FetchFailure.HASH_MISMATCH -> stringResource(R.string.fetch_failure_hash_mismatch)
    FetchFailure.SIZE_MISMATCH -> stringResource(R.string.fetch_failure_size_mismatch)
    FetchFailure.SIGNER_MISMATCH -> stringResource(R.string.fetch_failure_signer_mismatch)
}

@Composable
private fun tierWord(t: Tier): String = stringResource(when (t) {
    Tier.VERIFIED -> R.string.addmodule_tier_verified
    Tier.COMMUNITY -> R.string.addmodule_tier_community
    Tier.QUARANTINED, Tier.REVOKED -> R.string.addmodule_tier_paused
})

// M3: installed modules with their state, Remove and Update.
@Composable
private fun InstalledScreen(state: ModulesState, onIntent: (ModulesIntent) -> Unit, onAdd: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    PendingScreenScaffold(modifier, topBar = { PendingTopBar(onBack = onBack, backDescription = stringResource(R.string.action_back)) },
        bottomBar = { PendingPrimaryButton(stringResource(R.string.modules_add), onAdd) }) {
        PendingScreenTitle(stringResource(R.string.modules_title))
        if (state.installed.isEmpty()) PendingLead(stringResource(R.string.modules_empty))
        PendingListGroup {
            state.installed.forEach { m ->
                val stateWord = when {
                    m.tier == Tier.REVOKED || m.tier == Tier.QUARANTINED -> stringResource(R.string.addmodule_tier_paused)
                    m.card == CardAnswer.SHOWN -> stringResource(R.string.modules_state_shown)
                    m.card == CardAnswer.HIDDEN -> stringResource(R.string.modules_state_hidden)
                    else -> null
                }
                PendingListRow(m.name, subtitle = listOfNotNull(stateWord, m.updateTo?.let { msg(R.string.modules_update_available, "version" to it) }).joinToString(" · ").ifEmpty { null },
                    trailing = {
                        if (m.updateTo != null) PendingTertiaryButton(stringResource(R.string.modules_update), { onIntent(ModulesIntent.Update(m.id)) })
                        if (state.removing == m.id) PendingSpinner() else PendingTertiaryButton(stringResource(R.string.modules_remove), { removing = m.id }, enabled = state.removing == null)
                    })
            }
        }
    }
    removing?.let { id ->
        val m = state.installed.firstOrNull { it.id == id }
        PendingBottomSheet({ removing = null }) {
            PendingSheetHeader(msg(R.string.modules_remove_title, "name" to (m?.name ?: ""), "tv" to state.tvName))
            PendingBody(stringResource(R.string.modules_remove_body))
            PendingSecondaryButton(stringResource(R.string.modules_remove_confirm), { onIntent(ModulesIntent.Remove(id)); removing = null })
            PendingTertiaryButton(stringResource(R.string.action_cancel), { removing = null })
            PendingGap()
        }
    }
}

// M3a Add to Mi TV 4A: search, module cards, the community footer.
@Composable
private fun AddScreen(state: ModulesState, onIntent: (ModulesIntent) -> Unit, onBack: () -> Unit, modifier: Modifier) {
    var tiers by rememberSaveable { mutableStateOf(false) }
    PendingScreenScaffold(modifier, topBar = { PendingTopBar(onBack = onBack, backDescription = stringResource(R.string.action_back)) }) {
        PendingScreenTitle(msg(R.string.addmodule_title, "tv" to state.tvName))
        PendingTextField(state.query, { onIntent(ModulesIntent.Search(it)) }, stringResource(R.string.addmodule_search_hint))
        IndexNotice(state.index, onIntent)
        if (state.index !is IndexView.Loading && state.filtered.isEmpty()) PendingBody(stringResource(R.string.addmodule_empty))
        state.filtered.forEach { m -> ModuleCardRow(m) { onIntent(ModulesIntent.Open(m.id)) } }
        PendingBody(stringResource(R.string.addmodule_footer))
        PendingTertiaryButton(stringResource(R.string.addmodule_footer_link), { tiers = true })
    }
    if (tiers) PendingBottomSheet({ tiers = false }) {
        PendingSheetHeader(stringResource(R.string.addmodule_tiers_title))
        PendingBody(stringResource(R.string.addmodule_tiers_community))
        PendingBody(stringResource(R.string.addmodule_tiers_verified))
        PendingBody(stringResource(R.string.addmodule_tiers_paused))
        PendingGap()
    }
}

// TODO(W7 merge): ModuleCard(name, summary, chips, accent, onClick).
@Composable
private fun ModuleCardRow(m: ModuleRow, onClick: () -> Unit) {
    PendingListGroup {
        PendingListRow(m.name, subtitle = m.oneLine, onClick = onClick)
        Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PendingGap(0)
            PendingInfoChip(tierWord(m.tier))
            if (m.licence.isNotBlank()) PendingInfoChip(stringResource(R.string.addmodule_open_source))
            if (m.sizeBytes != null) PendingInfoChip(msg(R.string.addmodule_size, "size" to megabytes(m.sizeBytes)))
        }
        PendingGap(8)
    }
}

// M3b the module page (UX 5.2 with D1 and D6).
@Composable
private fun DetailScreen(d: ModuleDetail, tvName: String, onIntent: (ModulesIntent) -> Unit, onOpenUrl: (String) -> Unit, modifier: Modifier) {
    val m = d.module
    val v = d.version
    val paused = m.tier == Tier.REVOKED || m.tier == Tier.QUARANTINED
    PendingScreenScaffold(modifier,
        topBar = { PendingTopBar(onBack = { onIntent(ModulesIntent.CloseDetail) }, backDescription = stringResource(R.string.action_back)) },
        bottomBar = {
            if (paused && d.installed) PendingSecondaryButton(stringResource(R.string.modules_remove), { onIntent(ModulesIntent.Remove(m.id)) })
            else if (!paused && !d.installed) PendingPrimaryButton(msg(R.string.module_add, "tv" to tvName), { onIntent(ModulesIntent.Add(m.id)) })
        }) {
        // TODO(W7 merge): ModuleHero(name, tagline, accent).
        PendingScreenTitle(m.name)
        PendingLead(m.oneLine)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PendingInfoChip(tierWord(m.tier))
            if (m.licence.isNotBlank()) PendingInfoChip(stringResource(R.string.addmodule_open_source))
            PendingInfoChip(msg(R.string.addmodule_size, "size" to megabytes(v.artifact.size)))
        }
        if (paused) PendingBanner(msg(R.string.module_paused_reason, "reason" to m.description), PendingBannerTone.WARNING)
        PendingBody(m.description, color = MaterialTheme.colorScheme.onSurface)
        PendingOverline(stringResource(R.string.module_what_title))
        v.permissions.forEach { p ->
            val cd = msg(R.string.module_perm_can_cd, "statement" to p.plainWords)
            PendingCheckLine(p.plainWords, Modifier.semantics { contentDescription = cd })
        }
        if (v.networkStatement.isNotBlank()) PendingCheckLine(v.networkStatement)
        if (m.type == "service") PendingCheckLine(msg(R.string.module_runs_background, "reason" to m.oneLine))
        else PendingCheckLine(stringResource(R.string.module_no_background), done = false)
        PendingOverline(stringResource(R.string.module_who_title))
        PendingBody(msg(R.string.module_maker, "handle" to m.publisher), color = MaterialTheme.colorScheme.onSurface)
        PendingBody(msg(R.string.module_version_line, "version" to v.versionName, "date" to v.releasedAt))
        PendingBody(msg(R.string.module_checks, "count" to v.permissions.size))
        PendingBody(stringResource(R.string.module_not_reviewed))
        // D1: the first 12 hex of the signer digest in groups of 4, exactly what the TV prompt prints.
        PendingMonospaceBlock(listOf(msg(R.string.module_key_line, "key" to d.key12)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PendingTertiaryButton(stringResource(R.string.module_source), { onOpenUrl(m.sourceUrl) })
            PendingTertiaryButton(stringResource(R.string.module_report), { onOpenUrl(m.sourceUrl.trimEnd('/') + "/issues") })
        }
    }
}

// The install narration, then M3c (D1, D45).
@Composable
private fun InstallScreen(step: InstallStep, state: ModulesState, onIntent: (ModulesIntent) -> Unit, modifier: Modifier) {
    val name = state.detail?.module?.name ?: ""
    PendingScreenScaffold(modifier, topBar = { PendingTopBar(onBack = { onIntent(ModulesIntent.DismissInstall) }, backDescription = stringResource(R.string.action_back)) }) {
        when (step) {
            is InstallStep.Downloading -> {
                PendingScreenTitle(msg(R.string.module_install_adding, "name" to name))
                PendingLiveLine(msg(R.string.module_install_downloading, "size" to megabytes(step.total)), found = false)
                PendingProgressBar(if (step.total > 0) step.done.toFloat() / step.total else 0f)
            }
            InstallStep.Checking -> { PendingScreenTitle(msg(R.string.module_install_adding, "name" to name)); PendingLiveLine(stringResource(R.string.module_install_checking), found = false) }
            is InstallStep.Installing -> {
                PendingScreenTitle(msg(R.string.module_install_adding, "name" to name))
                PendingLiveLine(stringResource(R.string.module_install_installing), found = false)
                PendingProgressBar(step.percent / 100f)
            }
            is InstallStep.Asking -> {
                PendingScreenTitle(stringResource(R.string.cardprompt_title))
                PendingCardPromptMirror(msg(R.string.cardprompt_art_title, "name" to step.name), step.pkg, msg(R.string.cardprompt_art_key, "key" to step.key12),
                    stringResource(R.string.cardprompt_art_keep), stringResource(R.string.cardprompt_art_show),
                    contentDescription = msg(R.string.cardprompt_art_cd, "name" to step.name, "key" to step.key12))
                PendingLead(stringResource(R.string.cardprompt_body))
                PendingBody(msg(R.string.cardprompt_instruction, "key" to step.key12), color = MaterialTheme.colorScheme.onSurface)
                PendingLiveLine(stringResource(R.string.cardprompt_waiting), found = false)
                PendingBody(stringResource(R.string.cardprompt_later))
                // 0.1 cannot read the TV's answer without the provider: the person tells us (CardAnswerDetector).
                PendingSecondaryButton(msg(R.string.cardprompt_shown, "name" to step.name), { onIntent(ModulesIntent.Answered(CardAnswer.SHOWN)) })
                PendingTertiaryButton(stringResource(R.string.cardprompt_art_keep), { onIntent(ModulesIntent.Answered(CardAnswer.HIDDEN)) })
            }
            is InstallStep.Shown -> { PendingScreenTitle(stringResource(R.string.cardprompt_title)); PendingBanner(msg(R.string.cardprompt_shown, "name" to step.name), PendingBannerTone.SUCCESS)
                PendingPrimaryButton(stringResource(R.string.action_done), { onIntent(ModulesIntent.DismissInstall) }) }
            is InstallStep.Hidden -> { PendingScreenTitle(stringResource(R.string.cardprompt_title)); PendingBanner(msg(R.string.cardprompt_hidden, "name" to step.name), PendingBannerTone.NEUTRAL)
                PendingPrimaryButton(stringResource(R.string.action_done), { onIntent(ModulesIntent.DismissInstall) }) }
            is InstallStep.Failed -> {
                PendingStopCard(msg(R.string.module_install_failed, "name" to step.name), step.fetch?.let { fetchFailureText(it) } ?: "",
                    primaryLabel = stringResource(R.string.action_ok), onPrimary = { onIntent(ModulesIntent.DismissInstall) })
            }
        }
    }
}
