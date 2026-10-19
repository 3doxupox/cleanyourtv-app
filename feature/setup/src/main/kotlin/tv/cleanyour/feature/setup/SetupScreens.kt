package tv.cleanyour.feature.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.cleanyour.core.engine.NarrationState
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.Finishing
import tv.cleanyour.core.flows.Reconnect
import tv.cleanyour.core.flows.RestartState
import tv.cleanyour.core.flows.SetupActions
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupIntent
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.core.flows.Sheet
import tv.cleanyour.core.flows.StopAction
import tv.cleanyour.core.flows.StopCase
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.GroupSummary
import tv.cleanyour.core.planner.RowSummary

// Screens S2d probing, S3, S3a, S3b, S4a, S4b, S4c to S4e and S5 (PRODUCT-SPEC 4, UX 2 screens 3 to 5,
// UX 4.7, 4.8, 4.14). Stateless over SetupState and SetupExtras.
@Composable
fun SetupFlow(
    state: SetupState,
    extras: SetupExtras,
    actions: SetupActions,
    modifier: Modifier = Modifier,
    onCopyCommands: (String) -> Unit = {},
    nowMs: () -> Long = System::currentTimeMillis,
) {
    when (state) {
        is SetupState.Probing -> ProbingScreen(state, extras, modifier)
        is SetupState.Found -> FoundScreen(state, extras, actions, modifier, onCopyCommands)
        is SetupState.Working -> WorkingScreen(state, extras, actions, modifier)
        is SetupState.Check -> CheckScreen(state, extras, actions, modifier, nowMs)
        is SetupState.Stopped -> StoppedScreen(state, extras, actions, modifier)
        is SetupState.Done -> DoneScreen(state, extras, actions, modifier)
        else -> {}
    }
}

@Composable
private fun Chip(extras: SetupExtras) {
    val name = extras.tvName ?: return
    val status = extras.chip ?: ChipState.CONNECTED
    PendingTvStatusChip(msg(R.string.chip_label, "tv" to name, "state" to chipWord(status, R.string.chip_connected, R.string.chip_waiting,
        R.string.chip_working, R.string.chip_debugging_off, R.string.chip_offline)), status)
}

@Composable
private fun TopBar(extras: SetupExtras, onBack: (() -> Unit)? = null) =
    PendingTopBar(onBack = onBack, backDescription = stringResource(R.string.action_back), center = { Chip(extras) })

fun dateTime(ms: Long, locale: java.util.Locale): String = SimpleDateFormat("d MMM, HH:mm", locale).format(Date(ms))

// S2d to S3: the probe's narration while the summary fills in.
@Composable
fun ProbingScreen(state: SetupState.Probing, extras: SetupExtras, modifier: Modifier = Modifier) {
    PendingScreenScaffold(modifier, topBar = { TopBar(extras) }) {
        PendingStepCounter(msg(R.string.step_counter, "step" to 3))
        PendingLiveLine(stringResource(R.string.probing_line), found = false)
        val stages = tv.cleanyour.core.probe.ProbeStage.entries
        val probingCd = stringResource(R.string.probing_progress_cd)
        PendingProgressBar((stages.indexOf(state.stage) + 1f) / stages.size, Modifier.semantics { contentDescription = probingCd })
    }
}

