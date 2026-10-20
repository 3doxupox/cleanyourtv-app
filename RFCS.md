# RFCs from the dockrows workstream

## RFC-DOCK-1: the Setup plan should push the dock and the look (applied; Fable APPROVE WITH FOLLOW-UPS, reviews/RF-DOCK-1.md)

What: `DefaultPlanner.setup` uses `Choices.dock` only for `PlanSummary.dock` (the names on S3 and the
check screen) and never uses `Choices.theme`; the Setup plan has no config.json step at all (the
emulator plan in `WhatWeFoundMatchesThePlanTest.emulatorCapture` is nine steps, none of them a
push). So the dock and look picked in the wizard, rows included, never reach the TV: the launcher
starts on its built-in dock (Netflix, Prime Video, YouTube, Hotstar), and only a later change on
Manage (M2, an ApplyTheme plan) writes config.json. S3 says "Home screen: Netflix, YouTube, ..."
and the check screen "with ... along the bottom", which is only true when the built-in dock
happens to match. `SetupMachine.showDone` already looks for the run's config.json push (D15, R5 F10)
and finds none. Why it is not fixed here: the planner is pure and takes blobs as `BlobRef`, and
`PlanRequest.Setup(resolution, choices, launcher, level)` is frozen (section 5.8), so the encoded
config cannot reach the Setup plan without an interface change. Smallest change: add
`val config: BlobRef? = null` (and `wallpaper: BlobRef? = null`) to `PlanRequest.Setup`, defaulted so
every caller compiles; `SetupMachine.replan` encodes `DockLayers.applyTo(theme?.doc ?: LauncherConfigDoc(), dock)`
into the BlobStore like `HomeScreenMachine.apply` does, and `DefaultPlanner.setup` appends
`configTransport.writePlanSteps(config, wallpaper)` after the takeover section (group "theme"), so
S3's commands list and the plan hash include it. Affected: W3 (planner), W8 (SetupMachine, Done
records the digest it already looks for), W1 (fakes). Alternative without an interface change: run
an ApplyTheme plan after Done, which the person would not see in S3's "Show commands"; not
recommended (plan honesty).

Applied (setupcfg, 2026-10-20, pending Fable review): `PlanRequest.Setup` gains `config: BlobRef? = null`
and `wallpaper: BlobRef? = null` after its four parameters (DECISIONS-W1 lists the edit).
`SetupMachine.replan` builds both with `Look.encode`, the helper `HomeScreenMachine.apply` now uses
too, whenever the switch is possible; `SetupDeps` gains `blobs` (and `encoder`, defaulted). In
`DefaultPlanner.setup` the transport's steps (group "theme") come after the launcher install and
before the guarded home screen switch, only when the switch is chosen and the launcher is on the TV
or installed by the plan; ApplyTheme and Setup share one private function for the steps, their
priors and narration. Two choices to review: (1) the steps sit before the section rather than after
it, so the check screen's "with ... along the bottom" is what the TV shows when the person is asked,
nothing is added to the watchdog's window, and a failed push happens while the TV's own home screen
is still in charge; (2) in Setup the pushes CONTINUE on failure (ApplyTheme keeps
STOP_AND_ROLLBACK): outside a section STOP_AND_ROLLBACK pauses the run, and the Setup stop card's
way back is undoing the whole run for a cosmetic step that Manage can send again. On a fresh or
replaced launcher both pushes record `present = false`, so Undo everything and the laptop restore
scripts delete them again; the laptop scripts also delete a wallpaper whose first record is that
removal when a later look pushed one.

## RFC-CURTAIN-1: the TV curtain (proposed, needs owner and Fable sign-off)

What: a bundled, pinned TV app (`tv.cleanyour.curtain`, new module `:tv:curtain`, signed with the
launcher key K1) that the Setup plan installs after the engine's restore point, cues once per
narration stage, and uninstalls as its last step, so the TV shows the approved Lantern animation
while the phone works. Full plan: `app-design/CURTAIN-PLAN.md`. Interface changes, each binding
under section 5: (1) `:core:model` 5.2: `enum class CurtainStage { SCAN, RESTORE_SAVED, OFF,
GROUP_DONE, INSTALL, INSTALLED, FINALE }` and `data class Op.CurtainCue(receiver: Component,
token: RunToken, stage: CurtainStage, count: Int, total: Int)`, rendered by `ShellRenderer` as one
fixed `am broadcast` argv (component, constant action, token, stage name, count, total; no free
text), `Privilege.PROFILE_STEP`, no verify, `inverse` null, `UndoOrder.target` null; JSON tag
`curtain_cue` in `OpJson` and `ChangeJson`. (2) `:core:planner` 5.8: `PlanRequest.Setup` gains
`curtain: LauncherArtifact? = null`; `Choices` gains `curtain: Boolean = true`; group id `curtain`;
every curtain step `CONTINUE`; `Uninstall(curtain)` also planned first by Leftovers and DebugOff
when the package is present. (3) `:core:trust`: `PinnedCurtain`, `interface CurtainPin { fun
bundled(): LauncherArtifact }`, `BundledCurtainPin`, `verifyCurtainPin` before `:android:platform`
`preBuild`. (4) `:core:engine`: a step with a blank narration is silent (not listed on the Working
screen, not counted in the percent). (5) `build-logic` `ModuleRules`: layer entry `:tv:curtain` at
10 with no edges in or out and an empty external allowlist; a size gate entry at 256 KB. (6)
`WizardDraft.curtain: Boolean = true` (growable, listed for completeness). Why: the approved design
needs a TV-side renderer fed by typed cues; the existing verbs cover install, start and uninstall,
only the cue is new. Affected: W1 (model, fakes, build-logic), W3 (ops, planner, engine), W5
(trust, bundled asset), W6 (ChangeJson, RestoreCommands branches), W8 (flows, Customise switch),
W9 (forge build script, e2e). Smallest alternative: no new op, cues as `PushAsset` of a one-line
file polled by the curtain; rejected because a push per stage costs a sync round trip, shows as a
sync line with a hash in Show commands, and leaves files to clean. Ordering: applied on `main` after
RFC-DOCK-1 (`setupcfg`) merges; the FINALE cue goes before whichever step starts the launcher first.
