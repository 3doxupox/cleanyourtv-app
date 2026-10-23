package tv.cleanyour.core.flows

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.store.DocumentStore

// 0.3.2 B1 and B2 (ISSUES-032-PLAN): the plain text reports a person can post on GitHub or send
// themselves, built from what this phone already stored (the probe snapshot), so no TV connection is
// needed. The app itself sends nothing: the preview screen copies or shares the text, and opens the
// issue form in the browser only when the person taps it.
//
// What a report may hold: the app version and build, the phone model and Android version, the TV's
// brand, maker, model, device codename, SDK, Android release and build fingerprint, how the profile
// resolved, the home screen switch decision, the HOME resolver and claimants, and the system
// packages with their state. Everything else the app knows is left out on purpose: the TV's serial
// (DeviceFacts.serialLast4), its id (TvId is derived from the serial), its name and room, every
// address, the saved Wi-Fi, the settings the probe read (device_name is the person's own words),
// the input names, the key and every code. Apps the person installed are only counted: their names
// say something about the person, the factory's system apps do not. A package whose system flag the
// probe could not read counts as the person's (the safe side).
//
// The text is English on purpose: it is for the maintainers, and a fixed shape keeps it easy to
// read and to compare. Sections and lines keep a fixed order (packages by name, claimants by
// priority), so the same TV gives the same report.

// The app and the phone the report comes from; the app fills it from its versionName, its store
// flavour and android.os.Build.
data class AppAbout(val version: String, val flavour: String, val phone: String, val phoneAndroid: String) {
    val appLine: String get() = "$version ($flavour)"
    val phoneLine: String get() = listOf(phone, phoneAndroid).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "unknown" }
}

// Why S3 did or did not plan the new home screen, as the wizard decided it (SetupMachine.homeBlock and
// the plan). Kept beside the TV record, so Manage's report knows it without the TV.
data class HomeDecision(val planned: Boolean, val block: String? = null, val alreadyOn: Boolean = false) {
    val line: String get() = when {
        planned -> "home switch planned"
        block != null -> "home switch blocked $block"
        alreadyOn -> "home switch not needed (the new home screen is on)"
        else -> "home switch not in the plan"
    }

    companion object {
        fun of(block: HomeBlock, planHasSwitch: Boolean, alreadyOn: Boolean): HomeDecision = when {
            block != HomeBlock.NONE -> HomeDecision(false, block.name)
            planHasSwitch -> HomeDecision(true)
            else -> HomeDecision(false, alreadyOn = alreadyOn)
        }
    }
}

// tvs/<id>/decision.txt, one line ("home=planned", "home=blocked:WIZARD_STAYS_ON", "home=on" or
// "home=none"). Forget this TV and Delete everything remove it with the TV's folder.
class HomeDecisions(private val store: DocumentStore) {
    private fun path(id: TvId) = "tvs/${id.value}/decision.txt"

    suspend fun save(id: TvId, d: HomeDecision) {
        val v = when {
            d.planned -> "planned"
            d.block != null -> "blocked:${d.block}"
            d.alreadyOn -> "on"
            else -> "none"
        }
        store.write(path(id), "home=$v\n".encodeToByteArray())
    }

    suspend fun load(id: TvId): HomeDecision? = store.read(path(id)) { bytes ->
        val v = bytes.decodeToString().lines().firstOrNull { it.startsWith("home=") }?.removePrefix("home=")?.trim()
        when {
            v == null -> null
            v == "planned" -> HomeDecision(true)
            v.startsWith("blocked:") -> v.removePrefix("blocked:").takeIf { b -> HomeBlock.entries.any { it.name == b } }?.let { HomeDecision(false, it) }
            v == "on" -> HomeDecision(false, alreadyOn = true)
            v == "none" -> HomeDecision(false)
            else -> null
        }
    }
}

// Everything a report is built from.
data class TvReportInput(
    val about: AppAbout,
    val snapshot: Snapshot,
    val profile: ProfileResolution?,
    val home: HomeDecision?,
    val switchedOffByApp: Set<PackageName> = emptySet(),
)

object TvReport {
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    // B1 "Help add this TV": the whole TV report, system packages included.
    fun text(input: TvReportInput): String = buildString {
        appendLine("Clean Your TV: TV report")
        appendHeader(input.about)
        appendTv(input, packages = true)
    }.trimEnd() + "\n"

    // B2 "Report a problem": the TV summary (no package list) when a TV was read, then the redacted
    // diagnostics log (already redacted by the app's logger).
    fun problem(about: AppAbout, input: TvReportInput?, log: String): String = buildString {
        appendLine("Clean Your TV: problem report")
        appendHeader(about)
        if (input != null) appendTv(input, packages = false) else { appendLine(); appendLine("TV: none read yet") }
        appendLine()
        appendLine("Diagnostics log (addresses, serial numbers, keys and codes removed)")
        append(log.trimEnd())
    }.trimEnd() + "\n"

    private fun StringBuilder.appendHeader(about: AppAbout) {
        appendLine("App: ${v(about.appLine)}")
        appendLine("Phone: ${v(about.phoneLine)}")
    }