// S3 What we found (D4, D17 grey line, UX 2 rules behind the numbers, UX 4.6).
@Composable
fun FoundScreen(state: SetupState.Found, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier, onCopyCommands: (String) -> Unit = {}) {
    val summary = state.plan.summary
    val off = summary.groups.sumOf { it.off }
    PendingScreenScaffold(modifier,
        topBar = { TopBar(extras, onBack = { actions.back() }) },
        bottomBar = {
            PendingSafetyLine(stringResource(R.string.found_safety))
            if (extras.noScreenLock) PendingBody(stringResource(R.string.found_no_lock))
            PendingPrimaryButton(stringResource(R.string.found_make_clean), { actions.intent(SetupIntent.MakeItClean) }, loading = extras.starting)
            PendingTertiaryButton(stringResource(R.string.found_customise), { actions.intent(SetupIntent.Open(Sheet.CUSTOMISE)) }, Modifier.align(Alignment.CenterHorizontally))
        }) {
        PendingStepCounter(msg(R.string.step_counter, "step" to 3))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            if (off > 0) Text("$off", style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Light), color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(12.dp))
            PendingScreenTitle(msg(if (extras.unknownModel) R.string.found_unknown_headline else R.string.found_headline, "count" to off))
        }
        if (off == 0) PendingBody(stringResource(R.string.found_zero_body))
        if (extras.unknownModel) PendingBody(stringResource(R.string.found_unknown_body))
        PendingListGroup {
            summary.groups.filter { it.off > 0 }.forEach { g ->
                PendingListRow(g.title, value = "${g.off}", modifier = Modifier.semantics(mergeDescendants = true) {})
            }
            PendingListRow(stringResource(R.string.found_see_list), titleColor = MaterialTheme.colorScheme.tertiary, onClick = { actions.intent(SetupIntent.Open(Sheet.LIST)) })
        }
        val fixed = stringResource(R.string.found_keep_fixed)
        PendingBody(if (summary.keepNames.isEmpty()) msg(R.string.found_keep_paragraph_no_apps, "fixed" to fixed)
            else msg(R.string.found_keep_paragraph, "apps" to summary.keepNames.joinToString(", "), "fixed" to fixed))
        if (extras.homePossible && summary.homeScreenPossible) {
            val dock = summary.dock.ifEmpty { extras.dock.map { it.name } }.joinToString(", ")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(msg(R.string.found_home_row, "dock" to dock), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                PendingTertiaryButton(stringResource(R.string.found_home_change), { actions.intent(SetupIntent.Open(Sheet.DOCK)) })
            }
        } else {
            PendingBanner(msg(R.string.stop_tv_refuses_home_body, "brand" to (extras.tvName ?: "")), PendingBannerTone.INFO)
        }
    }
    when (state.sheet) {
        Sheet.LIST, Sheet.CUSTOMISE -> CustomiseSheet(state, extras, actions, onCopyCommands)
        Sheet.DOCK -> DockSheet(extras.dock, extras.apps, onDone = { actions.intent(SetupIntent.SetDock(it)); actions.intent(SetupIntent.Open(null)) },
            onDismiss = { actions.intent(SetupIntent.Open(null)) })
        Sheet.THEME -> ThemeSheet(extras, actions)
        else -> {}
    }
}

