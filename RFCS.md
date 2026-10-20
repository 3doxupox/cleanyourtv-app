# RFCs from the dockrows workstream

## RFC-DOCK-1: the Setup plan should push the dock and the look (applied on setupcfg, pending Fable review)

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
