package tv.cleanyour.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.ProbeKind
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.ReadArg
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.core.ops.PackageDump
import tv.cleanyour.core.ops.Privilege
import tv.cleanyour.core.ops.RenderRefused
import tv.cleanyour.core.ops.Rendered
import tv.cleanyour.core.ops.Transfer
import tv.cleanyour.core.ops.VerifyResult
import tv.cleanyour.core.planner.GuardedSection
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.Step
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.session.BootOutcome
import tv.cleanyour.core.session.ExecResult
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.InstallOutcome
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.SessionHold
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.GatePass
import tv.cleanyour.core.store.SnapshotStore
import java.util.concurrent.ConcurrentHashMap

// The executor (PRODUCT-SPEC 5.9, SECURITY 3.5, ARCHITECTURE 2.4). One op per exec, the journal
// written and forced before every line, verify read after every write, the denylist checked again
// with live facts, the plan hash compared with what the UI confirmed, a GatePass required for any
// write (D17), and the HOME takeover run as a guarded section with the phone-side timer, the live
// focus check and step R (section 3.4). A run lives in the engine's own scope; the UI watches
// runState. Steps are cancellable only between steps.
class DefaultEngine(
    private val renderer: OpRenderer,
    private val prober: Prober,
    private val journal: Journal,
    private val snapshots: SnapshotStore,
    private val plans: PlanStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val profiles: ProfileSource? = null,
    private val liveCheckEveryMs: Long = 2_000L,
    private val thumbnailEveryMs: Long = 5_000L,
    private val recentThumbnailMs: Long = 15_000L,
    private val noThumbnailWindowMs: Long = 120_000L,
    private val bootTimeoutMs: Long = 300_000L,
) : Engine {

    private class Run(val plan: Plan, val session: TvSession) {
        @Volatile var at = 0
        val done = sortedSetOf<Int>()
        val skipped = mutableListOf<Step>()
        val states = Array(plan.steps.size) { NarrationState.UPCOMING }
        var restorePoint = NarrationState.DOING
        var snapshot: Snapshot? = null
        var profile: Profile? = null
        @Volatile var stopRequested = false
        val decisions = Channel<Boolean>(Channel.CONFLATED)
        var job: Job? = null
        var hold: SessionHold? = null
        var thumbnail: ByteArray? = null
        var lastThumbnailOkAt = Long.MIN_VALUE / 2
        var lastThumbnailTryAt = Long.MIN_VALUE / 2
        val openSections = linkedSetOf<String>()
        val closedSections = linkedSetOf<String>()
        var home: HomeOutcome? = null
        var lastPause: PauseReason? = null
        var sectionOpenedAt = Long.MIN_VALUE / 2
    }

    private sealed interface Outcome {
        data object Ok : Outcome
        data class Refused(val exit: Int, val stderr: String) : Outcome        // the TV said no (non-zero exit, install refusal)
        data class Failed(val error: AdbError, val detail: String) : Outcome   // verify mismatch, policy, device error
        data class Lost(val error: AdbError) : Outcome                          // transport or auth: the TV went away
    }

    private val runs = ConcurrentHashMap<TvId, Run>()
    private val states = ConcurrentHashMap<TvId, MutableStateFlow<RunState>>()
    private val startLock = Mutex()

    private fun flow(tvId: TvId) = states.computeIfAbsent(tvId) { MutableStateFlow(RunState.Idle) }
    override fun runState(tvId: TvId): StateFlow<RunState> = flow(tvId).asStateFlow()

    // ---- start, gate, hash -------------------------------------------------------------------------

    override suspend fun start(plan: Plan, session: TvSession, confirmed: Sha256, pass: GatePass?): StartResult = startLock.withLock {
        val current = flow(plan.tvId).value
        if (current !is RunState.Idle && current !is RunState.Finished) return StartResult.Refused(StartRefusal.RUN_OPEN)
        if (journal.openRuns(plan.tvId).isNotEmpty()) return StartResult.Refused(StartRefusal.RUN_OPEN)
        // A plan runs once (R1): a second run would reuse its run token (and so its watchdog name and
        // token file) and append to a closed journal. "Try again" is a new plan from the planner.
        if (journal.read(plan.id).isNotEmpty()) return StartResult.Refused(StartRefusal.RUN_OPEN)
        if (session.state.value !is ConnectionState.Connected) return StartResult.Refused(StartRefusal.NOT_CONNECTED)
        if (plan.tvId != session.tvId) return StartResult.Refused(StartRefusal.WRONG_TV)
        if (confirmed != plan.hash || !rendersTheSame(plan)) return StartResult.Refused(StartRefusal.HASH_MISMATCH)
        gate(plan, plan.steps, pass)?.let { return StartResult.Refused(it) }

        plans.save(plan)
        val run = Run(plan, session)
        runs[plan.tvId] = run
        try {
            journal.append(JournalRecord.PlanStart(plan.id, plan.tvId, plan.baseSnapshot, plan.steps.size, plan.hash, plan.appVersion, clock.nowMs()))
        } catch (e: Throwable) {
            runs.remove(plan.tvId, run)
            throw e
        }
        run.hold = session.hold()
        publishRunning(run, 0)
        run.job = scope.launch { execute(run, prePlan = true) }
        StartResult.Started
    }

    // R0: 5. Every op re-rendered through the renderer; the lines must equal the plan's and the hash
    // over them must equal the plan's hash. A RenderRefused (a protected package, a bad argument)
    // is a mismatch too.
    private fun rendersTheSame(plan: Plan): Boolean {
        val again = try {
            plan.steps.mapIndexed { i, s ->
                if (s.index != i) return false
                renderer.render(s.op)
            }
        } catch (e: RenderRefused) {
            return false
        }
        // Everything the executor acts on is compared, not only the lines: the privilege decides the
        // gate (D17), the verify read decides "done", the timeout and cap bound the exec (R1).
        val same = again.zip(plan.steps).all { (a, s) ->
            a.lines.map { it.text } == s.rendered.lines.map { it.text } && a.transfer == s.rendered.transfer &&
                a.privilege == s.rendered.privilege && a.verify == s.rendered.verify &&
                a.timeoutMs == s.rendered.timeoutMs && a.stdoutCap == s.rendered.stdoutCap
        }
        return same && renderer.planHash(again) == plan.hash
    }

    // D17 by type (R0: 6): a plan with a write step needs a live pass for this TV.
    private fun gate(plan: Plan, steps: List<Step>, pass: GatePass?): StartRefusal? {
        if (steps.none { it.rendered.privilege in GATED }) return null
        if (pass == null || pass.tvId != plan.tvId) return StartRefusal.GATE_REQUIRED
        if (pass.expiresAtMs <= clock.nowMs()) return StartRefusal.GATE_EXPIRED
        return null
    }

    // ---- the run -----------------------------------------------------------------------------------

    private suspend fun execute(run: Run, prePlan: Boolean) {
        try {
            if (prePlan) {
                if (!takeRestorePoint(run)) return
            } else {
                if (run.home != null) { finishWithSkips(run, run.home, run.at); return }   // the section was rolled back: a new plan is needed
                if (!recheck(run, save = false, from = run.at + 1)) return   // the step at `at` may have half happened; it is re-run anyway
            }
            loop(run)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbException) {
            pause(run, if (e.error.isLost()) PauseReason.CONNECTION_LOST else PauseReason.STEP_FAILED, e.error)
        } catch (e: Throwable) {
            pause(run, PauseReason.STEP_FAILED, AdbError.Transport.Protocol(e.toString().take(200)))
        }
    }

    // The run's first action: the PRE_PLAN snapshot, saved, then the staleness check against the
    // priors of every remaining step.
    private suspend fun takeRestorePoint(run: Run): Boolean {
        run.restorePoint = NarrationState.DOING
        publishRunning(run, run.at)
        val ok = recheck(run, save = true, from = run.at)
        if (ok) { run.restorePoint = NarrationState.DONE; publishRunning(run, run.at) }
        return ok
    }

    private suspend fun recheck(run: Run, save: Boolean, from: Int): Boolean {
        val snap = try {
            prober.snapshot(run.session, SnapshotReason.PRE_PLAN)
        } catch (e: AdbException) {
            pause(run, if (e.error.isLost()) PauseReason.CONNECTION_LOST else PauseReason.STEP_FAILED, e.error); return false
        }
        if (save) snapshots.save(snap)
        run.snapshot = snap
        run.profile = (profiles?.resolve(snap.facts) as? ProfileResolution.Exact)?.profile
        val diff = Staleness.diff(run.plan.steps.drop(from), snap)
        if (diff.isNotEmpty()) { pause(run, PauseReason.STALE, AdbError.Policy.Stale(diff)); return false }
        return true
    }

    private suspend fun loop(run: Run) {
        val plan = run.plan
        while (run.at < plan.steps.size) {
            if (run.stopRequested) { pause(run, PauseReason.USER_STOP, null); return }
            val i = run.at
            val step = plan.steps[i]
            plan.sections.firstOrNull { it.from == i && it.name !in run.closedSections && it.name !in run.openSections }?.let { section ->
                if (!openSection(run, section)) { finishWithSkips(run, HomeOutcome.VERIFY_FAILED, i); return }
            }
            val section = plan.sections.firstOrNull { it.name in run.openSections && i in it.from..it.to }
            if (i == 0 || plan.steps[i - 1].group != step.group) thumbnail(run, force = true) else thumbnail(run, force = false)
            run.states[i] = NarrationState.DOING
            publishRunning(run, i)

            when (val outcome = runStep(run, step)) {
                Outcome.Ok -> { run.done += i; run.states[i] = NarrationState.DONE; run.at = i + 1 }
                is Outcome.Lost -> { run.states[i] = NarrationState.UPCOMING; pause(run, PauseReason.CONNECTION_LOST, outcome.error); return }
                is Outcome.Refused, is Outcome.Failed -> {
                    val error: AdbError = when (outcome) {
                        is Outcome.Failed -> outcome.error
                        is Outcome.Refused -> AdbError.NonZeroExit(outcome.exit, outcome.stderr.take(4096))
                        else -> AdbError.Transport.Closed
                    }
                    when {
                        section != null -> {
                            run.states[i] = NarrationState.FAILED
                            if (!rollbackSection(run, section, HomeOutcome.VERIFY_FAILED)) return
                            finishWithSkips(run, HomeOutcome.VERIFY_FAILED, i + 1); return
                        }
                        step.onFailure == FailurePolicy.CONTINUE || (step.onFailure == FailurePolicy.SKIP_IF_REFUSED && outcome is Outcome.Refused) -> {
                            run.skipped += step; run.states[i] = NarrationState.SKIPPED; run.at = i + 1
                        }
                        else -> { run.states[i] = NarrationState.FAILED; pause(run, PauseReason.STEP_FAILED, error); return }
                    }
                }
            }

            if (section != null && i == section.to && run.at == i + 1) {
                when (val outcome = awaitConfirm(run, section)) {
                    HomeOutcome.CONFIRMED -> {
                        journal.append(JournalRecord.Section(plan.id, section.name, section.token, SectionState.CONFIRMED, clock.nowMs()))
                        run.openSections -= section.name; run.closedSections += section.name
                    }
                    HomeOutcome.CONNECTION_LOST -> { pause(run, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed); return }
                    else -> {
                        if (!rollbackSection(run, section, outcome)) return
                        finishWithSkips(run, outcome, i + 1); return
                    }
                }
            }
        }
        end(run, if (run.skipped.isEmpty()) RunStatus.COMPLETED else RunStatus.COMPLETED_WITH_SKIPS, run.home)
    }

    // Section 3.4 preconditions at run time: the launcher installed and, when this plan installed
    // it, hashing to what was pushed; a screenshot probe succeeding; the token in the journal.
    private suspend fun openSection(run: Run, section: GuardedSection): Boolean {
        val launcherPkg = section.expectedHome.pkg
        val installed = prober.installedSha256(run.session, launcherPkg) ?: return false
        val expected = run.plan.steps.firstNotNullOfOrNull { s ->
            (s.op as? Op.Install)?.takeIf { it.pkg == launcherPkg }?.sha256 ?: (s.op as? Op.InstallStream)?.takeIf { it.pkg == launcherPkg }?.sha256
        }
        if (expected != null && installed != expected) return false
        if (!thumbnail(run, force = true)) return false
        val arm = run.plan.steps.getOrNull(section.from)?.op as? Op.ArmHomeWatchdog ?: return false
        if (arm.token != section.token) return false
        journal.append(JournalRecord.Section(run.plan.id, section.name, section.token, SectionState.ARMED, clock.nowMs() + arm.seconds * 1000L))
        run.openSections += section.name
        run.sectionOpenedAt = clock.monotonicMs()
        return true
    }

    // The check screen (3.4 step 5): the phone timer (90 s, or 120 s without a recent thumbnail,
    // D23), the 2 s focus check, a thumbnail every 5 s, and the user's answer.
    private suspend fun awaitConfirm(run: Run, section: GuardedSection): HomeOutcome {
        // D23: 90 s with a thumbnail fetched now or within the last 15 s since the section opened, else 120 s.
        val fresh = thumbnail(run, force = true)
        val now = clock.monotonicMs()
        val recent = run.lastThumbnailOkAt > run.sectionOpenedAt && now - run.lastThumbnailOkAt <= recentThumbnailMs
        val window = if (fresh || recent) section.confirmWindowMs else noThumbnailWindowMs
        val deadlineMono = now + window
        val deadlineWall = clock.nowMs() + window
        var homeCheck: Boolean? = null
        while (true) {
            flow(run.plan.tvId).value = RunState.AwaitingConfirm(run.plan, section, deadlineWall, homeCheck, run.thumbnail)
            val decision = withTimeoutOrNull(liveCheckEveryMs) { run.decisions.receive() }
            if (decision == true) return HomeOutcome.CONFIRMED
            if (decision == false) return HomeOutcome.USER_SAID_NO
            if (clock.monotonicMs() >= deadlineMono) return HomeOutcome.TIMED_OUT
            val focus = try {
                prober.homeFocus(run.session)
            } catch (e: AdbException) {
                if (e.error.isLost()) return HomeOutcome.CONNECTION_LOST else null
            }
            if (focus != null) {
                if (focus.pkg in section.rollbackIfFocus) return HomeOutcome.WRONG_FOCUS
                homeCheck = focus.pkg == section.expectedHome.pkg
            }
            thumbnail(run, force = false)
        }
    }

    // Step R: journaled like any op, idempotent; a lost connection leaves the section open so resume
    // runs it again. Only step R's own vocabulary runs (the plan file is not hashed for its
    // sections), and a failure to put the TV's own home screen back is never recorded as ROLLED_BACK:
    // the run pauses with the error, the section stays open, and continue, undo or resume try again
    // (SECURITY 3.5: undo failures are shown, never hidden) (R1).
    private suspend fun rollbackSection(run: Run, section: GuardedSection, outcome: HomeOutcome): Boolean {
        flow(run.plan.tvId).value = RunState.RollingBack(run.plan, section.from)
        journal.append(JournalRecord.Rollback(run.plan.id, section.from))
        var failed: AdbError? = null
        for (op in section.rollback) {
            if (!StepR.allows(op, section)) continue
            val undone = run.plan.steps.firstOrNull { it.index in section.from..section.to && undoes(op, it.op) }?.index ?: -1
            val rendered = try { renderer.render(op) } catch (e: RenderRefused) { continue }
            when (val r = perform(run, undone, op, rendered, null)) {
                is Outcome.Lost -> { pause(run, PauseReason.CONNECTION_LOST, r.error); return false }
                is Outcome.Refused -> if (StepR.mustSucceed(op) && failed == null) failed = AdbError.NonZeroExit(r.exit, r.stderr.take(4096))
                is Outcome.Failed -> if (StepR.mustSucceed(op) && failed == null) failed = r.error
                Outcome.Ok -> {}
            }
        }
        if (failed != null) { pause(run, PauseReason.STEP_FAILED, failed); return false }
        journal.append(JournalRecord.Section(run.plan.id, section.name, section.token, SectionState.ROLLED_BACK, clock.nowMs()))
        run.openSections -= section.name
        run.closedSections += section.name
        run.home = outcome
        return true
    }

    private fun undoes(rollbackOp: Op, stepOp: Op): Boolean = when (rollbackOp) {
        is Op.Enable -> stepOp is Op.Disable && stepOp.pkg == rollbackOp.pkg
        is Op.SetHome -> stepOp is Op.SetHome
        else -> false
    }

    // One plan step: the live denylist check, then Begin, the op, its verify read, Done or Failed.
    private suspend fun runStep(run: Run, step: Step): Outcome {
        val target = Staleness.writeTarget(step.op)
        if (target != null) {
            val dumpRead = renderer.render(Op.Read(ProbeKind.PACKAGE_DUMP, ReadArg.Pkg(target)))
            val dump = try {
                read(run.session, dumpRead)
            } catch (e: AdbException) {
                return if (e.error.isLost()) Outcome.Lost(e.error) else Outcome.Failed(e.error, "dumpsys package failed")
            }
            val live = liveState(target, dump, run.snapshot)
            val reason = Denylist.reason(target, live, run.profile, run.snapshot)
            if (reason != null) {
                journal.append(JournalRecord.Begin(run.plan.id, step.index, step.op, step.rendered.lines.map { it.text }, step.prior, clock.nowMs()))
                journal.append(JournalRecord.Failed(run.plan.id, step.index, null, "protected: $reason", ""))
                return Outcome.Failed(AdbError.Policy.ProtectedPackage(target), reason)
            }
            if (!PackageDump.exists(target, dump) && step.op !is Op.Uninstall) {
                journal.append(JournalRecord.Begin(run.plan.id, step.index, step.op, step.rendered.lines.map { it.text }, step.prior, clock.nowMs()))
                journal.append(JournalRecord.Failed(run.plan.id, step.index, 1, "package not on the TV", ""))
                return Outcome.Refused(1, "package not on the TV")
            }
        }
        return perform(run, step.index, step.op, step.rendered, step.prior)
    }

    // Begin (forced) before the line is sent, Done or Failed before the next Begin.
    private suspend fun perform(run: Run, index: Int, op: Op, rendered: Rendered, prior: Prior?): Outcome {
        journal.append(JournalRecord.Begin(run.plan.id, index, op, rendered.lines.map { it.text }, prior, clock.nowMs()))
        val started = clock.monotonicMs()
        val outcome = try {
            dispatch(run, op, rendered, prior)
        } catch (e: AdbException) {
            if (rendered.privilege == Privilege.SESSION_END && e.error.isLost()) Outcome.Ok   // debug_off: the TV closes the socket (5.2)
            else if (e.error.isLost()) Outcome.Lost(e.error)
            else Outcome.Failed(e.error, e.error.toString())
        }
        val ms = clock.monotonicMs() - started
        when (outcome) {
            Outcome.Ok -> journal.append(JournalRecord.Done(run.plan.id, index, 0, verified = true, ms = ms))
            is Outcome.Refused -> journal.append(JournalRecord.Failed(run.plan.id, index, outcome.exit, "exit ${outcome.exit}", outcome.stderr.take(4096)))
            is Outcome.Failed -> journal.append(JournalRecord.Failed(run.plan.id, index, null, outcome.detail.take(4096), ""))
            is Outcome.Lost -> {}   // in doubt: no Done, no Failed; resume re-verifies it
        }
        return outcome
    }

    // The dispatch table of 5.2 (R0: 7).
    private suspend fun dispatch(run: Run, op: Op, rendered: Rendered, prior: Prior?): Outcome {
        val session = run.session
        var ownOutput = ""
        when (op) {
            is Op.Settle -> { delay(op.seconds * 1000L); return Outcome.Ok }
            Op.Reboot -> {
                session.reboot()
                return when (session.awaitBoot(bootTimeoutMs)) {
                    is BootOutcome.Back -> Outcome.Ok
                    BootOutcome.Timeout -> Outcome.Lost(AdbError.Transport.Timeout("boot"))
                }
            }
            else -> {}
        }
        for (line in rendered.lines) {
            val r = send(session, line, ExecSpec(timeoutMs = rendered.timeoutMs, stdoutCap = rendered.stdoutCap, retry = RetryClass.NEVER))
            if (r.exit != 0) return Outcome.Refused(r.exit, r.stderr)
            ownOutput = r.stdout
        }
        when (val t = rendered.transfer) {
            is Transfer.Push -> session.push(t.src, t.dest)
            is Transfer.Pull -> { run.thumbnail = session.pull(t.src, t.maxBytes) }
            is Transfer.StreamInstall -> when (val o = session.installStream(t.src, t.size)) {
                InstallOutcome.Installed -> {}
                is InstallOutcome.Failed -> return Outcome.Refused(1, "${o.code}: ${o.detail}")
            }
            null -> {}
        }
        val verifyOutput = rendered.verify?.let { read(session, renderer.render(it)) } ?: ownOutput
        return when (val v = renderer.check(op, verifyOutput, prior)) {
            VerifyResult.Ok -> Outcome.Ok
            is VerifyResult.Mismatch -> Outcome.Failed(AdbError.Policy.GuardFailed(-1, "expected ${v.expected}, got ${v.actual}"), "verify: expected ${v.expected}, got ${v.actual}")
        }
    }

    // Verify and probe reads: idempotent, so the session may retry them.
    private suspend fun read(session: TvSession, rendered: Rendered): String {
        var out = ""
        for (line in rendered.lines) {
            out = send(session, line, ExecSpec(timeoutMs = rendered.timeoutMs, stdoutCap = rendered.stdoutCap, retry = RetryClass.IDEMPOTENT_READ)).stdout
        }
        return out
    }

    // The single call site of TvSession.exec in the engine (the lint in :core:engine tests).
    private suspend fun send(session: TvSession, line: ShellLine, spec: ExecSpec): ExecResult = session.exec(line, spec)

    private fun liveState(pkg: PackageName, dump: String, snapshot: Snapshot?): PackageState? {
        val known = snapshot?.packages?.get(pkg)
        if (!PackageDump.exists(pkg, dump)) return known
        return PackageState(
            enabled = PackageDump.enabledState(dump) ?: known?.enabled ?: EnabledState.ENABLED,
            system = known?.system ?: PackageDump.isSystem(dump),
            versionCode = PackageDump.versionCode(dump) ?: known?.versionCode ?: 0,
            signer = known?.signer,
            persistent = PackageDump.isPersistent(dump) || known?.persistent == true,
            tvInput = known?.tvInput ?: false,
            label = known?.label,
            installedByUs = known?.installedByUs ?: false,
        )
    }

    // A thumbnail at most every 5 s (every group change forces one); returns whether one was taken.
    private suspend fun thumbnail(run: Run, force: Boolean): Boolean {
        val now = clock.monotonicMs()
        if (!force && now - run.lastThumbnailTryAt < thumbnailEveryMs) return false
        run.lastThumbnailTryAt = now
        val png = try { prober.screenshot(run.session) } catch (e: AdbException) { null }
        if (png != null) { run.thumbnail = png; run.lastThumbnailOkAt = now }
        return png != null
    }

    // ---- ending a run ------------------------------------------------------------------------------

    private suspend fun finishWithSkips(run: Run, home: HomeOutcome?, from: Int) {
        for (i in from until run.plan.steps.size) if (i !in run.done && run.plan.steps[i] !in run.skipped) {
            run.skipped += run.plan.steps[i]; run.states[i] = NarrationState.SKIPPED
        }
        end(run, RunStatus.COMPLETED_WITH_SKIPS, home)
    }

    private suspend fun end(run: Run, status: RunStatus, home: HomeOutcome?) {
        journal.append(JournalRecord.End(run.plan.id, status))
        run.hold?.close(); run.hold = null
        runs.remove(run.plan.tvId, run)
        flow(run.plan.tvId).value = RunState.Finished(run.plan, status, run.skipped.toList(), home)
    }

    private suspend fun pause(run: Run, reason: PauseReason, error: AdbError?) {
        run.lastPause = reason
        runCatching { journal.append(JournalRecord.Paused(run.plan.id, run.at, reason)) }
        run.hold?.close(); run.hold = null
        flow(run.plan.tvId).value = RunState.Paused(run.plan, run.at, reason, error)
    }

    private fun publishRunning(run: Run, step: Int) {
        val lines = listOf(NarrationLine("Restore point saved", run.restorePoint)) +
            run.plan.steps.mapIndexed { i, s -> NarrationLine(s.narration, run.states[i]) }
        val total = run.plan.steps.size
        val percent = if (total == 0) 100 else (run.done.size * 100) / total
        flow(run.plan.tvId).value = RunState.Running(run.plan, step, lines, percent, run.thumbnail)
    }

    // ---- controls ----------------------------------------------------------------------------------

    override suspend fun confirmHome(tvId: TvId) { runs[tvId]?.decisions?.trySend(true) }
    override suspend fun rejectHome(tvId: TvId) { runs[tvId]?.decisions?.trySend(false) }
    override suspend fun stop(tvId: TvId) { runs[tvId]?.stopRequested = true }

    override suspend fun continueRun(tvId: TvId, pass: GatePass?) {
        val run = runs[tvId] ?: return
        if (flow(tvId).value !is RunState.Paused) return
        if (gate(run.plan, run.plan.steps.drop(run.at), pass) != null) return
        if (run.session.state.value !is ConnectionState.Connected) return
        run.stopRequested = false
        run.hold = run.session.hold()
        publishRunning(run, run.at)
        run.job = scope.launch {
            // A section left open by a lost connection: step R first, then this run is over.
            val open = run.plan.sections.firstOrNull { it.name in run.openSections }
            if (open != null) {
                if (rollbackSection(run, open, HomeOutcome.CONNECTION_LOST)) finishWithSkips(run, HomeOutcome.CONNECTION_LOST, run.at)
                return@launch
            }
            execute(run, prePlan = false)
        }
    }

    // The inverse of every done step, most recent first, journaled as a rollback (ARCHITECTURE 2.4).
    override suspend fun undoRun(tvId: TvId, pass: GatePass?) {
        val run = runs[tvId] ?: return
        if (flow(tvId).value !is RunState.Paused) return
        if (gate(run.plan, run.plan.steps, pass) != null) return
        if (run.session.state.value !is ConnectionState.Connected) return
        run.hold = run.session.hold()
        run.job = scope.launch {
            try {
                val open = run.plan.sections.firstOrNull { it.name in run.openSections }
                if (open != null && !rollbackSection(run, open, HomeOutcome.CONNECTION_LOST)) return@launch
                flow(tvId).value = RunState.RollingBack(run.plan, run.at)
                journal.append(JournalRecord.Rollback(run.plan.id, run.at))
                for (i in run.done.reversed()) {
                    val step = run.plan.steps[i]
                    val inverse = renderer.inverse(step.op, step.prior ?: Prior()) ?: continue
                    val rendered = try { renderer.render(inverse) } catch (e: RenderRefused) { continue }
                    flow(tvId).value = RunState.RollingBack(run.plan, i)
                    when (val r = perform(run, i, inverse, rendered, null)) {
                        is Outcome.Lost -> { pause(run, PauseReason.CONNECTION_LOST, r.error); return@launch }
                        else -> {}
                    }
                }
                end(run, RunStatus.ROLLED_BACK, run.home)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdbException) {
                pause(run, if (e.error.isLost()) PauseReason.CONNECTION_LOST else PauseReason.STEP_FAILED, e.error)
            }
        }
    }

    override suspend fun abandon(tvId: TvId) {
        val run = runs[tvId] ?: return
        if (flow(tvId).value !is RunState.Paused) return
        val open = run.plan.sections.firstOrNull { it.name in run.openSections }
        if (open == null) { end(run, RunStatus.ABANDONED, run.home); return }
        // An open section means the TV's own home screen may be off: step R runs before the run is
        // given up. Without the TV there is nothing to run, so the run stays paused until it is back
        // and resume or abandon runs step R then (R1).
        if (run.session.state.value !is ConnectionState.Connected) return
        run.hold = run.session.hold()
        run.job = scope.launch {
            if (rollbackSection(run, open, HomeOutcome.CONNECTION_LOST)) end(run, RunStatus.ABANDONED, run.home)
        }
    }

    // Safety only (R0: 6): find the open run, run step R for an open section, re-verify the
    // in-doubt step, land in Paused. Continue or undo is the user's decision afterwards.
    override suspend fun resume(tvId: TvId, session: TvSession) {
        val job = startLock.withLock {
            if (runs.containsKey(tvId)) return
            val planId = journal.openRuns(tvId).firstOrNull() ?: return
            val records = journal.read(planId)
            val plan = plans.load(tvId, planId)
            if (plan == null || plan.id != planId || plan.tvId != tvId || !rendersTheSame(plan)) {
                journal.append(JournalRecord.End(planId, RunStatus.ABANDONED))   // no trustworthy plan to resume from
                flow(tvId).value = RunState.Idle
                return
            }
            val run = Run(plan, session)
            Replay.apply(records, run.done, run.openSections, run.closedSections)
            run.lastPause = records.filterIsInstance<JournalRecord.Paused>().lastOrNull()?.reason
            run.home = if (run.closedSections.isNotEmpty() && records.filterIsInstance<JournalRecord.Section>().any { it.state == SectionState.ROLLED_BACK }) HomeOutcome.CONNECTION_LOST else null
            for (i in run.done) run.states[i] = NarrationState.DONE
            run.restorePoint = NarrationState.DONE
            run.snapshot = snapshots.load(tvId, plan.baseSnapshot) ?: snapshots.latest(tvId)
            run.profile = run.snapshot?.let { s -> (profiles?.resolve(s.facts) as? ProfileResolution.Exact)?.profile }
            runs[tvId] = run
            scope.launch { safety(run, Replay.inDoubt(records)) }
        }
        job.join()
    }

    private suspend fun safety(run: Run, inDoubt: Int?) {
        val plan = run.plan
        try {
            if (run.session.state.value !is ConnectionState.Connected) { pause(run, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed); return }
            run.hold = run.session.hold()
            var reason = if (inDoubt != null) PauseReason.IN_DOUBT else run.lastPause ?: PauseReason.IN_DOUBT
            val open = plan.sections.firstOrNull { it.name in run.openSections }
            if (open != null) {
                flow(plan.tvId).value = RunState.RollingBack(plan, open.from)
                if (!rollbackSection(run, open, HomeOutcome.CONNECTION_LOST)) return
                reason = PauseReason.CONNECTION_LOST   // the card: "We've put the old home screen back to be safe"
            }
            var at = (run.done.maxOrNull() ?: -1) + 1
            if (inDoubt != null) {
                val step = plan.steps[inDoubt]
                flow(plan.tvId).value = RunState.InDoubt(plan, inDoubt)
                val verified = step.rendered.verify?.let { v ->
                    val out = read(run.session, renderer.render(v))
                    renderer.check(step.op, out, step.prior) == VerifyResult.Ok
                } ?: false
                if (verified) {
                    journal.append(JournalRecord.Done(plan.id, inDoubt, 0, verified = true, ms = 0, resumed = true))
                    run.done += inDoubt; run.states[inDoubt] = NarrationState.DONE
                    at = inDoubt + 1
                } else {
                    at = inDoubt
                }
            }
            run.at = at
            pause(run, reason, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbException) {
            pause(run, if (e.error.isLost()) PauseReason.CONNECTION_LOST else PauseReason.STEP_FAILED, e.error)
        }
    }

    private fun AdbError.isLost(): Boolean = this is AdbError.Transport || this is AdbError.Auth

    companion object {
        val GATED = setOf(Privilege.PROFILE_STEP, Privilege.COMMUNITY, Privilege.TAKEOVER)
    }
}

// Step R's vocabulary (PRODUCT-SPEC 3.4): defuse both layers for this section's token, then the
// TV's own home screen back. Anything else in a plan file's rollback list is ignored.
internal object StepR {
    fun allows(op: Op, section: GuardedSection): Boolean = when (op) {
        is Op.ConfirmHome -> op.token == section.token
        is Op.KillWatchdog -> op.token == section.token
        is Op.Enable, is Op.SetHome, is Op.VerifyHome, Op.PressHome -> true
        else -> false
    }

    // The ops whose failure means the TV's own home screen is not back.
    fun mustSucceed(op: Op): Boolean = op is Op.Enable || op is Op.SetHome || op is Op.VerifyHome
}

// What a journal says about a run, for resume.
internal object Replay {
    fun apply(records: List<JournalRecord>, done: MutableSet<Int>, open: MutableSet<String>, closed: MutableSet<String>) {
        var undoing = false
        for (r in records) {
            when (r) {
                is JournalRecord.Done -> if (undoing) done -= r.i else if (r.i >= 0) done += r.i
                is JournalRecord.Rollback -> undoing = true
                is JournalRecord.Section -> when (r.state) {
                    SectionState.ARMED -> open += r.name
                    SectionState.CONFIRMED, SectionState.ROLLED_BACK -> { open -= r.name; closed += r.name }
                }
                else -> {}
            }
        }
    }

    // The step index of a Begin with no Done or Failed after it, if the journal ends that way.
    fun inDoubt(records: List<JournalRecord>): Int? {
        var pending: Int? = null
        var undoing = false
        for (r in records) {
            when (r) {
                is JournalRecord.Begin -> pending = if (undoing) null else r.i
                is JournalRecord.Done, is JournalRecord.Failed -> pending = null
                is JournalRecord.Rollback -> { undoing = true; pending = null }
                else -> {}
            }
        }
        return pending
    }
}

// The staleness check of 5.9: the priors of the remaining steps against a fresh snapshot.
internal object Staleness {
    fun writeTarget(op: Op): PackageName? = when (op) {
        is Op.Disable -> op.pkg
        is Op.DefaultState -> op.pkg
        is Op.Uninstall -> op.pkg
        else -> null
    }

    fun diff(steps: List<Step>, snapshot: Snapshot): List<String> {
        val out = mutableListOf<String>()
        for (s in steps) {
            val prior = s.prior ?: continue
            when (val op = s.op) {
                is Op.Disable -> packageDiff(op.pkg, prior, snapshot, out)
                is Op.Enable -> packageDiff(op.pkg, prior, snapshot, out)
                is Op.DefaultState -> packageDiff(op.pkg, prior, snapshot, out)
                is Op.Uninstall -> if (prior.present == true && op.pkg !in snapshot.packages) out += "${op.pkg.value}: was installed, now missing"
                is Op.Install -> presentDiff(op.pkg, prior, snapshot, out)
                is Op.InstallStream -> presentDiff(op.pkg, prior, snapshot, out)
                is Op.SettingsPut -> if (op.key in snapshot.settings) {
                    val now = snapshot.settings[op.key]
                    if (now != prior.settingValue) out += "${op.key.key}: was ${prior.settingValue ?: "unset"}, now ${now ?: "unset"}"
                }
                is Op.SetHome -> prior.home?.let { if (snapshot.home.resolved != it) out += "home: was ${it.flat}, now ${snapshot.home.resolved?.flat ?: "none"}" }
                else -> {}
            }
        }
        return out.distinct()
    }

    private fun packageDiff(pkg: PackageName, prior: Prior, snapshot: Snapshot, out: MutableList<String>) {
        val was = prior.packageState ?: return
        val now = snapshot.packages[pkg]?.enabled
        if (now == null) out += "${pkg.value}: was ${was.name}, now missing"
        else if (now != was) out += "${pkg.value}: was ${was.name}, now ${now.name}"
    }

    private fun presentDiff(pkg: PackageName, prior: Prior, snapshot: Snapshot, out: MutableList<String>) {
        val was = prior.present ?: return
        val now = pkg in snapshot.packages
        if (was != now) out += "${pkg.value}: was ${if (was) "installed" else "absent"}, now ${if (now) "installed" else "absent"}"
    }
}