    private fun StringBuilder.appendTv(input: TvReportInput, packages: Boolean) {
        val s = input.snapshot
        val f = s.facts
        appendLine()
        appendLine("TV")
        appendLine("Brand: ${v(f.brand)}")
        appendLine("Manufacturer: ${v(f.manufacturer)}")
        appendLine("Model: ${v(f.model)}")
        appendLine("Device: ${v(f.device)}")
        appendLine("Android: ${v(f.release)} (SDK ${f.sdk})")
        appendLine("Build: ${Scrub.text(oneLine(f.fingerprint), codes = false).ifEmpty { "unknown" }}")
        appendLine("Google Play services: ${if (f.hasGms) "yes" else "no"}")
        appendLine("Home screen it came with: ${platformOf(s)}")
        appendLine("Read from the TV: ${DAY.format(Instant.ofEpochMilli(s.takenAtMs))}${if (s.reason == SnapshotReason.BASELINE) ", before any change" else ""}")
        appendLine()
        appendLine("Matching")
        appendLine("Profile: ${LogCodes.profile(input.profile)}")
        appendLine("Home switch: ${input.home?.line?.removePrefix("home switch ") ?: "not recorded"}")
        appendLine()
        appendLine("Home screen")
        appendLine("Resolves to: ${s.home.resolved?.flat ?: "nothing (or the chooser)"}")
        val claimants = s.home.claimants.sortedWith(compareByDescending<tv.cleanyour.core.model.HomeClaimant> { it.priority }.thenBy { it.component.flat })
        if (claimants.isEmpty()) appendLine("Claimants: none")
        for (c in claimants) appendLine("Claimant: ${c.component.flat} priority ${c.priority}")
        val system = s.packages.filter { it.value.system }.toSortedMap(compareBy { it.value })
        val others = s.packages.size - system.size
        appendLine()
        if (packages) {
            appendLine("System apps (${system.size})")
            for ((pkg, st) in system) {
                val ours = if (pkg in input.switchedOffByApp) ", switched off by this app" else ""
                appendLine("${pkg.value} ${state(st.enabled)}$ours")
            }
            appendLine()
            appendLine("Other apps: $others (not named)")
        } else {
            appendLine("Apps: ${system.size} system, $others other")
        }
    }

    private fun state(e: EnabledState): String = when (e) {
        EnabledState.ENABLED -> "enabled"
        EnabledState.DEFAULT -> "default"
        EnabledState.DISABLED_USER -> "disabled-user"
        EnabledState.DISABLED -> "disabled"
    }

    private fun oneLine(s: String) = s.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }.joinToString("").trim()

    // A TV-provided value, on one line, masked (LogCodes.clean) and never empty.
    private fun v(s: String): String = LogCodes.clean(s, 120).ifEmpty { "unknown" }

    // The home screen the TV came with, for the tv_support form's "platform" field, from the TV's own
    // packages and facts (never from a profile: the TVs that need a report have none). A guess, so
    // the form lets the person change it.
    fun platformOf(s: Snapshot): String {
        val f = s.facts
        val has = { p: String -> s.packages.keys.any { it.value == p } }
        val claimants = s.home.claimants.map { it.component.pkg.value }
        return when {
            f.manufacturer.equals("Amazon", true) || f.brand.equals("Amazon", true) || f.model.startsWith("AFT", true) ||
                has("com.amazon.tv.launcher") || has("com.amazon.firehomestarter") -> "Fire TV"
            has("com.google.android.apps.tv.launcherx") -> "Google TV"
            has("com.google.android.tvlauncher") || has("com.google.android.leanbacklauncher") -> "Android TV (Leanback)"
            claimants.any { it != Launcher.pkg.value && !it.startsWith("com.google.") && !it.startsWith("com.android.") && "setupwraith" !in it } -> "The maker's own launcher"
            else -> "Not sure"
        }
    }

    // "Hisense 55A6H": the brand, then the model unless it already starts with the brand.
    fun tvName(s: Snapshot): String {
        val brand = v(s.facts.brand)
        val model = v(s.facts.model)
        return if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
    }

    // "Android 11, RTM6.230109.155": the release and the build id from the fingerprint
    // (brand/product/device:release/id/incremental:type/tags).
    fun androidLine(s: Snapshot): String {
        val id = s.facts.fingerprint.split(':').getOrNull(1)?.split('/')?.getOrNull(1)?.let { LogCodes.clean(it, 40) }
        return listOfNotNull("Android ${v(s.facts.release)}", id?.takeIf { it.isNotEmpty() }).joinToString(", ")
    }
}

// The new-issue links the preview screen opens (B1, B2). GitHub issue forms take a field's id as a
// query parameter and prefill that text field. Each value is short and the whole link stays far
// under GitHub's limit; the report itself goes on the clipboard, never into the link.
object IssueLinks {
    const val REPO = "https://github.com/udhaykumarbala/cleanyourtv-app"
    const val MAX_VALUE = 200
    const val MAX_URL = 2000   // the tests hold every link under it

    // The line the form's report box starts with, so the person knows to paste.
    const val PASTE_NOTE = "The app copied the report. Long-press here, choose Paste, and the report goes in below this line."

    fun tvSupport(input: TvReportInput): String = link("tv_support.yml", listOf(
        "title" to "TV support: ${TvReport.tvName(input.snapshot)}",
        "model" to TvReport.tvName(input.snapshot),
        "android" to TvReport.androidLine(input.snapshot),
        "platform" to TvReport.platformOf(input.snapshot),
        "report" to PASTE_NOTE,
    ))

    fun bugReport(about: AppAbout, input: TvReportInput?): String = link("bug_report.yml", listOfNotNull(
        "version" to about.appLine,
        "phone" to about.phoneLine,
        input?.let { "tv" to "${TvReport.tvName(it.snapshot)}, ${TvReport.androidLine(it.snapshot)} (${TvReport.platformOf(it.snapshot)})" },
        "diagnostics" to PASTE_NOTE,
    ))

    private fun link(template: String, params: List<Pair<String, String>>): String {
        val query = (listOf("template" to template) + params).joinToString("&") { (k, v) -> "$k=${enc(v.take(MAX_VALUE))}" }
        return "$REPO/issues/new?$query"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
