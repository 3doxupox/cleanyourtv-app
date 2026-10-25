package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.FireOs
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.TclGoogleTv
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.Looks

// M2 Home screen: the dock (S3b sheet), the look (theme picker), "Use the TV's own home screen"
// with its preview, the drift banner (D15: the overlay digest re-read on every connect; "Restore
// mine" pushes ours again) and "Update the home screen" with its preview (D4, RFC-LAUNCHER-1).
data class AppChoice(val pkg: PackageName, val name: String)

// 0.3.3 D2 (issue #3): the new home screen is not on this TV (Setup did not install it, or it was
// removed since), so there is no dock or look to send: the planner refuses ApplyTheme then, and
// 0.3.2 showed that refusal as "We stopped". Instead the screen says why, from the wizard's stored
// decision (HomeDecisions), and what can change it.
// `reason`: the home switch block Setup recorded; null when none was recorded (a TV set up before
// 0.3.2) or the switch was planned and the launcher is gone since.
// `fireTv`: for TV_REFUSES, name Fire TV instead of the brand (issue #2).
// `unlisted`: no exact profile knows this TV, so even with the Wi-Fi saved another way its setup
// wizard has to stay on (HomeSwitchRisk) and WIZARD_WIFI needs a list first.
// `setupAgain`: running Setup again can add it (nothing blocks it, or the block can change).
// `helpAddTv`: a list for this TV is what is missing; "Help add this TV" makes its report.
data class NotOnTv(
    val reason: HomeBlock?,
    val brand: String,
    val fireTv: Boolean = false,
    val unlisted: Boolean = false,
    val setupAgain: Boolean = false,
    val helpAddTv: Boolean = false,
)

// 0.4.0: a picture of the Picture row: a gallery picture by id, with its file for the thumbnail
// (null when it has none on this phone).
data class PictureOption(val id: String, val name: String, val path: String?)

// 0.4.0: the Look sheet's choice while it is open. Nothing reaches the TV until UseLook.
// `picture`: null for the look's own, LookPicture.NONE_ID, MINE_ID (the person's photo) or a gallery id. `ownPictureOnly`: a dated
// look (a festival) keeps its own picture, so the sheet shows no Picture row for it. `preview`: the
// TV's home screen with this choice (LookPreview), null when it cannot be made.
data class LookDraft(val themeId: String, val picture: String?, val ownPictureOnly: Boolean = false, val preview: LookPreview? = null)

data class HomeScreenState(
    val tvName: String = "",
    val dock: List<AppChoice> = emptyList(),           // the dock, then Row 2 and Row 3 (DockLayers)
    val apps: List<AppChoice> = emptyList(),           // installed apps for the dock sheet
    val themeId: String? = null,
    val themes: List<ThemeOption> = emptyList(),
    val drift: Boolean = false,
    val stock: Component? = null,                       // the profile's stock launcher, when there is one
    val stockPreview: Plan? = null,
    val busy: Busy? = null,
    val homeScreenOurs: Boolean = true,
    val columns: Int = DockLayers.DEFAULT_COLUMNS,     // apps per dock row for the look on the TV
    val updateTo: String? = null,                       // D4: the bundled launcher's version, when the TV has an older build of ours
    val updatePreview: Plan? = null,
    val tvSideSettings: Boolean = false,                // the TV's launcher has Launcher settings of its own (2.4, versionCode 5, and later)
    val notOnTv: NotOnTv? = null,                       // 0.3.3 D2: the new home screen is not on this TV; no dock or look rows
    val tclRemoteKeys: Boolean = false,                 // issue #275: a TCL Google TV, whose remote's gear, Netflix and YouTube buttons need its own home screen
    val endedLook: String? = null,                      // 0.4.0: the name of a dated look that has ended and is still on the TV
    val pictureId: String? = null,                      // 0.4.0: the saved picture choice (HomeChoice.picture)
    val pictures: List<PictureOption> = emptyList(),    // 0.4.0: the gallery of the Picture row
    val ownPicture: BlobRef? = null,                    // 0.4.0: the person's photo kept for this TV (OwnPicture), as a file
    val ownDim: Int = OwnPicture.DEFAULT_DIM,           // and its dim
    val look: LookDraft? = null,                        // 0.4.0: the Look sheet's choice, while it is open
)

