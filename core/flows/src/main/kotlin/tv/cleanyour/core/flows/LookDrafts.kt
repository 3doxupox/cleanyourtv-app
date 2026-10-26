package tv.cleanyour.core.flows

import java.time.Instant
import java.time.ZoneId
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.Looks
import tv.cleanyour.core.spec.PictureSource
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.core.spec.ThemeSource
import tv.cleanyour.core.store.Clock

// 0.4.0 (RFC-LOOK-SETUP-1): the Look choice as both the Look sheet (HomeScreenMachine) and Setup's
// look step (SetupMachine) make it, so the same choice gives the same draft, the same preview and
// the same bytes wherever it is made. A look tap resets apps in a row to that look's own; apps in a
// row is kept only when it differs from the look's own (HomeChoice.columns); a dated look (a
// festival) keeps its own picture.
internal class LookDrafts(
    private val themes: ThemeSource,
    private val pictures: PictureSource,
    private val encoder: ConfigEncoder,
    private val clock: Clock,
) {
    fun theme(id: String?): Theme? = id?.let { themes.bundled().firstOrNull { t -> t.id == it } }

    // The look's own apps in a row.
    fun ownColumns(themeId: String?): Int = DockLayers.columnsOf(theme(themeId)?.doc)

    // The apps in a row to keep and send for `columns`: null when it is the look's own or not offered.
    fun override(themeId: String?, columns: Int?): Int? = columns?.takeIf { it in OFFERED && it != ownColumns(themeId) }

    // The draft for a choice. `columns`: null for the look's own. `apps`: the whole dock as the TV
    // shows it, the waiting apps too (with more apps in a row they come into view). `mine` and `new`:
    // the person's kept photo and a photo just made, with their dims, for LookPicture.MINE_ID and NEW_ID.
    fun draft(themeId: String, picture: String?, columns: Int?, apps: List<AppChoice>, mine: LookPicture.Of?, new: LookPicture.Of?): LookDraft {
        val dated = Looks.dated(themeId)
        val p = picture.takeUnless { dated }
        val own = ownColumns(themeId)
        val count = columns ?: own
        return LookDraft(themeId, p, ownPictureOnly = dated, preview = preview(themeId, p, apps, count, mine, new), columns = count, lookColumns = own)
    }

    // The preview from the bytes the push would send for this choice (cards as they are do not change
    // the picture, so they are left at the default).
    private fun preview(themeId: String, picture: String?, apps: List<AppChoice>, columns: Int, mine: LookPicture.Of?, new: LookPicture.Of?): LookPreview? = runCatching {
        val p = when (picture) {
            LookPicture.MINE_ID -> mine
            LookPicture.NEW_ID -> new
            else -> LookPicture.of(picture, pictures)
        }
        val (doc, wallpaper) = Look.docOf(theme(themeId), apps.map { it.pkg }, cards = Cards.MANAGE_DEFAULT, p, columns)
        val now = Instant.ofEpochMilli(clock.nowMs()).atZone(ZoneId.systemDefault())
        LookPreview.of(encoder.encode(doc), wallpaper, apps.map { it.name }, now.toLocalDate(), now.hour * 60 + now.minute)
    }.getOrNull()

    // The gallery of the Picture row.
    fun pictureOptions(): List<PictureOption> =
        runCatching { pictures.pictures() }.getOrNull().orEmpty().map { PictureOption(it.id, it.name, it.wallpaper.path.takeIf { p -> p.startsWith("/") }) }

    // The looks on offer today (a dated look up to its last day), with their own pictures as files.
    fun offered(): List<ThemeOption> {
        val today = clock.today()
        return themes.bundled().filter { Looks.offered(it.id, today) }
            .map { ThemeOption(it.id, it.name, it.description, it.wallpaper?.path?.takeIf { p -> p.startsWith("/") }) }
    }

    companion object {
        val OFFERED = DockLayers.MIN_OFFERED_COLUMNS..DockLayers.MAX_OFFERED_COLUMNS
    }
}
