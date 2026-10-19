package tv.cleanyour.app

import android.content.Context
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tv.cleanyour.adb.session.AdbSessionManager
import tv.cleanyour.android.platform.AndroidCredentialGate
import tv.cleanyour.android.platform.BundledAssets
import tv.cleanyour.android.platform.FileBlobStore
import tv.cleanyour.android.platform.FileStore
import tv.cleanyour.android.platform.KeystoreAdbSigner
import tv.cleanyour.android.platform.NsdDiscovery
import tv.cleanyour.android.platform.PackageManagerApkInspector
import tv.cleanyour.android.platform.PlainSocketFactory
import tv.cleanyour.core.backup.DefaultTvRestoreFiles
import tv.cleanyour.core.backup.DocumentSnapshotStore
import tv.cleanyour.core.backup.PlainRestoreNote
import tv.cleanyour.core.backup.ZipRestoreBundleWriter
import tv.cleanyour.core.engine.DefaultEngine
import tv.cleanyour.core.engine.DocumentPlanStore
import tv.cleanyour.core.engine.Engine
import tv.cleanyour.core.engine.FileJournal
import tv.cleanyour.core.engine.FileOverlayTransport
import tv.cleanyour.core.flows.AppForeground
import tv.cleanyour.core.flows.AppPrefs
import tv.cleanyour.core.flows.AppSettingsMachine
import tv.cleanyour.core.flows.CardAnswerDetector
import tv.cleanyour.core.flows.ConfigEncoder
import tv.cleanyour.core.flows.DockPicker
import tv.cleanyour.core.flows.DocumentTvRepository
import tv.cleanyour.core.flows.GateKeeper
import tv.cleanyour.core.flows.HomePrefs
import tv.cleanyour.core.flows.HomeScreenMachine
import tv.cleanyour.core.flows.LeftoversMachine
import tv.cleanyour.core.flows.ManageDeps
import tv.cleanyour.core.flows.ManageMachine
import tv.cleanyour.core.flows.ModulesMachine
import tv.cleanyour.core.flows.OpenWrites
import tv.cleanyour.core.flows.PlanRunner
import tv.cleanyour.core.flows.RestorePointMachine
import tv.cleanyour.core.flows.RestorePointWriter
import tv.cleanyour.core.flows.RunLedger
import tv.cleanyour.core.flows.ScreenLock
import tv.cleanyour.core.flows.SecureIdGen
import tv.cleanyour.core.flows.SessionEnd
import tv.cleanyour.core.flows.FlowLog
import tv.cleanyour.core.flows.SetupDeps
import tv.cleanyour.core.flows.SetupMachine
import tv.cleanyour.core.flows.SetupStart
import tv.cleanyour.core.flows.SwitchedOffMachine
import tv.cleanyour.core.flows.TvAccess
import tv.cleanyour.core.flows.TvSettingsMachine
import tv.cleanyour.core.flows.UpdateChecker
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.ConfigTransport
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.probe.AdbProber
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.registry.IndexBundle
import tv.cleanyour.core.registry.IndexRegistry
import tv.cleanyour.core.registry.ResumableArtifactFetcher
import tv.cleanyour.core.registry.UrlConnectionFetcher
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.BundledThemeSource
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.trust.BundledLauncherPin
import tv.cleanyour.core.trust.TrustRoots

// Manual dependency graph (ARCHITECTURE D8): one instance per process, built in CytApplication.
// Every section 5 seam is the real implementation (T7), built in the integration plan's order with
// `profiles` before the planner and the engine. `seams` keeps the other streams' pieces together
// for the app code that reads them (CytApplication, MainActivity).
class AppGraph(context: Context, val appVersion: String = "0.1.0") {
    private val app = context.applicationContext

    // UI-facing watchers run on the main thread. Sessions, the engine's runs and key creation run
    // on Default: AdbSessionManager runs its handshakes and pumps on its scope's dispatcher.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val clock: Clock = AppClock
    val zone: ZoneId = ZoneId.systemDefault()
    val idGen = SecureIdGen(clock)                       // the planner's only id source (R0: 15)
    val dataDir = File(app.filesDir, "v1")               // one root: tv.json and drafts, journal, plans, snapshots (ARCHITECTURE 5.1)
    val documents = FileStore(dataDir)
    val tvs = DocumentTvRepository(documents)
    val discovery = NsdDiscovery(app)
    val credentialGate = AndroidCredentialGate(app, clock)
    val copy = ResourceFlowCopy(app)
    val foreground = AppForeground(false)

