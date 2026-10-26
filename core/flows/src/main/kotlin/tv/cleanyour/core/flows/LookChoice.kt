package tv.cleanyour.core.flows

import java.time.LocalDate
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.spec.BandChoice
import tv.cleanyour.core.spec.ButtonsChoice
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.GlowChoice
import tv.cleanyour.core.spec.LookGates
import tv.cleanyour.core.spec.LookKeys
import tv.cleanyour.core.spec.LookSpec
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.PictureKeys
import tv.cleanyour.core.spec.StyleId

// look2: the Look choice as the person makes it in the Look editor (spec 4 to 8). A style, a
// picture, apps in a row, names, movement and Lift and zoom, plus the detail changes of More
// options and Advanced. LookDrafts resolves it into launcher keys (LookKeys.build); a saved 0.4.0
// look keeps its own bytes until the next send (LegacyLook).

// The person's detail changes (More options, Advanced). null is the style's or the picture's own.
data class LookDetails(
    val bigName: Boolean? = null,
    val glow: GlowChoice? = null,
    val spotColour: Boolean? = null,
    val ring: Boolean? = null,
    val band: BandChoice? = null,
    val seeThrough: Int? = null,
    val buttons: ButtonsChoice? = null,
    val greenWifi: Boolean? = null,
    val dim: Int? = null,
    val sky: Boolean? = null,
    val skyStrength: Int? = null,
)

// What the look depends on about the TV.
data class LookTv(val versionCode: Long? = null, val ramMb: Int? = null, val hasInputs: Boolean = true)

// Why a row of More options or Advanced is disabled (the UI turns it into the spec 7.3 words).
enum class LookReason {
    NEEDS_UPDATE, ALL_APPS_NO_BIG_NAME, SPOTLIGHT_BIG_NAME, THREE_ROWS, ALL_APPS_NO_BAND, SPOTLIGHT_BAND,
    MENU_NO_WIFI, NO_PICTURE, LIFT_MOVEMENT, SEE_THROUGH_OFF, SKY_OFF,
}

// One row: the value the TV will really use (shown checked even when disabled; null when nothing
// is checked, like All apps' band), whether it can be changed, why not, and whether it shows at all.
data class LookRow<T>(val value: T?, val enabled: Boolean, val reason: LookReason? = null, val visible: Boolean = true)

data class LookRows(
    val names: LookRow<Boolean>,
    val bigName: LookRow<Boolean>,
    val glow: LookRow<GlowChoice>,          // visible false in Spotlight
    val spotColour: LookRow<Boolean>,       // visible only in Spotlight
    val ring: LookRow<Boolean>,
    val band: LookRow<BandChoice>,
    val buttons: LookRow<ButtonsChoice>,
    val greenWifi: LookRow<Boolean>,
    val dim: LookRow<Int>,
    val sky: LookRow<Boolean>,
    val lift: LookRow<Boolean>,             // visible per LookGates.liftShown
    val seeThrough: LookRow<Int>,
    val skyStrength: LookRow<Int>,
    val resettable: Int,                    // rows "Use {style}'s own settings" puts back; 0 disables it
    val festiveRing: Boolean,               // Diwali runs: "While Diwali runs, the highlight is gold instead."
    val liftSlowRamMb: Int?,                // Lift and zoom is on below 2 GB: the memory for its warning line
)

enum class WayBack { NONE, UNDO, EARLIER }

enum class LookNoteKind { STYLE_CHANGED, UNDONE, RESET, EARLIER_CHOSEN }
enum class DetailWord { BUTTONS, GLOW, BIG_NAME, RING, DOCK_BAND, WIFI_COLOUR, SEE_THROUGH }

// A snackbar the editor shows once (W10, Undo changes, Use own settings, the earlier look).
// `action`: Keep mine, Bring them back or Undo is offered (LookEdit.NoteAction).
data class LookNote(
    val id: Long,
    val kind: LookNoteKind,
    val style: StyleId? = null,
    val own: List<DetailWord> = emptyList(),
    val dropped: List<DetailWord> = emptyList(),
    val action: Boolean = false,
)