// S3a See the list / Customise (D2, D3, D21, D22).
@Composable
fun CustomiseSheet(state: SetupState.Found, extras: SetupExtras, actions: SetupActions, onCopyCommands: (String) -> Unit = {}) {
    val summary = state.plan.summary
    val total = summary.groups.sumOf { it.total }
    val off = summary.groups.sumOf { it.off }
    val custom = state.level == Level.CUSTOM
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keptText = stringResource(R.string.customise_snackbar_kept)
    val offText = stringResource(R.string.customise_snackbar_off)
    val undo = stringResource(R.string.action_undo)
    val locale = LocalConfiguration.current.locales[0]
    var why by remember { mutableStateOf<RowSummary?>(null) }
    PendingBottomSheet({ actions.intent(SetupIntent.Open(null)) }) {
        PendingSheetHeader(stringResource(R.string.customise_title), trailing = msg(R.string.customise_live, "total" to total, "off" to off),
            subtitle = stringResource(R.string.customise_nothing_deleted))
        val (protectedRows, groups) = summary.groups.map { g -> g to g.rows.filter { it.protected } }.let { pairs ->
            pairs.flatMap { it.second } to pairs.map { (g, _) -> g.copy(rows = g.rows.filterNot { it.protected }) }.filter { it.rows.isNotEmpty() }
        }
        groups.forEach { g ->
            GroupSection(g, custom) { row ->
                actions.intent(SetupIntent.Keep(row.pkg, keep = row.off))
                scope.launch {
                    val text = android.icu.text.MessageFormat(if (row.off) keptText else offText, locale).format(mapOf("name" to row.friendlyName))
                    if (snack.showSnackbar(text, actionLabel = undo, duration = androidx.compose.material3.SnackbarDuration.Short) == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        actions.intent(SetupIntent.Keep(row.pkg, keep = !row.off))
                    }
                }
            }
        }
        if (protectedRows.isNotEmpty()) {
            PendingOverline(stringResource(R.string.customise_protected_title))
            PendingListGroup {
                protectedRows.forEach { r ->
                    PendingListRow(r.friendlyName, subtitle = r.description, trailing = {
                        PendingTertiaryButton(stringResource(R.string.customise_protected_why), { why = r })
                    })
                }
            }
        }
        PendingOverline(stringResource(R.string.customise_group_home))
        PendingListGroup {
            PendingListRow(msg(R.string.customise_dock_row, "names" to extras.dock.joinToString(", ") { it.name }), onClick = { actions.intent(SetupIntent.Open(Sheet.DOCK)) })
            val theme = extras.themes.firstOrNull { it.id == extras.draft?.themeId }?.name ?: stringResource(R.string.theme_default)
            PendingListRow(msg(R.string.customise_look_row, "theme" to theme), onClick = { actions.intent(SetupIntent.Open(Sheet.THEME)) })
        }
        PendingOverline(stringResource(R.string.customise_group_afterwards))
        PendingListGroup {
            PendingSwitchRow(stringResource(R.string.customise_keep_debugging), extras.draft?.keepDebuggingOn == true,
                { actions.intent(SetupIntent.KeepDebuggingOn(it)) }, subtitle = stringResource(R.string.customise_keep_debugging_warning), warning = true)
        }
        PendingOverline(stringResource(R.string.customise_group_commands))
        PendingBody(stringResource(R.string.customise_commands_intro))
        PendingMonospaceBlock(extras.commands, copyLabel = stringResource(R.string.customise_copy_commands), onCopy = { onCopyCommands(extras.commands.joinToString("\n")) })
        SnackbarHost(snack)
        PendingGap()
    }
    why?.let { r ->
        PendingBottomSheet({ why = null }) {
            PendingSheetHeader(stringResource(R.string.customise_protected_why_title))
            PendingBody(r.whyProtected ?: r.description, color = MaterialTheme.colorScheme.onSurface)
            PendingGap()
        }
    }
}

@Composable
private fun GroupSection(g: GroupSummary, custom: Boolean, onToggle: (RowSummary) -> Unit) {
    var expanded by rememberSaveable(g.id) { mutableStateOf(false) }
    val visible = if (expanded || g.rows.size <= 6) g.rows else g.rows.take(4)
    PendingOverline("${g.title}  ${msg(R.string.customise_live, "total" to g.total, "off" to g.off)}")
    PendingListGroup {
        visible.forEach { r ->
            val cd = msg(if (r.off) R.string.customise_pill_off_cd else R.string.customise_pill_kept_cd, "name" to r.friendlyName)
            PendingDiffRow(r.friendlyName, r.description, if (custom) r.pkg.value else null, r.off, stringResource(R.string.customise_pill_off),
                stringResource(R.string.customise_pill_kept), onToggle = { onToggle(r) }, stateDescription = cd)
        }
        if (visible.size < g.rows.size) PendingListRow(msg(R.string.customise_show_more, "count" to g.rows.size - visible.size),
            titleColor = MaterialTheme.colorScheme.tertiary, onClick = { expanded = true })
    }
}

