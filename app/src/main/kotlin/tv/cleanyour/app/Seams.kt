package tv.cleanyour.app

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.cleanyour.core.backup.RestoreBundleWriter
import tv.cleanyour.core.backup.RestoreNote
import tv.cleanyour.core.backup.TvRestoreFiles
import tv.cleanyour.core.engine.Engine
import tv.cleanyour.core.engine.Journal
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.StartRefusal
import tv.cleanyour.core.engine.StartResult
import tv.cleanyour.core.flows.KeyInfo
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.ArtifactRef
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.LauncherArtifact
import tv.cleanyour.core.model.Module
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.ConfigTransport
import tv.cleanyour.core.ops.ConfigTransportKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.planner.Planner
import tv.cleanyour.core.probe.ProbeStage
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.registry.ArtifactFetcher
import tv.cleanyour.core.registry.FetchFailure
import tv.cleanyour.core.registry.FetchResult
import tv.cleanyour.core.registry.HttpFetcher
import tv.cleanyour.core.registry.HttpResponse
import tv.cleanyour.core.registry.IndexState
import tv.cleanyour.core.registry.Registry
import tv.cleanyour.core.session.OpenResult
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.PackageFacts
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.core.spec.ThemeSource
import tv.cleanyour.core.store.BlobStore
import tv.cleanyour.core.store.GatePass
import tv.cleanyour.core.store.SnapshotStore
import tv.cleanyour.core.trust.ApkInspector
import tv.cleanyour.core.trust.LauncherPin
import tv.cleanyour.core.model.ApkInfo

// The implementations of section 5 that other streams own. AppGraph takes them as one value so
// the integration is one constructor call. Until W2 to W6 merge, `Seams.unwired()` gives inert
// stand-ins that fail politely (no TV ever connects), so the app starts, discovers TVs with the
// real NsdDiscovery and shows every first-run screen.
//
// Integration (merge) checklist, one line per seam:
//   sessions      -> W2 AdbSessionManager(PlainSocketFactory, KeystoreAdbSigner, ...)
//   keyInfo       -> W2 KeystoreAdbSigner (fingerprint, backing, reset)
//   prober, profiles, facts, themes -> W4 (Prober, bundled profiles, FactsTable data, ThemeSource)
//   planner, engine, journal, configTransport -> W3 (Planner(clock, idGen, renderer), Executor, JsonlJournal, FileOverlayTransport)
//   snapshots, restoreFiles, restoreNote, bundleWriter -> W6
//   blobs, launcherPin, registry, fetcher, inspector, http -> W5 (DECISIONS-W5 "Wiring notes for W8"):
//       blobs = FileBlobStore(context); inspector = PackageManagerApkInspector(context);
//       launcherPin = BundledLauncherPin(blobs); http = UrlConnectionFetcher();
//       registry = IndexRegistry(http, documents, clock, BundledAssets.seedIndex(context), sources = null, TrustRoots.indexVerifier());
//       fetcher = ResumableArtifactFetcher(http, blobs, blobs, inspector);
//       at start registry.restore(); "Add a card" refreshes; install only from registry.installableIndex()
data class Seams(
    val sessions: SessionManager,
    val keyInfo: KeyInfo,
    val prober: Prober,
    val profiles: ProfileSource,
    val facts: FactsTable,
    val themes: ThemeSource,
    val planner: Planner,
    val engine: Engine,
    val journal: Journal,
    val configTransport: ConfigTransport,
    val snapshots: SnapshotStore,
    val restoreFiles: TvRestoreFiles,
    val restoreNote: RestoreNote,
    val bundleWriter: RestoreBundleWriter,
    val blobs: BlobStore,
    val launcherPin: LauncherPin,
    val registry: Registry,
    val fetcher: ArtifactFetcher,
    val inspector: ApkInspector,
    val http: HttpFetcher,
) {
    companion object {
        fun unwired(): Seams = Seams(
            sessions = UnwiredSessions, keyInfo = UnwiredKey, prober = UnwiredProber, profiles = UnwiredProfiles,
            facts = object : FactsTable { override fun lookup(pkg: PackageName): PackageFacts? = null },
            themes = object : ThemeSource { override fun bundled(): List<Theme> = emptyList() },
            planner = object : Planner {
                override fun plan(request: PlanRequest, snapshot: Snapshot, facts: FactsTable, journals: List<ChangeRecord>) =
                    PlanResult.Refused(listOf("planner not integrated"))
            },
            engine = UnwiredEngine, journal = UnwiredJournal, configTransport = UnwiredConfig, snapshots = UnwiredSnapshots,
            restoreFiles = object : TvRestoreFiles {
                override fun restorePointJson(tv: Tv, changes: List<ChangeRecord>): ByteArray = ByteArray(0)
                override fun restoreTxt(tv: Tv, changes: List<ChangeRecord>, profile: Profile?): ByteArray = ByteArray(0)
            },
            restoreNote = object : RestoreNote {
                override fun text(tv: Tv, changes: List<ChangeRecord>, facts: FactsTable, stockHomeName: String, dateText: String) = ""
            },
            bundleWriter = object : RestoreBundleWriter {
                override suspend fun write(tv: Tv, baseline: Snapshot, changes: List<ChangeRecord>, overlay: ByteArray?, wallpaper: ByteArray?, sink: OutputStream) =
                    throw IOException("restore bundle not integrated")
            },
            blobs = UnwiredBlobs, launcherPin = object : LauncherPin { override fun bundled(): LauncherArtifact = throw IllegalStateException("launcher pin not integrated") },
            registry = UnwiredRegistry,
            fetcher = object : ArtifactFetcher { override suspend fun fetch(ref: ArtifactRef, onProgress: (Long, Long) -> Unit) = FetchResult.Failed(FetchFailure.OFFLINE, null) },
            inspector = object : ApkInspector { override fun inspect(blob: BlobRef): ApkInfo? = null },
            http = object : HttpFetcher { override suspend fun get(url: String, etag: String?, rangeFrom: Long?) = HttpResponse(503, null, null, null) },
        )
    }
}

