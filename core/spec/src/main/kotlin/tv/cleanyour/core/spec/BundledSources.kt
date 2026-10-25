package tv.cleanyour.core.spec

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Sha256
import java.security.MessageDigest

private fun resourceBytes(path: String): ByteArray? = BundledProfileSource::class.java.getResourceAsStream(path)?.use { it.readBytes() }

// Profiles shipped inside the APK (D28), converted at build time from the pinned upstream
// examples. resolve(): an exact profile by model or device (with brand, SDK range and
// fingerprint prefix when the profile sets them; the highest score, then the longest fingerprint
// prefix), else the brand sure set (D26), else None with the generic stock launcher allowlist.
//
// Test build: a candidate list (Profile.isCandidate, only in a build with -Pcyt.candidates=true) is
// the exact profile only when no tested list matches and the person chose to try it on this phone
// (`consent`); otherwise the TV resolves as it would without it, to the brand sure set or None.
class BundledProfileSource(private val bundle: ProfileBundle = bundled, private val consent: CandidateConsent? = null) : ProfileSource {
    override suspend fun all(): List<Profile> = bundle.profiles

    override suspend fun resolve(facts: DeviceFacts): ProfileResolution {
        val candidate = candidateFor(facts)
        return resolveNow(facts, tryCandidate = candidate != null && consent?.tries(candidate.id) == true)
    }

    fun sureSets(): List<SureSet> = bundle.sureSets

    // The candidate list this TV would get, or null: a tested list matches, or none of either does.
    fun candidateFor(facts: DeviceFacts): Profile? =
        if (best(facts, candidates = false) != null) null else best(facts, candidates = true)

    private fun best(facts: DeviceFacts, candidates: Boolean): Profile? =
        bundle.profiles.filter { it.isCandidate == candidates }.mapNotNull { p -> score(p, facts)?.let { it to p } }
            .sortedWith(compareByDescending<Pair<Int, Profile>> { it.first }.thenByDescending { specificity(it.second) }.thenBy { it.second.id })
            .firstOrNull()?.second

    fun resolveNow(facts: DeviceFacts, tryCandidate: Boolean = false): ProfileResolution {
        val exact = best(facts, candidates = false) ?: if (tryCandidate) best(facts, candidates = true) else null
        if (exact != null) return ProfileResolution.Exact(exact)
        val sure = bundle.sureSets.firstOrNull { it.brand.equals(facts.brand, ignoreCase = true) || it.brand.equals(facts.manufacturer, ignoreCase = true) }
        if (sure != null) return ProfileResolution.BrandSureSet(sure.brand, sure.groups, sure.stockLaunchers)
        return ProfileResolution.None(KnownLaunchers.generic)
    }

    companion object {
        val bundled: ProfileBundle by lazy {
            val bytes = resourceBytes(ProfileBundleJson.RESOURCE) ?: error("profiles.json missing from the :core:spec resources")
            ProfileBundleJson.decode(bytes.decodeToString())
        }

        // RF-PROFILES-63 ruling 1: of two profiles with the same score, the one whose fingerprint
        // prefix is longer names the firmware more exactly and wins (none counts as 0); only a pair
        // that is still level falls back to the id, and ProfilesTest pins every such pair.
        fun specificity(p: Profile): Int = p.match.fingerprintPrefix?.length ?: 0

        // 3 for model and device, 2 for the model, 1 for the device; null when it does not match.
        fun score(p: Profile, f: DeviceFacts): Int? {
            val m = p.match
            val model = m.models.any { it.equals(f.model, ignoreCase = true) }
            val device = m.devices.any { it.equals(f.device, ignoreCase = true) }
            if (!model && !device) return null
            val brand = m.brand
            if (brand != null && !brand.equals(f.brand, ignoreCase = true) && !brand.equals(f.manufacturer, ignoreCase = true)) return null
            val min = m.sdkMin
            val max = m.sdkMax
            val prefix = m.fingerprintPrefix
            if (min != null && f.sdk < min) return null
            if (max != null && f.sdk > max) return null
            if (prefix != null && !f.fingerprint.startsWith(prefix)) return null
            return (if (model) 2 else 0) + (if (device) 1 else 0)
        }
    }
}

// Test build: whether the person chose to try a candidate list (by its profile id) on this phone.
// The app keeps the answer (CandidateChoices); the resolver, the engine's live check, Manage and the
// TV report all ask the same source, so they agree on the list.
fun interface CandidateConsent { suspend fun tries(profileId: String): Boolean }

// Friendly names and one-line descriptions (English) for packages in the profiles and the dock
// table, from facts.tsv: package, name, description, category.
class BundledFactsTable(private val facts: Map<PackageName, PackageFacts> = bundled) : FactsTable {
    override fun lookup(pkg: PackageName): PackageFacts? = facts[pkg]

    val size: Int get() = facts.size

