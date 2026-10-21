package tv.cleanyour.core.flows

import tv.cleanyour.core.engine.DefaultEngine
import tv.cleanyour.core.engine.Journal
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.SectionState
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.store.DocumentStore

// Which plans ran on which TV, so the flows can derive ChangeRecords from the journals (the
// Journal interface lists only open runs) and tell the resume prompt its counts without the Plan
// document. One line per run in tvs/<tvId>/runs.txt: planId|kind|createdAtMs|disables|takeover.
data class RunEntry(val planId: PlanId, val kind: PlanKind, val createdAtMs: Long, val disables: Int, val takeover: Boolean)

// What a run's journal says so far: the counts for "14 of 23" and whether a HOME section is open.
data class RunProgress(val doneSteps: Int, val doneDisables: Int, val openSection: Boolean, val ended: Boolean)

class RunLedger(private val store: DocumentStore, private val journal: Journal) {

    private fun path(tvId: TvId) = "tvs/${tvId.value}/runs.txt"

    suspend fun record(plan: Plan) {
        val existing = runs(plan.tvId)
        if (existing.any { it.planId == plan.id }) return
        val entry = RunEntry(plan.id, plan.kind, plan.createdAtMs, plan.steps.count { it.op is Op.Disable }, plan.sections.isNotEmpty())
        store.write(path(plan.tvId), encode(existing + entry))
    }

    suspend fun runs(tvId: TvId): List<RunEntry> = store.read(path(tvId)) { decode(it) }.orEmpty()

    suspend fun entry(tvId: TvId, planId: PlanId): RunEntry? = runs(tvId).firstOrNull { it.planId == planId }

    suspend fun forget(tvId: TvId) { store.delete(path(tvId)) }

    // Every committed change this app made to the TV, oldest first: a Begin followed by its Done.
    // Reads and phone-side steps are not changes.
    suspend fun changes(tvId: TvId): List<ChangeRecord> = runs(tvId).flatMap { run ->
        val records = journal.read(run.planId)
        val begins = HashMap<Int, JournalRecord.Begin>()
        val out = ArrayList<ChangeRecord>()
        for (r in records) {
            when (r) {
                is JournalRecord.Begin -> begins[r.i] = r
                is JournalRecord.Done -> begins.remove(r.i)?.takeIf { isChange(it.op) }?.let { out += ChangeRecord(run.planId, r.i, it.op, it.prior, it.atMs) }
                else -> {}
            }
        }
        out
    }

    suspend fun progress(planId: PlanId): RunProgress {
        val records = journal.read(planId)
        val begins = HashMap<Int, Op>()
        var doneSteps = 0
        var doneDisables = 0
        var open = false
        var ended = false
        for (r in records) {
            when (r) {
                is JournalRecord.Begin -> begins[r.i] = r.op
                is JournalRecord.Done -> { doneSteps++; if (begins[r.i] is Op.Disable) doneDisables++ }
                is JournalRecord.Section -> open = r.state == SectionState.ARMED
                is JournalRecord.End -> ended = true
                else -> {}
            }
        }
        return RunProgress(doneSteps, doneDisables, open, ended)
    }

    // The steps of a run whose switch-off the live check kept because the package runs the TV's
    // inputs (RF-LAUNCHER-250 F1): Done and Manage name them with their own line.
    suspend fun keptTvInputs(planId: PlanId): Set<Int> =
        journal.read(planId).filterIsInstance<JournalRecord.Failed>().filter { it.error == DefaultEngine.KEPT_TV_INPUT }.map { it.i }.toSet()

    // The packages this app switched off and has not put back since, in the order they went off.
    suspend fun switchedOffByUs(tvId: TvId): List<PackageName> {
        val off = LinkedHashSet<PackageName>()
        for (c in changes(tvId)) when (val op = c.op) {
            is Op.Disable -> off.add(op.pkg)
            is Op.Enable -> off.remove(op.pkg)
            is Op.DefaultState -> off.remove(op.pkg)
            is Op.Uninstall -> off.remove(op.pkg)
            else -> {}
        }
        return off.toList()
    }

    companion object {
        fun isChange(op: Op): Boolean = when (op) {
            is Op.Read, is Op.VerifyHome, is Op.Settle, Op.PressHome, is Op.CurtainCue -> false
            else -> true
        }

        internal fun encode(entries: List<RunEntry>): ByteArray = entries.joinToString("") {
            "${it.planId.value}|${it.kind.name}|${it.createdAtMs}|${it.disables}|${it.takeover}\n"
        }.encodeToByteArray()

        internal fun decode(bytes: ByteArray): List<RunEntry> = bytes.decodeToString().lines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 5) return@mapNotNull null
            val kind = PlanKind.entries.firstOrNull { it.name == p[1] } ?: return@mapNotNull null
            RunEntry(PlanId(p[0]), kind, p[2].toLongOrNull() ?: 0, p[3].toIntOrNull() ?: 0, p[4] == "true")
        }
    }
}
