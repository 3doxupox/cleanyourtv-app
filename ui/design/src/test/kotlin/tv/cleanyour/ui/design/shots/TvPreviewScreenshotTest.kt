package tv.cleanyour.ui.design.shots

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.LookPreview
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.BandChoice
import tv.cleanyour.core.spec.ButtonsChoice
import kotlinx.serialization.json.JsonPrimitive
import tv.cleanyour.core.spec.GlowChoice
import tv.cleanyour.core.spec.HighlightChoice
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.LookExtras
import tv.cleanyour.core.spec.TilesChoice
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LookKeys
import tv.cleanyour.core.spec.LookSpec
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.PictureKeys
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.ui.design.component.TvPreview
import tv.cleanyour.ui.design.component.toTvLook
import tv.cleanyour.ui.design.theme.CytTheme

// The TV drawing at the TV's own size (960 x 540 dp, one pixel a dp), for comparing by eye with
// the 2.7 emulator captures (proto/cap, proto/tv): each style with Mist and with no picture, each
// detail on Classic with Mist, names, 4 to 8 apps in a row, the sky at 20:30 and the photo crop.
// The previews are made the way the flows make them: LookKeys.build, the codec's bytes, then
// LookPreview.of and toTvLook. The clock is the captures' (Monday 26 October, 8:30 PM) and the dock
// the emulator's four stand-in apps, with no Inputs unless a case says so, like the emulator.
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h600dp-mdpi")
class TvPreviewScreenshotTest(private val case: TvCase) {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shot() {
        val picture = TvShots.image(case.picture)
        val look = TvShots.preview(case).toTvLook(if (case.crop) null else picture, crop = case.crop)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (case.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                CytTheme(darkTheme = true) {
                    Box(Modifier.testTag("tv").size(960.dp, 540.dp)) {
                        // The crop draws over the photo frame: the photo under it, as PhotoCrop shows it.
                        if (case.crop && picture != null) Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        TvPreview(look, "TV")
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tv").captureRoboImage("src/test/screenshots/tvpreview_${case.name}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TvShots.cases.map { arrayOf<Any>(it) }
    }
}

// A picture as the push writes it, and its file among the :core:spec resources.
class TvPicture(val id: String, val keys: PictureKeys, val resource: String?)

class TvCase(
    val name: String,
    val spec: LookSpec,
    val picture: TvPicture = TvShots.mist,
    val columns: Int = 5,
    val dock: List<AppChoice> = TvShots.dock4,
    val minute: Int = 20 * 60 + 30,
    val inputs: Boolean = false,
    val crop: Boolean = false,
    val rtl: Boolean = false,
    val extras: LookExtras = LookExtras.DEFAULT,
) {
    override fun toString() = name
}

object TvShots {
    private fun app(pkg: String, name: String) = AppChoice(PackageName(pkg), name)
    val dock4 = listOf(
        app("com.netflix.ninja", "Netflix"), app("com.google.android.youtube.tv", "YouTube"),
        app("com.amazon.amazonvideo.livingroom", "Prime Video"), app("in.startv.hotstar", "JioHotstar"),
    )
    // The emulator's other apps, in the order its grid shows them.
    val others = listOf(
        app("com.jio.media.ondemand", "JioCinema"), app("com.plexapp.android", "Plex"), app("com.sonyliv", "SonyLIV"),
        app("com.spotify.tv.android", "Spotify"), app("com.android.traceur", "System Tracing"), app("com.example.tvbrowser", "TV Browser"),
        app("com.google.android.videos", "Videos by Google"), app("com.graymatrix.did", "ZEE5"),
    )
    val dock8 = dock4 + others.take(4)
    val dock15 = dock4 + others + listOf(
        app("org.videolan.vlc", "VLC"), app("org.xbmc.kodi", "Kodi"), app("tv.twitch.android.app", "Twitch"),
    )
    private const val LOOKS = "/tv/cleanyour/core/spec/looks"
    val mist = TvPicture("mist", PictureKeys(true, "#0E1022", 0, 0), "$LOOKS/mist/wallpaper.jpg")
    val none = TvPicture("none", PictureKeys(false, "#000000", 0, 0), null)
    val mountains = TvPicture("mountains", PictureKeys(true, "#000000", 0, LookKeys.SKY_STRENGTH_DEFAULT), "$LOOKS/horizon/wallpaper.jpg")
    val diwali = TvPicture("diwali", PictureKeys(true, "#120809", 0, 0, "diwali", "2026-11-10", "#FFB23C"), "$LOOKS/diwali/wallpaper.jpg")
    val cobalt = TvPicture("cobalt", PictureKeys(true, "#020611", 0, 0), "$LOOKS/console/wallpaper.jpg")

    private fun classic(f: (LookSpec) -> LookSpec) = f(LookKeys.spec(StyleId.CLASSIC))
    private val spot = LookKeys.spec(StyleId.SPOTLIGHT)

    val cases: List<TvCase> = buildList {
        for (style in StyleId.entries) for (p in listOf(mist, none)) add(TvCase("style-${style.id}-${p.id}", LookKeys.spec(style), p))
        add(TvCase("d-bigname-off", classic { it.copy(bigName = false) }))
        add(TvCase("d-glow-top", classic { it.copy(glow = GlowChoice.FROM_TOP) }))
        add(TvCase("d-glow-none", classic { it.copy(glow = GlowChoice.NONE) }))
        add(TvCase("d-ring-on", classic { it.copy(ring = true) }))
        add(TvCase("d-band-see50", classic { it.copy(band = BandChoice.SEE_THROUGH, seeThrough = 50) }))
        add(TvCase("d-band-glass", classic { it.copy(band = BandChoice.GLASS) }))
        add(TvCase("d-buttons-round", classic { it.copy(buttons = ButtonsChoice.ROUND) }))
        add(TvCase("d-buttons-menu", classic { it.copy(buttons = ButtonsChoice.MENU) }))
        add(TvCase("d-wifi-green", classic { it.copy(greenWifi = true) }))
        add(TvCase("d-dim-40", LookKeys.spec(StyleId.CLASSIC), TvPicture("mist40", mist.keys.copy(dim = 40), mist.resource)))
        add(TvCase("d-sky-on", LookKeys.spec(StyleId.CLASSIC), TvPicture("mistsky", mist.keys.copy(skyIntensity = LookKeys.SKY_STRENGTH_DEFAULT), mist.resource)))
        add(TvCase("d-spot-colour-off", spot.copy(spotColour = false)))
        add(TvCase("d-spot-ring-off", spot.copy(ring = false)))
        add(TvCase("adv-see70", classic { it.copy(band = BandChoice.SEE_THROUGH, seeThrough = 70) }))
        add(TvCase("adv-see30", classic { it.copy(band = BandChoice.SEE_THROUGH, seeThrough = 30) }))
        add(TvCase("adv-skystr-100", LookKeys.spec(StyleId.CLASSIC), TvPicture("mountains100", mountains.keys.copy(skyIntensity = 100), mountains.resource)))
        add(TvCase("adv-lift-on", classic { it.copy(lift = true) }))
        add(TvCase("movement-off", classic { it.copy(movement = Movement.OFF) }))
        add(TvCase("names-classic", classic { it.copy(names = true) }))
        add(TvCase("names-spotlight", spot.copy(names = true)))
        add(TvCase("names-allapps", LookKeys.spec(StyleId.ALL_APPS, names = true)))
        for (c in 4..8) add(TvCase("cols-$c-dock4", LookKeys.spec(StyleId.CLASSIC), columns = c))
        add(TvCase("cols-4-dock15", LookKeys.spec(StyleId.CLASSIC), columns = 4, dock = dock15))
        add(TvCase("cols-8-dock15", LookKeys.spec(StyleId.CLASSIC), columns = 8, dock = dock15))
        add(TvCase("cols-8-allapps", LookKeys.spec(StyleId.ALL_APPS), columns = 8))
        add(TvCase("cols-4-spotlight-dock15", spot, columns = 4, dock = dock15))
        add(TvCase("dock8-classic-mist", LookKeys.spec(StyleId.CLASSIC), dock = dock8))
        add(TvCase("inputs-labelled", LookKeys.spec(StyleId.CLASSIC), inputs = true))
        add(TvCase("inputs-round", classic { it.copy(buttons = ButtonsChoice.ROUND) }, inputs = true))
        add(TvCase("sky-mountains-2030", LookKeys.spec(StyleId.CALM, LookKeys.own(StyleId.CALM).copy(buttons = ButtonsChoice.WITH_NAMES)), mountains, columns = 6))
        add(TvCase("sky-mountains-0700", LookKeys.spec(StyleId.CALM), mountains, columns = 6, minute = 7 * 60))
        add(TvCase("diwali-classic", classic { it.copy(lift = true) }, diwali))
        add(TvCase("console-cobalt-7", classic { it.copy(lift = true) }, cobalt, columns = 7, dock = dock8))
        add(TvCase("crop-classic", LookKeys.spec(StyleId.CLASSIC), crop = true))
        add(TvCase("crop-spotlight", spot, crop = true))
        add(TvCase("rtl-glass-mist", LookKeys.spec(StyleId.GLASS), rtl = true))
        // Home screen 3.0 (0.4.5): each switch on Classic with Mist, from the bytes it writes.
        val d = LookExtras.DEFAULT
        add(TvCase("one_row", LookKeys.spec(StyleId.CLASSIC), dock = dock15, extras = d.copy(oneRow = true)))
        add(TvCase("one_row_spotlight", spot, dock = dock15, extras = d.copy(oneRow = true)))
        add(TvCase("no_band", LookKeys.spec(StyleId.CLASSIC), extras = d.copy(noBand = true)))
        add(TvCase("header_variants", LookKeys.spec(StyleId.CLASSIC), extras = d.copy(hideGreeting = true, dateBelow = true, brightDate = true)))
        add(TvCase("icon_tiles", LookKeys.spec(StyleId.CLASSIC), dock = dock15, extras = d.copy(tiles = TilesChoice.ICONS)))
        add(TvCase("rim", LookKeys.spec(StyleId.CLASSIC), extras = d.copy(highlight = HighlightChoice.RIM)))
        add(TvCase("halo", LookKeys.spec(StyleId.CLASSIC), extras = d.copy(highlight = HighlightChoice.HALO)))
    }

    private val today = LocalDate.of(2026, 10, 26)

    fun preview(case: TvCase): LookPreview {
        val bytes = LauncherConfigCodec.encode(with30(LookKeys.build(case.spec, case.picture.keys, case.columns, case.dock.map { it.pkg }, cards = true), case.extras, case.spec))
        return LookPreview.of(bytes, null, case.dock, others.filter { o -> case.dock.none { it.pkg == o.pkg } }, today, case.minute, case.inputs)
    }

    // Stand-in for WP5's LookKeys.build(extras) until up300-look-model lands: each 3.0 key written only
    // when it is not the launcher's default and where it takes effect (plan WP5 step 4). With one row
    // there are no rows under the dock, so rows_below_dock is 0 and the big name is the style's own
    // again (the launcher still sizes and lifts the row by rows_below_dock, MainActivity.java:609-630).
    private fun with30(doc: LauncherConfigDoc, e: LookExtras, spec: LookSpec): LauncherConfigDoc {
        val dockRow = doc.dockRow != false
        val keys = buildMap<String, JsonPrimitive> {
            if (e.oneRow && dockRow) put("single_row", JsonPrimitive(true))
            if (e.bandAll && e.oneRow && dockRow) put("dock_band_all", JsonPrimitive(true))
            if (e.noBand && dockRow) put("dock_band", JsonPrimitive(false))
            if (e.hideGreeting) put("show_greeting", JsonPrimitive(false))
            if (e.dateBelow) put("date_below_clock", JsonPrimitive(true))
            if (e.brightDate) put("header_white", JsonPrimitive(true))
            if (e.tiles == TilesChoice.ICONS) put("uniform_tiles", JsonPrimitive(true))
            if (e.highlight != HighlightChoice.GLASS) put("glass_style", JsonPrimitive(e.highlight.id))
        }
        if (keys.isEmpty()) return doc
        val rows = if (keys.containsKey("single_row")) doc.copy(rowsBelowDock = 0, dockTitle = spec.style == StyleId.SPOTLIGHT || spec.bigName) else doc
        return rows.copy(unknown = doc.unknown + keys)
    }

    private val images = HashMap<String, ImageBitmap>()

    fun image(p: TvPicture): ImageBitmap? = p.resource?.takeIf { p.keys.wallpaper }?.let { path ->
        images.getOrPut(path) {
            TvShots::class.java.getResourceAsStream(path)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })!!.asImageBitmap() }
        }
    }
}
