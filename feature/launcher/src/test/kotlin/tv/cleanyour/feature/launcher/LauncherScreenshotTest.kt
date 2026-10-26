package tv.cleanyour.feature.launcher

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.time.LocalDate
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.spec.BundledThemeSource
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.flows.LookDocs
import tv.cleanyour.core.flows.LookPicture
import tv.cleanyour.ui.design.theme.CytTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.Busy
import tv.cleanyour.core.flows.HomeBlock
import tv.cleanyour.core.flows.NotOnTv
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.LookDraft
import tv.cleanyour.core.flows.LookPreview
import tv.cleanyour.core.flows.PictureOption
import tv.cleanyour.core.flows.ThemeOption
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.component.DockEditorState
import tv.cleanyour.ui.design.component.LookPreviewCard
import tv.cleanyour.ui.design.component.PhotoCropContent
import tv.cleanyour.ui.design.component.PhotoPicker
import tv.cleanyour.ui.design.component.toTvLook

// Every section 4 state of :feature:launcher (PRODUCT-SPEC 4: M2 with the drift banner, the
// Look and dock sheets and the stock home screen preview), each in dark, light, 200% font and
// right to left, with ATF on.

private fun app(pkg: String, name: String) = AppChoice(PackageName(pkg), name)

private val defaultDock = listOf(
    app("com.netflix.ninja", "Netflix"), app("com.google.android.youtube.tv", "YouTube"),
    app("com.amazon.amazonvideo.livingroom", "Prime Video"), app("com.droidlogic.tvinput", "Live TV"),
)
private val apps = defaultDock + listOf(app("com.spotify.tv.android", "Spotify"), app("com.plexapp.android", "Plex"), app("org.videolan.vlc", "VLC"))
private val rowsDock = defaultDock + apps.drop(4).take(2)
// 0.4.0: a saved dock of 15 (the Console look's three rows of 7 hold it all), for the waiting lines.
private val longDock = apps + listOf(
    app("com.disney.disneyplus", "Disney+"), app("tv.twitch.android.app", "Twitch"), app("com.sonyliv", "Sony LIV"),
    app("com.graymatrix.did", "ZEE5"), app("com.jio.media.ondemand", "JioCinema"), app("in.startv.hotstar", "JioHotstar"),
    app("com.zattoo.player", "Zattoo"), app("com.apple.atve.androidtv.appletv", "Apple TV"),
)

// The dock sheet's state as DockSheet makes it (remembered, so it survives recomposition).
@Composable
private fun dockState(dock: List<AppChoice>, addRow: Boolean = false) =
    remember { DockEditorState(dock.map { it.toDockApp() }, 4).apply { if (addRow) addRow() } }
private val themes = listOf(
    ThemeOption("default", "Default", "A black background with four apps along the bottom."),
    ThemeOption("aurora", "Aurora", "A deep blue background with a soft glow behind the apps."),
    ThemeOption("six-columns", "Six columns", "Six apps across, so more fit on the screen."),
)

private fun home(
    themeId: String? = null, drift: Boolean = false, busy: Busy? = null, stock: Boolean = true, ours: Boolean = true, dock: List<AppChoice> = defaultDock,
    updateTo: String? = null, endedLook: String? = null,
) = HomeScreenState(
    tvName = "Mi TV 4A", dock = dock, apps = apps, themeId = themeId, themes = themes, drift = drift,
    stock = if (stock) Samples.stockLauncher else null, busy = busy, homeScreenOurs = ours, updateTo = updateTo,
    tvSideSettings = updateTo == null,   // a TV still on launcher 2.0 (an update offered) has no Launcher settings
    endedLook = endedLook,
)

