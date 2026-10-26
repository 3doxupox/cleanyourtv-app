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
// `picture`: null for the look's own, LookPicture.NONE_ID, MINE_ID (the person's photo kept for this TV),
// NEW_ID (a photo just made in this sheet) or a gallery id. `ownPictureOnly`: a dated
// look (a festival) keeps its own picture, so the sheet shows no Picture row for it. `preview`: the
// TV's home screen with this choice (LookPreview), null when it cannot be made.
// `columns` (0.4.0): the apps in a row this choice sends, 4 to 8, always a number; `lookColumns` the
// look's own, for the helper lines and the save rule (HomeChoice.columns is kept only when the two
// differ). A look tap resets `columns` to that look's own, because three looks name their count.
data class LookDraft(val themeId: String, val picture: String?, val ownPictureOnly: Boolean = false, val preview: LookPreview? = null,
    val columns: Int = DockLayers.DEFAULT_COLUMNS, val lookColumns: Int = DockLayers.DEFAULT_COLUMNS)

data class HomeScreenState(
    val tvName: String = "",
    val dock: List<AppChoice> = emptyList(),           // the dock, then Row 2 and Row 3 (DockLayers), as the TV shows them
    val apps: List<AppChoice> = emptyList(),           // installed apps for the dock sheet
    val themeId: String? = null,
    val themes: List<ThemeOption> = emptyList(),
    val drift: Boolean = false,
    val stock: Component? = null,                       // the profile's stock launcher, when there is one
    val stockPreview: Plan? = null,
    val busy: Busy? = null,
    val homeScreenOurs: Boolean = true,
    val columns: Int = DockLayers.DEFAULT_COLUMNS,     // apps per dock row on the TV: the saved apps in a row, else the look's own
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
    // 0.4.0: a photo just made in the open Look sheet (staged, OwnPicture.stage) and its dim. It
    // becomes the kept one only when "Put it on the TV" put it there; closing the sheet or a failed
    // push drops it, so ownPicture and ownDim stay what the TV has.
    val newPicture: BlobRef? = null,
    val newDim: Int = OwnPicture.DEFAULT_DIM,
    val look: LookDraft? = null,                        // 0.4.0: the Look sheet's choice, while it is open
    // 0.4.0: the apps of the saved dock the TV would show after `dock` with more apps in a row (the
    // saved list holds up to 24, DockLayers.keep). The dock sheet never shows them, so a new dock
    // from it keeps them after its own apps; the Look sheet's preview and lines count them.
    val waitingApps: List<AppChoice> = emptyList(),
) {
    val waiting: Int get() = waitingApps.size
}

sealed interface HomeScreenIntent {
    data class SetDock(val dock: List<PackageName>) : HomeScreenIntent
    data class SetTheme(val id: String, val picture: String? = null) : HomeScreenIntent
    data object OpenLook : HomeScreenIntent              // 0.4.0: the Look sheet, starting from the TV's look
    data class SelectLook(val id: String) : HomeScreenIntent
    data class SelectPicture(val id: String?) : HomeScreenIntent
    data class SelectColumns(val count: Int) : HomeScreenIntent   // 0.4.0: apps in a row, 4 to 8; anything else is ignored
    data object UseLook : HomeScreenIntent               // the sheet's choice to the TV
    // The photo the phone just made (OwnPicture: 16:9, 1920 x 1080, at most 280 KB) and its dim: staged
    // and chosen in the sheet. Nothing reaches the TV, and nothing is kept, until UseLook succeeds.
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
    private val drafts = LookDrafts(d.themes, d.pictures, d.encoder, d.clock)
    private data class Loaded(val s: HomeScreenState)
    private data class BusyNow(val busy: Busy?)
    private data class Preview(val plan: Plan?)
    private data class Drift(val drift: Boolean)
    private data class UpdatePreview(val plan: Plan?)
    private class OwnPictureStaged(val ref: BlobRef, val dim: Int, val jpeg: ByteArray)
    private var staged: ByteArray? = null   // the bytes of state.newPicture, kept once the TV has them
    private var updated = false   // D4: this screen updated the launcher; the stored snapshot does not know yet

    init { scope.launch { load(); checkDrift() } }

