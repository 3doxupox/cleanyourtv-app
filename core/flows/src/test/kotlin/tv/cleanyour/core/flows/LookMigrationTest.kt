package tv.cleanyour.core.flows

import java.io.File
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.spec.BundledThemeSource
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.Looks
import kotlin.test.Test

// The 0.4.0 bytes of every saved look, recorded from the 0.4.0 path (LookDocs.of and the codec)
// before look2 changed anything, into src/test/resources/legacy-looks. A saved 0.4.0 choice must
// keep sending exactly these bytes until the person sends a new look.
class LookMigrationTest {
    @Test fun recordLegacyLooks() {
        val out = File("build/legacy-looks").apply { deleteRecursively(); mkdirs() }
        for (c in LegacyCases.all) {
            val (doc, _) = LookDocs.of(c.theme(), c.dock, cards = false, c.picture(), c.columns)
            File(out, c.file).writeBytes(LauncherConfigCodec.encode(doc))
        }
    }
}

// The legacy cases: the 11 ids, with apps in a row absent and each of 4 to 8 other than the look's
// own, with the picture absent, none, aurora, ruska and the person's photo at dim 30 (a dated look
// only with its own picture), each with a dock of 4 and of 15 apps.
internal object LegacyCases {
    val ids = listOf("default", "aurora", "six-columns", "cinema", "console", "horizon", "quiet", "diwali", "library", "arcade", "mist")
    val pictures = listOf<String?>(null, LookPicture.NONE_ID, "aurora", "ruska", LookPicture.MINE_ID)
    const val MINE_DIM = 30
    val mine = BlobRef(Sha256("1".repeat(64)), 1234, "/blobs/mine.jpg")

    val fifteen = listOf(
        "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar",
        "com.spotify.tv.android", "com.plexapp.android", "com.sonyliv", "com.graymatrix.did", "com.jio.media.ondemand",
        "com.example.tvbrowser", "org.videolan.vlc", "tv.twitch.android.app", "com.disney.disneyplus",
        "com.apple.atve.androidtv.appletv", "com.zattoo.player",
    ).map(::PackageName)

    private val source = BundledThemeSource()

    class Case(val id: String, val columns: Int?, val pictureId: String?, val dock: List<PackageName>) {
        val file: String get() = "$id.cols-${columns ?: "own"}.pic-${pictureId ?: "own"}.dock-${dock.size}.json"
        fun theme() = source.bundled().single { it.id == id }
        fun picture(): LookPicture? =
            if (pictureId == LookPicture.MINE_ID) LookPicture.Of(mine, MINE_DIM) else LookPicture.of(pictureId, source)
    }

    val all: List<Case> by lazy {
        ids.flatMap { id ->
            val own = DockLayers.columnsOf(source.bundled().single { it.id == id }.doc)
            val columns = listOf<Int?>(null) + (4..8).filter { it != own }
            val pics = if (Looks.dated(id)) listOf<String?>(null) else pictures
            columns.flatMap { c -> pics.flatMap { p -> listOf(fifteen.take(4), fifteen).map { d -> Case(id, c, p, d) } } }
        }
    }
}