// S3b Your dock: up to four, with up and down buttons for motor access (UX 7.1).
@Composable
fun DockSheet(current: List<AppChoice>, apps: List<AppChoice>, onDone: (List<PackageName>) -> Unit, onDismiss: () -> Unit) {
    var dock by remember(current) { mutableStateOf(current) }
    PendingBottomSheet(onDismiss) {
        PendingSheetHeader(stringResource(R.string.dock_title), subtitle = stringResource(R.string.dock_body))
        PendingOverline(stringResource(R.string.dock_section_in))
        PendingListGroup {
            dock.forEachIndexed { i, app ->
                val upCd = msg(R.string.dock_move_up_cd, "name" to app.name)
                val downCd = msg(R.string.dock_move_down_cd, "name" to app.name)
                val outCd = msg(R.string.dock_remove_cd, "name" to app.name)
                val inCd = msg(R.string.dock_item_in_cd, "name" to app.name, "position" to i + 1)
                PendingListRow(app.name, modifier = Modifier.semantics { contentDescription = inCd }, trailing = {
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
                PendingListRow(app.name, onClick = if (dock.size < 4) ({ dock = dock + app }) else null)
            }
        }
        PendingPrimaryButton(stringResource(R.string.action_done), { onDone(dock.map { it.pkg }) })
        PendingGap()
    }
}

@Composable
private fun ThemeSheet(extras: SetupExtras, actions: SetupActions) {
    PendingBottomSheet({ actions.intent(SetupIntent.Open(null)) }) {
        PendingSheetHeader(stringResource(R.string.theme_title))
        PendingListGroup {
            val selected = extras.draft?.themeId
            PendingListRow(stringResource(R.string.theme_default), subtitle = stringResource(R.string.theme_default_desc),
                value = if (selected == null) "✓" else null, onClick = { actions.intent(SetupIntent.SetTheme("default")) })
            extras.themes.filter { it.id != "default" }.forEach { t ->
                PendingListRow(t.name, subtitle = t.description, value = if (selected == t.id) "✓" else null, onClick = { actions.intent(SetupIntent.SetTheme(t.id)) })
            }
        }
        PendingGap()
    }
}

// S4a Making it clean: narration, live thumbnail, the quiet "Stop and undo" (UX 4a, D18, D32).
@Composable
fun WorkingScreen(state: SetupState.Working, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    val run = state.run
    PendingScreenScaffold(modifier, topBar = { TopBar(extras) }, bottomBar = {
        PendingTertiaryButton(stringResource(if (extras.rollingBack) R.string.working_stopping else R.string.working_stop), { actions.intent(SetupIntent.StopAndUndo) },
            Modifier.align(Alignment.CenterHorizontally), quiet = !extras.highlightStop, enabled = !extras.rollingBack)
    }) {
        PendingStepCounter(msg(R.string.step_counter, "step" to 4))
        val percentCd = msg(R.string.working_percent_cd, "percent" to run.percent)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PendingScreenTitle(stringResource(R.string.working_title), Modifier.weight(1f))
            Text(msg(R.string.working_percent, "percent" to run.percent), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = percentCd })
        }
        PendingProgressBar(run.percent / 100f)
        PendingGap(12)
        Narration(run)
        if (extras.rollingBack) PendingLiveLine(stringResource(R.string.working_putting_back), found = false)
        PendingTvThumbnail(state.thumbnail ?: run.thumbnail, stringResource(R.string.working_thumbnail_cd), label = stringResource(R.string.working_thumbnail_caption))
        PendingBody(stringResource(R.string.working_keep_on))
    }
}

