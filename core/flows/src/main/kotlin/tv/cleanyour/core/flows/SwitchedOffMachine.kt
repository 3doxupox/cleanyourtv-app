package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.PlanRequest

// M4 "Switched off (23)": rows by group with "Put back" (one op, 5 s undo snackbar, UX 4.13); the
// Custom level adds the profile's `ask` groups with Off / Kept pills (D2).
data class OffRow(val pkg: PackageName, val name: String, val description: String?, val off: Boolean)
data class OffGroup(val id: String, val title: String, val rows: List<OffRow>)

data class SwitchedOffState(
    val tvName: String = "",
    val level: Level = Level.SIMPLE,
    val groups: List<OffGroup> = emptyList(),
    val askGroups: List<OffGroup> = emptyList(),
    val busy: PackageName? = null,
    val loading: Boolean = true,
    val count: Int = 0,
)

sealed interface SwitchedOffIntent {
    data class PutBack(val pkg: PackageName) : SwitchedOffIntent
    data class Undo(val pkg: PackageName) : SwitchedOffIntent           // the snackbar's Undo: switch it off again
    data class SetOff(val pkg: PackageName, val off: Boolean) : SwitchedOffIntent   // Custom: the ask groups' pills
    data object Refresh : SwitchedOffIntent
}

class SwitchedOffMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    private val tvId: TvId,
) : Machine<SwitchedOffState, SwitchedOffIntent, ManageEffect>(SwitchedOffState(), scope) {

    private val writer = TvWriter(d)
    private data class Loaded(val s: SwitchedOffState)
    private data class BusyOn(val pkg: PackageName?)

    init { scope.launch { load() } }

    override fun reduce(state: SwitchedOffState, event: Any): SwitchedOffState = when (event) {
        is Loaded -> event.s.copy(busy = state.busy)
        is BusyOn -> state.copy(busy = event.pkg)
        else -> state
    }

    override fun handle(event: Any, before: SwitchedOffState, after: SwitchedOffState) {
        if (after.busy != null && event is SwitchedOffIntent && event !is SwitchedOffIntent.Refresh) return
        when (event) {
            is SwitchedOffIntent.PutBack -> change(event.pkg, PlanRequest.PutBack(event.pkg), SnackKind.PUT_BACK, undo = true)
            is SwitchedOffIntent.Undo -> change(event.pkg, PlanRequest.SwitchOff(setOf(event.pkg)), SnackKind.SWITCHED_OFF, undo = false)
            is SwitchedOffIntent.SetOff -> if (event.off) change(event.pkg, PlanRequest.SwitchOff(setOf(event.pkg)), SnackKind.SWITCHED_OFF, undo = false)
                else change(event.pkg, PlanRequest.PutBack(event.pkg), SnackKind.PUT_BACK, undo = false)
            SwitchedOffIntent.Refresh -> scope.launch { load() }
            else -> {}
        }
    }

    private fun change(pkg: PackageName, request: PlanRequest, kind: SnackKind, undo: Boolean) {
        scope.launch {
            send(BusyOn(pkg))
            val r = writer.write(tvId, request)
            send(BusyOn(null))
            load()
            when (r) {
                // RF-LAUNCHER-250 F1: kept because it runs the TV's inputs; nothing to undo.
                is WriteResult.Done -> if (kind == SnackKind.SWITCHED_OFF && r.finished.skipped.isNotEmpty() && d.ledger.keptTvInputs(r.plan.id).isNotEmpty())
                    effect(ManageEffect.Snackbar(SnackKind.KEPT_TV_INPUT, d.nameOf(pkg), false))
                else effect(ManageEffect.Snackbar(kind, d.nameOf(pkg), undo, pkg))
                WriteResult.GateCancelled, WriteResult.NoTv, is WriteResult.Paused -> {}   // Paused: the open-run card shows (O2)
                else -> effect(ManageEffect.Snackbar(SnackKind.FAILED, d.nameOf(pkg), false))
            }
        }
    }

    private suspend fun load() {
        val tv = d.tv(tvId) ?: return
        val off = d.ledger.switchedOffByUs(tvId)
        val profile = d.profileOf(tv)
        val labels = d.snapshots.latest(tvId)?.packages.orEmpty()
        fun row(p: PackageName, isOff: Boolean) = OffRow(p, d.nameOf(p, labels[p]?.label), d.facts.lookup(p)?.description, isOff)
        val groups = profile?.groups.orEmpty()
        val placed = HashSet<PackageName>()
        val byGroup = groups.mapNotNull { g ->
            val rows = g.packages.map { it.name }.filter { it in off }.map { placed += it; row(it, true) }
            if (rows.isEmpty()) null else OffGroup(g.id, g.title, rows)
        }
        val rest = off.filterNot { it in placed }.map { row(it, true) }
        val all = byGroup + listOfNotNull(if (rest.isEmpty()) null else OffGroup("other", "", rest))
        val ask = if (tv.level == Level.CUSTOM) groups.filter { it.default == GroupDefault.ASK }.map { g ->
            OffGroup(g.id, g.title, g.packages.filter { labels.containsKey(it.name) }.map { row(it.name, it.name in off) })
        }.filter { it.rows.isNotEmpty() } else emptyList()
        send(Loaded(SwitchedOffState(tv.nickname, tv.level, all, ask, null, loading = false, count = off.size)))
    }
}
