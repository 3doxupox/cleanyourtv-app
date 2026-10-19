package tv.cleanyour.core.backup

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.ProbeKind
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.testing.fixtures.sha256Of

// The reference TV before and after a realistic set of plans: the Mi TV capture's debloat (with
// one package that started in the DEFAULT state), the launcher install and HOME takeover with the
// watchdog, two settings (one of them unset before), a launcher config push, the restore point
// files, a later "Put back" of one app, and the transient ops a journal also records.
object Scenario {
    val launcher = Samples.launcherComponent
    val stock = Samples.stockLauncher
    val defaultStatePkg = PackageName("com.google.android.feedback")
    val putBackPkg = PackageName("com.xiaomi.mimusic2")
    val disabledAfter: List<PackageName> = Fixtures.miTv().disabledAfter.map(::PackageName)
    private val setupPlan = PlanId("01SETUPPLAN000000000000001")
    private val restorePlan = PlanId("01RESTOREPT000000000000002")
    private val putBackPlan = PlanId("01PUTBACKPL000000000000003")
    private val token = RunToken("00000000000000aa")
    private const val T0 = Samples.NOW_MS + 60_000

    fun baseline(): Snapshot {
        val pkgs = Samples.miTvPackages(disabled = emptySet()).toMutableMap()
        pkgs[defaultStatePkg] = pkgs.getValue(defaultStatePkg).copy(enabled = EnabledState.DEFAULT)
        val draft = Samples.snapshot(packages = pkgs).copy(settings = mapOf(SettingKey.SCREENSAVER_ENABLED to "1", SettingKey.SCREENSAVER_COMPONENTS to null))
        return draft.copy(contentHash = SnapshotCodec.hash(draft))
    }

    fun tv(): Tv = Samples.tv()

    fun changes(): List<ChangeRecord> {
        val out = ArrayList<ChangeRecord>()
        var at = T0
        fun add(plan: PlanId, op: Op, prior: Prior?) { out += ChangeRecord(plan, out.count { it.planId == plan }, op, prior, at); at += 1_000 }
        val base = baseline()

        add(setupPlan, Op.Read(ProbeKind.PROPS), null)
        for (p in disabledAfter) add(setupPlan, Op.Disable(p), Prior(packageState = base.packages.getValue(p).enabled))
        add(setupPlan, Op.Mkdir(RemoteDir.TMP), Prior())
        val apk = BlobRef(sha256Of("launcher apk"), 260_000, "bundled:launcher.apk")
        add(setupPlan, Op.InstallStream(apk, apk.sha256, launcher.pkg, 1, Samples.zeroHash), Prior(present = false))
        add(setupPlan, Op.ArmHomeWatchdog(stock, 120, token), Prior())
        add(setupPlan, Op.SetHome(launcher), Prior(home = stock))
        add(setupPlan, Op.VerifyHome(launcher), null)
        add(setupPlan, Op.ConfirmHome(token), Prior())
        add(setupPlan, Op.KillWatchdog(token), Prior())
        add(setupPlan, Op.Disable(stock.pkg), Prior(packageState = EnabledState.ENABLED))
        add(setupPlan, Op.SettingsPut(SettingKey.SCREENSAVER_ENABLED, "0"), Prior(settingValue = "1"))
        add(setupPlan, Op.SettingsPut(SettingKey.SCREENSAVER_COMPONENTS, "com.gathin.tvhome/.ClockDream"), Prior(settingValue = null))
        add(setupPlan, Op.Mkdir(RemoteDir.OVERLAY), Prior())
        val config = BlobRef(sha256Of("{}"), 2, "mem:config")
        add(setupPlan, Op.PushAsset(config, RemotePath(RemoteDir.OVERLAY, listOf("config.json")), config.sha256), Prior(present = false))
        add(setupPlan, Op.RestartHome(launcher), Prior())
        add(setupPlan, Op.Settle(2), null)

        val rp = BlobRef(sha256Of("rp"), 2, "mem:rp")
        add(restorePlan, Op.Mkdir(RemoteDir.RESTORE), Prior())
        add(restorePlan, Op.PushAsset(rp, RemotePath(RemoteDir.RESTORE, listOf("restore-point.json")), rp.sha256), Prior(present = false))

        add(putBackPlan, Op.Enable(putBackPkg), Prior(packageState = EnabledState.DISABLED_USER))
        add(putBackPlan, Op.DebugOff, null)
        return out
    }

    // The packages that should read as switched off now: the debloat and the stock launcher,
    // without the one we put back.
    fun expectedOff(): List<PackageName> = (disabledAfter + stock.pkg).filter { it != putBackPkg }
}
