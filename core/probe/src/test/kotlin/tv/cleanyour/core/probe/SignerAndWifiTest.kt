package tv.cleanyour.core.probe

import kotlinx.coroutines.runBlocking
import org.junit.Test
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.testing.fixtures.FakeTvSession
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Launcher v2.4.0 plan finding 4: the snapshot carries the installed launcher's (and the TV
// curtain's) signer, read from the APK Signing Block of a copy of the installed APK. D3: the
// owners of the saved Wi-Fi networks, cname words only.
class SignerAndWifiTest {
    private fun p(s: String) = PackageName(s)
    private val prober = AdbProber(ProbeFixtures.clock())
    private val bundled = File(Fixtures.repoRoot, "android/platform/src/main/assets/bundled")
    // SHA-256 of the launcher key K1's certificate, as PinnedLauncher.SIGNER_SHA256 holds it.
    private val k1 = Sha256("576418154bec6505bfc709ccaa6ae85b6e09d98eb89f4a25147ca1b4b0505132")

    @Test fun signerOfTheBundledApks() {
        assertEquals(k1, ApkSigners.signerSha256(File(bundled, "cleanyourtv-launcher.apk").readBytes()))
        assertEquals(k1, ApkSigners.signerSha256(File(bundled, "cleanyourtv-curtain.apk").readBytes()))
    }

    @Test fun anythingElseIsUnknown() {
        val apk = File(bundled, "cleanyourtv-launcher.apk").readBytes()
        assertNull(ApkSigners.signerSha256(ByteArray(0)))
        assertNull(ApkSigners.signerSha256("not an apk".toByteArray()))
        assertNull(ApkSigners.signerSha256(apk.copyOf(apk.size / 2)))
        // A zip without a signing block (v1 or unsigned).
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z -> z.putNextEntry(java.util.zip.ZipEntry("a")); z.write(1); z.closeEntry() }
        }.toByteArray()
        assertNull(ApkSigners.signerSha256(zip))
        // Every single-byte change in the signing block either reads the same certificate, another
        // one, or nothing; it never throws.
        val r = kotlin.random.Random(7)
        repeat(2_000) {
            val bad = apk.copyOf()
            val at = bad.size - 1 - r.nextInt(minOf(bad.size, 12_000))
            bad[at] = (bad[at].toInt() xor (1 shl r.nextInt(8))).toByte()
            ApkSigners.signerSha256(bad)
        }
    }

    private fun session(installed: Boolean, apk: ByteArray?, copyExit: Int = 0): FakeTvSession {
        val packages = Fixtures.miTv().packagesAllText + if (installed) "package:/data/app/com.gathin.tvhome-1/base.apk=com.gathin.tvhome\n" else ""
        val rec = ProbeFixtures.miTvRecording()
            .on(ProbeScripts.PACKAGES.text, packages)
            .on(ProbeScripts.copyApk(p("com.gathin.tvhome")).text, "", exit = copyExit)
            .on(ProbeScripts.SIGNER_CLEANUP.text, "")
        return FakeTvSession(recording = rec).also { s -> apk?.let { s.files[ProbeScripts.signerPath.absolute] = it } }
    }

    @Test fun theSnapshotCarriesTheInstalledLaunchersSigner() = runBlocking<Unit> {
        val s = session(installed = true, apk = File(bundled, "cleanyourtv-launcher.apk").readBytes())
        val snap = prober.snapshot(s, SnapshotReason.BASELINE)
        assertEquals(k1, snap.packages.getValue(p("com.gathin.tvhome")).signer)
        assertTrue(snap.packages.filterKeys { it != p("com.gathin.tvhome") }.values.all { it.signer == null })
        // Copy, pull, delete; the copy never stays on the TV.
        assertEquals(ProbeScripts.SIGNER_CLEANUP.text, s.execLines.last())
        assertTrue(ProbeScripts.copyApk(p("com.gathin.tvhome")).text in s.execLines)
        assertEquals(2, s.calls.count { it is FakeTvSession.Call.Pull })
    }

    @Test fun anUnreadableSignerIsUnknownAndTheSnapshotStillWorks() = runBlocking<Unit> {
        val failed = prober.snapshot(session(installed = true, apk = null, copyExit = 1), SnapshotReason.BASELINE)
        assertNull(failed.packages.getValue(p("com.gathin.tvhome")).signer)
        val garbage = prober.snapshot(session(installed = true, apk = "junk".toByteArray()), SnapshotReason.BASELINE)
        assertNull(garbage.packages.getValue(p("com.gathin.tvhome")).signer)
        // Not installed: nothing is copied.
        val none = session(installed = false, apk = null)
        prober.snapshot(none, SnapshotReason.BASELINE)
        assertFalse(none.execLines.any { "signer.apk" in it })
    }

    // tests/test_setupwraith_guard.py of v2.4.0, on the phone's side.
    @Test fun wifiOwners() = runBlocking<Unit> {
        val wizard = "com.google.android.tungsten.setupwraith"
        fun owners(out: String, exit: Int = 0) = runBlocking {
            prober.savedWifiOwners(FakeTvSession(recording = ProbeFixtures.miTvRecording().on(ProbeScripts.WIFI_OWNERS.text, out, exit = exit)))
        }
        val byWizard = owners("cname=$wizard:10031\n")
        assertEquals(setOf("$wizard:10031"), byWizard)
        assertTrue(WifiOwnersParser.wizardMayOwnWifi(byWizard, wizard))
        val bySystem = owners("cname=android.uid.system:1000\ncname=android.uid.system:1000\n")
        assertEquals(setOf("android.uid.system:1000"), bySystem)
        assertFalse(WifiOwnersParser.wizardMayOwnWifi(bySystem, wizard))
        // dumpsys failed, or listed no network: cannot tell, so the wizard is taken as the owner.
        val failed = owners("", exit = 1)
        assertEquals(emptySet(), failed)
        assertTrue(WifiOwnersParser.wizardMayOwnWifi(failed, wizard))
        assertTrue(WifiOwnersParser.wizardMayOwnWifi(owners("Wi-Fi is off\n"), wizard))
        // One network each: the wizard's counts.
        assertTrue(WifiOwnersParser.wizardMayOwnWifi(owners("cname=android.uid.system:1000\ncname=$wizard:10031\n"), wizard))
        // Another app marked as the wizard by a profile.
        assertTrue(WifiOwnersParser.wizardMayOwnWifi(setOf("com.example.oobe:10050"), "com.example.oobe"))
        // Only owners come through: a word that is not one is dropped.
        assertEquals(setOf("android.uid.system:1000"), WifiOwnersParser.parse(sequenceOf("cname=android.uid.system:1000", "cname=\"My home\"", "SSID=x", "cname=;rm")))
    }
}
