# RFCs from the dockrows workstream

## RFC-DOCK-1: the Setup plan should push the dock and the look (not applied, needs sign-off)

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