    override fun reduce(state: HomeScreenState, event: Any): HomeScreenState = when (event) {
        is Loaded -> event.s.copy(busy = state.busy, stockPreview = state.stockPreview, drift = state.drift, updatePreview = state.updatePreview, look = state.look,
            newPicture = state.newPicture, newDim = state.newDim)
        HomeScreenIntent.OpenLook -> if (state.busy != null || state.notOnTv != null) state else
            state.copy(look = draft(state.themeId ?: DEFAULT_LOOK, state.pictureId, state, state.columns))
        // A look tap resets apps in a row to that look's own; a picture keeps the draft's.
        is HomeScreenIntent.SelectLook -> state.look?.let { l -> state.copy(look = draft(event.id, l.picture, state, null)) } ?: state
        is HomeScreenIntent.SelectPicture -> state.look?.let { l -> state.copy(look = if (l.ownPictureOnly) l else draft(l.themeId, event.id, state, l.columns)) } ?: state
        is HomeScreenIntent.SelectColumns -> state.look?.takeIf { event.count in OFFERED_COLUMNS && event.count != it.columns }
            ?.let { l -> state.copy(look = draft(l.themeId, l.picture, state, event.count)) } ?: state
        // Closing the sheet drops a photo made in it and its dim; "Put it on the TV" hands them to the push.
        HomeScreenIntent.CloseLook, HomeScreenIntent.UseLook -> state.copy(look = null, newPicture = null, newDim = state.ownDim)
        // A photo made for a sheet that has closed since is dropped.
        is OwnPictureStaged -> state.look?.takeUnless { it.ownPictureOnly }?.let { l ->
            state.copy(newPicture = event.ref, newDim = event.dim).let { s -> s.copy(look = draft(l.themeId, LookPicture.NEW_ID, s, l.columns)) }
        } ?: state
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
            // 0.4.0: the sheet's list, then the saved apps it never showed (waiting for more apps in
            // a row), so a new dock never loses them. Apps in a row stays the saved one.
            is HomeScreenIntent.SetDock -> scope.launch {
                val tail = after.waitingApps.map { it.pkg }.filter { it !in event.dock }
                apply(event.dock + tail, after.themeId, after.pictureId, columns = savedColumns())
            }
            // RF-LAUNCHER-250 F4: a new look and "Restore mine" push the dock as it was saved, as
            // ModulesMachine does, not the list as the TV shows it now (an app since gone or switched
            // off would drop out and change dock_apps, and the TV's own dock edit would stop applying,
            // upstream #340). Only SetDock changes the list.
            // A look tap resets apps in a row to that look's own (columns null).
            is HomeScreenIntent.SetTheme -> if (Looks.offered(event.id, d.clock.today())) scope.launch { apply(savedDock() ?: after.dock.map { it.pkg }, event.id, event.picture, columns = null) }
            HomeScreenIntent.UseLook -> {
                val photo = staged?.let { b -> before.newPicture?.let { NewPhoto(it, b, before.newDim) } }
                staged = null
                before.look?.let { l ->
                    if (Looks.offered(l.themeId, d.clock.today())) scope.launch { apply(savedDock() ?: after.dock.map { it.pkg }, l.themeId, l.picture, photo, l.columns) }
                }
            }
            HomeScreenIntent.CloseLook -> staged = null
            is OwnPictureStaged -> if (after.newPicture == event.ref) staged = event.jpeg
            HomeScreenIntent.RestoreMine -> scope.launch { apply(savedDock() ?: after.dock.map { it.pkg }, after.themeId, after.pictureId, columns = savedColumns()) }
            is HomeScreenIntent.SetOwnPicture -> scope.launch {
                val dim = event.dim.coerceIn(0, 90)
                attempt { OwnPicture.stage(d, event.jpeg) }.onSuccess { send(OwnPictureStaged(it, dim, event.jpeg)) }
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
        // later ones move up a slot, as the launcher does. 0.4.0: at the saved apps in a row, else the
        // look's own; the saved apps beyond what that shows wait (waitingApps).
        val columns = choice?.columns ?: DockLayers.columnsOf(theme(lookId)?.doc)
        val shown = { p: PackageName -> snap == null || packages[p]?.enabled.let { it != null && it != EnabledState.DISABLED && it != EnabledState.DISABLED_USER } }
        fun named(p: PackageName) = AppChoice(p, d.nameOf(p, packages[p]?.label))
        val dock = DockLayers.decode(choice?.dock.orEmpty(), shown, columns).map(::named)
        val waiting = DockLayers.keep(choice?.dock.orEmpty().filter(shown)).drop(DockLayers.capacity(columns)).map(::named)
        send(Loaded(HomeScreenState(tv.nickname, dock, apps, lookId, offeredThemes(d), stock = profile?.stockLaunchers?.firstOrNull(),
            homeScreenOurs = tv.launcher != null, columns = columns, updateTo = updateTo(tv, packages),
            tvSideSettings = (packages[Launcher.pkg]?.versionCode ?: 0) >= FIRST_WITH_TV_SETTINGS,
            notOnTv = notOnTv(tv, snap), tclRemoteKeys = snap != null && TclGoogleTv.applies(snap.facts, snap),
            endedLook = choice?.endedLook(today)?.let { id -> theme(id)?.name ?: id },
            pictureId = choice?.picture?.takeUnless { Looks.dated(lookId) }, pictures = pictureOptions(),
            ownPicture = attempt { OwnPicture.ref(d, tvId) }.getOrNull(), ownDim = choice?.dim ?: OwnPicture.DEFAULT_DIM,
            waitingApps = waiting)))
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
        private val OFFERED_COLUMNS = DockLayers.MIN_OFFERED_COLUMNS..DockLayers.MAX_OFFERED_COLUMNS

        // 0.4.0: the looks on offer today; a dated look (a festival) up to its last day.
        private fun offeredThemes(d: ManageDeps): List<ThemeOption> = LookDrafts(d.themes, d.pictures, d.encoder, d.clock).offered()
    }

    private fun theme(id: String?) = drafts.theme(id)

    // `columns`: the draft's apps in a row; null for the look's own. The preview shows the whole dock
    // as the TV shows it, the waiting apps too: with more apps in a row they come into view.
    private fun draft(themeId: String, picture: String?, state: HomeScreenState, columns: Int?): LookDraft =
        drafts.draft(themeId, picture, columns, state.dock + state.waitingApps,
            mine = state.ownPicture?.let { LookPicture.Of(it, state.ownDim) }, new = state.newPicture?.let { LookPicture.Of(it, state.newDim) })

    private fun pictureOptions(): List<PictureOption> = drafts.pictureOptions()

    private suspend fun savedDock(): List<PackageName>? = d.homePrefs.load(tvId)?.dock
    private suspend fun savedColumns(): Int? = d.homePrefs.load(tvId)?.columns

    // A photo made in the Look sheet on its way to the TV: its staged file, its bytes and its dim.
    private class NewPhoto(val ref: BlobRef, val jpeg: ByteArray, val dim: Int)

    // Dock and look go to the TV as one config.json (LauncherConfigCodec), pushed by an ApplyTheme
    // plan; the digest we wrote is remembered so the next connect can warn on drift. dock_apps is
    // the dock and the rows under it in order, rows_below_dock the rows under the dock that hold an
    // app (DockLayers), so the TV shows the rows the sheet showed. module_cards keeps this TV's cards
    // choice (RFC-CARDS-1): a new dock or theme never turns cards on or off.
    // 0.4.0 `picture`: the Picture row's choice (HomeChoice.picture); a dated look keeps its own.
    // `photo`: the photo made in the sheet (LookPicture.NEW_ID), kept for this TV only once the TV has
    // it; until then the kept one (and "Restore mine") stays the photo on the TV.
    // 0.4.0 `columns`: the apps in a row to send, null for the look's own; it is kept only when it
    // differs from the look's own. The whole `dock` is kept (DockLayers.keep, up to 24 apps), not the
    // part this many in a row shows, so a smaller row never loses the rest of the order.
    private suspend fun apply(dock: List<PackageName>, themeId: String?, picture: String?, photo: NewPhoto? = null, columns: Int? = null) {
        val cards = Cards.current(d, tvId, d.homePrefs.load(tvId))
        val pictureId = picture.takeUnless { Looks.dated(themeId) }
        val sendsNew = pictureId == LookPicture.NEW_ID && photo != null
        val dim = if (sendsNew) photo!!.dim else state.value.ownDim
        val lookPicture = if (sendsNew) LookPicture.Of(photo!!.ref, photo.dim)
            else OwnPicture.lookPicture(d, tvId, pictureId.takeUnless { it == LookPicture.NEW_ID }, dim)
        val override = drafts.override(themeId, columns)
        val look = attempt { Look.encode(d.encoder, d.blobs, theme(themeId), dock, cards, lookPicture, override) }.getOrNull()
            ?: return effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        val kept = DockLayers.keep(dock)
        val r = writer.write(tvId, PlanRequest.ApplyTheme(look.doc, look.config, look.wallpaper)) { send(BusyNow(it)) }
        send(BusyNow(null))
        if (r is WriteResult.Done) {
            // The new photo is kept only when the run completed, so it is the one on the TV; when it
            // cannot be kept, the choice stays as it was saved.
            val keptNew = sendsNew && r.finished.status == RunStatus.COMPLETED &&
                attempt { OwnPicture.keep(d, tvId, photo!!.jpeg) }.isSuccess
            // A dated look keeps its own picture, so the picture and dim saved for the look before it
            // stay, and come back with that look once the dated one ends.
            val saved = d.homePrefs.load(tvId) ?: HomeChoice(emptyList(), null)
            val keepSaved = Looks.dated(themeId) || (pictureId == LookPicture.NEW_ID && !keptNew)
            val chosen = if (keptNew) LookPicture.MINE_ID else pictureId
            d.homePrefs.save(tvId, saved.withTheme(themeId, kept, cards, picture = if (keepSaved) saved.picture else chosen,
                dim = if (keepSaved) saved.dim else if (chosen == LookPicture.MINE_ID) dim else null, columns = override))
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
