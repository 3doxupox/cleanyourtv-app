package tv.cleanyour.core.planner

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.InstallFlag
import tv.cleanyour.core.model.LauncherArtifact
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.Tier
import tv.cleanyour.core.ops.ConfigTransport
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.core.ops.RenderRefused
import tv.cleanyour.core.ops.Rendered
import tv.cleanyour.core.ops.UndoOrder
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.IdGen

// The planner (PRODUCT-SPEC 5.8): pure, one plan per request, every op rendered through the one
// renderer so the plan screen, the hash and the executor see the same lines. Protected packages
// never reach a step (Denylist, applied here and again by the executor with live facts).
//
// Idempotence: a request whose effect the snapshot already shows yields a plan with no steps.
// Refusals carry plain reasons for the UI. The HOME takeover (section 3.4) is one GuardedSection
// with its rollback (step R) rendered into the plan so the engine can run it from the data alone.
class DefaultPlanner(
    private val clock: Clock,
    private val idGen: IdGen,
    private val renderer: OpRenderer,
    private val configTransport: ConfigTransport,
    private val appVersion: String,
    private val launcher: Component = Component(PackageName("com.gathin.tvhome"), ".MainActivity"),
    private val confirmWindowMs: Long = 90_000L,
    private val watchdogSeconds: Int = 120,
    private val settleSeconds: Int = 15,
) : Planner {

    override fun plan(request: PlanRequest, snapshot: Snapshot, facts: FactsTable, journals: List<ChangeRecord>): PlanResult {
        val id = idGen.planId()   // first, so a plan's id does not depend on how many run tokens it drew
        val d = Draft(snapshot, facts, journals)
        val kind = when (request) {
            is PlanRequest.Setup -> { d.setup(request); PlanKind.SETUP }
            is PlanRequest.PutBack -> { d.putBack(request.pkg); PlanKind.PUT_BACK }
            is PlanRequest.SwitchOff -> { d.switchOff(request.pkgs); PlanKind.SWITCH_OFF }
            is PlanRequest.Leftovers -> { d.leftovers(request.expectedDisabled); PlanKind.LEFTOVERS }
            is PlanRequest.ApplyTheme -> { d.applyTheme(request.config, request.wallpaper); PlanKind.THEME }
            is PlanRequest.AddModule -> { d.addModule(request); PlanKind.ADD_MODULE }
            is PlanRequest.RemoveModule -> { d.removeModule(request.pkg); PlanKind.REMOVE_MODULE }
            is PlanRequest.UseStockHome -> { d.useStockHome(request.stock, null); PlanKind.USE_STOCK_HOME }
            is PlanRequest.UndoEverything -> { d.undoEverything(request.changes, request.removeLauncher); PlanKind.UNDO_EVERYTHING }
            PlanRequest.BeforeFactoryReset -> { d.beforeFactoryReset(); PlanKind.BEFORE_FACTORY_RESET }
            PlanRequest.DebugOff -> { d.debugOff(); PlanKind.DEBUG_OFF }
            is PlanRequest.WriteRestorePoint -> { d.writeRestorePoint(request.restorePointJson, request.restoreTxt); PlanKind.WRITE_RESTORE_POINT }
        }
        if (d.reasons.isNotEmpty()) return PlanResult.Refused(d.reasons.toList())
        val steps = d.steps.toList()
        val plan = Plan(
            id = id, tvId = snapshot.tvId, kind = kind, baseSnapshot = snapshot.contentHash,
            steps = steps, sections = d.sections.toList(), summary = d.summary(),
            hash = renderer.planHash(steps.map { it.rendered }), createdAtMs = clock.nowMs(), appVersion = appVersion,
        )
        return PlanResult.Ok(plan)
    }

    private inner class Draft(val snapshot: Snapshot, val facts: FactsTable, val journals: List<ChangeRecord>) {
        val steps = mutableListOf<Step>()
        val sections = mutableListOf<GuardedSection>()
        val reasons = mutableListOf<String>()
        var groups = mutableListOf<GroupSummary>()
        var keepNames = listOf<String>()
        var dockNames = listOf<String>()
        var homeScreenPossible = false
        var replacesLauncher = false

        fun summary() = PlanSummary(groups.toList(), keepNames, dockNames, homeScreenPossible, replacesLauncher)

        fun name(pkg: PackageName): String = facts.lookup(pkg)?.friendlyName ?: snapshot.packages[pkg]?.label ?: pkg.value
        fun description(pkg: PackageName): String = facts.lookup(pkg)?.description ?: ""
        fun state(pkg: PackageName): PackageState? = snapshot.packages[pkg]
        fun isOff(pkg: PackageName): Boolean = state(pkg)?.enabled.let { it == EnabledState.DISABLED_USER || it == EnabledState.DISABLED }
        fun installed(pkg: PackageName): Boolean = pkg in snapshot.packages

        // The one place a step is made: render, or record the refusal as a reason.
        fun step(op: Op, prior: Prior?, group: String?, narration: String, policy: FailurePolicy): Int {
            val rendered: Rendered = try {
                renderer.render(op)
            } catch (e: RenderRefused) {
                reasons += "cannot render ${op::class.simpleName}: ${e.reason}"
                return -1
            }
            steps += Step(steps.size, op, rendered, prior, group, narration, policy)
            return steps.size - 1
        }

        fun refuse(reason: String) { reasons += reason }
        fun planned(op: Op): Boolean = steps.any { it.op == op }

        // Denylist plus the two planner-only rules: the TV's own home screens and the setup wizard
        // are only ever switched off inside the home screen switch (section 3.4).
        fun protectedReason(pkg: PackageName, profile: Profile?, stockLaunchers: List<Component>): String? {
            Denylist.reason(pkg, state(pkg), profile, snapshot)?.let { return it }
            if (stockLaunchers.any { it.pkg == pkg } || isHomeScreen(pkg)) return REASON_HOME_SCREEN
            if (pkg.value in Denylist.setupWizards || profile?.setupWraith == pkg) return REASON_WIZARD_SECTION_ONLY
            return null
        }

        // The TV's current HOME, whatever it is (R5-W4 O5: PatchWall on an unknown Xiaomi), and every
        // other HOME claimant: switched off only inside the home screen switch, never by a plain
        // Setup, SwitchOff or Leftovers (PLAN 6.5: the current HOME resolver is protected).
        fun isHomeScreen(pkg: PackageName): Boolean =
            snapshot.home.resolved?.pkg == pkg || snapshot.home.claimants.any { it.component.pkg == pkg }

        fun disable(pkg: PackageName, group: String?, policy: FailurePolicy = FailurePolicy.SKIP_IF_REFUSED, narration: String = "Switching off ${name(pkg)}") =
            step(Op.Disable(pkg), Prior(packageState = state(pkg)?.enabled), group, narration, policy)

        fun enable(pkg: PackageName, group: String?, narration: String = "Switching ${name(pkg)} back on", policy: FailurePolicy = FailurePolicy.STOP_AND_ROLLBACK) =
            step(Op.Enable(pkg), Prior(packageState = state(pkg)?.enabled), group, narration, policy)

        // ---- Setup -------------------------------------------------------------------------------

        fun setup(req: PlanRequest.Setup) {
            val profile = (req.resolution as? ProfileResolution.Exact)?.profile
            val packageGroups: List<PackageGroup> = when (val r = req.resolution) {
                is ProfileResolution.Exact -> r.profile.groups
                is ProfileResolution.BrandSureSet -> r.sureSet
                is ProfileResolution.None -> emptyList()
            }
            val stockLaunchers: List<Component> = when (val r = req.resolution) {
                is ProfileResolution.Exact -> r.profile.stockLaunchers
                is ProfileResolution.BrandSureSet -> r.stockLaunchers
                is ProfileResolution.None -> r.stockLaunchers
            }
            val choices = req.choices
            val covered = mutableSetOf<PackageName>()
            var disabled = 0

            for (group in packageGroups) {
                val rows = mutableListOf<RowSummary>()
                for (entry in group.packages) {
                    val pkg = entry.name
                    if (!installed(pkg) || pkg in covered) continue
                    covered += pkg
                    val why = protectedReason(pkg, profile, stockLaunchers)
                    val wanted = when (group.default) {
                        GroupDefault.DISABLE -> pkg !in choices.kept
                        GroupDefault.ASK, GroupDefault.KEEP -> pkg in choices.extraOff && pkg !in choices.kept
                    }
                    val off = why == null && wanted
                    rows += RowSummary(pkg, name(pkg), description(pkg), off, why != null, why)
                    if (off && !isOff(pkg)) { disable(pkg, group.id); disabled++ }
                }
                groups += GroupSummary(group.id, group.title, rows.count { it.off }, rows.size, rows)
            }
            // Custom: packages switched off outside the groups.
            val custom = mutableListOf<RowSummary>()
            for (pkg in choices.extraOff.sortedBy { it.value }) {
                if (pkg in covered || !installed(pkg) || pkg in choices.kept) continue
                val why = protectedReason(pkg, profile, stockLaunchers)
                if (why != null) { refuse("${name(pkg)} cannot be switched off: $why"); continue }
                custom += RowSummary(pkg, name(pkg), description(pkg), off = true, protected = false, whyProtected = null)
                if (!isOff(pkg)) { disable(pkg, GROUP_CUSTOM); disabled++ }
            }
            if (custom.isNotEmpty()) groups += GroupSummary(GROUP_CUSTOM, "Also switched off", custom.size, custom.size, custom)
            keepNames = choices.kept.filter { installed(it) }.map { name(it) }.sorted()
            dockNames = choices.dock.orEmpty().map { name(it) }

            // The settle sits between the debloat and the launcher steps, so the TV has caught up with
            // the switched-off apps before anything is installed or HOME changes.
            val launcherNeeded = choices.installLauncher && !pinInstalled(req.launcher)
            val needsHome = choices.takeover && snapshot.home.resolved != launcher
            if (disabled > 0 && (launcherNeeded || needsHome)) {
                step(Op.Settle(settleSeconds), null, GROUP_LAUNCHER, "Giving the TV $settleSeconds seconds to settle", FailurePolicy.CONTINUE)
            }
            val refusedBefore = reasons.size
            val launcherSteps = if (launcherNeeded) launcherInstall(req.launcher, choices.replaceLauncherSigner, stockLaunchers) else false
            if (reasons.size > refusedBefore) return   // the launcher reason says it all; no second reason about the switch
            if (choices.takeover) takeover(req.launcher, profile, stockLaunchers, launcherSteps)
        }

        fun pinInstalled(artifact: LauncherArtifact): Boolean {
            val current = state(artifact.pkg) ?: return false
            return current.versionCode == artifact.versionCode && current.signer == artifact.signer
        }

        // D39 ladder: streaming install when the TV advertises `cmd`, else push to tmp and pm install.
        // Returns true when install steps were added (false when the pin is already installed).
        fun launcherInstall(artifact: LauncherArtifact, replaceSigner: Boolean, stockLaunchers: List<Component>): Boolean {
            if (artifact.pkg != launcher.pkg) { refuse("the launcher artifact is ${artifact.pkg.value}, not ${launcher.pkg.value}"); return false }
            val current = state(artifact.pkg)
            val present = current != null
            if (current != null && current.signer != null && current.signer != artifact.signer) {
                if (!replaceSigner) { refuse("the home screen app on the TV was installed with a different key; replacing it needs consent (D36)"); return false }
                val homeNow = snapshot.home.resolved
                if (homeNow == null || stockLaunchers.none { it.pkg == homeNow.pkg }) { refuse("the home screen app can only be replaced while the TV's own home screen is in charge (D36)"); return false }
                replacesLauncher = true
                step(Op.Uninstall(artifact.pkg), Prior(present = true), GROUP_LAUNCHER,
                    "Replace the home screen app (its card answers on the TV are reset)", FailurePolicy.STOP_AND_ROLLBACK)
            } else if (current != null && current.versionCode == artifact.versionCode && current.signer == artifact.signer) {
                return false   // the pin is already installed
            }
            installApk(artifact.blob, artifact.sha256, artifact.pkg, artifact.versionCode, artifact.signer,
                present = present && !replacesLauncher, group = GROUP_LAUNCHER, narration = "Installing the new home screen")
            return true
        }

        fun installApk(blob: BlobRef, sha256: Sha256, pkg: PackageName, versionCode: Long, signer: Sha256, present: Boolean, group: String, narration: String) {
            val prior = Prior(present = present)
            if ("cmd" in snapshot.facts.adbFeatures) {
                step(Op.InstallStream(blob, sha256, pkg, versionCode, signer), prior, group, narration, FailurePolicy.CONTINUE)
            } else {
                val apk = RemotePath(RemoteDir.TMP, listOf(sha256.hex.take(APK_NAME_HEX) + ".apk"))   // a FileName is at most 64 characters
                step(Op.Mkdir(RemoteDir.TMP), null, group, "Preparing the TV", FailurePolicy.CONTINUE)
                step(Op.PushAsset(blob, apk, sha256), Prior(present = false), group, "Copying the app to the TV", FailurePolicy.CONTINUE)
                val flags = if (present) setOf(InstallFlag.REPLACE) else emptySet()
                step(Op.Install(apk, sha256, pkg, versionCode, signer, flags), prior, group, narration, FailurePolicy.CONTINUE)
                step(Op.RemoveFile(apk), null, group, "Tidying up", FailurePolicy.CONTINUE)
            }
        }

        // Section 3.4: the guarded HOME takeover.
        fun takeover(artifact: LauncherArtifact, profile: Profile?, stockLaunchers: List<Component>, launcherInstalledByPlan: Boolean) {
            if (snapshot.home.resolved == launcher) { homeScreenPossible = true; return }   // already ours
            if (snapshot.facts.installRefusal) { refuse("this TV refuses app installs, so the home screen switch is not possible"); return }
            val current = state(launcher.pkg)
            val installedAndVerified = current != null && current.signer == artifact.signer
            if (!launcherInstalledByPlan && !installedAndVerified) { refuse("the new home screen is not installed and the plan does not install it"); return }
            if (stockLaunchers.isEmpty()) { refuse("no known home screen for this TV model, so the switch is not possible"); return }
            // Matched by component first, then by package: only the package of each allowlisted stock
            // launcher is verified on a real TV for every profile, the class is not. Either way the
            // component used for SetHome in step R is the claimant the TV itself reported.
            val claimants = snapshot.home.claimants.map { it.component }
            val resolved = snapshot.home.resolved
            fun stockPkg(c: Component) = stockLaunchers.any { it.pkg == c.pkg }
            val stock = claimants.firstOrNull { it == resolved && it in stockLaunchers }
                ?: claimants.firstOrNull { it in stockLaunchers }
                ?: claimants.firstOrNull { it == resolved && stockPkg(it) }
                ?: claimants.firstOrNull { stockPkg(it) }
            if (stock == null) { refuse("the TV's home screen is not one of the known ones for this model (issue #85)"); return }
            val wizard = profile?.setupWraith?.takeIf { Denylist.isWizardAllowed(it, profile) && installed(it) && Denylist.reason(it, snapshot, profile) == null }
            // D25: the stock launcher is disabled unless the profile keeps it enabled (quirk or never_disable).
            val keepStockEnabled = profile != null && (Quirk.STOCK_LAUNCHER_KEEP_ENABLED in profile.quirks || Denylist.reason(stock.pkg, snapshot, profile) != null)
            val token = idGen.runToken()

            step(Op.StartActivity(launcher, wait = true), null, GROUP_HOME, "Starting the new home screen", FailurePolicy.CONTINUE)
            step(Op.Mkdir(RemoteDir.TMP), null, GROUP_HOME, "Preparing the safety timer", FailurePolicy.CONTINUE)
            val from = step(Op.ArmHomeWatchdog(stock, watchdogSeconds, token), null, GROUP_HOME, "Arming the safety timer", FailurePolicy.STOP_AND_ROLLBACK)
            if (!keepStockEnabled) disable(stock.pkg, GROUP_HOME, FailurePolicy.STOP_AND_ROLLBACK, "Switching off the TV's own home screen")
            if (wizard != null) disable(wizard, GROUP_HOME, FailurePolicy.STOP_AND_ROLLBACK, NARRATION_WIZARD)
            step(Op.SetHome(launcher), Prior(home = snapshot.home.resolved), GROUP_HOME, "Making the new home screen the default", FailurePolicy.STOP_AND_ROLLBACK)
            val to = step(Op.VerifyHome(launcher), null, GROUP_HOME, "Checking the home screen", FailurePolicy.STOP_AND_ROLLBACK)
            step(Op.ConfirmHome(token), null, GROUP_HOME, "Confirming the new home screen", FailurePolicy.CONTINUE)
            step(Op.KillWatchdog(token), null, GROUP_HOME, "Disarming the safety timer", FailurePolicy.CONTINUE)
            if (from < 0 || to < 0) return

            // Step R (section 3.4): defuse both layers first, then the TV's own home screen back.
            val rollback: List<Op> = listOfNotNull(
                Op.ConfirmHome(token), Op.KillWatchdog(token),
                if (keepStockEnabled) null else Op.Enable(stock.pkg), wizard?.let { Op.Enable(it) },
                Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome,
            )
            // Focus on any of these after the HOME key means the takeover did not hold: the TV's
            // own home screens, the setup wizard, the resolver (package android) and FallbackHome.
            val rollbackIfFocus: Set<PackageName> = (
                stockLaunchers.map { it.pkg } +
                    claimants.filter { it != launcher }.map { it.pkg } +
                    (Denylist.setupWizards + Denylist.FRAMEWORK).mapNotNull { runCatching { PackageName(it) }.getOrNull() } +
                    PackageName("com.android.settings")
                ).toSet()
            sections += GuardedSection(SECTION_HOME, from, to, token, confirmWindowMs, launcher, rollbackIfFocus, rollback)
            homeScreenPossible = true
        }

        // ---- Manage ------------------------------------------------------------------------------

        fun putBack(pkg: PackageName) {
            if (!installed(pkg)) { refuse("${pkg.value} is not on this TV"); return }
            if (!isOff(pkg)) return   // already on
            val change = journals.filter { it.op is Op.Disable && (it.op as Op.Disable).pkg == pkg }.maxByOrNull { it.atMs }
            val op = change?.let { renderer.inverse(it.op, it.prior ?: Prior()) } ?: Op.Enable(pkg)
            step(op, Prior(packageState = state(pkg)?.enabled), GROUP_PUT_BACK, "Switching ${name(pkg)} back on", FailurePolicy.STOP_AND_ROLLBACK)
        }

        fun switchOff(pkgs: Set<PackageName>) {
            val rows = mutableListOf<RowSummary>()
            for (pkg in pkgs.sortedBy { it.value }) {
                if (!installed(pkg)) { refuse("${pkg.value} is not on this TV"); continue }
                val why = Denylist.reason(pkg, snapshot, null) ?: if (isHomeScreen(pkg)) REASON_HOME_SCREEN else null
                if (why != null) { refuse("${name(pkg)} cannot be switched off: $why"); continue }
                rows += RowSummary(pkg, name(pkg), description(pkg), off = true, protected = false, whyProtected = null)
                if (!isOff(pkg)) disable(pkg, GROUP_SWITCH_OFF)
            }
            if (rows.isNotEmpty()) groups += GroupSummary(GROUP_SWITCH_OFF, "Switched off", rows.size, rows.size, rows)
        }

        fun leftovers(expectedDisabled: Set<PackageName>) {
            for (pkg in expectedDisabled.sortedBy { it.value }) {
                if (!installed(pkg) || isOff(pkg)) continue
                // After a TV update the stock launcher may be enabled and HOME again (R1): a leftover
                // that is now a home screen is never switched off outside the home screen switch.
                val why = Denylist.reason(pkg, snapshot, null) ?: if (isHomeScreen(pkg)) REASON_HOME_SCREEN else null
                if (why != null) { refuse("${name(pkg)} cannot be switched off: $why"); continue }
                disable(pkg, GROUP_LEFTOVERS)
            }
        }

        fun applyTheme(config: BlobRef, wallpaper: BlobRef?) {
            if (!installed(launcher.pkg)) { refuse("the new home screen is not installed"); return }
            val overlayPrior = Prior(present = snapshot.launcherOverlay != null, overlay = snapshot.launcherOverlay)
            for (op in configTransport.writePlanSteps(config, wallpaper)) {
                val (prior, narration, policy) = when (op) {
                    is Op.PushAsset -> Triple(if (op.src == config) overlayPrior else Prior(present = null), "Sending the look to the TV", FailurePolicy.STOP_AND_ROLLBACK)
                    is Op.RestartHome -> Triple(null, "Restarting the home screen", FailurePolicy.CONTINUE)
                    is Op.RemoveFile -> Triple(Prior(present = null), "Clearing the old wallpaper", FailurePolicy.CONTINUE)
                    else -> Triple(null, "Preparing the home screen", FailurePolicy.CONTINUE)
                }
                step(op, prior, GROUP_THEME, narration, policy)
            }
        }

        fun addModule(req: PlanRequest.AddModule) {
            val module = req.module; val version = req.version; val apk = req.apk
            if (module.tier != Tier.VERIFIED && module.tier != Tier.COMMUNITY) { refuse("${module.name} is ${module.tier.name.lowercase()} in the index"); return }
            if (apk.sha256 != version.artifact.sha256 || apk.size != version.artifact.size) { refuse("the downloaded file does not match the index entry"); return }
            if (version.minSdk > snapshot.facts.sdk) { refuse("${module.name} needs Android ${version.minSdk}+ and this TV has ${snapshot.facts.sdk}") ; return }
            if (version.abis.isNotEmpty() && version.abis.none { it in snapshot.facts.abis }) { refuse("${module.name} has no build for this TV's processor"); return }
            if (snapshot.facts.installRefusal) { refuse("this TV refuses app installs"); return }
            val current = state(module.pkg)
            if (current != null && current.signer != null && current.signer != version.artifact.signer) { refuse("${module.name} is already on the TV, installed with another key"); return }
            if (current != null && current.versionCode >= version.versionCode) return   // already there
            // postInstall is parsed and ignored in 0.1 (R0: no VERIFIED module exists yet).
            installApk(apk, version.artifact.sha256, module.pkg, version.versionCode, version.artifact.signer,
                present = current != null, group = GROUP_MODULE, narration = "Adding ${module.name} to the TV")
            step(Op.PressHome, null, GROUP_MODULE, "Showing the home screen", FailurePolicy.CONTINUE)
        }

        fun removeModule(pkg: PackageName) {
            val current = state(pkg) ?: return   // already gone
            if (current.system) { refuse("${name(pkg)} is part of the TV's system and cannot be removed"); return }
            val ours = current.installedByUs || journals.any { (it.op as? Op.Install)?.pkg == pkg || (it.op as? Op.InstallStream)?.pkg == pkg }
            if (!ours) { refuse("${name(pkg)} was not installed by this app"); return }
            if (pkg == launcher.pkg && snapshot.home.resolved == launcher) { refuse("use the TV's own home screen first, then remove the new one"); return }
            step(Op.Uninstall(pkg), Prior(present = true), GROUP_MODULE, "Removing ${name(pkg)} from the TV", FailurePolicy.STOP_AND_ROLLBACK)
        }

        // Also the core of UndoEverything and BeforeFactoryReset: the TV's own home screen back.
        fun useStockHome(stock: Component, wizardFromJournal: PackageName?) {
            if (stock == launcher) { refuse("that is the new home screen, not the TV's own"); return }
            val known = snapshot.home.claimants.any { it.component == stock } ||
                journals.any { it.op is Op.SetHome && it.prior?.home == stock }
            if (!known) { refuse("${stock.flat} is not a home screen this TV had"); return }
            if (isOff(stock.pkg) && !planned(Op.Enable(stock.pkg))) enable(stock.pkg, GROUP_HOME, "Switching the TV's own home screen back on")
            val wizard = wizardFromJournal ?: journals.firstOrNull { it.op is Op.Disable && (it.op as Op.Disable).pkg.value in Denylist.setupWizards }?.let { (it.op as Op.Disable).pkg }
            if (wizard != null && installed(wizard) && isOff(wizard) && !planned(Op.Enable(wizard))) enable(wizard, GROUP_HOME, "Switching the setup wizard back on")
            if (snapshot.home.resolved != stock) {
                step(Op.SetHome(stock), Prior(home = snapshot.home.resolved), GROUP_HOME, "Making the TV's own home screen the default", FailurePolicy.STOP_AND_ROLLBACK)
                step(Op.VerifyHome(stock), null, GROUP_HOME, "Checking the home screen", FailurePolicy.STOP_AND_ROLLBACK)
                step(Op.PressHome, null, GROUP_HOME, "Showing the home screen", FailurePolicy.CONTINUE)
            }
        }

        // Computed from the change records (every change this app made), never from "enable
        // everything disabled". UndoOrder (shared with the laptop restore scripts) picks the
        // earliest record of each item, which holds the state before this app touched it, and
        // orders them: package states, settings, the home screen, our apps, our files.
        fun undoEverything(changes: List<ChangeRecord>, removeLauncher: Boolean) {
            val order = UndoOrder.undoOrder(changes)
            val originalHome: Component? = order.firstOrNull { it.op is Op.SetHome }?.prior?.home
            val ourApps = order.filter { UndoOrder.target(it.op) is UndoOrder.Target.App && it.prior?.present == false }
                .mapNotNull { packageOf(it.op) }.toSet()
            var clearedLook = false
            for (c in order) {
                when (val t = UndoOrder.target(c.op)) {
                    is UndoOrder.Target.PackageEnabled -> {
                        val current = state(t.pkg) ?: continue
                        val prior = c.prior ?: continue
                        val original = prior.packageState ?: continue
                        if (current.enabled == original) continue
                        val inverse = renderer.inverse(c.op, prior) ?: continue
                        step(inverse, Prior(packageState = current.enabled), GROUP_UNDO, undoNarration(inverse, t.pkg), FailurePolicy.CONTINUE)
                    }
                    is UndoOrder.Target.Setting -> {
                        val original = c.prior?.settingValue
                        val current = snapshot.settings[t.key]
                        if (t.key !in snapshot.settings || current == original) continue
                        if (t.key == SettingKey.ADB_ENABLED || t.key == SettingKey.ADB_WIFI_ENABLED) continue   // never switched back on by the app
                        step(Op.SettingsPut(t.key, original), Prior(settingValue = current), GROUP_UNDO, "Putting back the TV's ${t.key.key.replace('_', ' ')} setting", FailurePolicy.CONTINUE)
                    }
                    UndoOrder.Target.Home -> if (originalHome != null && originalHome != launcher) useStockHome(originalHome, null)
                    is UndoOrder.Target.App -> {
                        val pkg = t.pkg
                        if (pkg !in ourApps || !installed(pkg)) continue
                        if (pkg == launcher.pkg && !removeLauncher) continue
                        if (pkg == launcher.pkg && snapshot.home.resolved == launcher && (originalHome == null || originalHome == launcher)) {
                            refuse("the new home screen is still in charge; the TV's own home screen must come back first"); continue
                        }
                        step(Op.Uninstall(pkg), Prior(present = true), GROUP_UNDO, "Removing ${name(pkg)} from the TV", FailurePolicy.CONTINUE)
                    }
                    is UndoOrder.Target.File -> {
                        // Only files in the launcher's folder that did not exist before us; a file that
                        // existed cannot be rebuilt in 0.1, and nothing is left to tidy when the launcher goes.
                        val launcherStays = installed(launcher.pkg) && !(removeLauncher && launcher.pkg in ourApps)
                        if (!launcherStays || t.path.dir != RemoteDir.OVERLAY || c.op !is Op.PushAsset || c.prior?.present != false) continue
                        step(Op.RemoveFile(t.path), null, GROUP_UNDO, "Clearing the look", FailurePolicy.CONTINUE)
                        clearedLook = true
                    }
                    null -> {}
                }
            }
            if (clearedLook) step(Op.RestartHome(launcher), null, GROUP_UNDO, "Restarting the home screen", FailurePolicy.CONTINUE)
        }

        fun beforeFactoryReset() {
            val originalHome = journals.filter { it.op is Op.SetHome }.minByOrNull { it.atMs }?.prior?.home
            val wizard = journals.firstOrNull { it.op is Op.Disable && (it.op as Op.Disable).pkg.value in Denylist.setupWizards }?.let { (it.op as Op.Disable).pkg }
            if (originalHome != null && originalHome != launcher) {
                useStockHome(originalHome, wizard)
            } else if (wizard != null && installed(wizard) && isOff(wizard)) {
                enable(wizard, GROUP_HOME, "Switching the setup wizard back on")
            }
        }

        fun debugOff() {
            if (snapshot.facts.sdk >= 30) {
                step(Op.SettingsPut(SettingKey.ADB_WIFI_ENABLED, "0"), Prior(settingValue = snapshot.settings[SettingKey.ADB_WIFI_ENABLED]),
                    GROUP_SESSION_END, "Switching wireless debugging off", FailurePolicy.CONTINUE)
            }
            step(Op.DebugOff, Prior(settingValue = snapshot.settings[SettingKey.ADB_ENABLED]), GROUP_SESSION_END, "Switching debugging off", FailurePolicy.CONTINUE)
        }

        fun writeRestorePoint(json: BlobRef, txt: BlobRef) {
            step(Op.Mkdir(RemoteDir.RESTORE), null, GROUP_RESTORE, "Preparing the restore point folder", FailurePolicy.CONTINUE)
            step(Op.PushAsset(json, RemotePath(RemoteDir.RESTORE, listOf("restore-point.json")), json.sha256), Prior(present = null), GROUP_RESTORE, "Saving the restore point on the TV", FailurePolicy.CONTINUE)
            step(Op.PushAsset(txt, RemotePath(RemoteDir.RESTORE, listOf("restore.txt")), txt.sha256), Prior(present = null), GROUP_RESTORE, "Saving the restore list on the TV", FailurePolicy.CONTINUE)
        }

        private fun undoNarration(op: Op, pkg: PackageName) = when (op) {
            is Op.Enable, is Op.DefaultState -> "Switching ${name(pkg)} back on"
            else -> "Switching ${name(pkg)} back off"
        }
    }

    companion object {
        const val SECTION_HOME = "home_takeover"
        const val APK_NAME_HEX = 40
        const val GROUP_CUSTOM = "custom"
        const val GROUP_LAUNCHER = "launcher"
        const val GROUP_HOME = "home"
        const val GROUP_PUT_BACK = "put_back"
        const val GROUP_SWITCH_OFF = "switch_off"
        const val GROUP_LEFTOVERS = "leftovers"
        const val GROUP_THEME = "theme"
        const val GROUP_MODULE = "module"
        const val GROUP_UNDO = "undo"
        const val GROUP_SESSION_END = "session_end"
        const val GROUP_RESTORE = "restore"
        const val REASON_HOME_SCREEN = "it is the TV's own home screen; only the home screen switch changes it"
        const val REASON_WIZARD_SECTION_ONLY = "the first-run setup wizard is only switched off as part of the home screen switch"
        const val NARRATION_WIZARD = "Switch off the first-run setup wizard so the new home screen can take over"

        fun packageOf(op: Op): PackageName? = when (op) {
            is Op.Disable -> op.pkg
            is Op.Enable -> op.pkg
            is Op.DefaultState -> op.pkg
            is Op.Uninstall -> op.pkg
            is Op.Install -> op.pkg
            is Op.InstallStream -> op.pkg
            else -> null
        }
    }
}