    companion object {
        const val RESOURCE = "/tv/cleanyour/core/spec/facts.tsv"

        fun parse(text: String): Map<PackageName, PackageFacts> {
            val out = LinkedHashMap<PackageName, PackageFacts>()
            text.lineSequence().forEachIndexed { i, line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
                val f = line.split('\t')
                require(f.size == 4) { "facts.tsv:${i + 1}: expected 4 fields, got ${f.size}" }
                val pkg = PackageName(f[0])
                require(out.put(pkg, PackageFacts(f[1], f[2], f[3])) == null) { "facts.tsv:${i + 1}: ${f[0]} listed twice" }
            }
            return out
        }

        val bundled: Map<PackageName, PackageFacts> by lazy {
            parse(resourceBytes(RESOURCE)?.decodeToString() ?: error("facts.tsv missing from the :core:spec resources"))
        }
    }
}

// The three upstream themes, copied from the pinned submodule into the :core:spec resources at
// build time, then the app's own looks (0.4.0), kept in this module's resources under looks/. Each
// look's config.json sets every layout and motion key outright, so a new look never keeps one from
// the last. A wallpaper is a bundled asset: BlobRef.path is "bundled:" plus the resource path, and
// bytes(blob) reads it back. Dated looks are in the list after their last day too (a saved choice
// still names them); the flows offer only what Looks.offered allows.
// The pictures (PictureSource) are Aurora's, from the submodule, and Glass and Ruska, from later
// upstream releases (MIT), kept in this module's resources under pictures/.
class BundledThemeSource : ThemeSource, PictureSource {
    override fun bundled(): List<Theme> = themes

    override fun pictures(): List<Picture> = gallery

    private class Entry(val id: String, val name: String, val description: String, val dir: String)

    companion object {
        const val PREFIX = "bundled:"
        private const val THEMES = "/tv/cleanyour/core/spec/themes"
        private const val LOOKS = "/tv/cleanyour/core/spec/looks"
        private const val PICTURES = "/tv/cleanyour/core/spec/pictures"
        private val catalogue = listOf(
            Entry("default", "Default", "Black, four tiles a row, the dock at the very bottom.", THEMES),
            Entry("aurora", "Aurora", "A dark aurora picture on navy behind the apps.", THEMES),
            Entry("six-columns", "Six columns", "Six smaller tiles a row, one row showing under the dock.", THEMES),
            Entry("cinema", "Cinema", "The focused app's art across the top, velvet curtains at the sides.", LOOKS),
            Entry("console", "Console", "Seven small tiles, a big name and deep cobalt light.", LOOKS),
            Entry("horizon", "Horizon", "Mountain ridges under a sky that follows the time of day.", LOOKS),
            Entry("quiet", "Quiet black", "Pure black and nothing else, calm motion. Good for OLED and slow TVs.", LOOKS),
            Entry("diwali", "Diwali", "Lamps, a gold rangoli and festive icons, until 10 November.", LOOKS),
            Entry("library", "Library", "Every app on one calm page, no dock band, warm lamp light.", LOOKS),
            Entry("arcade", "Arcade", "A neon sun over a glowing grid, and tiles that bounce.", LOOKS),
            Entry("mist", "Mist", "Soft out of focus light in indigo, teal and peach.", LOOKS),
        )

        val themes: List<Theme> by lazy {
            catalogue.map { e ->
                val id = e.id
                val config = resourceBytes("${e.dir}/$id/config.json") ?: error("theme $id missing from the :core:spec resources")
                val doc = when (val r = LauncherConfigCodec.decode(config)) {
                    is DecodeResult.Ok -> r.doc
                    is DecodeResult.Rejected -> error("bundled theme $id does not decode: ${r.reason}")
                }
                val wallpaperPath = "${e.dir}/$id/wallpaper.jpg"
                val wallpaper = resourceBytes(wallpaperPath)?.let { BlobRef(sha256(it), it.size.toLong(), PREFIX + wallpaperPath.removePrefix("/")) }
                Theme(id, e.name, e.description, doc, wallpaper)
            }
        }

        val gallery: List<Picture> by lazy {
            listOf(
                Triple("aurora", "Aurora", "$THEMES/aurora/wallpaper.jpg"),
                Triple("glass", "Glass", "$PICTURES/glass.jpg"),
                Triple("ruska", "Ruska", "$PICTURES/ruska.jpg"),
            ).map { (id, name, path) ->
                val b = resourceBytes(path) ?: error("picture $id missing from the :core:spec resources")
                Picture(id, name, BlobRef(sha256(b), b.size.toLong(), PREFIX + path.removePrefix("/")))
            }
        }

        // Bytes of a bundled theme asset, or null when the ref is not one of ours.
        fun bytes(blob: BlobRef): ByteArray? {
            if (!blob.path.startsWith(PREFIX)) return null
            val b = resourceBytes("/" + blob.path.removePrefix(PREFIX)) ?: return null
            return b.takeIf { sha256(it) == blob.sha256 }
        }

        private fun sha256(b: ByteArray) = Sha256(MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
}