// DESIGN-SYSTEM 8.7: at most six lines; spinner, tick, hollow dot, dash. Polite live region.
@Composable
private fun Narration(run: RunState.Running) {
    val lines = run.narration
    val start = (lines.indexOfFirst { it.state == NarrationState.DOING }.takeIf { it >= 0 } ?: lines.size).minus(3).coerceIn(0, maxOf(0, lines.size - 6))
    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        androidx.compose.foundation.layout.Column {
            lines.drop(start).take(6).forEach { l ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (l.state) {
                        NarrationState.DOING -> PendingSpinner()
                        NarrationState.DONE -> Text("✓", color = PendingTokens.success)
                        NarrationState.SKIPPED -> Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        NarrationState.FAILED -> Text("!", color = PendingTokens.warning)
                        NarrationState.UPCOMING -> Text("○", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(l.text, style = MaterialTheme.typography.bodyMedium,
                        color = if (l.state == NarrationState.UPCOMING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

// S4b Can you see it? (DESIGN-SYSTEM 8.6, D23): the ring counts down from the engine's deadline;
// TalkBack hears 60, 30 and 10 seconds.
@Composable
fun CheckScreen(state: SetupState.Check, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier, nowMs: () -> Long = System::currentTimeMillis) {
    val run = state.run
    var now by remember { mutableLongStateOf(nowMs()) }
    // Ticks once a second until the deadline; a clock that stands still (tests) stops the loop.
    LaunchedEffect(run.deadlineMs) {
        while (now < run.deadlineMs) {
            delay(1_000)
            val t = nowMs()
            if (t == now) break
            now = t
        }
    }
    val remaining = (run.deadlineMs - now).coerceAtLeast(0)
    val total = run.section.confirmWindowMs.coerceAtLeast(1)
    val view = LocalView.current
    val a60 = stringResource(R.string.check_announce_60)
    val a30 = stringResource(R.string.check_announce_30)
    val a10 = stringResource(R.string.check_announce_10)
    val secs = (remaining / 1000).toInt()
    LaunchedEffect(secs) {
        when (secs) { 60 -> a60; 30 -> a30; 10 -> a10; else -> null }?.let { @Suppress("DEPRECATION") view.announceForAccessibility(it) }
    }
    PendingScreenScaffold(modifier, topBar = { TopBar(extras) }, bottomBar = {
        PendingPrimaryButton(stringResource(R.string.check_yes), { actions.intent(SetupIntent.YesISeeIt) })
        PendingSecondaryButton(stringResource(R.string.check_no), { actions.intent(SetupIntent.NoPutItBack) })
    }) {
        PendingStepCounter(stringResource(R.string.check_overline))
        PendingScreenTitle(stringResource(R.string.check_title))
        val shot = state.thumbnail ?: run.thumbnail
        PendingTvThumbnail(shot, msg(R.string.check_thumbnail_cd, "count" to state.dockNames.size), label = stringResource(R.string.working_thumbnail_caption))
        PendingBody(if (state.dockNames.isEmpty()) stringResource(R.string.check_dock_sentence_empty)
            else msg(R.string.check_dock_sentence, "dock" to state.dockNames.joinToString(", ")), color = MaterialTheme.colorScheme.onSurface)
        PendingStepCard(stringResource(R.string.check_press_home), "", trailing = {
            PendingCheckLine(stringResource(R.string.check_live_ok), done = run.homeCheck == true)
        })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            PendingCountdownRing(remaining, total, description = msg(R.string.check_timer_cd, "seconds" to secs))
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.check_countdown_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PendingBody(stringResource(if (shot == null) R.string.check_no_thumbnail_note else R.string.check_thumbnail_note))
    }
}

// S4c, S4d, S4e: stop cards with a grey icon and the way out on the card (UX 4).
@Composable
fun StoppedScreen(state: SetupState.Stopped, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    val c = state.card
    var details by rememberSaveable { mutableStateOf(false) }
    val homeCase = c.case in setOf(StopCase.HOME_SETUPWRAITH, StopCase.HOME_OTHER_LAUNCHER, StopCase.HOME_UNKNOWN, StopCase.HOME_USER_SAID_NO, StopCase.HOME_TIMED_OUT, StopCase.UNDONE, StopCase.HOME_PUT_BACK)
    PendingScreenScaffold(modifier, topBar = { TopBar(extras) }) {
        PendingGap(24)
        val body = if (c.case == StopCase.CONNECTION_LOST && extras.reconnect == Reconnect.GAVE_UP) stringResource(R.string.stop_connection_lost_gone_body) else c.body
        PendingStopCard(c.title, body,
            primaryLabel = c.primary, onPrimary = { actions.intent(SetupIntent.StopAction(StopAction.PRIMARY)) },
            secondaryLabel = c.secondary, onSecondary = { actions.intent(SetupIntent.StopAction(StopAction.SECONDARY)) },
            tertiaryLabel = c.tertiary, onTertiary = { actions.intent(SetupIntent.StopAction(StopAction.TERTIARY)) },
            detailsLabel = if (homeCase && extras.commands.isNotEmpty()) stringResource(R.string.stop_show_details) else null, onDetails = { details = true },
            busyText = when {
                c.case == StopCase.CONNECTION_LOST && extras.reconnect == Reconnect.TRYING -> stringResource(R.string.stop_connection_lost_reconnecting)
                extras.rollingBack -> stringResource(R.string.stop_home_wait_hint)
                else -> null
            })
        if (c.case == StopCase.CONNECTION_LOST && extras.reconnect == Reconnect.BACK) PendingBanner(stringResource(R.string.stop_connection_lost_back), PendingBannerTone.SUCCESS)
    }
    // "Show details" opens the run's lines (D21); the full run log viewer comes with the engine's log.
    if (details) PendingBottomSheet({ details = false }) {
        PendingSheetHeader(stringResource(R.string.stop_show_details))
        PendingMonospaceBlock(extras.commands)
        PendingGap()
    }
}

// S5 Done (D7, D24, D31, UX 4.8 restart flow).
@Composable
fun DoneScreen(state: SetupState.Done, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    val s = state.summary
    val locale = LocalConfiguration.current.locales[0]
    PendingScreenScaffold(modifier, topBar = { TopBar(extras) }, bottomBar = {
        val finishing = extras.finishing
        PendingPrimaryButton(
            when (finishing) { Finishing.SWITCHING -> stringResource(R.string.done_finishing); Finishing.DONE -> stringResource(R.string.done_finished); null -> stringResource(R.string.done_finish) },
            { actions.intent(SetupIntent.Finish) }, loading = finishing == Finishing.SWITCHING, enabled = finishing == null)
    }) {
        PendingGap(24)
        val doneCd = stringResource(R.string.done_hero_cd)
        // TODO(W7 merge): GlassCheckMark() with Glow.Done (D31).
        Text("✓", fontSize = 48.sp, color = PendingTokens.success, modifier = Modifier.semantics { contentDescription = doneCd })
        PendingScreenTitle(stringResource(R.string.done_title))
        PendingLead(if (s.homeScreenOn) msg(R.string.done_body, "count" to s.switchedOff) else msg(R.string.done_body_no_home, "count" to s.switchedOff))
        PendingCheckLine(msg(R.string.done_restore_saved, "date" to dateTime(s.restorePointAtMs, locale)))
        PendingCheckLine(stringResource(if (extras.finishing == Finishing.DONE) R.string.done_finished else R.string.done_debug_note), done = extras.finishing == Finishing.DONE)
        when {
            s.skipped.size == 1 -> PendingBody(msg(R.string.done_skipped, "name" to s.skipped.single()))
            s.skipped.size > 1 -> PendingBody(msg(R.string.done_skipped_many, "count" to s.skipped.size))
        }
        RestartCard(state, extras, actions)
        extras.card?.takeIf { it.case == StopCase.NOT_BACK_AFTER_RESTART }?.let { c ->
            PendingStopCard(c.title, c.body, primaryLabel = c.primary, onPrimary = { actions.intent(SetupIntent.StopAction(StopAction.PRIMARY)) },
                secondaryLabel = c.secondary, onSecondary = { actions.intent(SetupIntent.StopAction(StopAction.SECONDARY)) })
        }
        PendingListGroup {
            PendingListRow(stringResource(R.string.done_add_card_title), subtitle = stringResource(R.string.done_add_card_body), onClick = { actions.intent(SetupIntent.AddCard) })
            PendingListRow(stringResource(R.string.done_note_title), onClick = { actions.intent(SetupIntent.SaveRestoreNote) })
        }
    }
}

@Composable
private fun RestartCard(state: SetupState.Done, extras: SetupExtras, actions: SetupActions) {
    val r = state.restartState
    val text = stringResource(if (state.summary.restartRecommended) R.string.restart_card_recommended else R.string.restart_card_optional)
    PendingStepCard(text, "", trailing = {
        when (r) {
            null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PendingSecondaryButton(stringResource(R.string.restart_button), { actions.intent(SetupIntent.Restart) }, Modifier.width(160.dp))
            }
            is RestartState.Restarting -> {
                PendingLiveLine(stringResource(R.string.restart_intro), found = false)
                if (extras.restartSlow) PendingBody(stringResource(R.string.restart_slow))
            }
            is RestartState.Back -> {
                PendingCheckLine(stringResource(R.string.restart_progress_home))
                if (extras.restartReenabled > 0) PendingBody(msg(R.string.restart_came_back, "count" to extras.restartReenabled))
                PendingCheckLine(stringResource(R.string.restart_progress_good))
            }
            is RestartState.NotBack -> {}
        }
    })
}
