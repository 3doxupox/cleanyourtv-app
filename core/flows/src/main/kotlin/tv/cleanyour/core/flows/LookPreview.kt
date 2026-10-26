package tv.cleanyour.core.flows

import java.time.LocalDate
import java.time.format.DateTimeParseException
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.spec.DecodeResult
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc

// 0.4.0: the phone's picture of the TV's home screen for the Look sheet's choice, read back from
// the config.json bytes the push would send: encoded, then decoded and filled in with the
// launcher's own defaults as the TV reads it (LauncherConfigCodec.effective). So the preview never
// shows a key the TV would not get. `picturePath`: the wallpaper.jpg the push sends, as a file on
// this phone, when the TV shows one. `festive`: a festival is on for `today` (the diya, bell and
// marigold pills, the accent focus and the greeting). `dock`: the person's own app names, the dock
// and the rows under it in order. `minuteOfDay` is for the sky (sky_intensity above 0).
// `fillDock` (0.4.0, fill_dock): false, as every push writes it, leaves a dock space with no app empty
// on the TV, so the preview draws it as empty band.
data class LookPreview(
    val backgroundArgb: Int,
    val picturePath: String?,
    val dim: Int,
    val sky: Int,
    val columns: Int,
    val rowsBelowDock: Int,
    val dockRow: Boolean,
    val dockTitle: Boolean,
    val fillDock: Boolean,
    val cinematic: Boolean,
    val festive: Boolean,
    val accentArgb: Int,
    val greeting: String,
    val motion: LookMotion,
    val dock: List<String>,
    val minuteOfDay: Int,
) {
    companion object {
        fun of(bytes: ByteArray, wallpaper: BlobRef?, dock: List<String>, today: LocalDate, minuteOfDay: Int): LookPreview {
            val doc = (LauncherConfigCodec.decode(bytes) as? DecodeResult.Ok)?.doc ?: LauncherConfigDoc()
            val e = LauncherConfigCodec.effective(doc, LauncherConfigCodec.builtInDefaults)
            val festive = e.festival.isNotBlank() && (e.festivalUntil.isBlank() || lastDay(e.festivalUntil)?.let { !today.isAfter(it) } == true)
            val name = e.festival.trim().lowercase()
            return LookPreview(
                backgroundArgb = e.backgroundColorArgb,
                picturePath = wallpaper?.path?.takeIf { e.wallpaper && it.startsWith("/") },
                dim = e.wallpaperDim, sky = e.skyIntensity,
                columns = e.columns, rowsBelowDock = e.rowsBelowDock, dockRow = e.dockRow, dockTitle = e.dockTitle,
                fillDock = e.fillDock, cinematic = e.cinematicHome, festive = festive,
                accentArgb = if (festive) (e.accentColorArgb.takeIf { it != 0 } ?: DIYA_GOLD) else 0,
                greeting = if (!festive) "" else e.greeting.ifBlank { BUILT_IN_GREETINGS[name].orEmpty() },
                motion = when {
                    !e.animations -> LookMotion.OFF
                    e.reducedMotion -> LookMotion.CALM
                    e.advancedEffects -> LookMotion.CRISP
                    else -> LookMotion.BOUNCY
                },
                dock = dock, minuteOfDay = minuteOfDay,
            )
        }

        private fun lastDay(s: String): LocalDate? = try { LocalDate.parse(s.trim()) } catch (_: DateTimeParseException) { null }

        // The launcher's own gold for a festival without accent_color, and the greetings it
        // carries for the festivals it knows.
        const val DIYA_GOLD = 0xFFFFB23C.toInt()
        private val BUILT_IN_GREETINGS = mapOf("diwali" to "Happy Diwali", "dussehra" to "Happy Dussehra")
    }
}

// How the TV moves: OFF (animations false), CALM (reduced motion), CRISP (advanced effects: lift,
// zoom and a stiffer spring) or BOUNCY (the launcher's default spring).
enum class LookMotion { OFF, CALM, CRISP, BOUNCY }