data class StyleCard(val style: StyleId, val preview: LookPreview?, val enabled: Boolean)

// Every tap of the Look editor. Both machines take the same edits (HomeScreenIntent.Look,
// SetupIntent.Look) and hand them to LookDrafts, so the two places behave the same. None sends.
sealed interface LookEdit {
    data class SelectStyle(val style: StyleId) : LookEdit
    data class SelectPicture(val id: String) : LookEdit
    data class SelectColumns(val count: Int) : LookEdit
    data class SetNames(val on: Boolean) : LookEdit
    data class SetMovement(val movement: Movement) : LookEdit
    data class SetLift(val on: Boolean) : LookEdit
    data class SetBigName(val on: Boolean) : LookEdit
    data class SetGlow(val glow: GlowChoice) : LookEdit
    data class SetSpotColour(val on: Boolean) : LookEdit
    data class SetRing(val on: Boolean) : LookEdit
    data class SetBand(val band: BandChoice) : LookEdit
    data class SetSeeThrough(val percent: Int) : LookEdit
    data class SetButtons(val buttons: ButtonsChoice) : LookEdit
    data class SetGreenWifi(val on: Boolean) : LookEdit
    data class SetDim(val percent: Int) : LookEdit
    data class SetSky(val on: Boolean) : LookEdit
    data class SetSkyStrength(val percent: Int) : LookEdit
    data object TurnGlowOff : LookEdit
    data object ResetDetails : LookEdit
    data object UndoChanges : LookEdit
    data object PutBackEarlier : LookEdit
    data class NoteAction(val id: Long) : LookEdit
    data class NoteSeen(val id: Long) : LookEdit
    data class SetKeyNames(val on: Boolean) : LookEdit
}

// The Look editor's choice while it is open (the Look sheet and Setup's look step). Nothing reaches
// the TV until "Put it on the TV" (or Setup's run). LookDrafts makes and changes it; it is never
// changed anywhere else.
// 0.4.0 fields, kept for ui until it moves (removed after look2 merges, except picture, preview,
// columns): `themeId` is the legacy look id while the draft is an untouched migrated look, else
// "default"; `ownPictureOnly` is always false in look2 (Diwali is a picture); `lookColumns` is the
// legacy look's own apps in a row while untouched, else `columns`.
// look2: `picture` is LookPicture.NONE_ID, MINE_ID, NEW_ID, EARLIER_ID or a gallery id, never null.
// `movementPicked`: Quick was picked for this TV's memory ("Quick movement, picked for this TV").
// `legacyThemeId`: an untouched migrated look; any edit clears it. `styleCards`: the five, in
// StyleId order, this TV with this picture. `changed`: differs from what the phone last sent (Undo
// changes, the leave question). `newPhoto`: the picture is a photo made in this sheet, or the
// earlier look's photo, and not on the TV yet. `styles`: offered on this TV (LookGates.styles).
// `reads27` false: the older home screen banner, 2.7 rows disabled. `keyNames`: Advanced's Show
// launcher names (a phone setting). `settingsFile`: the look keys the push writes (no dock_apps, no
// module_cards); `fullFile`: the whole config.json, for Copy. `apps`: the dock apps as the TV shows
// them plus the waiting ones; `waiting`: of them, how many wait at `columns`. `tvChanged`: the drift
// check saw changes made on the TV.
data class LookDraft(
    val themeId: String,
    val picture: String?,
    val ownPictureOnly: Boolean = false,
    val preview: LookPreview? = null,
    val columns: Int = DockLayers.DEFAULT_COLUMNS,
    val lookColumns: Int = DockLayers.DEFAULT_COLUMNS,
    val style: StyleId = StyleId.CLASSIC,
    val names: Boolean = false,
    val movement: Movement = Movement.LIVELY,
    val movementPicked: Boolean = false,
    val lift: Boolean = false,
    val details: LookDetails = LookDetails(),
    val legacyThemeId: String? = null,
    val styleCards: List<StyleCard> = emptyList(),
    val rows: LookRows? = null,
    val changed: Boolean = false,
    val newPhoto: Boolean = false,
    val wayBack: WayBack = WayBack.NONE,
    val note: LookNote? = null,
    val styles: List<StyleId> = StyleId.entries,
    val reads27: Boolean = true,
    val ramMb: Int? = null,
    val keyNames: Boolean = false,
    val settingsFile: String = "",
    val fullFile: String = "",
    val apps: Int = 0,
    val waiting: Int = 0,
    val tvChanged: Boolean = false,
)

