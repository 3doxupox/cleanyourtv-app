package tv.cleanyour.ui.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.cleanyour.ui.design.R
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.theme.CytTheme
import tv.cleanyour.ui.design.theme.glass

// An app the dock editor can place: a stable key (the package name) and the name to show.
@Immutable
data class DockApp(val key: String, val name: String)

// The dock sheet (S3b) as one ordered list cut into rows of `columns`: the first row is the dock,
// the next are Row 2 and Row 3 under it on the TV's home screen. The launcher sorts its grid by
// the same list, so a row can only hold apps once the rows above it are full; the list cannot
// have a gap, and taking an app out pulls the next one up, as the TV would. A row under the dock
// opens only when the person asks for it ("Add a row under the dock") and the rows above are full.
@Stable
class DockEditorState(initial: List<DockApp>, columns: Int, val maxRows: Int = MAX_ROWS) {
    val columns: Int = columns.coerceAtLeast(1)
    val capacity: Int get() = columns * maxRows

    var apps: List<DockApp> by mutableStateOf(initial.distinctBy { it.key }.take(this.columns * maxRows))
        private set

    // "Add a row under the dock" was tapped and the new row is still empty.
    private var extraRow by mutableStateOf(false)

    // The rows on screen: the ones that hold an app, and the empty one just added.
    val rowCount: Int get() = if (apps.isEmpty()) 0 else (apps.size + columns - 1) / columns + if (extraRow) 1 else 0

    // The rows that hold an app (what rows_below_dock counts, plus the dock).
    val filledRows: Int get() = (apps.size + columns - 1) / columns

    val hasRoom: Boolean get() = apps.size < maxOf(rowCount, 1) * columns

    val canAddRow: Boolean get() = apps.isNotEmpty() && !extraRow && apps.size % columns == 0 && apps.size < capacity

    fun rowOf(index: Int): Int = index / columns

    fun rowApps(row: Int): List<DockApp> = apps.drop(row * columns).take(columns)

    fun add(app: DockApp) {
        if (!hasRoom || apps.any { it.key == app.key }) return
        apps = apps + app
        extraRow = false
    }

    fun remove(key: String) {
        apps = apps.filterNot { it.key == key }
        extraRow = false
    }

    fun move(from: Int, to: Int) {
        if (from !in apps.indices) return
        val target = to.coerceIn(apps.indices)
        if (target == from) return
        apps = apps.toMutableList().apply { add(target, removeAt(from)) }
    }

    // To the end of a row above, or the start of a row below (TalkBack's "Move to row 2").
    fun moveToRow(index: Int, row: Int) {
        val current = rowOf(index)
        if (row == current || row !in 0 until filledRows) return
        move(index, if (row < current) (row + 1) * columns - 1 else row * columns)
    }

    fun addRow() {
        if (canAddRow) extraRow = true
    }

    // The app being dragged and where its lifted copy is, in the editor's own coordinates.
    var dragging: String? by mutableStateOf(null)
        internal set
    internal var ghostTop by mutableFloatStateOf(Float.NaN)
    internal var ghostHeight by mutableIntStateOf(0)
    internal var settling by mutableStateOf(false)

    // Screenshot tests: shows the app at `from` lifted over place `to`, `nudgePx` past its slot.
    internal fun previewDrag(from: Int, to: Int, nudgePx: Float) {
        val key = apps.getOrNull(from)?.key ?: return
        move(from, to)
        dragging = key
        ghostTop = Float.NaN
        previewNudge = nudgePx
    }
    internal var previewNudge: Float = 0f

    companion object {
        const val MAX_ROWS = 3
    }
}

// Where each row sits, for the drag and for the spring that moves the others out of the way:
// layout positions in the editor's coordinates (outside each row's translation), the position
// each app had before the last change, and the offset each app springs back from.
@Stable
private class DockMotion {
    var editor: LayoutCoordinates? = null
    val coords = HashMap<String, LayoutCoordinates>()
    val lastTop = HashMap<String, Float>()
    val offsets = HashMap<String, Animatable<Float, AnimationVector1D>>()
    var awaitingLayout = false

