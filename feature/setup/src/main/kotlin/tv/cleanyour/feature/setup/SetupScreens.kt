// CytBottomSheet takes the Material 3 sheet state, which is still marked experimental.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package tv.cleanyour.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay
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
import tv.cleanyour.ui.design.component.ActionColumn
import tv.cleanyour.ui.design.component.Banner
import tv.cleanyour.ui.design.component.BannerTone
import tv.cleanyour.ui.design.component.CheckLine
import tv.cleanyour.ui.design.component.CheckState
import tv.cleanyour.ui.design.component.CollapsedGroupsCard
import tv.cleanyour.ui.design.component.CountdownConfirm
import tv.cleanyour.ui.design.component.CytBottomSheet
import tv.cleanyour.ui.design.component.CytIcon
import tv.cleanyour.ui.design.component.CytIconButton
import tv.cleanyour.ui.design.component.CytTopBar
import tv.cleanyour.ui.design.component.DiffGroup
import tv.cleanyour.ui.design.component.DiffGroupSection
import tv.cleanyour.ui.design.component.DiffItem
import tv.cleanyour.ui.design.component.DockRow
import tv.cleanyour.ui.design.component.Gap
import tv.cleanyour.ui.design.component.GlassCheckMark
import tv.cleanyour.ui.design.component.GroupTone
import tv.cleanyour.ui.design.component.Lead
import tv.cleanyour.ui.design.component.ListDivider
import tv.cleanyour.ui.design.component.ListGroup
import tv.cleanyour.ui.design.component.ListRow
import tv.cleanyour.ui.design.component.MonospaceBlock
import tv.cleanyour.ui.design.component.NarrationLine
import tv.cleanyour.ui.design.component.NarrationState
import tv.cleanyour.ui.design.component.NumeralHeadline
import tv.cleanyour.ui.design.component.Overline
import tv.cleanyour.ui.design.component.PlanSummaryCard
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.ProgressBar
import tv.cleanyour.ui.design.component.ProgressNarration
import tv.cleanyour.ui.design.component.ProtectedGroupSection
import tv.cleanyour.ui.design.component.SafetyLine
import tv.cleanyour.ui.design.component.ScreenScaffold
import tv.cleanyour.ui.design.component.ScreenTitle
import tv.cleanyour.ui.design.component.SheetHeader
import tv.cleanyour.ui.design.component.SmallGlassButton
import tv.cleanyour.ui.design.component.Spinner
import tv.cleanyour.ui.design.component.StepCounter
import tv.cleanyour.ui.design.component.StopCard
import tv.cleanyour.ui.design.component.SummaryGroup
import tv.cleanyour.ui.design.component.SwitchRow
import tv.cleanyour.ui.design.component.TertiaryButton
import tv.cleanyour.ui.design.component.TextActionRow
import tv.cleanyour.ui.design.component.ThemeOption
import tv.cleanyour.ui.design.component.ThemePicker
import tv.cleanyour.ui.design.component.TitleSize
import tv.cleanyour.ui.design.component.TvStatusChip
import tv.cleanyour.ui.design.component.TvThumbnail
import tv.cleanyour.ui.design.component.themeSwatch
import tv.cleanyour.ui.design.component.UndoSnackbarHost
import tv.cleanyour.ui.design.component.UndoSnackbarState
import tv.cleanyour.ui.design.component.rememberEmphasis
import tv.cleanyour.ui.design.component.rememberUndoSnackbarState
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.theme.CytTheme
import tv.cleanyour.ui.design.theme.Glow
import tv.cleanyour.core.engine.NarrationState as EngineNarration

// Screens S2d probing, S3, S3a, S3b, S4a, S4b, S4c to S4e and S5 (PRODUCT-SPEC 4, UX 2 screens 3 to 5,
// UX 4.7, 4.8, 4.14), built from the :ui:design components. Stateless over SetupState and
// SetupExtras.
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

private const val WIZARD_STEPS = 5

@Composable
private fun TopBar(extras: SetupExtras, onBack: (() -> Unit)? = null) =
    CytTopBar(onBack = onBack, center = { extras.tvName?.let { TvStatusChip(it, (extras.chip ?: ChipState.CONNECTED).toTvStatus()) } })

fun dateTime(ms: Long, locale: java.util.Locale): String = SimpleDateFormat("d MMM, HH:mm", locale).format(Date(ms))

