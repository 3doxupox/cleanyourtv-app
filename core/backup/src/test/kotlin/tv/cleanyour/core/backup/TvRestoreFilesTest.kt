package tv.cleanyour.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.PackageEntry
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.ProfileMatch
import tv.cleanyour.testing.fixtures.FakeClock
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Samples
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TvRestoreFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock(Samples.NOW_MS + 3_600_000)
    private val files = DefaultTvRestoreFiles(clock)
    private val tv = Scenario.tv()
    private val changes = Scenario.changes()

    // A profile in the shape W4's converter produces: groups from the comment headers of the
    // upstream example, notes from the trailing comments.
    private fun upstreamProfile(folder: String): Profile {
        val text = Fixtures.upstreamExamples().single { it.folder == folder }.debloatTxt
        val groups = ArrayList<PackageGroup>()
        var title = "Ungrouped"
        var entries = ArrayList<PackageEntry>()
        fun flush() { if (entries.isNotEmpty()) groups += PackageGroup("g${groups.size}", title, GroupDefault.DISABLE, entries); entries = ArrayList() }
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#") -> { flush(); title = line.trimStart('#').trim() }
                else -> {
                    val name = line.substringBefore('#').trim()
                    if (Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(name)) {
                        entries += PackageEntry(PackageName(name), line.substringAfter('#', "").trim().ifEmpty { null })
                    }
                }
            }
        }
        flush()
        return Profile(folder, ProfileMatch(listOf("MiTV-AXSO0"), emptyList(), "Xiaomi", null, null, null), Platform.ANDROID_TV,
            listOf(Samples.stockLauncher), null, null, emptySet(), groups, emptySet(), emptyList(), null, Samples.zeroHash)
    }

    private fun listed(txt: String): List<String> =
        txt.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    @Test fun restoreTxtListsWhatIsOffNow() {
        val txt = files.restoreTxt(tv, changes, null).decodeToString()
        assertEquals(Scenario.expectedOff().map { it.value }, listed(txt))
        assertTrue(txt.lines().first().startsWith("# Clean Your TV restore list for Mi TV 4A (MiTV-AXSO0), 2025-10-09."), txt)
        assertTrue(txt.contains("tools/restore.sh restore.txt com.google.android.tvlauncher"), txt)
        assertTrue(txt.lines().all { Regex("^[ -~]*$").matches(it) })
    }

    @Test fun restoreTxtUsesTheProfileGroupsAndNotes() {
        val profile = upstreamProfile("xiaomi-mitv-axso0")
        val txt = files.restoreTxt(tv, changes, profile).decodeToString()
        assertEquals(Scenario.expectedOff().map { it.value }.sorted(), listed(txt).sorted())
        val inProfile = profile.groups.flatMap { g -> g.packages.map { it.name } }.toSet()
        assertTrue(Scenario.expectedOff().any { it in inProfile }, "the scenario should overlap the profile")
        profile.groups.filter { g -> g.packages.any { it.name in Scenario.expectedOff() } }.forEach { g -> assertTrue(txt.contains("# ${g.title}"), g.title) }
    }

    // D44: upstream tools/restore.sh reads our restore.txt unchanged. Runs the pinned upstream
    // script itself (bash, GNU grep and sed) with tools/venv/bin/python replaced by a capture.
    @Test fun upstreamRestoreShReadsIt() {
        val bash = Shells.which("bash") ?: fail("bash is needed to run upstream tools/restore.sh")
        val repo = tmp.newFolder("upstream")
        File(repo, "tools").mkdirs()
        File(Fixtures.upstreamDir, "tools/restore.sh").copyTo(File(repo, "tools/restore.sh"))
        val py = File(repo, "tools/venv/bin/python").also { it.parentFile.mkdirs() }
        val captured = File(repo, "captured.txt")
        py.writeText("#!/bin/sh\ncat > '${captured.absolutePath}'\n")
        check(py.setExecutable(true))
        val list = File(tmp.root, "restore.txt").also { it.writeBytes(files.restoreTxt(tv, changes, upstreamProfile("xiaomi-mitv-axso0"))) }
        val p = ProcessBuilder(bash.absolutePath, File(repo, "tools/restore.sh").absolutePath, list.absolutePath, "com.google.android.tvlauncher")
            .redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), out)
        val enabled = captured.readLines().filter { it.startsWith("pm enable ") }.map { it.removePrefix("pm enable ") }
        assertEquals((Scenario.expectedOff().map { it.value } + "com.google.android.tvlauncher").sorted(), enabled.sorted())
    }

    @Test fun restorePointJsonSchema1() {
        val bytes = files.restorePointJson(tv, changes)
        val o = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(1, o.getValue("schema").jsonPrimitive.content.toInt())
        assertEquals(DefaultTvRestoreFiles.KIND, o.getValue("kind").jsonPrimitive.content)
        assertEquals(tv.id.value, o.getValue("tvId").jsonPrimitive.content)
        assertEquals(clock.nowMs(), o.getValue("createdAtMs").jsonPrimitive.content.toLong())
        assertEquals(Scenario.expectedOff().map { it.value }, o.getValue("switchedOff").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(Scenario.stock.flat, o.getValue("homeBefore").jsonPrimitive.content)
        assertEquals(Scenario.launcher.flat, o.getValue("homeNow").jsonPrimitive.content)
        assertEquals(listOf("com.gathin.tvhome"), o.getValue("installed").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("/sdcard/Android/data/com.gathin.tvhome/files/config.json"), o.getValue("files").jsonArray.map { it.jsonPrimitive.content })
        val settings = o.getValue("settings").jsonArray.map { it.jsonObject }
        assertEquals(listOf("screensaver_components", "screensaver_enabled"), settings.map { it.getValue("name").jsonPrimitive.content })
        val changesBack = ChangeJson.decodeArray(o.getValue("changes").jsonArray)
        assertContentEquals(ChangeJson.encode(changes), ChangeJson.encode(changesBack))
        assertTrue(!bytes.decodeToString().contains(tv.nickname), "no nickname on the TV's shared storage")
    }

    @Test fun nothingSwitchedOff() {
        val txt = files.restoreTxt(tv, emptyList(), null).decodeToString()
        assertTrue(listed(txt).isEmpty() && txt.contains("Nothing is switched off"), txt)
    }
}
