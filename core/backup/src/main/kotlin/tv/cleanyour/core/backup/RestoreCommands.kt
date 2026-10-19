package tv.cleanyour.core.backup

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.core.ops.RenderRefused

enum class RestoreStepKind {
    PUT_BACK,          // a TV command that puts one change back
    REMOVE_APP,        // uninstalls an app we installed; laptop scripts run it only when asked
    NOT_FROM_LAPTOP,   // cannot be put back with a shell line from a computer; the step says why
}

// One entry of a laptop restore script, newest change first.
data class RestoreStep(val kind: RestoreStepKind, val change: ChangeRecord, val inverse: Op?, val description: String,
    val lines: List<String>, val note: String?)

// Turns change records into the TV command lines that put them back, newest first. The inverse and
// the lines come from the app's own OpRenderer (inverse, then render), so commands.txt, restore.sh
// and restore.ps1 carry exactly the lines the phone would run for the same undo (ARCHITECTURE
// 5.4: "same lines"). Nothing here builds a shell command itself.
//
// Left out on purpose: ops that change nothing lasting on the TV (reads, settles, the HOME
// watchdog and its confirmation, restarts, debugging off, reboot, mkdir), and our own restore
// point files under /sdcard/cleanyourtv, which every plan rewrites.
class RestoreCommands(private val renderer: OpRenderer) {

    fun steps(changes: List<ChangeRecord>): List<RestoreStep> =
        newestFirst(changes).filterNot { isTransient(it.op) || isRestorePointFile(it.op) }.mapNotNull(::step)

    private fun step(change: ChangeRecord): RestoreStep? {
        val what = TextSafety.ascii("${describeChange(change.op)} (plan step ${change.step})", 160)
        val prior = change.prior
            ?: return RestoreStep(RestoreStepKind.NOT_FROM_LAPTOP, change, null, what, emptyList(),
                "there is no record of how it was before, so it is left as it is")
        val inverse = renderer.inverse(change.op, prior)
            ?: return RestoreStep(RestoreStepKind.NOT_FROM_LAPTOP, change, null, what, emptyList(), cannotUndo(change.op))
        if (isTransient(inverse)) return null
        val rendered = try {
            renderer.render(inverse)
        } catch (e: RenderRefused) {
            return RestoreStep(RestoreStepKind.NOT_FROM_LAPTOP, change, inverse, what, emptyList(),
                TextSafety.ascii("the app refused the command that would put it back: ${e.reason}", 200))
        }
        val description = TextSafety.ascii(describeInverse(inverse), 160)
        if (rendered.transfer != null) {
            return RestoreStep(RestoreStepKind.NOT_FROM_LAPTOP, change, inverse, description, emptyList(),
                "it needs a file from the phone; use the app's Undo everything instead")
        }
        val lines = rendered.lines.map { it.text }
        if (lines.isEmpty()) return null
        if (lines.any { it.isEmpty() || it.length > MAX_LINE || !TextSafety.isPrintableAscii(it) }) {
            return RestoreStep(RestoreStepKind.NOT_FROM_LAPTOP, change, inverse, description, emptyList(),
                "its command is not a single printable line")
        }
        val kind = if (inverse is Op.Uninstall) RestoreStepKind.REMOVE_APP else RestoreStepKind.PUT_BACK
        return RestoreStep(kind, change, inverse, description, lines, null)
    }

    companion object {
        const val MAX_LINE = 131_000

        // Chronological by atMs, ties in list order (stable), then reversed: the newest change is
        // put back first, as the journal undo does.
        fun newestFirst(changes: List<ChangeRecord>): List<ChangeRecord> = changes.sortedBy { it.atMs }.asReversed()

        fun isTransient(op: Op): Boolean = when (op) {
            is Op.Read, is Op.VerifyHome, is Op.Settle, is Op.ArmHomeWatchdog, is Op.ConfirmHome, is Op.KillWatchdog,
            Op.PressHome, is Op.RestartHome, is Op.StartActivity, Op.DebugOff, Op.Reboot, is Op.Mkdir -> true
            else -> false
        }

        fun isRestorePointFile(op: Op): Boolean = when (op) {
            is Op.PushAsset -> op.dest.dir == RemoteDir.RESTORE
            is Op.RemoveFile -> op.path.dir == RemoteDir.RESTORE
            else -> false
        }

        fun describeChange(op: Op): String = when (op) {
            is Op.Disable -> "Switched off ${op.pkg.value}"
            is Op.Enable -> "Switched on ${op.pkg.value}"
            is Op.DefaultState -> "Reset ${op.pkg.value} to its default state"
            is Op.Install -> "Installed ${op.pkg.value}"
            is Op.InstallStream -> "Installed ${op.pkg.value}"
            is Op.Uninstall -> "Removed ${op.pkg.value}"
            is Op.SettingsPut -> if (op.value == null) "Cleared the setting ${op.key.key}" else "Set ${op.key.key} to ${op.value}"
            is Op.SetHome -> "Made ${op.component.flat} the home screen"
            is Op.PushAsset -> "Wrote ${op.dest.absolute}"
            is Op.RemoveFile -> "Deleted ${op.path.absolute}"
            else -> "Ran ${ChangeJson.typeOf(op)}"
        }

        fun describeInverse(op: Op): String = when (op) {
            is Op.Enable -> "Switch back on: ${op.pkg.value}"
            is Op.Disable -> "Switch off again: ${op.pkg.value}"
            is Op.DefaultState -> "Back to its default state: ${op.pkg.value}"
            is Op.Uninstall -> "Remove the app: ${op.pkg.value}"
            is Op.SettingsPut -> if (op.value == null) "Setting ${op.key.key} back to not set" else "Setting ${op.key.key} back to ${op.value}"
            is Op.SetHome -> "Home screen back to ${op.component.flat}"
            is Op.RemoveFile -> "Delete ${op.path.absolute}"
            else -> describeChange(op)
        }

        private fun cannotUndo(op: Op): String = when (op) {
            is Op.PushAsset -> "the earlier file has to be pushed from the phone; use Undo everything in the app"
            is Op.Install, is Op.InstallStream -> "the app was already on the TV, so it stays"
            is Op.Uninstall -> "the app cannot be reinstalled from here"
            else -> "this change has no undo"
        }
    }
}
