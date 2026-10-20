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

## RFC-CARDS-1: module cards stay off until the person asks for a card (applied on cardsoptin; Fable APPROVE WITH FOLLOW-UPS, reviews/RF-CARDS-1.md)

What: the owner's first real Setup ended with the new home screen asking "New card from Retro ...
Keep hidden / Show card", because Retro was already on the TV and the config.json Setup pushes
(RFC-DOCK-1) leaves `module_cards` out, which the launcher reads as on (upstream main 1bbf0c9,
MainActivity: with it on, every installed app that declares a DOCK_CARD is discovered and asked
about; with it off, no discovery, no prompt, and the Settings long press does nothing). Cards are
optional and something to add after Setup, never forced. Change: (1) `Look.encode` always writes
`module_cards`, from the TV's cards choice (`Cards.of`, rule as revised by RF-CARDS-1 F1): the value
kept in HomePrefs (`tvs/<id>/home.txt`, new line `cards=true|false`, `HomeChoice.cards: Boolean? =
null`) wins; else the TV's current overlay, whoever wrote it, when it carries an explicit
`module_cards`; else the caller's default, Setup off and Manage on (what the launcher does on that
TV now, its built-in default, so a dock or theme change never takes a card away). A first Setup is therefore always off,
and so is a re-run on a TV whose earlier overlay left the key out; a TV whose cards were turned on
earlier keeps them. `SetupDeps` gains `homePrefs: HomePrefs? = null`;
Done keeps what the run pushed (dock, theme, cards) so Manage starts from it. M2 (HomeScreenMachine)
keeps the current value on every dock or theme change. (2) Cards come on only from Cards and
modules: `PlanRequest.AddModule` gains `config: BlobRef? = null, wallpaper: BlobRef? = null`
(frozen section 5.8, defaulted so every caller compiles); when the TV's home screen is ours and its
cards are off, the Modules add encodes the kept dock and theme with `module_cards` true and the
planner appends `configTransport.writePlanSteps(config, wallpaper)` after the install and before
Home, with ApplyTheme's priors, narration and failure policy (STOP_AND_ROLLBACK), so the plan, its
hash and Show commands carry the push and the launcher's restart is what makes the TV ask. A card
module already on the TV gets "Show its card" on M3 while cards are off: the same look as an
ApplyTheme plan, then the M3c mirror. Turning cards off again is not offered: with no card
module left nothing is discovered and nothing asks, and a later install of an app with a card is
the person's own choice. Affected: W3 (planner), W8 (flows, M3 screen), W1 (DECISIONS-W1 lists the
5.8 edit). Alternative without an interface change: a second ApplyTheme run after the add; not
chosen, because the person's one tap would be two plans and two journal entries, and a failed push
would leave a module installed with cards still off and no honest way to say so.

Review (RF-CARDS-1, Fable APPROVE WITH FOLLOW-UPS): F1 applied (the rule above, tests in
`aTvWithoutAKeptChoiceKeepsWhatItsOverlaySays` for both defaults), F2 applied (M3b says, while cards
are off, that adding the module turns them on), F5 applied (PRODUCT-SPEC 5.8 shows the new fields).
Follow-ups, open:
- F3: the add's look pushes STOP_AND_ROLLBACK, so a failed push pauses the run for the open-run
  card. "Undo what we did" there uninstalls the module but cannot put the earlier config.json back
  (its prior is the probed overlay, the 0.1 limit on files that existed), so cards stay on after
  that undo. Add a sentence to the card's undo question when the plan is an add with a look, or
  accept and document.
- F4: "our launcher is home" is `tv.launcher != null`, which misses a HOME changed by hand on the
  TV; the add's RestartHome then starts our launcher over the stock home for a moment (ApplyTheme
  has the same gap). A check in `cardsOnLook` that the latest snapshot's resolved HOME is the
  launcher would close it.