    fun top(key: String): Float? {
        val e = editor?.takeIf { it.isAttached } ?: return null
        val c = coords[key]?.takeIf { it.isAttached } ?: return null
        return e.localPositionOf(c, Offset.Zero).y
    }

    fun height(key: String): Int = coords[key]?.takeIf { it.isAttached }?.size?.height ?: 0

    // Before a change: where every app is now, so each one that moves can spring from there.
    fun capture(keys: List<String>) {
        for (k in keys) top(k)?.let { lastTop[k] = it }
    }

    fun placed(key: String, c: LayoutCoordinates, animate: Boolean, scope: CoroutineScope) {
        coords[key] = c
        awaitingLayout = false
        val now = top(key) ?: return
        val before = lastTop.put(key, now)
        if (before == null || before == now) return
        val a = offsets.getOrPut(key) { Animatable(0f) }
        scope.launch {
            if (!animate) { a.snapTo(0f); return@launch }
            a.snapTo(a.value + before - now)
            a.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    fun offset(key: String): Float = offsets[key]?.value ?: 0f

    // A preview lift (screenshot tests) waits for the first layout to know where its slot is.
    fun seedGhost(state: DockEditorState) {
        val key = state.dragging ?: return
        if (state.ghostTop.isNaN()) top(key)?.let { state.ghostTop = it + state.previewNudge; state.ghostHeight = height(key) }
    }
}

// The dock, its rows and the other apps (S3b). Hold an app and drag it to a new place, in its row
// or another one; the others make room. The up and down buttons stay for motor access (UX 7.1),
// and TalkBack gets "Move up", "Move down", "Move to row 2" and "Take out" on every app. Under
// reduced motion nothing lifts or springs: the apps change places at once.
@Composable
fun DockEditor(state: DockEditorState, apps: List<DockApp>, modifier: Modifier = Modifier, haptics: Boolean = true) {
    val reduced = CytTheme.reducedMotion
    val feel = rememberCytHaptics(haptics)
    val scope = rememberCoroutineScope()
    val motion = remember(state) { DockMotion() }
    fun edit(change: () -> Unit) {
        motion.capture(state.apps.map { it.key })
        change()
    }
    Column(modifier.fillMaxWidth()) {
        // An empty dock has no "Dock" heading over nothing (round 3); the apps to add follow.
        if (state.apps.isNotEmpty()) {
            Box(
                Modifier.fillMaxWidth()
                    .onGloballyPositioned { motion.editor = it; motion.seedGhost(state) }
                    .pointerInput(state, motion, feel, reduced) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { at -> startDrag(state, motion, at) { feel.light() } },
                            onDrag = { change, amount -> if (dragBy(state, motion, amount.y)) change.consume() },
                            onDragEnd = { endDrag(state, motion, reduced, scope) { feel.light() } },
                            onDragCancel = { endDrag(state, motion, reduced, scope) { feel.light() } },
                        )
                    },
            ) {
                Column {
                    for (row in 0 until state.rowCount) {
                        val title = if (row == 0) stringResource(R.string.cyt_dock_row_dock) else stringResource(R.string.cyt_dock_row_n, row + 1)
                        Overline(title, Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
                        ListGroup {
                            val inRow = state.rowApps(row)
                            if (inRow.isEmpty()) {
                                Text(
                                    stringResource(R.string.cyt_dock_row_empty), Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(16.dp),
                                    color = CytTheme.colors.textSecondary, style = CytTheme.type.bodyMedium,
                                )
                            }
                            inRow.forEachIndexed { j, app ->
                                key(app.key) {
                                    if (j > 0) ListDivider()
                                    DockItem(state, motion, row * state.columns + j, app, reduced, scope, ::edit)
                                }
                            }
                        }
                    }
                }
                DragGhost(state, reduced)
            }
            val note = when {
                state.hasRoom && state.apps.size % state.columns != 0 -> stringResource(R.string.cyt_dock_gaps)
                state.hasRoom -> null
                state.canAddRow && state.rowCount == 1 -> stringResource(R.string.cyt_dock_full, state.columns)
                state.canAddRow -> stringResource(R.string.cyt_dock_row_full)
                else -> stringResource(R.string.cyt_dock_all_full)
            }
            if (note != null) {
                Text(note, Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), color = CytTheme.colors.textSecondary, style = CytTheme.type.bodyMedium)
            }
            if (state.canAddRow) {
                TertiaryButton(stringResource(R.string.cyt_dock_add_row), { edit { state.addRow() } }, Modifier.padding(top = 4.dp))
            }
        }
        val others = apps.filter { a -> state.apps.none { it.key == a.key } }
        if (others.isNotEmpty()) {
            Overline(stringResource(R.string.cyt_dock_others), Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
            ListGroup {
                val room = state.hasRoom
                val targetRow = state.rowOf(state.apps.size)
                others.forEachIndexed { i, app ->
                    if (i > 0) ListDivider()
                    val cd = when {
                        !room -> stringResource(R.string.cyt_dock_item_out_cd, app.name)
                        targetRow == 0 -> stringResource(R.string.cyt_dock_add_dock_cd, app.name)
                        else -> stringResource(R.string.cyt_dock_add_row_cd, app.name, targetRow + 1)
                    }
                    DockRow(
                        app.name, Modifier.semantics { contentDescription = cd },
                        controls = if (room) ({ CytIcon(CytIcons.Plus, null, tint = CytTheme.colors.accent) }) else null,
                        onClick = if (room) ({ edit { state.add(app) } }) else null,
                    )
                }
            }
        }
    }
}

// One app in a row: the tile and name, up, down and take out, and the TalkBack actions.
@Composable
private fun DockItem(
    state: DockEditorState,
    motion: DockMotion,
    index: Int,
    app: DockApp,
    reduced: Boolean,
    scope: CoroutineScope,
    edit: (() -> Unit) -> Unit,
) {
    val row = state.rowOf(index)
    val place = index % state.columns + 1
    val last = state.apps.size - 1
    val cd = if (row == 0) stringResource(R.string.cyt_dock_item_dock_cd, app.name, place)
    else stringResource(R.string.cyt_dock_item_row_cd, app.name, row + 1, place)
    val up = stringResource(R.string.cyt_dock_action_up)
    val down = stringResource(R.string.cyt_dock_action_down)
    val out = stringResource(R.string.cyt_dock_action_remove)
    val toDock = stringResource(R.string.cyt_dock_action_to_dock)
    val toRows = (1 until state.filledRows).associateWith { stringResource(R.string.cyt_dock_action_to_row, it + 1) }
    val actions = buildList {
        if (index > 0) add(CustomAccessibilityAction(up) { edit { state.move(index, index - 1) }; true })
        if (index < last) add(CustomAccessibilityAction(down) { edit { state.move(index, index + 1) }; true })
        for (r in 0 until state.filledRows) {
            if (r == row) continue
            add(CustomAccessibilityAction(if (r == 0) toDock else toRows.getValue(r)) { edit { state.moveToRow(index, r) }; true })
        }
        add(CustomAccessibilityAction(out) { edit { state.remove(app.key) }; true })
    }
    DockRow(
        app.name,
        Modifier
            .onGloballyPositioned {
                motion.placed(app.key, it, animate = !reduced && state.dragging != app.key, scope)
                motion.seedGhost(state)
            }
            .graphicsLayer {
                translationY = motion.offset(app.key)
                alpha = if (state.dragging == app.key) 0f else 1f
            }
            .semantics { contentDescription = cd; customActions = actions },
        controls = {
            Row {
                if (index > 0) CytIconButton(CytIcons.ArrowUp, stringResource(R.string.cyt_dock_move_up_cd, app.name), { edit { state.move(index, index - 1) } })
                if (index < last) CytIconButton(CytIcons.ArrowDown, stringResource(R.string.cyt_dock_move_down_cd, app.name), { edit { state.move(index, index + 1) } })
                CytIconButton(CytIcons.Minus, stringResource(R.string.cyt_dock_remove_cd, app.name), { edit { state.remove(app.key) } })
            }
        },
    )
}

// The lifted copy of the app being dragged: the glass highlight on a solid surface, a soft shadow
// and a 3% lift. Hidden from TalkBack, which moves apps with its actions instead.
@Composable
private fun DragGhost(state: DockEditorState, reduced: Boolean) {
    val key = state.dragging ?: return
    val app = state.apps.firstOrNull { it.key == key } ?: return
    val c = CytTheme.colors
    val shape = CytTheme.shapes.medium
    val lift = remember(key) { Animatable(1f) }
    val lifted = !state.settling && !reduced
    LaunchedEffect(key, lifted) { lift.animateTo(if (lifted) 1.03f else 1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) }
    val height = with(LocalDensity.current) { state.ghostHeight.toDp() }
    Box(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, if (state.ghostTop.isNaN()) 0 else state.ghostTop.roundToInt()) }
            .zIndex(1f)
            .graphicsLayer { scaleX = lift.value; scaleY = lift.value; alpha = if (state.ghostTop.isNaN()) 0f else 1f }
            .shadow(if (reduced) 4.dp else 12.dp, shape, clip = false)
            .background(c.surface2, shape)
            .glass(shape, CytTheme.highlight)
            .clearAndSetSemantics {},
    ) {
        DockRow(app.name, Modifier.heightIn(min = height), controls = {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CytIcon(CytIcons.Reorder, null, tint = c.textSecondary) }
        })
    }
}