// S2d to S3: the probe's progress while the summary fills in.
@Composable
fun ProbingScreen(state: SetupState.Probing, extras: SetupExtras, modifier: Modifier = Modifier) {
    ScreenScaffold(Glow.Tv, modifier, topBar = { TopBar(extras) }) {
        StepCounter(3, WIZARD_STEPS, Modifier.padding(top = 8.dp))
        StatusLine(stringResource(R.string.probing_line), Modifier.padding(start = 4.dp, top = 20.dp))
        val stages = tv.cleanyour.core.probe.ProbeStage.entries
        val probingCd = stringResource(R.string.probing_progress_cd)
        ProgressBar((stages.indexOf(state.stage) + 1f) / stages.size, Modifier.padding(top = 16.dp).semantics { contentDescription = probingCd })
    }
}

// S3 What we found (D4, D17 grey line, UX 2 rules behind the numbers, UX 4.6).
@Composable
fun FoundScreen(state: SetupState.Found, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier, onCopyCommands: (String) -> Unit = {}) {
    val summary = state.plan.summary
    val off = summary.groups.sumOf { it.off }
    ScreenScaffold(
        Glow.Tv, modifier,
        topBar = { TopBar(extras, onBack = { actions.back() }) },
        bottomBar = {
            // UX 7.3 rule 8: the safety sentence sits above the button that needs it, so it is
            // pinned with the button rather than scrolled under it (R4-W8).
            SafetyLine(stringResource(R.string.found_safety), Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp))
            ActionColumn {
                PrimaryButton(stringResource(R.string.found_make_clean), { actions.intent(SetupIntent.MakeItClean) }, loading = extras.starting)
                TertiaryButton(stringResource(R.string.found_customise), { actions.intent(SetupIntent.Open(Sheet.CUSTOMISE)) })
            }
        },
    ) {
        StepCounter(3, WIZARD_STEPS, Modifier.padding(top = 8.dp), extra = stringResource(R.string.found_overline))
        if (off > 0) {
            NumeralHeadline(off, msg(if (extras.unknownModel) R.string.found_unknown_headline else R.string.found_headline, "count" to off), Modifier.padding(top = 6.dp))
        } else {
            ScreenTitle(msg(if (extras.unknownModel) R.string.found_unknown_title else R.string.found_title, "count" to 0), Modifier.padding(top = 8.dp))
            if (!extras.unknownModel) Body(stringResource(R.string.found_zero_body), Modifier.padding(start = 4.dp, top = 8.dp))
        }
        if (extras.unknownModel) Body(stringResource(R.string.found_unknown_body), Modifier.padding(start = 4.dp, top = 12.dp))
        PlanSummaryCard(
            summary.groups.filter { it.off > 0 }.map { SummaryGroup(it.id, it.title, it.off) },
            stringResource(R.string.found_see_list), { actions.intent(SetupIntent.Open(Sheet.LIST)) }, Modifier.padding(top = 20.dp),
        )
        val fixed = stringResource(R.string.found_keep_fixed)
        val apps = summary.keepNames.joinToString(", ")
        val keep = if (summary.keepNames.isEmpty()) msg(R.string.found_keep_paragraph_no_apps, "fixed" to fixed)
        else msg(R.string.found_keep_paragraph, "apps" to apps, "fixed" to fixed)
        Text(
            rememberEmphasis(keep, apps), Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp),
            color = CytTheme.colors.textSecondary, style = CytTheme.type.bodyMedium,
        )
        if (extras.homePossible && summary.homeScreenPossible) {
            val dock = summary.dock.ifEmpty { extras.dock.map { it.name } }.joinToString(", ")
            TextActionRow(msg(R.string.found_home_row, "dock" to dock), stringResource(R.string.found_home_change), { actions.intent(SetupIntent.Open(Sheet.DOCK)) }, Modifier.padding(top = 8.dp))
        } else {
            Banner(msg(R.string.stop_tv_refuses_home_body, "brand" to (extras.tvName ?: "")), BannerTone.INFO, Modifier.padding(top = 16.dp))
        }
        if (extras.noScreenLock) Body(stringResource(R.string.found_no_lock), Modifier.padding(start = 4.dp, top = 12.dp))
        Gap(8.dp)
    }
    when (state.sheet) {
        Sheet.LIST, Sheet.CUSTOMISE -> CustomiseSheet(state, extras, actions, onCopyCommands)
        Sheet.DOCK -> DockSheet(extras.dock, extras.apps, onDone = { actions.intent(SetupIntent.SetDock(it)); actions.intent(SetupIntent.Open(null)) },
            onDismiss = { actions.intent(SetupIntent.Open(null)) })
        Sheet.THEME -> CytBottomSheet({ actions.intent(SetupIntent.Open(null)) }) { ThemeContent(extras, actions) }
        else -> {}
    }
}