// The parts of a choice the rules read, from a draft or a saved choice: every value concrete.
// `picture` is a picture id as LookDraft.picture holds it.
internal data class LookParts(
    val style: StyleId,
    val picture: String,
    val columns: Int,
    val names: Boolean = false,
    val movement: Movement = Movement.LIVELY,
    val lift: Boolean = false,
    val details: LookDetails = LookDetails(),
    val movementPicked: Boolean = false,
    val legacyThemeId: String? = null,
)

// A picture as the rules and a push see it: its file (null: no picture), its background colour,
// its own dim (a photo's crop value, 0 for a gallery picture), its own sky strength (0: no sky) and
// the festival keys a dated picture carries.
internal data class LookPictureInfo(
    val id: String,
    val wallpaper: BlobRef?,
    val backgroundArgb: Int = BLACK,
    val ownDim: Int = 0,
    val sky: Int = 0,
    val festival: String? = null,
    val until: LocalDate? = null,
    val accentColor: String? = null,
) {
    val hasPicture: Boolean get() = wallpaper != null

    companion object {
        const val BLACK = 0xFF000000.toInt()
        val NONE = LookPictureInfo(LookPicture.NONE_ID, null)
    }
}

// The rules of spec 7.3 and 8.1 as the prototype has them (rowsOf, keysOf, autoPick), pure.
internal object LookRules {
    val SKY_STRENGTH = 20..100
    val DIM = 0..90
    val OFFERED_COLUMNS = DockLayers.MIN_OFFERED_COLUMNS..DockLayers.MAX_OFFERED_COLUMNS

    // The detail rows a style owns (a style change resets them) and the ones a picture owns.
    fun withoutStyleDetails(d: LookDetails) = d.copy(bigName = null, glow = null, spotColour = null, ring = null, band = null,
        seeThrough = null, buttons = null, greenWifi = null)
    fun withoutPictureDetails(d: LookDetails) = d.copy(dim = null, sky = null, skyStrength = null)

    // How many rows under the dock hold an app, as DockLayers writes them (0 on Spotlight's shelf).
    fun rowsBelow(style: StyleId, columns: Int, apps: Int): Int =
        if (style == StyleId.SPOTLIGHT) 0 else DockLayers.rowsBelowDock(apps, columns)

    // The dock fills three rows, so the big name is off (DockLayers writes dock_title false).
    fun threeRows(style: StyleId, columns: Int, apps: Int): Boolean =
        style != StyleId.ALL_APPS && apps > 0 && rowsBelow(style, columns, apps) == DockLayers.MAX_ROWS - 1