private object UnwiredSessions : SessionManager {
    private val s = MutableStateFlow<Map<TvId, ConnectionState>>(emptyMap())
    override val states: StateFlow<Map<TvId, ConnectionState>> = s.asStateFlow()
    override suspend fun open(endpoint: Endpoint, expected: TvId?, onAwaitingAllow: suspend (fingerprint: String) -> Unit): OpenResult =
        OpenResult.Failed(AdbError.Transport.Unreachable)
    override fun existing(tvId: TvId): TvSession? = null
    override suspend fun closeAll() {}
}

private object UnwiredKey : KeyInfo {
    override val fingerprint: String = ""
    override val backing: String = ""
    override suspend fun reset() {}
}

private object UnwiredProber : Prober {
    private fun no(): Nothing = throw AdbException(AdbError.Transport.Closed)
    override suspend fun facts(session: TvSession): DeviceFacts = no()
    override suspend fun snapshot(session: TvSession, reason: SnapshotReason, onStage: (ProbeStage) -> Unit): Snapshot = no()
    override suspend fun homeNow(session: TvSession): HomeResolution = no()
    override suspend fun homeFocus(session: TvSession): Component? = null
    override suspend fun screenshot(session: TvSession): ByteArray? = null
    override suspend fun fileSha256(session: TvSession, path: RemotePath): Sha256? = null
    override suspend fun installedSha256(session: TvSession, pkg: PackageName): Sha256? = null
    override suspend fun freeDataMb(session: TvSession): Int = 0
    override suspend fun installRefusal(session: TvSession, noopApk: BlobRef): Boolean = false
    override suspend fun watchdogAlive(session: TvSession, token: RunToken): Boolean = false
    override suspend fun bootId(session: TvSession): String = no()
}

private object UnwiredProfiles : ProfileSource {
    override suspend fun all(): List<Profile> = emptyList()
    override suspend fun resolve(facts: DeviceFacts): ProfileResolution = ProfileResolution.None(emptyList())
}

private object UnwiredEngine : Engine {
    private val idle = MutableStateFlow<RunState>(RunState.Idle)
    override fun runState(tvId: TvId): StateFlow<RunState> = idle.asStateFlow()
    override suspend fun start(plan: tv.cleanyour.core.planner.Plan, session: TvSession, confirmed: Sha256, pass: GatePass?): StartResult = StartResult.Refused(StartRefusal.NOT_CONNECTED)
    override suspend fun resume(tvId: TvId, session: TvSession) {}
    override suspend fun confirmHome(tvId: TvId) {}
    override suspend fun rejectHome(tvId: TvId) {}
    override suspend fun stop(tvId: TvId) {}
    override suspend fun continueRun(tvId: TvId, pass: GatePass?) {}
    override suspend fun undoRun(tvId: TvId, pass: GatePass?) {}
    override suspend fun abandon(tvId: TvId) {}
}

private object UnwiredJournal : Journal {
    override suspend fun append(record: JournalRecord) {}
    override suspend fun read(planId: PlanId): List<JournalRecord> = emptyList()
    override suspend fun openRuns(tvId: TvId): List<PlanId> = emptyList()
}

private object UnwiredConfig : ConfigTransport {
    override val kind = ConfigTransportKind.FILE_OVERLAY
    override suspend fun readDigest(session: TvSession): Sha256? = null
    override fun writePlanSteps(config: BlobRef, wallpaper: BlobRef?): List<Op> = emptyList()
    override fun resetPlanSteps(): List<Op> = emptyList()
}

private object UnwiredSnapshots : SnapshotStore {
    override suspend fun save(snapshot: Snapshot): Sha256 = snapshot.contentHash
    override suspend fun load(tvId: TvId, hash: Sha256): Snapshot? = null
    override suspend fun baseline(tvId: TvId): Snapshot? = null
    override suspend fun latest(tvId: TvId): Snapshot? = null
    override suspend fun prune(tvId: TvId, keep: Int) {}
}

private object UnwiredBlobs : BlobStore {
    override suspend fun put(sha256: Sha256, source: InputStream, expectedSize: Long): BlobRef = throw IOException("blob store not integrated")
    override fun get(sha256: Sha256): BlobRef? = null
    override suspend fun delete(sha256: Sha256) {}
    override fun bundled(name: String): BlobRef = throw IOException("blob store not integrated")
}

private object UnwiredRegistry : Registry {
    private val s = MutableStateFlow<IndexState>(IndexState.Empty)
    override val index: StateFlow<IndexState> = s.asStateFlow()
    override suspend fun refresh(): IndexState = IndexState.Failed(FetchFailure.OFFLINE, null, null).also { s.value = it }
    override fun module(id: String): Module? = null
}