// S3a See the list / Customise (D2, D3, D21, D22): a modal sheet that scrolls, with its own undo
// snackbar inside the sheet window.
@Composable
fun CustomiseSheet(state: SetupState.Found, extras: SetupExtras, actions: SetupActions, onCopyCommands: (String) -> Unit = {}) {
    val undo = rememberUndoSnackbarState()
    var why by remember { mutableStateOf(false) }
    CytBottomSheet({ actions.intent(SetupIntent.Open(null)) }) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CustomiseContent(state, extras, actions, undo, onWhy = { why = true }, onCopyCommands = onCopyCommands)
            }
            UndoSnackbarHost(undo, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (why) CytBottomSheet({ why = false }) { WhyContent(state) }
}

@Composable
internal fun ColumnScope.CustomiseContent(
    state: SetupState.Found,
    extras: SetupExtras,
    actions: SetupActions,
    undo: UndoSnackbarState,
    onWhy: () -> Unit,
    onCopyCommands: (String) -> Unit,
    initiallyOpen: List<String>? = null,
) {
    val summary = state.plan.summary
    val total = summary.groups.sumOf { it.total }
    val off = summary.groups.sumOf { it.off }
    val custom = state.level == Level.CUSTOM
    val keptText = stringResource(R.string.customise_snackbar_kept)
    val offText = stringResource(R.string.customise_snackbar_off)
    val locale = LocalConfiguration.current.locales[0]
    SheetHeader(
        stringResource(R.string.customise_title),
        trailing = rememberEmphasis(msg(R.string.customise_live, "total" to total, "off" to off), "$off"),
        subtitle = stringResource(R.string.customise_nothing_deleted),
    )
    val protectedRows = summary.groups.flatMap { g -> g.rows.filter { it.protected } }
    val groups = summary.groups.map { g -> g.copy(rows = g.rows.filterNot { it.protected }) }.filter { it.rows.isNotEmpty() }
    // The first group opens; the others wait in one collapsed card until tapped (DESIGN-SYSTEM 8.4,
    // mockup 08).
    var open by rememberSaveable { mutableStateOf(initiallyOpen ?: listOfNotNull(groups.firstOrNull()?.id)) }
    groups.filter { it.id in open }.forEach { g ->
        GroupSection(g, custom) { row ->
            actions.intent(SetupIntent.Keep(row.pkg, keep = row.off))
            val text = android.icu.text.MessageFormat(if (row.off) keptText else offText, locale).format(mapOf("name" to row.friendlyName))
            undo.show(text) { actions.intent(SetupIntent.Keep(row.pkg, keep = !row.off)) }
        }
    }
    val closed = groups.filter { it.id !in open }
    if (closed.isNotEmpty()) {
        CollapsedGroupsCard(closed.map { g -> DiffGroup(g.id, g.title, g.rows.map { it.toItem() }) }, { open = open + it.key }, Modifier.padding(top = 10.dp))
    }
    if (protectedRows.isNotEmpty()) {
        ProtectedGroupSection(stringResource(R.string.customise_protected_title), protectedRows.map { it.toItem() }, onWhy, Modifier.padding(top = 16.dp), showPackageNames = custom)
    }
    Overline(stringResource(R.string.customise_group_home), Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
    ListGroup {
        ListRow(msg(R.string.customise_dock_row, "names" to extras.dock.joinToString(", ") { it.name }), onClick = { actions.intent(SetupIntent.Open(Sheet.DOCK)) })
        ListDivider()
        val theme = extras.themes.firstOrNull { it.id == extras.draft?.themeId }?.name ?: stringResource(R.string.theme_default)
        ListRow(msg(R.string.customise_look_row, "theme" to theme), onClick = { actions.intent(SetupIntent.Open(Sheet.THEME)) })
    }
    Overline(stringResource(R.string.customise_group_afterwards), Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
    ListGroup {
        SwitchRow(
            stringResource(R.string.customise_keep_debugging), extras.draft?.keepDebuggingOn == true,
            { actions.intent(SetupIntent.KeepDebuggingOn(it)) }, subtitle = stringResource(R.string.customise_keep_debugging_warning), warning = true,
        )
    }
    Overline(stringResource(R.string.customise_group_commands), Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
    Body(stringResource(R.string.customise_commands_intro), Modifier.padding(start = 4.dp))
    MonospaceBlock(
        extras.commands, Modifier.padding(top = 8.dp),
        copyLabel = stringResource(R.string.customise_copy_commands), onCopy = { onCopyCommands(extras.commands.joinToString("\n")) },
    )
}

private fun RowSummary.toItem() = DiffItem(pkg.value, friendlyName, description, pkg.value, off)

// A group of the full list: all rows when there are six or fewer, otherwise four and "Show n more".
@Composable
private fun GroupSection(g: GroupSummary, custom: Boolean, onToggle: (RowSummary) -> Unit) {
    var expanded by rememberSaveable(g.id) { mutableStateOf(false) }
    val visible = if (expanded || g.rows.size <= 6) g.rows.size else 4
    DiffGroupSection(
        DiffGroup(g.id, g.title, g.rows.map { it.toItem() }),
        onToggle = { item -> g.rows.firstOrNull { it.pkg.value == item.key }?.let(onToggle) },
        modifier = Modifier.padding(top = 16.dp), showPackageNames = custom, visibleCount = visible, onShowMore = { expanded = true },
    )
}

// "Why?" on the protected group: one reason per row.
@Composable
internal fun WhyContent(state: SetupState.Found) {
    SheetHeader(stringResource(R.string.customise_protected_why_title))
    state.plan.summary.groups.flatMap { g -> g.rows.filter { it.protected } }.forEach { r ->
        Text(r.friendlyName, Modifier.padding(top = 16.dp), color = CytTheme.colors.textPrimary, style = CytTheme.type.titleMedium)
        Body(r.whyProtected ?: r.description, Modifier.padding(top = 4.dp))
    }
}

// S3b Your dock: up to four, with up and down buttons for motor access (UX 7.1).
@Composable
fun DockSheet(current: List<AppChoice>, apps: List<AppChoice>, onDone: (List<PackageName>) -> Unit, onDismiss: () -> Unit) {
    var dock by remember(current) { mutableStateOf(current) }
    CytBottomSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            DockContent(dock, apps, { dock = it }, onDone = { onDone(dock.map { it.pkg }) })
        }
    }
}

@Composable
internal fun DockContent(dock: List<AppChoice>, apps: List<AppChoice>, onChange: (List<AppChoice>) -> Unit, onDone: () -> Unit) {
    SheetHeader(stringResource(R.string.dock_title), subtitle = stringResource(R.string.dock_body))
    Overline(stringResource(R.string.dock_section_in), Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
    ListGroup {
        dock.forEachIndexed { i, app ->
            if (i > 0) ListDivider()
            val inCd = msg(R.string.dock_item_in_cd, "name" to app.name, "position" to i + 1)
            DockRow(
                app.name, Modifier.semantics { contentDescription = inCd },
                controls = {
                    Row {
                        if (i > 0) CytIconButton(CytIcons.ArrowUp, msg(R.string.dock_move_up_cd, "name" to app.name), { onChange(dock.toMutableList().apply { add(i - 1, removeAt(i)) }) })
                        if (i < dock.size - 1) CytIconButton(CytIcons.ArrowDown, msg(R.string.dock_move_down_cd, "name" to app.name), { onChange(dock.toMutableList().apply { add(i + 1, removeAt(i)) }) })
                        CytIconButton(CytIcons.Minus, msg(R.string.dock_remove_cd, "name" to app.name), { onChange(dock - app) })
                    }
                },
            )
        }
    }
    if (dock.size >= 4) Body(stringResource(R.string.dock_full_note), Modifier.padding(start = 4.dp, top = 8.dp))
    val others = apps.filter { a -> dock.none { it.pkg == a.pkg } }
    if (others.isNotEmpty()) {
        Overline(stringResource(R.string.dock_section_other), Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
        ListGroup {
            others.forEachIndexed { i, app ->
                if (i > 0) ListDivider()
                val room = dock.size < 4
                val cd = msg(if (room) R.string.dock_add_cd else R.string.dock_item_out_cd, "name" to app.name)
                DockRow(
                    app.name, Modifier.semantics { contentDescription = cd },
                    controls = if (room) ({ CytIcon(CytIcons.Plus, null, tint = CytTheme.colors.accent) }) else null,
                    onClick = if (room) ({ onChange(dock + app) }) else null,
                )
            }
        }
    }
    PrimaryButton(stringResource(R.string.action_done), onDone, Modifier.padding(top = 20.dp))
}

// The look picker (S3a "Look: Default >"): Default, Aurora, Six columns.
@Composable
internal fun ThemeContent(extras: SetupExtras, actions: SetupActions) {
    SheetHeader(stringResource(R.string.theme_title))
    // The same picker as M2 (ThemePicker with the preview tiles), so the look reads the same in
    // the wizard and on Manage (R4-W8). A tap here only changes the draft, so it is not guarded.
    val options = listOf(ThemeOption("default", stringResource(R.string.theme_default), stringResource(R.string.theme_default_desc), themeSwatch("default"))) +
        extras.themes.filter { it.id != "default" }.map { ThemeOption(it.id, it.name, themeDescription(it.id) ?: it.description, themeSwatch(it.id)) }
    ThemePicker(options, extras.draft?.themeId ?: "default", { actions.intent(SetupIntent.SetTheme(it.id)) }, Modifier.padding(top = 16.dp))
}

@Composable
private fun themeDescription(id: String): String? = when (id) {
    "aurora" -> stringResource(R.string.theme_aurora_desc)
    "six-columns" -> stringResource(R.string.theme_six_columns_desc)
    else -> null
}

// S4a Making it clean: narration, live thumbnail, the quiet "Stop and undo" (UX 4a, D18, D32).
@Composable
fun WorkingScreen(state: SetupState.Working, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    val run = state.run
    ScreenScaffold(
        Glow.Tv, modifier,
        topBar = { TopBar(extras) },
        bottomBar = {
            ActionColumn {
                TertiaryButton(
                    stringResource(if (extras.rollingBack) R.string.working_stopping else R.string.working_stop), { actions.intent(SetupIntent.StopAndUndo) },
                    quiet = !extras.highlightStop, enabled = !extras.rollingBack,
                )
            }
        },
    ) {
        StepCounter(4, WIZARD_STEPS, Modifier.padding(top = 8.dp))
        ProgressNarration(stringResource(R.string.working_title), run.percent, run.narration.toLines(), Modifier.padding(top = 8.dp))
        if (extras.rollingBack) StatusLine(stringResource(R.string.working_putting_back), Modifier.padding(start = 4.dp, top = 16.dp))
        TvThumbnail(
            rememberPng(state.thumbnail ?: run.thumbnail), stringResource(R.string.working_thumbnail_cd), Modifier.padding(top = 20.dp),
            label = stringResource(R.string.working_thumbnail_caption),
        )
        Body(stringResource(R.string.working_keep_on), Modifier.padding(start = 4.dp, top = 16.dp))
    }
}

// The engine's five line states on the design system's four: a step that did not work is shown as
// skipped (it is named on Done, UX 4.14).
private fun List<tv.cleanyour.core.engine.NarrationLine>.toLines(): List<NarrationLine> = mapIndexed { i, l ->
    NarrationLine(
        "$i:${l.text}", l.text,
        when (l.state) {
            EngineNarration.DOING -> NarrationState.DOING
            EngineNarration.DONE -> NarrationState.DONE
            EngineNarration.UPCOMING -> NarrationState.UPCOMING
            EngineNarration.SKIPPED, EngineNarration.FAILED -> NarrationState.SKIPPED
        },
    )
}

// S4b Can you see it? (DESIGN-SYSTEM 8.6, D23): the ring counts down from the engine's deadline;
// the ring itself tells TalkBack at 60, 30 and 10 seconds.
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
    val shot = rememberPng(state.thumbnail ?: run.thumbnail)
    val locale = LocalConfiguration.current.locales[0]
    // "Netflix, YouTube, Prime Video and Live TV" in the person's language.
    val names = remember(state.dockNames, locale) { android.icu.text.ListFormatter.getInstance(locale).format(state.dockNames) }
    val dock = if (state.dockNames.isEmpty()) stringResource(R.string.check_dock_sentence_empty)
    else msg(R.string.check_dock_sentence, "dock" to names)
    ScreenScaffold(Glow.Tv, modifier, scrollable = false, topBar = { TopBar(extras) }) {
        CountdownConfirm(
            overline = stringResource(R.string.check_overline),
            title = stringResource(R.string.check_title),
            thumbnail = shot,
            thumbnailDescription = msg(R.string.check_thumbnail_cd, "count" to state.dockNames.size),
            sentence = dock + " " + stringResource(R.string.check_thumbnail_note),
            instruction = stringResource(R.string.check_press_home),
            liveCheck = stringResource(R.string.check_live_ok),
            liveCheckPassed = run.homeCheck == true,
            remainingMs = remaining, totalMs = total,
            timeoutSentence = stringResource(R.string.check_countdown_text),
            yesLabel = stringResource(R.string.check_yes), noLabel = stringResource(R.string.check_no),
            onYes = { actions.intent(SetupIntent.YesISeeIt) }, onNo = { actions.intent(SetupIntent.NoPutItBack) },
            noThumbnailSentence = dock + " " + stringResource(R.string.check_no_thumbnail_note),
        )
    }
}

// The home cases: their card offers "Show details" with the run's lines (D21).
private val HOME_CASES = setOf(
    StopCase.HOME_SETUPWRAITH, StopCase.HOME_OTHER_LAUNCHER, StopCase.HOME_UNKNOWN, StopCase.HOME_USER_SAID_NO,
    StopCase.HOME_TIMED_OUT, StopCase.UNDONE, StopCase.HOME_PUT_BACK,
)

// S4c, S4d, S4e: stop cards with a grey icon and the way out on the card (UX 4).
@Composable
fun StoppedScreen(state: SetupState.Stopped, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    val c = state.card
    var details by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(Glow.Tv, modifier, topBar = { TopBar(extras) }) {
        val body = if (c.case == StopCase.CONNECTION_LOST && extras.reconnect == Reconnect.GAVE_UP) stringResource(R.string.stop_connection_lost_gone_body) else c.body
        StopCard(
            c.title, body, Modifier.padding(top = 24.dp),
            primaryLabel = c.primary, onPrimary = { actions.intent(SetupIntent.StopAction(StopAction.PRIMARY)) },
            secondaryLabel = c.secondary, onSecondary = { actions.intent(SetupIntent.StopAction(StopAction.SECONDARY)) },
            tertiaryLabel = c.tertiary, onTertiary = { actions.intent(SetupIntent.StopAction(StopAction.TERTIARY)) },
            detailsLabel = if (c.case in HOME_CASES && extras.commands.isNotEmpty()) stringResource(R.string.stop_show_details) else null, onDetails = { details = true },
            busyText = when {
                c.case == StopCase.CONNECTION_LOST && extras.reconnect == Reconnect.TRYING -> stringResource(R.string.stop_connection_lost_reconnecting)
                extras.rollingBack -> stringResource(R.string.stop_home_wait_hint)
                else -> null
            },
            primaryGuarded = c.case.primaryActs(),
            secondaryGuarded = c.case.secondaryActs(),
            tertiaryGuarded = c.case.tertiaryActs(),
        )
        if (c.case == StopCase.CONNECTION_LOST && extras.reconnect == Reconnect.BACK) {
            Banner(stringResource(R.string.stop_connection_lost_back), BannerTone.SUCCESS, Modifier.padding(top = 16.dp))
        }
    }
    if (details) CytBottomSheet({ details = false }) { DetailsContent(extras.commands) }
}

@Composable
internal fun DetailsContent(lines: List<String>) {
    SheetHeader(stringResource(R.string.stop_show_details))
    MonospaceBlock(lines, Modifier.padding(top = 16.dp))
}

// S5 Done (D7, D24, D31, UX 4.8 restart flow).
@Composable
fun DoneScreen(state: SetupState.Done, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    val s = state.summary
    val locale = LocalConfiguration.current.locales[0]
    val finishing = extras.finishing
    ScreenScaffold(
        Glow.Done, modifier,
        topBar = { TopBar(extras) },
        bottomBar = {
            PrimaryButton(
                when (finishing) { Finishing.SWITCHING -> stringResource(R.string.done_finishing); Finishing.DONE -> stringResource(R.string.done_finished); null -> stringResource(R.string.done_finish) },
                { actions.intent(SetupIntent.Finish) }, loading = finishing == Finishing.SWITCHING, enabled = finishing == null,
            )
        },
    ) {
        val doneCd = stringResource(R.string.done_hero_cd)
        Box(Modifier.padding(top = 16.dp, start = 6.dp).semantics { contentDescription = doneCd }) { GlassCheckMark() }
        ScreenTitle(stringResource(R.string.done_title), Modifier.padding(top = 22.dp), size = TitleSize.DISPLAY)
        Lead(if (s.homeScreenOn) msg(R.string.done_body, "count" to s.switchedOff) else msg(R.string.done_body_no_home, "count" to s.switchedOff), Modifier.padding(top = 8.dp))
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckLine(msg(R.string.done_restore_saved, "date" to dateTime(s.restorePointAtMs, locale)))
            CheckLine(
                stringResource(if (finishing == Finishing.DONE) R.string.done_finished else R.string.done_debug_note),
                state = if (finishing == Finishing.SWITCHING) CheckState.PENDING else CheckState.DONE,
            )
        }
        when {
            s.skipped.size == 1 -> Body(msg(R.string.done_skipped, "name" to s.skipped.single()), Modifier.padding(start = 4.dp, top = 12.dp))
            s.skipped.size > 1 -> Body(msg(R.string.done_skipped_many, "count" to s.skipped.size), Modifier.padding(start = 4.dp, top = 12.dp))
        }
        RestartCard(state, extras, actions)
        extras.card?.takeIf { it.case == StopCase.NOT_BACK_AFTER_RESTART }?.let { c ->
            // The card is the news: scroll it into view when it appears instead of leaving it
            // under the pinned Finish (R4-W8).
            val bring = remember { BringIntoViewRequester() }
            LaunchedEffect(c.case) { bring.bringIntoView() }
            StopCard(
                c.title, c.body, Modifier.padding(top = 10.dp).bringIntoViewRequester(bring),
                primaryLabel = c.primary, onPrimary = { actions.intent(SetupIntent.StopAction(StopAction.PRIMARY)) },
                secondaryLabel = c.secondary, onSecondary = { actions.intent(SetupIntent.StopAction(StopAction.SECONDARY)) },
                primaryGuarded = c.case.primaryActs(),
            )
        }
        ListGroup(Modifier.padding(top = 10.dp)) {
            ListRow(stringResource(R.string.done_add_card_title), subtitle = stringResource(R.string.done_add_card_body), onClick = { actions.intent(SetupIntent.AddCard) })
            ListDivider()
            ListRow(stringResource(R.string.done_note_title), onClick = { actions.intent(SetupIntent.SaveRestoreNote) })
        }
        Gap(16.dp)
    }
}

// "Restart the TV to check it all sticks" with "Restart" (D24), then the UX 4.8 flow inline.
@Composable
private fun RestartCard(state: SetupState.Done, extras: SetupExtras, actions: SetupActions) {
    val r = state.restartState
    val title = stringResource(R.string.restart_card_title)
    val note = stringResource(if (state.summary.restartRecommended) R.string.restart_card_recommended else R.string.restart_card_optional)
    ListGroup(Modifier.padding(top = 18.dp), tone = GroupTone.RAISED) {
        when (r) {
            null, is RestartState.NotBack -> ListRow(
                title, subtitle = note,
                trailing = if (r == null) ({ SmallGlassButton(stringResource(R.string.restart_button), { actions.intent(SetupIntent.Restart) }) }) else null,
            )
            is RestartState.Restarting -> {
                ListRow(title, subtitle = stringResource(R.string.restart_intro), trailing = { Spinner() })
                if (extras.restartSlow) Body(stringResource(R.string.restart_slow), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
            }
            is RestartState.Back -> {
                ListRow(title)
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CheckLine(stringResource(R.string.restart_progress_home))
                    if (extras.restartReenabled > 0) CheckLine(msg(R.string.restart_came_back, "count" to extras.restartReenabled))
                    CheckLine(stringResource(R.string.restart_progress_good))
                }
            }
        }
    }
}