// A long press on an app lifts it; anywhere else in the rows does nothing.
private fun startDrag(state: DockEditorState, motion: DockMotion, at: Offset, tick: () -> Unit) {
    if (state.settling || state.dragging != null) return
    val app = state.apps.firstOrNull { a ->
        val top = motion.top(a.key) ?: return@firstOrNull false
        at.y >= top && at.y < top + motion.height(a.key)
    } ?: return
    motion.capture(state.apps.map { it.key })
    state.previewNudge = 0f
    state.ghostTop = motion.top(app.key) ?: return
    state.ghostHeight = motion.height(app.key)
    state.dragging = app.key
    tick()
}

// Moves the lifted copy with the finger and the app to the place nearest its middle. Returns
// whether this drag is ours (so the sheet does not scroll under it).
private fun dragBy(state: DockEditorState, motion: DockMotion, dy: Float): Boolean {
    val key = state.dragging ?: return false
    if (state.settling) return true
    val h = motion.height(key).toFloat()
    val first = motion.top(state.apps.first().key) ?: return true
    val lastTop = motion.top(state.apps.last().key) ?: return true
    state.ghostTop = (state.ghostTop + dy).coerceIn(first - h / 2, lastTop + h / 2)
    if (motion.awaitingLayout) return true
    val from = state.apps.indexOfFirst { it.key == key }
    val middle = state.ghostTop + h / 2
    val to = state.apps.indices.minByOrNull { i ->
        val k = state.apps[i].key
        val top = motion.top(k) ?: return@minByOrNull Float.MAX_VALUE
        abs(top + motion.height(k) / 2f - middle)
    } ?: return true
    if (to != from) {
        motion.capture(state.apps.map { it.key })
        state.move(from, to)
        motion.awaitingLayout = true
    }
    return true
}

// Drops the app: the lifted copy settles into its place on a spring (at once under reduced motion).
private fun endDrag(state: DockEditorState, motion: DockMotion, reduced: Boolean, scope: CoroutineScope, tick: () -> Unit) {
    val key = state.dragging ?: return
    if (state.settling) return
    tick()
    if (reduced) {
        state.dragging = null
        return
    }
    state.settling = true
    scope.launch {
        withFrameNanos { }   // the last move's layout, so the place is where the app now is
        val target = motion.top(key) ?: state.ghostTop
        animate(state.ghostTop, target, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) { v, _ -> state.ghostTop = v }
        state.dragging = null
        state.settling = false
    }
}
