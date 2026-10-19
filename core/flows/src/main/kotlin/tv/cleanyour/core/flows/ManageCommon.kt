package tv.cleanyour.core.flows

import tv.cleanyour.core.backup.RestoreBundleWriter
import tv.cleanyour.core.discovery.Discovery
import tv.cleanyour.core.engine.Engine
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.ConfigTransport
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.registry.ArtifactFetcher
import tv.cleanyour.core.registry.Registry
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.spec.ThemeSource
import tv.cleanyour.core.store.BlobStore
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.DocumentStore
import tv.cleanyour.core.store.SnapshotStore
import tv.cleanyour.core.store.TvRepository
import tv.cleanyour.core.trust.ApkInspector
import tv.cleanyour.core.trust.LauncherPin

// One-shot effects of the Manage family (M1 to M8). Snackbars carry a kind and a name; the screen
// turns them into the sentence from strings.xml, so the machines stay free of copy.
sealed interface ManageEffect {
    data class Navigate(val key: String) : ManageEffect
    data class Snackbar(val kind: SnackKind, val name: String, val undo: Boolean, val pkg: PackageName? = null) : ManageEffect
    data class Share(val text: String) : ManageEffect
    data class CreateDocument(val suggestedName: String) : ManageEffect   // SAF ACTION_CREATE_DOCUMENT
    data class OpenUrl(val url: String) : ManageEffect
    data object HapticLight : ManageEffect
    data object HapticDouble : ManageEffect
}

enum class SnackKind { PUT_BACK, SWITCHED_OFF, HOME_SAVED, MODULE_REMOVED, EXPORT_SAVED, EXPORT_FAILED, FAILED }

// What a write in the Manage family is doing, for the narration row and the disabled buttons.
sealed interface Busy {
    data object Connecting : Busy
    data class Working(val percent: Int, val lines: List<String>) : Busy
}

// Everything the Manage family needs; the app builds one from AppGraph.
class ManageDeps(
    val tvs: TvRepository,
    val sessions: SessionManager,
    val discovery: Discovery,
    val access: TvAccess,
    val gate: GateKeeper,
    val runner: PlanRunner,
    val engine: Engine,
    val ledger: RunLedger,
    val prober: Prober,
    val profiles: ProfileSource,
    val facts: FactsTable,
    val snapshots: SnapshotStore,
    val documents: DocumentStore,
    val blobs: BlobStore,
    val themes: ThemeSource,
    val launcherPin: LauncherPin,
    val configTransport: ConfigTransport,
    val restorePoint: RestorePointWriter,
    val bundleWriter: RestoreBundleWriter,
    val registry: Registry,
    val fetcher: ArtifactFetcher,
    val inspector: ApkInspector,
    val cardDetector: CardAnswerDetector,
    val sessionEnd: SessionEnd,
    val homePrefs: HomePrefs,
    val appPrefs: AppPrefs,
    val keyInfo: KeyInfo,
    val updates: UpdateChecker,
    val diagnostics: Diagnostics,
    val clock: Clock,
    val copy: FlowCopy,
    val encoder: ConfigEncoder = ConfigEncoder.Codec,
    val appVersion: String = "0.1.0",
) {
    fun tv(id: TvId): Tv? = tvs.tvs.value.firstOrNull { it.id == id }

    suspend fun profileOf(tv: Tv): Profile? = tv.profileId?.let { id -> attempt { profiles.all() }.getOrNull()?.firstOrNull { it.id == id } }

    fun nameOf(pkg: PackageName, label: String? = null): String = facts.lookup(pkg)?.friendlyName ?: label ?: pkg.value
}

// The dock and theme this phone last pushed to a TV. Tv has no field for them once the wizard
// draft is gone, so they live beside the TV record: tvs/<id>/home.txt, "dock=a,b" and "theme=id".
data class HomeChoice(val dock: List<PackageName>, val themeId: String?)

class HomePrefs(private val store: DocumentStore) {
    private fun path(id: TvId) = "tvs/${id.value}/home.txt"

