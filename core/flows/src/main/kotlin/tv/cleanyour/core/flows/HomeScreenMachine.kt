package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.spec.LauncherConfigDoc

// M2 Home screen: the dock (S3b sheet), the look (theme picker), "Use the TV's own home screen"
// with its preview, and the drift banner (D15: the overlay digest re-read on every connect;
// "Restore mine" pushes ours again).
data class AppChoice(val pkg: PackageName, val name: String)

data class HomeScreenState(
    val tvName: String = "",
    val dock: List<AppChoice> = emptyList(),
    val apps: List<AppChoice> = emptyList(),           // installed apps for the dock sheet
    val themeId: String? = null,
    val themes: List<ThemeOption> = emptyList(),
    val drift: Boolean = false,
    val stock: Component? = null,                       // the profile's stock launcher, when there is one
    val stockPreview: Plan? = null,
    val busy: Busy? = null,
    val homeScreenOurs: Boolean = true,
)

sealed interface HomeScreenIntent {
    data class SetDock(val dock: List<PackageName>) : HomeScreenIntent
    data class SetTheme(val id: String) : HomeScreenIntent
    data object RestoreMine : HomeScreenIntent
    data object PreviewStock : HomeScreenIntent
    data object UseStock : HomeScreenIntent
    data object CancelPreview : HomeScreenIntent
}

class HomeScreenMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    private val tvId: TvId,
) : Machine<HomeScreenState, HomeScreenIntent, ManageEffect>(HomeScreenState(themes = d.themes.bundled().map { ThemeOption(it.id, it.name, it.description) }), scope) {

    private val writer = TvWriter(d)
    private data class Loaded(val s: HomeScreenState)
    private data class BusyNow(val busy: Busy?)
    private data class Preview(val plan: Plan?)
    private data class Drift(val drift: Boolean)

    init { scope.launch { load(); checkDrift() } }

    override fun reduce(state: HomeScreenState, event: Any): HomeScreenState = when (event) {
        is Loaded -> event.s.copy(busy = state.busy, stockPreview = state.stockPreview, drift = state.drift)
        is BusyNow -> state.copy(busy = event.busy)
        is Preview -> state.copy(stockPreview = event.plan)
        is Drift -> state.copy(drift = event.drift)
        HomeScreenIntent.CancelPreview -> state.copy(stockPreview = null)
        else -> state
    }

    override fun handle(event: Any, before: HomeScreenState, after: HomeScreenState) {
        if (before.busy != null && event is HomeScreenIntent) return
        when (event) {
            is HomeScreenIntent.SetDock -> scope.launch { apply(event.dock, after.themeId) }
            is HomeScreenIntent.SetTheme -> scope.launch { apply(after.dock.map { it.pkg }, event.id) }
            HomeScreenIntent.RestoreMine -> scope.launch { apply(after.dock.map { it.pkg }, after.themeId) }
            HomeScreenIntent.PreviewStock -> after.stock?.let { stock -> scope.launch { send(Preview(writer.preview(tvId, PlanRequest.UseStockHome(stock)))) } }
            HomeScreenIntent.UseStock -> after.stock?.let { stock -> scope.launch { useStock(stock) } }
            else -> {}
        }
    }

    private suspend fun load() {
        val tv = d.tv(tvId) ?: return
        val profile = d.profileOf(tv)
        val packages = d.snapshots.latest(tvId)?.packages.orEmpty()
        val choice = d.homePrefs.load(tvId)
        val apps = packages.filter { (k, s) -> !s.system || d.facts.lookup(k) != null }.keys
            .map { AppChoice(it, d.nameOf(it, packages[it]?.label)) }.sortedBy { it.name.lowercase() }
        val dock = choice?.dock.orEmpty().map { AppChoice(it, d.nameOf(it, packages[it]?.label)) }
        send(Loaded(HomeScreenState(tv.nickname, dock, apps, choice?.themeId, state.value.themes, stock = profile?.stockLaunchers?.firstOrNull(),
            homeScreenOurs = tv.launcher != null)))
    }

    private suspend fun checkDrift() {
        val tv = d.tv(tvId) ?: return
        val ours = tv.launcher?.ourOverlayDigest ?: return
        val session = d.access.live(tvId) ?: return
        val now = attempt { d.configTransport.readDigest(session) }.getOrNull()
        send(Drift(now != null && now != ours))
    }

    // Dock and look go to the TV as one config.json (LauncherConfigCodec), pushed by an ApplyTheme
    // plan; the digest we wrote is remembered so the next connect can warn on drift.
    private suspend fun apply(dock: List<PackageName>, themeId: String?) {
        val theme = themeId?.let { id -> d.themes.bundled().firstOrNull { it.id == id } }
        val doc = (theme?.doc ?: LauncherConfigDoc()).copy(dockApps = dock.ifEmpty { null })
        val bytes = attempt { d.encoder.encode(doc) }.getOrNull() ?: return effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        val config = d.blobs.putBytes(bytes)
        val r = writer.write(tvId, PlanRequest.ApplyTheme(doc, config, theme?.wallpaper)) { send(BusyNow(it)) }
        send(BusyNow(null))
        if (r is WriteResult.Done) {
            d.homePrefs.save(tvId, HomeChoice(dock, themeId))
            remember(sha256Of(bytes))
            send(Drift(false))
            effect(ManageEffect.Snackbar(SnackKind.HOME_SAVED, "", false))
        } else if (r !is WriteResult.GateCancelled && r !is WriteResult.NoTv && r !is WriteResult.Paused) {   // Paused: the open-run card (O2)
            effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        }
        load()
    }

    private suspend fun remember(digest: Sha256) {
        val tv = d.tv(tvId) ?: return
        val pin = d.launcherPin.bundled()
        val l = tv.launcher ?: LauncherState(pin.versionCode, pin.signer, null, null)
        d.tvs.upsert(tv.copy(launcher = l.copy(overlayDigest = digest, ourOverlayDigest = digest)))
    }

    // "Use the TV's own home screen": re-enables the stock launcher, sets it as home, keeps ours
    // installed (PRODUCT-SPEC M2).
    private suspend fun useStock(stock: Component) {
        send(Preview(null))
        val r = writer.write(tvId, PlanRequest.UseStockHome(stock)) { send(BusyNow(it)) }
        send(BusyNow(null))
        if (r is WriteResult.Done) {
            d.tv(tvId)?.let { d.tvs.upsert(it.copy(launcher = null)) }
            effect(ManageEffect.Snackbar(SnackKind.HOME_SAVED, d.nameOf(stock.pkg), false))
        }
        load()
    }
}