// 0.4.0 Look sheet: the preview, Apps in a row, the looks, the Picture row and "Put it on the TV".
// The previews are made as the machine makes them, from the bytes the push would send for the
// bundled looks (LookDocs, so fill_dock false and the apps in a row are in them), with their real
// pictures read from the :core:spec resources.
private val bundledLooks = BundledThemeSource().bundled().associateBy { it.id }
private val gallery = BundledThemeSource().pictures().associateBy { it.id }
private fun resourcePath(ref: BlobRef?) = ref?.path?.removePrefix(BundledThemeSource.PREFIX)?.let { "/$it" }
private val pictureFiles: Map<String, ImageBitmap> by lazy {
    (bundledLooks.values.map { it.wallpaper } + gallery.values.map { it.wallpaper }).mapNotNull { resourcePath(it) }.distinct().associateWith { path ->
        BitmapFactory.decodeStream(LauncherShots::class.java.getResourceAsStream(path), null, BitmapFactory.Options().apply { inSampleSize = 4 })!!.asImageBitmap()
    }
}
private fun preview(id: String, picture: String? = null, minute: Int = 21 * 60 + 5, columns: Int? = null, dock: List<AppChoice> = rowsDock): LookPreview {
    val look = bundledLooks.getValue(id)
    val (doc, wallpaper) = LookDocs.of(look, dock.map { it.pkg }, cards = true, picture?.let { LookPicture.Of(gallery.getValue(it).wallpaper) }, columns)
    val p = LookPreview.of(LauncherConfigCodec.encode(doc), null, dock.map { it.name }, LocalDate.of(2026, 10, 25), minute)
    return p.copy(picturePath = if (doc.wallpaper != false) resourcePath(wallpaper) else null)
}
// The sheet's draft as HomeScreenMachine makes it: the look's own apps in a row unless `columns`.
private fun draft(id: String, picture: String? = null, columns: Int? = null, dock: List<AppChoice> = rowsDock, ownPictureOnly: Boolean = false): LookDraft {
    val own = DockLayers.columnsOf(bundledLooks.getValue(id).doc)
    return LookDraft(id, picture, ownPictureOnly, preview(id, picture, columns = columns, dock = dock), columns ?: own, own)
}
private val noPictures = emptyMap<String, ImageBitmap>()
private fun lookSheet(draft: LookDraft) = home(themeId = "aurora", dock = rowsDock).copy(
    themes = bundledLooks.values.map { ThemeOption(it.id, it.name, it.description, resourcePath(it.wallpaper)) },
    pictures = gallery.values.map { PictureOption(it.id, it.name, resourcePath(it.wallpaper)) },
    look = draft,
)

// 0.3.3 D2: a TV without the new home screen, as HomeScreenMachine loads it (no dock, no stock row).
private fun notOnTv(n: NotOnTv, tvName: String = "Mi TV 4A") =
    HomeScreenState(tvName = tvName, themes = themes, homeScreenOurs = false, notOnTv = n)

// D4 "Update the home screen": the plan's narration lines are the preview's list.
private val updatePlan = Samples.plan(
    listOf(Op.InstallStream(Samples.launcherArtifact().blob, Samples.zeroHash, Samples.launcherArtifact().pkg, 5, Samples.zeroHash),
        Op.StartActivity(Samples.launcherComponent, wait = true)),
).let { p ->
    val lines = listOf("Updating the home screen to version 2.4", "Starting the updated home screen")
    p.copy(steps = p.steps.mapIndexed { i, s -> s.copy(narration = lines[i]) })
}

// "Use the TV's own home screen": the plan's narration lines are the preview's list.
private val stockPlan = Samples.plan(
    listOf(Op.Enable(Samples.stockLauncher.pkg), Op.SetHome(Samples.stockLauncher), Op.VerifyHome(Samples.stockLauncher)),
).let { p ->
    val lines = listOf("Switch the TV's own home screen back on", "Make it the home screen", "Check that Home opens it")
    p.copy(steps = p.steps.mapIndexed { i, s -> s.copy(narration = lines[i]) })
}

@Composable
private fun PreviewShot(p: LookPreview, name: String) {
    Box(Modifier.fillMaxSize().background(CytTheme.colors.bg).padding(16.dp)) { LookPreviewCard(p.toEditor(), name, pictureFiles) }
}