    suspend fun load(id: TvId): HomeChoice? = store.read(path(id)) { bytes ->
        val map = bytes.decodeToString().lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        HomeChoice(map["dock"].orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { PackageName(it) }.getOrNull() },
            map["theme"]?.takeIf { it.isNotBlank() })
    }

    suspend fun save(id: TvId, choice: HomeChoice) {
        store.write(path(id), "dock=${choice.dock.joinToString(",") { it.value }}\ntheme=${choice.themeId.orEmpty()}\n".encodeToByteArray())
    }
}

// App settings (M8) that are preferences, not records: haptics and large text.
data class AppPrefsValue(val haptics: Boolean = true, val largeTextFollowsSystem: Boolean = true)

class AppPrefs(private val store: DocumentStore) {
    private val path = "settings.txt"

    suspend fun load(): AppPrefsValue = store.read(path) { bytes ->
        val map = bytes.decodeToString().lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        AppPrefsValue(haptics = map["haptics"] != "false", largeTextFollowsSystem = map["largeText"] != "false")
    } ?: AppPrefsValue()

    suspend fun save(v: AppPrefsValue) {
        store.write(path, "haptics=${v.haptics}\nlargeText=${v.largeTextFollowsSystem}\n".encodeToByteArray())
    }
}

// Deletes every document under a folder, depth first (DocumentStore.list returns child names).
suspend fun DocumentStore.deleteTree(dir: String) {
    for (child in list(dir)) {
        val path = "$dir/$child"
        val grand = list(path)
        if (grand.isEmpty()) delete(path) else deleteTree(path)
    }
}

sealed interface WriteResult {
    data class Done(val plan: Plan, val finished: RunState.Finished) : WriteResult
    data class Paused(val plan: Plan, val paused: RunState.Paused) : WriteResult
    data class Refused(val reasons: List<String>) : WriteResult
    data object NoTv : WriteResult           // cancelled at the debugging step, or the TV is unknown
    data object GateCancelled : WriteResult
    data class Failed(val detail: String) : WriteResult
}

// The returning-user write of UX 4.11: the debugging step if needed (TvAccess), a plan from the
// newest snapshot, the credential gate (D17), the run, then the restore point on the TV (D44).
class TvWriter(private val d: ManageDeps) {
    suspend fun write(tvId: TvId, request: PlanRequest, onBusy: (Busy) -> Unit = {}): WriteResult {
        onBusy(Busy.Connecting)
        val session = when (val a = d.access.session(tvId)) {
            is AccessResult.Ok -> a.session
            is AccessResult.Failed -> return WriteResult.Failed(a.error.toString())
            else -> return WriteResult.NoTv
        }
        val tv = d.tv(tvId) ?: return WriteResult.NoTv
        return attempt {
            val snapshot = d.runner.snapshotFor(tv, session)
            val plan = when (val r = d.runner.plan(tv, request, snapshot)) {
                is PlanResult.Ok -> r.plan
                is PlanResult.Refused -> return@attempt WriteResult.Refused(r.reasons)
            }
            d.sessionEnd.touched(tvId)
            val outcome = d.runner.run(tv, plan, session) { s ->
                if (s is RunState.Running) onBusy(Busy.Working(s.percent, s.narration.map { it.text }))
            }
            when (outcome) {
                is RunOutcome.Finished -> {
                    if (request !is PlanRequest.WriteRestorePoint && request != PlanRequest.DebugOff &&
                        outcome.state.status != RunStatus.ABANDONED) {
                        d.restorePoint.write(d.tv(tvId) ?: tv, session, d.profileOf(tv))
                    }
                    WriteResult.Done(plan, outcome.state)
                }
                is RunOutcome.Paused -> WriteResult.Paused(plan, outcome.state)
                is RunOutcome.StartRefused -> WriteResult.Failed(outcome.refusal.name)
                RunOutcome.GateCancelled -> WriteResult.GateCancelled
            }
        }.getOrElse { WriteResult.Failed(it.message ?: it.toString()) }
    }

    // A plan for preview only (Undo everything, Use the TV's own home screen): no gate, no run.
    suspend fun preview(tvId: TvId, request: PlanRequest): Plan? {
        val tv = d.tv(tvId) ?: return null
        val snapshot = d.snapshots.latest(tvId) ?: d.access.live(tvId)?.let { d.runner.snapshotFor(tv, it) } ?: return null
        return (d.runner.plan(tv, request, snapshot) as? PlanResult.Ok)?.plan
    }
}