    // W2: the phone's ADB key and the sessions (RFC-W2-3; plain TCP, no TLS in 0.1).
    val signer = DeferredAdbSigner(workScope) { KeystoreAdbSigner.create(app) }
    val keyInfo = SignerKeyInfo(signer)
    val sessions = AdbSessionManager(PlainSocketFactory(), signer, tls = null, clock = clock, scope = workScope)

    // W4: the probe and the bundled tables.
    val prober: Prober = AdbProber(clock)
    val profiles = BundledProfileSource()
    val facts = BundledFactsTable()

    // W3: renderer variant A (the nohup setsid watchdog) until S12 says otherwise.
    val renderer: OpRenderer = ShellRenderer()
    val configTransport: ConfigTransport = FileOverlayTransport(prober)
    // D23: the engine itself widens the confirm window to 120 s when there is no recent thumbnail,
    // so the planner keeps its 90 s default.
    val planner = DefaultPlanner(clock, idGen, renderer, configTransport, appVersion, profiles = profiles)
    val snapshots = DocumentSnapshotStore(documents)       // W6
    val journal = FileJournal(dataDir)                     // tvs/<tvId>/journal/<planId>.jsonl
    val plans = DocumentPlanStore(documents)
    val engine: Engine = LiveSessionEngine(DefaultEngine(renderer, prober, journal, snapshots, plans, clock, workScope, profiles), sessions, tvs)

    // W6: the restore files on the TV, the note, the zip.
    val restoreFiles = DefaultTvRestoreFiles(clock, zone)
    val restoreNote = PlainRestoreNote()
    val bundleWriter = ZipRestoreBundleWriter(renderer, clock, appVersion, profiles, zone)

    // W5, the recipe of DECISIONS-W5 "Wiring notes"; sources = null until a published copy exists.
    val blobs = FileBlobStore(app)
    val themes = MaterializedThemeSource(BundledThemeSource(), blobs, onError = { m, t -> CytLog.w(m, t) })
    val inspector = PackageManagerApkInspector(app)
    val launcherPin = BundledLauncherPin(blobs)
    val http = UrlConnectionFetcher()
    val seedIndex: IndexBundle? = runCatching { BundledAssets.seedIndex(app) }.onFailure { CytLog.w("seed index missing", it) }.getOrNull()
    val registry = IndexRegistry(http, documents, clock, seedIndex, sources = null, TrustRoots.indexVerifier())
    // F8: modules download only from installableIndex(), whatever the machine looked them up with.
    val fetcher = InstallableOnlyFetcher(ResumableArtifactFetcher(http, blobs, blobs, inspector)) { registry.installableIndex() }

    val seams = Seams(
        sessions = sessions, keyInfo = keyInfo, prober = prober, profiles = profiles, facts = facts, themes = themes,
        planner = planner, engine = engine, journal = journal, configTransport = configTransport, snapshots = snapshots,
        restoreFiles = restoreFiles, restoreNote = restoreNote, bundleWriter = bundleWriter, blobs = blobs,
        launcherPin = launcherPin, registry = registry, fetcher = fetcher, inspector = inspector, http = http,
    )

    val gate = GateKeeper(credentialGate, clock, copy)
    val ledger = RunLedger(documents, journal)
    val runner = PlanRunner(planner, engine, gate, ledger, facts, snapshots, prober)
    val restorePoint = RestorePointWriter(restoreFiles, blobs, ledger, runner)
    val access = TvAccess(sessions, discovery, tvs, clock)
    val sessionEnd = SessionEnd(sessions, discovery, tvs, runner, foreground)
    val homePrefs = HomePrefs(documents)
    val appPrefs = AppPrefs(documents)
    val openWrites = OpenWrites()                          // one per graph (R5-W8 O2), held by the one ManageDeps
    val diagnostics = LogDiagnostics { "Clean Your TV $appVersion, Android ${android.os.Build.VERSION.SDK_INT}" }
    val screenLock = ScreenLock { credentialGate.hasScreenLock() }