object LauncherShots {
    val all: List<Shot> = listOf(
        // M2 Home screen
        Shot("home_normal") { HomeScreen(home(), {}, {}) },
        Shot("home_aurora_theme") { HomeScreen(home(themeId = "aurora"), {}, {}) },
        Shot("home_drift_banner") { HomeScreen(home(drift = true), {}, {}) },
        Shot("home_busy_connecting") { HomeScreen(home(busy = Busy.Connecting), {}, {}) },
        Shot("home_busy_working") { HomeScreen(home(busy = Busy.Working(40, listOf("Updating the home screen on the TV..."))), {}, {}) },
        Shot("home_no_stock_launcher") { HomeScreen(home(stock = false), {}, {}) },
        Shot("home_stock_already_in_use") { HomeScreen(home(ours = false), {}, {}) },
        Shot("home_empty_dock") { HomeScreen(home(dock = emptyList()), {}, {}) },
        Shot("home_dock_rows") { HomeScreen(home(dock = rowsDock), {}, {}) },
        Shot("home_update_available") { HomeScreen(home(updateTo = "2.4"), {}, {}) },
        // Issue #275: TCL Google TV, the remote buttons the new home screen loses and the way back.
        Shot("home_tcl_remote_keys") { HomeScreen(home().copy(tvName = "TCL 55C755", tclRemoteKeys = true), {}, {}) },
        // 0.4.0: a dated look ended; the earlier one is the TV's again, one tap puts it back.
        Shot("home_look_ended") { HomeScreen(home(themeId = "aurora", endedLook = "Diwali"), {}, {}) },
        // 0.3.3 D2 (issue #3): the new home screen isn't on the TV. Why, and the next step when there is one.
        Shot("home_not_on_tv_wizard_wifi") { HomeScreen(notOnTv(NotOnTv(HomeBlock.WIZARD_WIFI, "Xiaomi", setupAgain = true)), {}, {}) },
        Shot("home_not_on_tv_unlisted") { HomeScreen(notOnTv(NotOnTv(HomeBlock.WIZARD_STAYS_ON, "Hisense", unlisted = true, helpAddTv = true), "Hisense 32A4200G"), {}, {}) },
        Shot("home_not_on_tv_fire_tv") { HomeScreen(notOnTv(NotOnTv(HomeBlock.TV_REFUSES, "Insignia", fireTv = true), "Insignia Fire TV"), {}, {}) },
        Shot("home_not_on_tv_home_changed") { HomeScreen(notOnTv(NotOnTv(HomeBlock.HOME_CHANGED, "TCL", setupAgain = true), "TCL G07"), {}, {}) },

        // Look (theme picker), the dock sheet (S3b on Manage) and the stock home screen preview
        Shot("home_look_sheet", 2000.dp) { SheetOver({ HomeScreen(home(), {}, {}) }) { LookContent(lookSheet(draft("aurora")), {}, pictureFiles, PhotoPicker {}) } },
        Shot("home_look_sheet_picture", 2000.dp) {
            SheetOver({ HomeScreen(home(), {}, {}) }) { LookContent(lookSheet(draft("quiet", "glass")), {}, pictureFiles) }
        },
        Shot("home_look_sheet_festival", 2000.dp) {
            SheetOver({ HomeScreen(home(), {}, {}) }) { LookContent(lookSheet(draft("diwali", ownPictureOnly = true)), {}, pictureFiles) }
        },
        Shot("home_look_sheet_no_preview", 1700.dp) {
            SheetOver({ HomeScreen(home(), {}, {}) }) {
                LookContent(lookSheet(LookDraft("six-columns", "glass", columns = 6, lookColumns = 6)).copy(pictures = gallery.values.map { PictureOption(it.id, it.name, null) }), {}, noPictures)
            }
        },
        // 0.4.0 Apps in a row: Default at 7 with a 4-app dock (3 spaces stay empty, drawn as empty
        // band); Mist at 8 with 6 apps (the 8 line); a saved 15-app dock at 4 (3 apps wait).
        Shot("home_look_sheet_columns", 2000.dp) {
            SheetOver({ HomeScreen(home(), {}, {}) }) { LookContent(lookSheet(draft("default", columns = 7, dock = defaultDock)).copy(dock = defaultDock), {}, pictureFiles) }
        },
        Shot("home_look_sheet_eight", 2000.dp) {
            SheetOver({ HomeScreen(home(), {}, {}) }) { LookContent(lookSheet(draft("mist", columns = 8)).copy(dock = rowsDock), {}, pictureFiles) }
        },
        Shot("home_look_sheet_waiting", 2000.dp) {
            SheetOver({ HomeScreen(home(), {}, {}) }) {
                LookContent(lookSheet(draft("console", columns = 4, dock = longDock)).copy(dock = longDock.take(12), waitingApps = longDock.drop(12)), {}, pictureFiles)
            }
        },
        // The preview of each 0.4.0 look (Horizon at night and at sunrise: its sky over the picture).
        Shot("look_preview_cinema", 360.dp) { PreviewShot(preview("cinema"), "Cinema") },
        Shot("look_preview_console", 360.dp) { PreviewShot(preview("console"), "Console") },
        Shot("look_preview_horizon", 360.dp) { PreviewShot(preview("horizon"), "Horizon") },
        Shot("look_preview_horizon_sunrise", 360.dp) { PreviewShot(preview("horizon", minute = 6 * 60 + 30), "Horizon") },
        Shot("look_preview_quiet", 360.dp) { PreviewShot(preview("quiet"), "Quiet black") },
        Shot("look_preview_diwali", 360.dp) { PreviewShot(preview("diwali"), "Diwali") },
        // "Your photo": the crop frame with the look's clock, buttons and dock over it, and the dim.
        Shot("home_photo_crop", 900.dp) {
            Column(Modifier.fillMaxSize().background(CytTheme.colors.bg).padding(20.dp)) {
                PhotoCropContent(pictureFiles.getValue(resourcePath(bundledLooks.getValue("mist").wallpaper)!!), preview("console").toEditor().toTvLook(null), 30, null, { _, _, _, _ -> }, {})
            }
        },
        Shot("look_preview_library", 360.dp) { PreviewShot(preview("library"), "Library") },
        Shot("look_preview_arcade", 360.dp) { PreviewShot(preview("arcade"), "Arcade") },
        Shot("look_preview_mist", 360.dp) { PreviewShot(preview("mist"), "Mist") },
        Shot("home_dock_sheet", 1000.dp) { SheetOver({ HomeScreen(home(), {}, {}) }) { DockContent(dockState(defaultDock.take(3)), apps, {}) } },
        Shot("home_dock_sheet_full", 1000.dp) { SheetOver({ HomeScreen(home(), {}, {}) }) { DockContent(dockState(defaultDock), apps, {}) } },
        Shot("home_dock_sheet_empty") { SheetOver({ HomeScreen(home(), {}, {}) }) { DockContent(dockState(emptyList()), apps, {}) } },
        Shot("home_dock_sheet_rows", 1200.dp) { SheetOver({ HomeScreen(home(dock = rowsDock), {}, {}) }) { DockContent(dockState(rowsDock), apps, {}) } },
        Shot("home_dock_sheet_new_row", 1200.dp) { SheetOver({ HomeScreen(home(), {}, {}) }) { DockContent(dockState(defaultDock, addRow = true), apps, {}) } },
        // 0.4.0: 15 saved at 4 in a row: the sheet holds 12, and one line says 3 apps wait.
        Shot("home_dock_sheet_waiting", 1500.dp) {
            SheetOver({ HomeScreen(home(dock = longDock.take(12)), {}, {}) }) { DockContent(dockState(longDock.take(12)), longDock, {}, waiting = 3) }
        },
        Shot("home_stock_preview") { SheetOver({ HomeScreen(home(), {}, {}) }) { StockPreviewContent(stockPlan) {} } },
        Shot("home_update_preview") { SheetOver({ HomeScreen(home(updateTo = "2.4"), {}, {}) }) { UpdatePreviewContent(updatePlan, "2.4") {} } },
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h2400dp-xhdpi")
class LauncherScreenshotTest(private val shot: Shot, private val variant: Variant) {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    @Test fun shot() = compose.shoot(shot, variant)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
        fun params(): List<Array<Any>> = LauncherShots.all.flatMap { s -> Variant.entries.map { v -> arrayOf<Any>(s, v) } }
    }
}