    fun rows(p: LookParts, pic: LookPictureInfo, apps: Int, tv: LookTv): LookRows {
        val style = p.style
        val own = LookKeys.own(style)
        val d = p.details
        val older = !LookGates.reads27(tv.versionCode)
        val need = if (older) LookReason.NEEDS_UPDATE else null
        val spot = style == StyleId.SPOTLIGHT
        val all = style == StyleId.ALL_APPS
        fun <T> row(set: T?, own: T, enabled: Boolean, reason: LookReason?): LookRow<T> =
            if (enabled) LookRow(set ?: own, true) else LookRow(own, false, reason)

        val names = LookRow(p.names && !older, !older, need)
        val bigName = when {
            all -> LookRow(false, false, LookReason.ALL_APPS_NO_BIG_NAME)
            spot -> LookRow(true, false, LookReason.SPOTLIGHT_BIG_NAME)
            threeRows(style, p.columns, apps) -> LookRow(false, false, LookReason.THREE_ROWS)
            else -> row(d.bigName, own.bigName, true, null)
        }
        val glow = if (spot) LookRow<GlowChoice>(null, false, null, visible = false) else row(d.glow, own.glow, !older, need)
        val spotColour = if (spot) row(d.spotColour, own.spotColour, !older, need) else LookRow(false, false, null, visible = false)
        val ring = row(d.ring, own.ring, !older, need)
        val band = when {
            all -> LookRow<BandChoice>(null, false, LookReason.ALL_APPS_NO_BAND)
            spot -> LookRow(BandChoice.SEE_THROUGH, false, LookReason.SPOTLIGHT_BAND)
            else -> row(d.band, own.band, !older, need)
        }
        val buttons = row(d.buttons, own.buttons, !older, need)
        val greenWifi = if (buttons.value == ButtonsChoice.MENU) LookRow(own.greenWifi, false, LookReason.MENU_NO_WIFI)
            else row(d.greenWifi, own.greenWifi, !older, need)
        val dim = row(d.dim, pic.ownDim, pic.hasPicture, LookReason.NO_PICTURE)
        val sky = row(d.sky, pic.sky > 0, true, null)
        val seeThrough = row(d.seeThrough, LookKeys.SEE_THROUGH_DEFAULT, band.enabled && band.value == BandChoice.SEE_THROUGH, LookReason.SEE_THROUGH_OFF)
        val skyStrength = row(d.skyStrength, pic.sky.takeIf { it > 0 } ?: LookKeys.SKY_STRENGTH_DEFAULT, sky.value == true, LookReason.SKY_OFF)
        val liftOk = p.movement == Movement.LIVELY || p.movement == Movement.SMOOTH
        val lift = LookRow(p.lift && liftOk, liftOk, if (liftOk) null else LookReason.LIFT_MOVEMENT, LookGates.liftShown(tv.ramMb, p.lift))

        fun <T> changed(r: LookRow<T>, set: T?, own: T?) = if (r.enabled && set != null && set != own) 1 else 0
        val resettable = changed(bigName, d.bigName, own.bigName) + changed(glow, d.glow, own.glow) +
            changed(spotColour, d.spotColour, own.spotColour) + changed(ring, d.ring, own.ring) + changed(band, d.band, own.band) +
            changed(buttons, d.buttons, own.buttons) + changed(greenWifi, d.greenWifi, own.greenWifi) +
            changed(seeThrough, d.seeThrough, LookKeys.SEE_THROUGH_DEFAULT) + changed(dim, d.dim, pic.ownDim) +
            changed(sky, d.sky, pic.sky > 0) + changed(skyStrength, d.skyStrength, pic.sky.takeIf { it > 0 } ?: LookKeys.SKY_STRENGTH_DEFAULT) +
            (if (p.movement != LookGates.defaultMovement(tv.ramMb)) 1 else 0) + (if (p.lift) 1 else 0)

        return LookRows(
            names = names, bigName = bigName, glow = glow, spotColour = spotColour, ring = ring, band = band, buttons = buttons,
            greenWifi = greenWifi, dim = dim, sky = sky, lift = lift, seeThrough = seeThrough, skyStrength = skyStrength,
            resettable = resettable, festiveRing = pic.festival != null,
            liftSlowRamMb = tv.ramMb?.takeIf { p.lift && it < LookGates.LIFT_MIN_RAM_MB },
        )
    }

