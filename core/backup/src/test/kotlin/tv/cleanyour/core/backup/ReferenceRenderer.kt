package tv.cleanyour.core.backup

import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.core.ops.Privilege
import tv.cleanyour.core.ops.RenderRefused
import tv.cleanyour.core.ops.Rendered
import tv.cleanyour.core.ops.Transfer
import tv.cleanyour.core.ops.VerifyResult
import tv.cleanyour.testing.fixtures.sha256Of

// Test stand-in for W3's renderer, NOT the renderer: it follows SECURITY 3.2 (argv) and 3.4
// (constant verb words, every argument single quoted) for the verbs an undo can produce, so the
// restore scripts can be run against a fake TV before :core:ops lands. The undo rules are the
// SECURITY 3.2 "Undo" column. When the real renderer exists, the shell tests should run with it
// too; the stub adb tokenises lines the way the TV's sh does, so either quoting style works.
@OptIn(RawShell::class)
class ReferenceRenderer : OpRenderer {
    override fun render(op: Op): Rendered {
        val (lines, transfer) = when (op) {
            is Op.Disable -> listOf("pm disable-user --user 0 ${q(op.pkg.value)}") to null
            is Op.Enable -> listOf("pm enable --user 0 ${q(op.pkg.value)}") to null
            is Op.DefaultState -> listOf("pm default-state --user 0 ${q(op.pkg.value)}") to null
            is Op.Uninstall -> listOf("pm uninstall --user 0 ${q(op.pkg.value)}") to null
            is Op.SettingsPut -> {
                val ns = q(op.key.ns.name.lowercase())
                val v = op.value
                if (v == null) listOf("settings delete $ns ${q(op.key.key)}") to null
                else {
                    if (!op.key.valueShape.matches(v)) throw RenderRefused(op, "value does not match ${op.key.key}")
                    listOf("settings put $ns ${q(op.key.key)} ${q(v)}") to null
                }
            }
            is Op.SetHome -> listOf("cmd package set-home-activity --user 0 ${q(op.component.flat)}") to null
            is Op.RemoveFile -> listOf("rm -f ${q(op.path.absolute)}") to null
            is Op.Install -> listOf("pm install -r --user 0 ${q(op.apk.absolute)}") to null
            is Op.InstallStream -> emptyList<String>() to Transfer.StreamInstall(op.blob, op.blob.size)
            is Op.PushAsset -> emptyList<String>() to Transfer.Push(op.src, op.dest)
            is Op.Settle -> emptyList<String>() to null
            Op.PressHome -> listOf("input keyevent KEYCODE_HOME") to null
            is Op.Mkdir -> listOf("mkdir -p ${q(op.dir.path)}") to null
            is Op.KillWatchdog -> listOf("touch ${q("/data/local/tmp/cleanyourtv/${op.token.value}.ok")}") to null
            else -> throw RenderRefused(op, "not in the reference renderer")
        }
        lines.forEach { check(Regex("^[ -~]{1,131000}$").matches(it)) }
        return Rendered(op, lines.map { ShellLine(it) }, transfer, Privilege.PROFILE_STEP, verify = null, timeoutMs = 30_000, stdoutCap = 64 * 1024)
    }

    override fun inverse(op: Op, prior: Prior): Op? = when (op) {
        is Op.Disable, is Op.Enable, is Op.DefaultState -> {
            val pkg = when (op) { is Op.Disable -> op.pkg; is Op.Enable -> op.pkg; is Op.DefaultState -> op.pkg; else -> error("unreachable") }
            when (prior.packageState) {
                EnabledState.ENABLED -> Op.Enable(pkg)
                EnabledState.DEFAULT -> Op.DefaultState(pkg)
                EnabledState.DISABLED_USER, EnabledState.DISABLED -> Op.Disable(pkg)
                null -> null
            }
        }
        is Op.SettingsPut -> Op.SettingsPut(op.key, prior.settingValue)
        is Op.SetHome -> prior.home?.let { Op.SetHome(it) }
        is Op.Install -> if (prior.present == false) Op.Uninstall(op.pkg) else null
        is Op.InstallStream -> if (prior.present == false) Op.Uninstall(op.pkg) else null
        is Op.PushAsset -> if (prior.present == false) Op.RemoveFile(op.dest) else null
        is Op.ArmHomeWatchdog -> Op.KillWatchdog(op.token)
        else -> null
    }

    override fun check(op: Op, verifyOutput: String, prior: Prior?): VerifyResult = VerifyResult.Ok

    override fun planHash(rendered: List<Rendered>): Sha256 = sha256Of(rendered.joinToString("") { r -> r.lines.joinToString("") { it.text + "\n" } })

    private fun q(s: String): String {
        require('\'' !in s)
        return "'$s'"
    }
}
