package tv.cleanyour.app

import android.content.Context
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tv.cleanyour.android.platform.AndroidCredentialGate
import tv.cleanyour.android.platform.FileStore
import tv.cleanyour.android.platform.NsdDiscovery
import tv.cleanyour.core.flows.AppForeground
import tv.cleanyour.core.flows.AppPrefs
import tv.cleanyour.core.flows.AppSettingsMachine
import tv.cleanyour.core.flows.CardAnswerDetector
import tv.cleanyour.core.flows.DocumentTvRepository
import tv.cleanyour.core.flows.GateKeeper
import tv.cleanyour.core.flows.HomePrefs
import tv.cleanyour.core.flows.HomeScreenMachine
import tv.cleanyour.core.flows.LeftoversMachine
import tv.cleanyour.core.flows.ManageDeps
import tv.cleanyour.core.flows.ManageMachine
import tv.cleanyour.core.flows.ModulesMachine
import tv.cleanyour.core.flows.PlanRunner
import tv.cleanyour.core.flows.RestorePointMachine
import tv.cleanyour.core.flows.RestorePointWriter
import tv.cleanyour.core.flows.RunLedger
import tv.cleanyour.core.flows.ScreenLock
import tv.cleanyour.core.flows.SecureIdGen
import tv.cleanyour.core.flows.SessionEnd
import tv.cleanyour.core.flows.SetupDeps
import tv.cleanyour.core.flows.SetupMachine
import tv.cleanyour.core.flows.SetupStart
import tv.cleanyour.core.flows.SwitchedOffMachine
import tv.cleanyour.core.flows.TvAccess
import tv.cleanyour.core.flows.TvSettingsMachine
import tv.cleanyour.core.flows.UpdateChecker
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.store.Clock

// Manual dependency graph (ARCHITECTURE D8): one instance per process, built in CytApplication.
// W8's own pieces are real (discovery, file store, credential gate, TV records, flows); the other
// streams' implementations arrive through `seams` (see Seams.kt for the merge checklist).
class AppGraph(context: Context, val seams: Seams = Seams.unwired(), val appVersion: String = "0.1.0") {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val clock: Clock = object : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
        override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
    }
    val idGen = SecureIdGen(clock)                     // the planner's id source (R0: 15), handed to W3's Planner at merge
    val documents = FileStore(File(app.filesDir, "v1"))
    val tvs = DocumentTvRepository(documents)
    val discovery = NsdDiscovery(app)
    val credentialGate = AndroidCredentialGate(app, clock)
    val copy = ResourceFlowCopy(app)
    val foreground = AppForeground(false)

    val gate = GateKeeper(credentialGate, clock, copy)
    val ledger = RunLedger(documents, seams.journal)
    val runner = PlanRunner(seams.planner, seams.engine, gate, ledger, seams.facts, seams.snapshots, seams.prober)
    val restorePoint = RestorePointWriter(seams.restoreFiles, seams.blobs, ledger, runner)
    val access = TvAccess(seams.sessions, discovery, tvs, clock)
    val sessionEnd = SessionEnd(seams.sessions, discovery, tvs, runner, foreground)
    val homePrefs = HomePrefs(documents)
    val appPrefs = AppPrefs(documents)
    val diagnostics = LogDiagnostics { "Clean Your TV $appVersion, Android ${android.os.Build.VERSION.SDK_INT}" }
    val screenLock = ScreenLock { credentialGate.hasScreenLock() }

    // D12: one GET on user action. The repository is private before announcement, so this
    // reports "couldn't check" until releases are public.
    val updates = UpdateChecker { current ->
        val r = seams.http.get(RELEASES_URL, null, null)
        if (r.code != 200) null else {
            val body = r.body?.use { it.readBytes().decodeToString() } ?: return@UpdateChecker null
            val tag = Regex("\"tag_name\"\\s*:\\s*\"v?([0-9][0-9A-Za-z.]*)\"").find(body)?.groupValues?.get(1) ?: return@UpdateChecker null
            if (tag != current) tag else ""
        }
    }

    val setupDeps = SetupDeps(
        discovery = discovery, sessions = seams.sessions, prober = seams.prober, profiles = seams.profiles, facts = seams.facts,
        runner = runner, engine = seams.engine, gate = gate, tvs = tvs, snapshots = seams.snapshots, ledger = ledger,
        launcherPin = seams.launcherPin, themes = seams.themes, restorePoint = restorePoint, restoreNote = seams.restoreNote,
        sessionEnd = sessionEnd, access = access, clock = clock, copy = copy, screenLock = screenLock, diagnostics = diagnostics,
        locale = { app.resources.configuration.locales[0].toLanguageTag() },
    )

    val manageDeps = ManageDeps(
        tvs = tvs, sessions = seams.sessions, discovery = discovery, access = access, gate = gate, runner = runner, engine = seams.engine,
        ledger = ledger, prober = seams.prober, profiles = seams.profiles, facts = seams.facts, snapshots = seams.snapshots,
        documents = documents, blobs = seams.blobs, themes = seams.themes, launcherPin = seams.launcherPin,
        configTransport = seams.configTransport, restorePoint = restorePoint, bundleWriter = seams.bundleWriter,
        registry = seams.registry, fetcher = seams.fetcher, inspector = seams.inspector, cardDetector = CardAnswerDetector.Unknown,
        sessionEnd = sessionEnd, homePrefs = homePrefs, appPrefs = appPrefs, keyInfo = seams.keyInfo, updates = updates,
        diagnostics = diagnostics, clock = clock, copy = copy, appVersion = appVersion,
    )

    fun setup(scope: CoroutineScope, start: SetupStart) = SetupMachine(setupDeps, scope, start)
    fun manage(scope: CoroutineScope, tvId: TvId?) = ManageMachine(manageDeps, scope, tvId)
    fun switchedOff(scope: CoroutineScope, tvId: TvId) = SwitchedOffMachine(manageDeps, scope, tvId)
    fun leftovers(scope: CoroutineScope, tvId: TvId) = LeftoversMachine(manageDeps, scope, tvId)
    fun homeScreen(scope: CoroutineScope, tvId: TvId) = HomeScreenMachine(manageDeps, scope, tvId)
    fun modules(scope: CoroutineScope, tvId: TvId) = ModulesMachine(manageDeps, scope, tvId)
    fun restorePoint(scope: CoroutineScope, tvId: TvId) = RestorePointMachine(manageDeps, scope, tvId)
    fun tvSettings(scope: CoroutineScope, tvId: TvId) = TvSettingsMachine(manageDeps, scope, tvId)
    fun appSettings(scope: CoroutineScope) = AppSettingsMachine(manageDeps, scope)

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/udhaykumarbala/cleanyourtv-app/releases/latest"
    }
}