    // D12: one GET on user action. The repository is private before announcement, so this
    // reports "couldn't check" until releases are public.
    val updates = UpdateChecker { current ->
        val r = http.get(RELEASES_URL, null, null)
        if (r.code != 200) { r.body?.close(); null } else {
            val body = r.body?.use { it.readBytes().decodeToString() } ?: return@UpdateChecker null
            val tag = Regex("\"tag_name\"\\s*:\\s*\"v?([0-9][0-9A-Za-z.]*)\"").find(body)?.groupValues?.get(1) ?: return@UpdateChecker null
            if (tag != current) tag else ""
        }
    }

    val setupDeps = SetupDeps(
        discovery = discovery, sessions = sessions, prober = prober, profiles = profiles, facts = facts,
        runner = runner, engine = engine, gate = gate, tvs = tvs, snapshots = snapshots, ledger = ledger,
        launcherPin = launcherPin, themes = themes, restorePoint = restorePoint, restoreNote = restoreNote,
        sessionEnd = sessionEnd, access = access, clock = clock, copy = copy, dock = DockPicker.Ranking, screenLock = screenLock,
        diagnostics = diagnostics, locale = { app.resources.configuration.locales[0].toLanguageTag() },
        log = FlowLog { CytLog.i(it) },   // redacted lines for "Share diagnostics": outcomes and counts only
    )

    val manageDeps = ManageDeps(
        tvs = tvs, sessions = sessions, discovery = discovery, access = access, gate = gate, runner = runner, engine = engine,
        ledger = ledger, prober = prober, profiles = profiles, facts = facts, snapshots = snapshots,
        documents = documents, blobs = blobs, themes = themes, launcherPin = launcherPin,
        configTransport = configTransport, restorePoint = restorePoint, bundleWriter = bundleWriter,
        registry = registry, fetcher = fetcher, inspector = inspector, cardDetector = CardAnswerDetector.Unknown,
        sessionEnd = sessionEnd, homePrefs = homePrefs, appPrefs = appPrefs, keyInfo = keyInfo, updates = updates,
        diagnostics = diagnostics, clock = clock, copy = copy, encoder = ConfigEncoder.Codec, appVersion = appVersion,
        openWrites = openWrites,
    )

    init {
        // At start and off the main thread: the key (the first launch makes it), the stored or seed
        // module index (no network, DECISIONS-W5), and the theme wallpapers as plain files.
        signer.warmUp()
        workScope.launch { runCatching { registry.restore() }.onFailure { CytLog.w("module index restore failed", it) } }
        workScope.launch { runCatching { themes.prepare() }.onFailure { CytLog.w("theme wallpapers not stored", it) } }
    }

    fun setup(scope: CoroutineScope, start: SetupStart) = SetupMachine(setupDeps, scope, start)
    fun manage(scope: CoroutineScope, tvId: TvId?) = ManageMachine(manageDeps, scope, tvId)
    fun switchedOff(scope: CoroutineScope, tvId: TvId) = SwitchedOffMachine(manageDeps, scope, tvId)
    fun leftovers(scope: CoroutineScope, tvId: TvId) = LeftoversMachine(manageDeps, scope, tvId)
    fun homeScreen(scope: CoroutineScope, tvId: TvId) = HomeScreenMachine(manageDeps, scope, tvId)
    fun modules(scope: CoroutineScope, tvId: TvId) = ModulesMachine(manageDeps, scope, tvId)
    fun restorePoint(scope: CoroutineScope, tvId: TvId) = RestorePointMachine(manageDeps, scope, tvId)
    fun tvSettings(scope: CoroutineScope, tvId: TvId) = TvSettingsMachine(manageDeps, scope, tvId)
    fun appSettings(scope: CoroutineScope) = AppSettingsMachine(manageDeps, scope)

    // Tests only; the process owns the graph otherwise.
    fun close() {
        scope.cancel()
        workScope.cancel()
    }

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/udhaykumarbala/cleanyourtv-app/releases/latest"
    }
}
