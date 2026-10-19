package tv.cleanyour.core.trust

import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

// The build-time launcher pin assertion, run by the verifyLauncherPin Gradle task (JavaExec on this
// source set, so apksig stays out of the app) and by LauncherPinTest. The sha256 pins the bytes and
// so package, versionCode and versionName as well (read once with aapt2 by the build script); the
// signer is checked with apksig, the library apksigner is built on.
object LauncherPinCheck {
    fun problems(apk: File): List<String> {
        if (!apk.isFile) return listOf("missing $apk")
        val out = mutableListOf<String>()
        val bytes = apk.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (bytes.size.toLong() != PinnedLauncher.SIZE) out += "size ${bytes.size} is not the pinned ${PinnedLauncher.SIZE}"
        if (sha != PinnedLauncher.SHA256) out += "sha256 $sha is not the pinned ${PinnedLauncher.SHA256}"
        val result = ApkVerifier.Builder(apk).build().verify()
        if (!result.isVerified) out += "APK signature does not verify: ${result.errors}"
        if (!result.isVerifiedUsingV2Scheme) out += "not signed with APK Signature Scheme v2"
        val certs = result.signerCertificates
        if (certs.size != 1) out += "expected one signer, found ${certs.size}"
        val signer = certs.firstOrNull()?.let { c -> MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) } }
        if (signer != PinnedLauncher.SIGNER_SHA256) out += "signer $signer is not the pinned ${PinnedLauncher.SIGNER_SHA256}"
        return out
    }
}

fun main(args: Array<String>) {
    val apk = File(args.single())
    val problems = LauncherPinCheck.problems(apk)
    if (problems.isNotEmpty()) {
        System.err.println("bundled launcher does not match PinnedLauncher (${apk.name}):")
        problems.forEach { System.err.println("  $it") }
        exitProcess(1)
    }
    println("bundled launcher matches its pin: ${PinnedLauncher.SHA256}, signer ${PinnedLauncher.SIGNER_SHA256}")
}
