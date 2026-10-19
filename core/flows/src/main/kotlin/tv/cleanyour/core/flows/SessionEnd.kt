package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tv.cleanyour.core.discovery.Discovery
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.store.TvRepository

// D7 and F11: debugging on the TV is switched off when the person taps Finish, when the app has
// been in the background for 10 minutes, or when the session is closed for any other reason,
// whichever comes first; "Keep debugging on" (per session, Customise > Afterwards) skips it. The
// DebugOff plan is SESSION_END, so it needs no pass (R0: 6). The TV closes the socket, so the
// result counts only once a follow-up probe of port 5555 fails.
class SessionEnd(
    private val sessions: SessionManager,
    private val discovery: Discovery,
    private val tvs: TvRepository,
    private val runner: PlanRunner,
    private val foreground: AppForeground,
    private val backgroundMs: Long = 10 * 60_000,
) {
    private val keepOn = HashSet<TvId>()
    private val touched = LinkedHashSet<TvId>()
    private var watcher: Job? = null

    fun keepDebuggingOn(tvId: TvId, on: Boolean) { synchronized(keepOn) { if (on) keepOn.add(tvId) else keepOn.remove(tvId) } }
    fun keepsDebuggingOn(tvId: TvId): Boolean = synchronized(keepOn) { tvId in keepOn }

    // Every TV a write touched this process; the 10 minute rule switches these off.
    fun touched(tvId: TvId) { synchronized(touched) { touched.add(tvId) } }

    // Start the background watch in the app's long-lived scope.
    fun watch(scope: CoroutineScope) {
        watcher?.cancel()
        watcher = scope.launch {
            foreground.foreground.collectLatest { fg ->
                if (!fg) {
                    delay(backgroundMs)
                    val ids = synchronized(touched) { touched.toList() }
                    for (id in ids) if (!keepsDebuggingOn(id)) switchOff(id)
                }
            }
        }
    }

    // Runs debug_off on the live session of this TV. Returns the debugging state it recorded.
    suspend fun switchOff(tvId: TvId): DebugState {
        val session = sessions.existing(tvId) ?: return DebugState.UNKNOWN
        val tv = tvs.tvs.value.firstOrNull { it.id == tvId } ?: return DebugState.UNKNOWN
        val snapshot = runCatching { runner.snapshotFor(tv, session) }.getOrNull() ?: return DebugState.UNKNOWN
        val plan = (runner.plan(tv, PlanRequest.DebugOff, snapshot) as? PlanResult.Ok)?.plan ?: return DebugState.UNKNOWN
        runCatching { runner.run(tv, plan, session) }
        val host = tv.endpoints.filterIsInstance<Endpoint.PlainTcp>().firstOrNull()?.host
        val stillOpen = host != null && discovery.probe5555(host)
        val state = if (stillOpen) DebugState.ON else DebugState.OFF
        val now = tvs.tvs.value.firstOrNull { it.id == tvId } ?: tv
        tvs.upsert(now.copy(debugging = state))
        if (state == DebugState.OFF) synchronized(touched) { touched.remove(tvId) }
        return state
    }
}