sealed interface HomeScreenIntent {
    data class SetDock(val dock: List<PackageName>) : HomeScreenIntent
    data class SetTheme(val id: String, val picture: String? = null) : HomeScreenIntent
    data object OpenLook : HomeScreenIntent              // 0.4.0: the Look sheet, starting from the TV's look
    data class SelectLook(val id: String) : HomeScreenIntent
    data class SelectPicture(val id: String?) : HomeScreenIntent
    data object UseLook : HomeScreenIntent               // the sheet's choice to the TV
    // The photo the phone just made (OwnPicture: 16:9, 1920 x 1080, at most 280 KB) and its dim: kept
    // for this TV and chosen in the sheet. Nothing reaches the TV until UseLook.
    class SetOwnPicture(val jpeg: ByteArray, val dim: Int) : HomeScreenIntent
    data object CloseLook : HomeScreenIntent
    data object RestoreMine : HomeScreenIntent
    data object PreviewStock : HomeScreenIntent
    data object UseStock : HomeScreenIntent
    data object CancelPreview : HomeScreenIntent
    data object PreviewUpdate : HomeScreenIntent       // D4
    data object UpdateHomeScreen : HomeScreenIntent    // D4
    data object RunSetupAgain : HomeScreenIntent       // 0.3.3 D2, NotOnTv.setupAgain
    data object HelpAddTv : HomeScreenIntent           // 0.3.3 D2, NotOnTv.helpAddTv
}

class HomeScreenMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    private val tvId: TvId,
) : Machine<HomeScreenState, HomeScreenIntent, ManageEffect>(HomeScreenState(themes = offeredThemes(d)), scope) {

    private val writer = TvWriter(d)
    private data class Loaded(val s: HomeScreenState)
    private data class BusyNow(val busy: Busy?)
    private data class Preview(val plan: Plan?)
    private data class Drift(val drift: Boolean)
    private data class UpdatePreview(val plan: Plan?)
    private data class OwnPictureKept(val ref: BlobRef, val dim: Int)
    private var updated = false   // D4: this screen updated the launcher; the stored snapshot does not know yet

    init { scope.launch { load(); checkDrift() } }

    override fun reduce(state: HomeScreenState, event: Any): HomeScreenState = when (event) {
        is Loaded -> event.s.copy(busy = state.busy, stockPreview = state.stockPreview, drift = state.drift, updatePreview = state.updatePreview, look = state.look)
        HomeScreenIntent.OpenLook -> if (state.busy != null || state.notOnTv != null) state else state.copy(look = draft(state.themeId ?: DEFAULT_LOOK, state.pictureId, state))
        is HomeScreenIntent.SelectLook -> state.look?.let { l -> state.copy(look = draft(event.id, l.picture, state)) } ?: state
        is HomeScreenIntent.SelectPicture -> state.look?.let { l -> state.copy(look = if (l.ownPictureOnly) l else draft(l.themeId, event.id, state)) } ?: state
        HomeScreenIntent.CloseLook, HomeScreenIntent.UseLook -> state.copy(look = null)
        is OwnPictureKept -> state.copy(ownPicture = event.ref, ownDim = event.dim).let { s ->
            s.look?.takeUnless { it.ownPictureOnly }?.let { l -> s.copy(look = draft(l.themeId, LookPicture.MINE_ID, s)) } ?: s
        }
        is UpdatePreview -> state.copy(updatePreview = event.plan)
        is BusyNow -> state.copy(busy = event.busy)
        is Preview -> state.copy(stockPreview = event.plan)
        is Drift -> state.copy(drift = event.drift)
        HomeScreenIntent.CancelPreview -> state.copy(stockPreview = null, updatePreview = null)
        else -> state
    }

    override fun handle(event: Any, before: HomeScreenState, after: HomeScreenState) {
        if (before.busy != null && event is HomeScreenIntent) return
        // 0.3.3 D2: nothing to send the dock or the look to.
        if (after.notOnTv != null && (event is HomeScreenIntent.SetDock || event is HomeScreenIntent.SetTheme || event == HomeScreenIntent.RestoreMine ||
            event == HomeScreenIntent.UseLook)) return
        when (event) {
            is HomeScreenIntent.SetDock -> scope.launch { apply(event.dock, after.themeId, after.pictureId) }
            // RF-LAUNCHER-250 F4: a new look and "Restore mine" push the dock as it was saved, as
            // ModulesMachine does, not the list as the TV shows it now (an app since gone or switched
            // off would drop out and change dock_apps, and the TV's own dock edit would stop applying,
            // upstream #340). Only SetDock changes the list.
            is HomeScreenIntent.SetTheme -> if (Looks.offered(event.id, d.clock.today())) scope.launch { apply(savedDock() ?: after.dock.map { it.pkg }, event.id, event.picture) }
            HomeScreenIntent.UseLook -> before.look?.let { l -> if (Looks.offered(l.themeId, d.clock.today())) scope.launch { apply(savedDock() ?: after.dock.map { it.pkg }, l.themeId, l.picture) } }
            HomeScreenIntent.RestoreMine -> scope.launch { apply(savedDock() ?: after.dock.map { it.pkg }, after.themeId, after.pictureId) }
            is HomeScreenIntent.SetOwnPicture -> scope.launch {
                val dim = event.dim.coerceIn(0, 90)
                attempt { OwnPicture.save(d, tvId, event.jpeg) }.onSuccess { send(OwnPictureKept(it, dim)) }
                    .onFailure { effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false)) }
            }
            HomeScreenIntent.PreviewStock -> after.stock?.let { stock -> scope.launch { send(Preview(writer.preview(tvId, PlanRequest.UseStockHome(stock)))) } }
            HomeScreenIntent.UseStock -> after.stock?.let { stock -> scope.launch { useStock(stock) } }
            HomeScreenIntent.PreviewUpdate -> scope.launch { previewUpdate() }
            HomeScreenIntent.UpdateHomeScreen -> scope.launch { update() }
            HomeScreenIntent.RunSetupAgain -> if (after.notOnTv?.setupAgain == true) effect(ManageEffect.Navigate("setup/${tvId.value}"))
            HomeScreenIntent.HelpAddTv -> if (after.notOnTv?.helpAddTv == true) effect(ManageEffect.Navigate("report/tv/${tvId.value}"))
            else -> {}
        }
    }

    private suspend fun load() {
        val tv = d.tv(tvId) ?: return
        val profile = d.profileOf(tv)
        val snap = d.snapshots.latest(tvId)
        val packages = snap?.packages.orEmpty()
        val choice = d.homePrefs.load(tvId)
        val today = d.clock.today()
        val lookId = choice?.lookOn(today)
        // The stored snapshot cannot say which apps are launchable, so the rule without that
        // answer applies: never a home screen, a provider or a system-only package.
        val apps = (snap?.let { DockCandidates.of(it, null, profile, d.facts) } ?: emptySet())
            .map { AppChoice(it, d.nameOf(it, packages[it]?.label)) }.sortedBy { it.name.lowercase() }
        // The dock as the TV shows it: an app no longer there (gone, switched off) is skipped and the
        // later ones move up a slot, as the launcher does.
        val columns = DockLayers.columnsOf(theme(lookId)?.doc)
        val shown = { p: PackageName -> snap == null || packages[p]?.enabled.let { it != null && it != EnabledState.DISABLED && it != EnabledState.DISABLED_USER } }
        val dock = DockLayers.decode(choice?.dock.orEmpty(), shown, columns).map { AppChoice(it, d.nameOf(it, packages[it]?.label)) }
        send(Loaded(HomeScreenState(tv.nickname, dock, apps, lookId, offeredThemes(d), stock = profile?.stockLaunchers?.firstOrNull(),
            homeScreenOurs = tv.launcher != null, columns = columns, updateTo = updateTo(tv, packages),
            tvSideSettings = (packages[Launcher.pkg]?.versionCode ?: 0) >= FIRST_WITH_TV_SETTINGS,
            notOnTv = notOnTv(tv, snap), tclRemoteKeys = snap != null && TclGoogleTv.applies(snap.facts, snap),
            endedLook = choice?.endedLook(today)?.let { id -> theme(id)?.name ?: id },
            pictureId = choice?.picture?.takeUnless { Looks.dated(lookId) }, pictures = pictureOptions(),
            ownPicture = attempt { OwnPicture.ref(d, tvId) }.getOrNull(), ownDim = choice?.dim ?: OwnPicture.DEFAULT_DIM)))
    }

    // 0.3.3 D2: null while the new home screen is on the TV: Setup recorded it (tv.launcher), or the
    // newest snapshot lists it (installed, perhaps not the home screen now: "Use the TV's own home
    // screen" keeps it, and a look still applies). Without a snapshot this phone cannot tell, so the
    // rows stay and the planner's refusal is the backstop.
    // 0.3.4 (issues #171, #187, #220): a launcher under our package name that Setup left as it is
    // (HOME_CHANGED: installed another way, with another key) is not ours either, so the screen says
    // why, as for issue #8, instead of offering a dock and a look for it.
    private suspend fun notOnTv(tv: tv.cleanyour.core.model.Tv, snap: tv.cleanyour.core.model.Snapshot?): NotOnTv? {
        if (tv.launcher != null || snap == null) return null
        val reason = attempt { d.homeDecisions.load(tvId) }.getOrNull()?.block?.let { b -> HomeBlock.entries.firstOrNull { it.name == b } }
        if (Launcher.pkg in snap.packages && reason != HomeBlock.HOME_CHANGED) return null
        val unlisted = tv.profileId == null
        val setupAgain = when (reason) {
            null, HomeBlock.NONE, HomeBlock.WIZARD_WIFI_UNKNOWN -> true
            // Issue #8: the person can make the TV's own home screen Home again, and on a TV an exact
            // profile knows Setup then offers the switch. Without that list it may still not.
            HomeBlock.HOME_CHANGED -> !unlisted
            // Forgetting and adding the network again makes the TV's own Wi-Fi settings the owner; on a
            // TV no exact profile knows the wizard still has to stay on (HomeSwitchRisk), so not there.
            HomeBlock.WIZARD_WIFI -> !unlisted
            else -> false
        }
        val needsList = reason == HomeBlock.UNKNOWN_HOME || reason == HomeBlock.WIZARD_STAYS_ON || (reason == HomeBlock.WIZARD_WIFI && unlisted)
        return NotOnTv(
            reason = reason, brand = LogCodes.clean(snap.facts.brand, 40).ifEmpty { tv.nickname },
            fireTv = reason == HomeBlock.TV_REFUSES && FireOs.isFireOs(snap.facts, snap), unlisted = unlisted,
            setupAgain = setupAgain, helpAddTv = needsList && unlisted,
        )
    }

    // D4: the bundled launcher is newer than ours on the TV (same signer, lower versionCode). The
    // stored snapshot says which build is installed and with which key; one from before the app
    // read signers falls back to what Setup recorded for this TV. The planner checks again on a
    // fresh snapshot before anything is installed.
    private fun updateTo(tv: tv.cleanyour.core.model.Tv, packages: Map<PackageName, tv.cleanyour.core.model.PackageState>): String? {
        if (updated) return null
        val pin = runCatching { d.launcherPin.bundled() }.getOrNull() ?: return null
        val installed = packages[pin.pkg] ?: return null
        val signer = installed.signer ?: tv.launcher?.signer ?: return null
        return pin.versionName.takeIf { signer == pin.signer && installed.versionCode < pin.versionCode }
    }

    private suspend fun previewUpdate() {
        val pin = attempt { d.launcherPin.bundled() }.getOrNull() ?: return
        val plan = writer.preview(tvId, PlanRequest.UpdateLauncher(pin), fresh = true)
        if (plan == null || plan.steps.isEmpty()) {
            // Nothing to update after all (a fresher look at the TV), or it cannot be planned.
            if (plan != null) updated = true
            effect(ManageEffect.Snackbar(if (plan != null) SnackKind.HOME_UPDATED else SnackKind.FAILED, pin.versionName, false))
            load()
            return
        }
        send(UpdatePreview(plan))
    }

    // "Update the home screen": the bundled launcher over ours, in place (RFC-LAUNCHER-1). The dock,
    // the look (config.json) and the card answers stay on the TV, since only an uninstall removes
    // them, so nothing is pushed again.
    private suspend fun update() {
        send(UpdatePreview(null))
        val pin = attempt { d.launcherPin.bundled() }.getOrNull() ?: return effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        val r = writer.write(tvId, PlanRequest.UpdateLauncher(pin), fresh = true) { send(BusyNow(it)) }
        send(BusyNow(null))
        when {
            r is WriteResult.Done && r.finished.status == RunStatus.COMPLETED -> {
                updated = true
                d.tv(tvId)?.let { t -> t.launcher?.let { l -> d.tvs.upsert(t.copy(launcher = l.copy(versionCode = pin.versionCode, signer = pin.signer))) } }
                effect(ManageEffect.Snackbar(SnackKind.HOME_UPDATED, pin.versionName, false))
            }
            r is WriteResult.GateCancelled || r is WriteResult.NoTv || r is WriteResult.Paused -> {}   // Paused: the open-run card (O2)
            else -> effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        }
        load()
    }

    private suspend fun checkDrift() {
        val tv = d.tv(tvId) ?: return
        val ours = tv.launcher?.ourOverlayDigest ?: return
        val session = d.access.live(tvId) ?: return
        val now = attempt { d.configTransport.readDigest(session) }.getOrNull()
        send(Drift(now != null && now != ours))
    }

    companion object {
        // Launcher 2.4 (versionCode 5) added Launcher settings on the TV (RF-LAUNCHER-240 F16).
        const val FIRST_WITH_TV_SETTINGS = 5L
        const val DEFAULT_LOOK = "default"

        // 0.4.0: the looks on offer today; a dated look (a festival) up to its last day.
        private fun offeredThemes(d: ManageDeps): List<ThemeOption> {
            val today = d.clock.today()
            return d.themes.bundled().filter { Looks.offered(it.id, today) }
                .map { ThemeOption(it.id, it.name, it.description, it.wallpaper?.path?.takeIf { p -> p.startsWith("/") }) }
        }
    }

    private fun theme(id: String?) = id?.let { d.themes.bundled().firstOrNull { t -> t.id == it } }

    private fun draft(themeId: String, picture: String?, state: HomeScreenState): LookDraft {
        val dated = Looks.dated(themeId)
        val p = picture.takeUnless { dated }
        return LookDraft(themeId, p, ownPictureOnly = dated, preview = preview(themeId, p, state))
    }

    // The preview from the bytes the push would send for this choice and the saved dock (cards as
    // they are do not change the picture, so they are left at the default).
    private fun preview(themeId: String, picture: String?, state: HomeScreenState): LookPreview? = runCatching {
        val p = if (picture == LookPicture.MINE_ID) state.ownPicture?.let { LookPicture.Of(it, state.ownDim) } else LookPicture.of(picture, d.pictures)
        val (doc, wallpaper) = Look.docOf(theme(themeId), state.dock.map { it.pkg }, cards = Cards.MANAGE_DEFAULT, p)
        val now = java.time.Instant.ofEpochMilli(d.clock.nowMs()).atZone(java.time.ZoneId.systemDefault())
        LookPreview.of(d.encoder.encode(doc), wallpaper, state.dock.map { it.name }, now.toLocalDate(), now.hour * 60 + now.minute)
    }.getOrNull()

    private fun pictureOptions(): List<PictureOption> =
        runCatching { d.pictures.pictures() }.getOrNull().orEmpty().map { PictureOption(it.id, it.name, it.wallpaper.path.takeIf { p -> p.startsWith("/") }) }

    private suspend fun savedDock(): List<PackageName>? = d.homePrefs.load(tvId)?.dock

    // Dock and look go to the TV as one config.json (LauncherConfigCodec), pushed by an ApplyTheme
    // plan; the digest we wrote is remembered so the next connect can warn on drift. dock_apps is
    // the dock and the rows under it in order, rows_below_dock the rows under the dock that hold an
    // app (DockLayers), so the TV shows the rows the sheet showed. module_cards keeps this TV's cards
    // choice (RFC-CARDS-1): a new dock or theme never turns cards on or off.
    // 0.4.0 `picture`: the Picture row's choice (HomeChoice.picture); a dated look keeps its own.
    private suspend fun apply(dock: List<PackageName>, themeId: String?, picture: String?) {
        val cards = Cards.current(d, tvId, d.homePrefs.load(tvId))
        val pictureId = picture.takeUnless { Looks.dated(themeId) }
        val dim = state.value.ownDim
        val look = attempt { Look.encode(d.encoder, d.blobs, theme(themeId), dock, cards, OwnPicture.lookPicture(d, tvId, pictureId, dim)) }.getOrNull()
            ?: return effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        val kept = look.doc.dockApps.orEmpty()
        val r = writer.write(tvId, PlanRequest.ApplyTheme(look.doc, look.config, look.wallpaper)) { send(BusyNow(it)) }
        send(BusyNow(null))
        if (r is WriteResult.Done) {
            // A dated look keeps its own picture, so the picture and dim saved for the look before it
            // stay, and come back with that look once the dated one ends.
            val saved = d.homePrefs.load(tvId) ?: HomeChoice(emptyList(), null)
            val dated = Looks.dated(themeId)
            d.homePrefs.save(tvId, saved.withTheme(themeId, kept, cards, picture = if (dated) saved.picture else pictureId,
                dim = if (dated) saved.dim else if (pictureId == LookPicture.MINE_ID) dim else null))
            rememberPushedLook(d, tvId, look.digest)
            send(Drift(false))
            effect(ManageEffect.Snackbar(SnackKind.HOME_SAVED, "", false))
            load()
            return
        }
        load()
        // 0.3.3 D2: the TV said the new home screen is gone (removed since the snapshot this screen
        // read): the reload shows why there is nothing to send, which says more than "We stopped".
        if (r is WriteResult.Refused && state.value.notOnTv != null) return
        if (r !is WriteResult.GateCancelled && r !is WriteResult.NoTv && r !is WriteResult.Paused) {   // Paused: the open-run card (O2)
            effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        }
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
