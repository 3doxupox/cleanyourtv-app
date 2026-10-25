package tv.cleanyour.core.spec

import java.time.LocalDate
import tv.cleanyour.core.model.BlobRef

// 0.4.0 looks: what the frozen Theme type cannot carry, by theme id. `until`: the last day a dated
// look (a festival) is offered, inclusive like festival_until; after it the look the person had
// before is theirs again (HomeChoice.previousThemeId). `sky`: the look draws the launcher's time of
// day sky over its picture (sky_intensity above 0). A theme without an entry is an undated look.
data class LookInfo(val id: String, val until: LocalDate? = null, val sky: Boolean = false)

object Looks {
    private val table = listOf(
        LookInfo("horizon", sky = true),
        LookInfo("diwali", until = LocalDate.of(2026, 11, 10)),
    ).associateBy { it.id }

    fun info(id: String?): LookInfo? = id?.let { table[it] }

    // A look with a last day.
    fun dated(id: String?): Boolean = info(id)?.until != null

    // Whether the look is offered on `today`: undated looks always are, a dated one up to its last day.
    fun offered(id: String?, today: LocalDate): Boolean = info(id)?.until?.let { !today.isAfter(it) } ?: true
}

// 0.4.0: a picture any undated look can show instead of its own (the Picture row): wallpaper.jpg
// for the launcher, a bundled asset like a theme's wallpaper.
data class Picture(val id: String, val name: String, val wallpaper: BlobRef)

interface PictureSource {
    fun pictures(): List<Picture>

    companion object {
        val NONE: PictureSource = object : PictureSource { override fun pictures(): List<Picture> = emptyList() }
    }
}