    // The resolved spec: what each row really gives (a disabled row its style's own).
    fun spec(p: LookParts, rows: LookRows): LookSpec {
        val own = LookKeys.own(p.style)
        return LookSpec(
            style = p.style, bigName = rows.bigName.value ?: own.bigName, glow = rows.glow.value ?: own.glow,
            spotColour = rows.spotColour.value ?: own.spotColour, ring = rows.ring.value ?: own.ring, band = rows.band.value ?: own.band,
            seeThrough = rows.seeThrough.value ?: LookKeys.SEE_THROUGH_DEFAULT, buttons = rows.buttons.value ?: own.buttons,
            greenWifi = rows.greenWifi.value ?: own.greenWifi, names = rows.names.value ?: false, movement = p.movement,
            lift = rows.lift.value ?: false,
        )
    }

    fun pictureKeys(pic: LookPictureInfo, rows: LookRows): PictureKeys = PictureKeys(
        wallpaper = pic.hasPicture,
        backgroundColor = hex(pic.backgroundArgb),
        dim = if (pic.hasPicture) (rows.dim.value ?: 0).coerceIn(DIM) else 0,
        skyIntensity = if (rows.sky.value == true) rows.skyStrength.value ?: LookKeys.SKY_STRENGTH_DEFAULT else 0,
        festival = pic.festival, festivalUntil = pic.festival?.let { pic.until?.toString() }, accentColor = pic.accentColor,
    )

    fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    // Spec 8.1: the auto pick fills only a Movement nobody chose (a migrated look counts as
    // unchosen): Quick, and Lift and zoom off, at 1.5 GB or less. `movementPicked` says it changed.
    fun autoPick(p: LookParts, ramMb: Int?): LookParts {
        if (ramMb == null || ramMb > LookGates.LOW_RAM_MB) return p.copy(movementPicked = false)
        return p.copy(movement = Movement.QUICK, lift = false, movementPicked = p.movement != Movement.QUICK || p.lift)
    }

    // The words of a style change note (the prototype's chooseStyle).
    fun word(field: String): DetailWord = when (field) {
        "bigName" -> DetailWord.BIG_NAME
        "glow", "spotColour" -> DetailWord.GLOW
        "ring" -> DetailWord.RING
        "band" -> DetailWord.DOCK_BAND
        "buttons" -> DetailWord.BUTTONS
        "greenWifi" -> DetailWord.WIFI_COLOUR
        else -> DetailWord.SEE_THROUGH
    }
}

// Spec 8.2, the prototype's LEGACY table: today's 11 looks as (style, picture, apps in a row,
// movement, details), so the TV would look the same after the next send.
internal object LegacyLooks {
    data class Entry(val style: StyleId, val picture: String, val columns: Int, val movement: Movement = Movement.LIVELY,
        val lift: Boolean = false, val details: LookDetails = LookDetails())

    val table: Map<String, Entry> = linkedMapOf(
        "default" to Entry(StyleId.CLASSIC, LookPicture.NONE_ID, 4),
        "aurora" to Entry(StyleId.CLASSIC, "aurora", 4),
        "six-columns" to Entry(StyleId.CLASSIC, LookPicture.NONE_ID, 6),
        // Cinema keeps App colour across the top off, so it looks as it did.
        "cinema" to Entry(StyleId.SPOTLIGHT, "curtains", 5, lift = true, details = LookDetails(spotColour = false)),
        "console" to Entry(StyleId.CLASSIC, "cobalt", 7, lift = true),
        "horizon" to Entry(StyleId.CALM, "mountains", 6, details = LookDetails(buttons = ButtonsChoice.WITH_NAMES)),
        "quiet" to Entry(StyleId.CALM, LookPicture.NONE_ID, 6, Movement.QUICK, details = LookDetails(buttons = ButtonsChoice.WITH_NAMES)),
        "diwali" to Entry(StyleId.CLASSIC, "diwali", 5, lift = true),
        "library" to Entry(StyleId.ALL_APPS, "lamp", 5, Movement.QUICK, details = LookDetails(ring = false)),
        "arcade" to Entry(StyleId.CLASSIC, "neon", 6),
        "mist" to Entry(StyleId.CLASSIC, "mist", 5, lift = true),
    )

    fun of(id: String?): Entry = table[id] ?: table.getValue("default")
}
