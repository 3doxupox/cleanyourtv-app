package tv.cleanyour.core.probe

import tv.cleanyour.core.model.PACKAGE_RE
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.ShellLine

// D40: the probe's shell lines. Reads are batched into one fixed script per stage, with marker
// lines between the commands; every script is a constant (golden test in ProbeScriptsTest), so
// the injection argument does not apply. The few lines that take an argument take only a typed
// value whose grammar admits no quote or shell metacharacter (RemotePath, PackageName, RunToken)
// and single-quote it. `pm` stays one per script where a later command matters, because
// upstream saw chained `pm` calls stop early on some sets.
@OptIn(RawShell::class)
object ProbeScripts {
    const val MARK = "@@cyt@@"

    private fun mark(name: String) = "echo '$MARK $name'"
    private fun q(s: String): String {
        require('\'' !in s && s.all { it in ' '..'~' }) { "refusing to quote $s" }
        return "'$s'"
    }

    const val HOME_ARGS = "-a android.intent.action.MAIN -c android.intent.category.HOME"
    const val SCREEN_FILE = "probe-screen.png"
    const val NOOP_FILE = "noop.apk"
    // The no-op APK the install-refusal check uses (W5 bundles it) must have this package name.
    val NOOP_PACKAGE = PackageName("tv.cleanyour.noop")

    // Stage FACTS: every property, memory, and whether Play Services exists.
    val FACTS = ShellLine("getprop; ${mark("meminfo")}; cat /proc/meminfo; ${mark("gms")}; pm path com.google.android.gms")

    // Stage PACKAGES: three calls, the package manager's own lists plus the flags and state of each.
    val PACKAGES = ShellLine("pm list packages -f")
    val DISABLED = ShellLine("pm list packages -d")
    val PACKAGE_FLAGS = ShellLine(
        "dumpsys package packages | grep -E '^(Packages:|Hidden system packages:)|^ +(Package \\[|codePath=|versionCode=|pkgFlags=|User 0: )'",
    )

    // Stage HOME: every HOME claimant with its priority, then what HOME resolves to now.
    val HOME = ShellLine("cmd package query-activities --brief $HOME_ARGS; ${mark("resolved")}; cmd package resolve-activity --brief $HOME_ARGS")
    val HOME_RESOLVED = ShellLine("cmd package resolve-activity --brief $HOME_ARGS")

    // Stage INPUTS: TIF inputs with their HDMI ports, then CEC device names.
    val INPUTS = ShellLine("dumpsys tv_input; ${mark("hdmi")}; dumpsys hdmi_control")

    // Stage SETTINGS: the secure and global tables (the allowlisted keys, the IME, the screensaver).
    val SETTINGS = ShellLine("settings list secure; ${mark("global")}; settings list global")

    val HOME_FOCUS = ShellLine("dumpsys activity activities | grep -E 'ResumedActivity'")
    val BOOT_ID = ShellLine("cat /proc/sys/kernel/random/boot_id")
    val FREE_DATA = ShellLine("df -k /data")
    val SCREENSHOT = ShellLine("mkdir -p ${RemoteDir.TMP.path} && screencap -p ${RemoteDir.TMP.path}/$SCREEN_FILE")
    val SCREENSHOT_CLEANUP = ShellLine("rm -f ${RemoteDir.TMP.path}/$SCREEN_FILE")
    val MKDIR_TMP = ShellLine("mkdir -p ${RemoteDir.TMP.path}")
    val NOOP_INSTALL = ShellLine("pm install -r ${RemoteDir.TMP.path}/$NOOP_FILE")
    val NOOP_UNINSTALL = ShellLine("pm uninstall ${NOOP_PACKAGE.value}")
    val NOOP_CLEANUP = ShellLine("rm -f ${RemoteDir.TMP.path}/$NOOP_FILE")
    val PS_ARGS = ShellLine("ps -A -o ARGS")

    val screenPath = RemotePath(RemoteDir.TMP, listOf(SCREEN_FILE))
    val noopPath = RemotePath(RemoteDir.TMP, listOf(NOOP_FILE))
    val overlayConfig = RemotePath(RemoteDir.OVERLAY, listOf("config.json"))

    fun fileSha256(path: RemotePath) = ShellLine("sha256sum ${q(path.absolute)}")

    // The installed APK's own hash: the path never leaves the TV, so no TV output is ever quoted back.
    fun installedSha256(pkg: PackageName): ShellLine {
        require(PACKAGE_RE.matches(pkg.value))
        return ShellLine("p=\$(pm path ${q(pkg.value)} | grep base.apk | head -n 1); sha256sum \"\${p#package:}\"")
    }

    // The bracket keeps pgrep from matching the shell that runs it.
    fun watchdogAlive(token: RunToken) = ShellLine("pgrep -f ${q("[c]leanyourtv-rollback-${token.value}")}")

    // Every constant line, for the golden test and for anyone auditing what a probe can send.
    val all: Map<String, ShellLine> = linkedMapOf(
        "FACTS" to FACTS, "PACKAGES" to PACKAGES, "DISABLED" to DISABLED, "PACKAGE_FLAGS" to PACKAGE_FLAGS, "HOME" to HOME,
        "HOME_RESOLVED" to HOME_RESOLVED, "INPUTS" to INPUTS, "SETTINGS" to SETTINGS, "HOME_FOCUS" to HOME_FOCUS, "BOOT_ID" to BOOT_ID,
        "FREE_DATA" to FREE_DATA, "SCREENSHOT" to SCREENSHOT, "SCREENSHOT_CLEANUP" to SCREENSHOT_CLEANUP, "MKDIR_TMP" to MKDIR_TMP,
        "NOOP_INSTALL" to NOOP_INSTALL, "NOOP_UNINSTALL" to NOOP_UNINSTALL, "NOOP_CLEANUP" to NOOP_CLEANUP, "PS_ARGS" to PS_ARGS,
    )
}
