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

## RFC-CURTAIN-1: the TV curtain (applied on curtain; APPROVE WITH FOLLOW-UPS, reviews/RF-CURTAIN-1.md)

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

Applied (curtain, 2026-10-20, owner answers to CURTAIN-PLAN section 8): (1) the TV says the phone's
words during cleaning ("Adverts: 2 of 4"), so `Op.CurtainCue` carries three more fields after the
five of the plan, all defaulted: `group: CurtainGroup = NONE`, `groupCount: Int = 0`,
`groupTotal: Int = 0`, with `enum class CurtainGroup { NONE, ADVERTS, TRACKING, NOBODY_ASKED,
BRAND_EXTRAS, SHOP_DEMO, OTHER }` in `:core:model`. The cue line gains three constant extras
(`group` as a string re-checked against `^[A-Z_]{1,16}$`, `gcount` and `gtotal` as ints 0 to 999);
`count` and `total` stay the switch-offs over the whole plan, for the percent. The words for each
group live in the curtain APK; the brand in "{brand} extras" is the TV's own `Build.BRAND`, read on
the TV. (2) The switch "Show the progress on the TV" is in Custom only, default on. (3) Silent. (4)
Signed with the launcher key K1. One tightening beyond the plan: the renderer refuses a cue whose
receiver package is not `tv.cleanyour.curtain`, so a cue can never be aimed at another app.

Two choices the plan left open, for review: (a) `DebugOff` does NOT gain the leftover uninstall of
section 4 (b). An `Uninstall` is a PROFILE_STEP, so it would make the Finish plan need a credential
pass; a cancelled prompt would then leave debugging on (D7). A leftover is still finished on the TV
by the curtain itself (four minutes, Back, or covered), removed by Leftovers, and replaced or reused
by the next Setup. (b) The probe reports no signer for any package (`PackageState.signer` is null),
so "the pin is already installed with our signer" is read as: the package is present, its
versionCode is the pin's, and no other signer is known; a package under our name with a known other
signer gets no curtain steps and is never uninstalled. The planner takes the pinned signer as
`curtainSigner` (AppGraph passes `PinnedCurtain.SIGNER_SHA256`). A Setup with no steps shows no
curtain.
(c) The Customise switch needs an intent: `SetupIntent` (frozen, 5.12) gains
`ShowOnTv(on: Boolean)`; `SetupDeps` gains `curtainPin: CurtainPin? = null`. The switch shows in
Custom only; replan hands the pin's artifact in when the level is Simple or the draft's `curtain` is
on, and plans without it when the asset does not match its pin.

Review (RF-CURTAIN-1, APPROVE WITH FOLLOW-UPS): F1 applied (a silent step never forces a
thumbnail; the engine compares a voiced step's group with the last voiced step's, test
`silentStepsForceNoThumbnail`). F2 applied: "ours" is the journal rule of `removeModule` (installed
by us, or an install of the curtain in this TV's change records), replacing (b) above; a curtain
package without such a record gets no Setup steps and no Leftovers step
(`onlyACurtainWeInstalledIsTouched`). F3 main merged. F5: SECURITY 3.2 has the `curtain_cue` row,
PRODUCT-SPEC 5.8 and DECISIONS-W1 list `PlanRequest.Setup.curtain` and `Choices.curtain`. F6 in part:
the cue line ends with `--receiver-foreground` (goldens 53 to 59); the lead reads the cue steps' ms
from the Mi TV journal. F7 applied: CueBus keeps early cues for any curtain that attaches, only
those of the newest run's token. F11 applied: the key frames go to build/curtain-frames, and to
src/test/frames only with -PcurtainFrames. Follow-ups, open:
- F6 rest: if Mi TV cue steps come near seconds, give cues a short timeout and count a cue's
  timeout as Failed (CONTINUE), not Lost.
- F8 (W8): after a rolled-back section the final uninstall is skipped; SetupMachine could run a
  one-step Leftovers with the run's own pass before Done or Undone.
- F9 (W4): read the curtain's signer the way the launcher's is read, so the signer test is real.
- F10 (W9): build-curtain-on-forge.sh builds the working tree but stamps the commit time; refuse a
  dirty tree or build from `git archive HEAD`.

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


## RFC-W4-2: the keys of launcher v2.4.0 in the config codec (applied on launcher240; for Opus review)

What: `LauncherConfigDoc` and `EffectiveConfig` (frozen, PRODUCT-SPEC 5.7) gain one field per key
that upstream v2.4.0's `Config.java` reads beyond 1bbf0c9's fifteen, after the RFC-W4-1 fields and
in `Config.load`'s read order: `fillDock`, `animations`, `advancedEffects`, `greetingName`,
`reducedMotion`, `dockTitle`, `dockRow`, `reorderButton`, `showStats`, `appLibrary`,
`skyIntensity`, `settingsKeyKeycodes`, `settingsKeyPanel`, and the theme-only festival keys
`festival`, `festivalUntil`, `greeting`, `accentColor`: seventeen keys, since `Config.java` at
69c93b6 reads 32 and 15 of them were modelled already. In the doc they are
nullable like every other key (`Boolean?`, `Int?`, `String?`, `List<Int>?`). In `EffectiveConfig`
they are non-null and defaulted to the v2.4.0 `config.xml` values, so every existing constructor call
compiles; the festival is effective as the launcher keeps it (`festival` the lower-case name or "",
`festivalUntil` the ISO date or "", `greeting` cleaned by `Cards.cleanLabel`, `accentColorArgb` 0 for
the diya gold), read from the doc alone as the launcher reads it from config.json alone. Why: the
codec answers "what will the TV show for these bytes"; left in `unknown`, decode would warn "the
launcher ignores it" for keys the bundled launcher reads, and `effective()` could not preview them.
The codec reads them with `Config.java`'s per-key rules (an all-or-nothing int list for
`settings_key_keycodes`, 0 to 100 for `sky_intensity`, `Festival.parse` ported in
`LauncherFestival`), warns without rejecting when `settings_key_keycodes` takes Back, an arrow, OK,
Enter or Menu (4, 19 to 23, 66, 82; SECURITY V1), and encodes them in `KEY_ORDER`; the fifteen old
keys keep their relative order, so every config.json the app wrote before is byte for byte the
same. `ConfigDifferentialTest` now runs the real v2.4.0 `Config.java` and `Festival.java` (copied
from the submodule) as the primary target, 10,000 generated files plus the themes and hand cases,
and keeps the vendored 1bbf0c9 copy as launcher 2.0 (3,000 files, the fifteen old keys), with
stubs for `SharedPreferences`, `Resources.getIntArray` and `Cards.cleanLabel`. Not modelled, since
the phone cannot read them: the choices made in Launcher settings on the TV (4 to 7 tiles per row,
motion), which win until the next push changes config.json (proved in
`aChoiceMadeOnTheTvLastsUntilTheNextPush`), the TV-side reorder and apps taken off Home there.
Affected: W4 (codec, tests), W8 (Look writes `dockTitle` false for a three-row dock, owner decision
D1; nothing else of the new keys is written by the app), W1 (DECISIONS-W1 lists the 5.7 edit).
Smallest alternative: keep the keys in `unknown` and change only the warning text; rejected
because the preview and the drift check would then not know what the TV shows.

## RFC-LAUNCHER-1: what launcher v2.4.0 needs from the planner (applied on launcher240; for Opus review)

What, all in `:core:planner` 5.8 and defaulted so every caller compiles: (1) `Choices` gains
`wizardWifi: WizardWifi = WizardWifi.UNKNOWN`, with `enum class WizardWifi { NOT_OWNER, OWNER,
UNKNOWN }` (owner decision D3). The home screen switch switches the setup wizard off only when it
is `NOT_OWNER`: a network saved during the first-run wizard belongs to it, and Android deletes it
the moment the wizard is switched off, taking the TV and this connection off Wi-Fi. The answer comes
from a new read-only probe call outside the frozen `Prober`, `SavedWifiOwners` (`dumpsys wifi | grep
-o 'cname=[^ ]*'` on the TV, so only owners leave it, never a network name; v2.4.0's
tools/disable.sh check), which `SetupDeps.savedWifi` (defaulted to the prober, like `launchable`)
asks once per snapshot when a setup wizard is on the TV. No answer is UNKNOWN, so the wizard stays
on. When the switch then fails because the wizard holds Home, the HOME_SETUPWRAITH card says why in
the owner's words and its primary tries again instead of "Try with the wizard off". (2) `PlanRequest`
gains `UpdateLauncher(launcher: LauncherArtifact)` and `PlanKind` gains `UPDATE_LAUNCHER` (owner
decision D4): the bundled launcher installed over our older build in place (same package and signer,
newer versionCode), `pm install -r` or the streaming install, then, when it is the TV's home screen,
`am start -W` to bring it up; refused for another signer (Setup's replace, D36), an unknown signer
or a missing launcher, no steps when the TV already has this version or a newer one (never `-d`).
The update keeps the launcher's files, so the dock, the look and the card answers stay and nothing is
pushed again. Manage's Home screen offers "Update the home screen" with a preview of the plan.
Related changes that need no interface edit: the probe reads the installed launcher's and the TV
curtain's signer from a copy of the installed APK (`ApkSigners`, the APK Signing Block, v3 then v2;
RF-CURTAIN-1 F9), so `pinInstalled` and the replace-signer flow work; the takeover's first
`StartActivity` is STOP_AND_ROLLBACK and `am start -W` must print "Status: ok" and no Error line
(`ShellRenderer.check`), so a launcher that does not start never gets the stock home screen
disabled; a look push (or the undo's restart of Home) puts the launcher's accessibility services
back when the TV had one on (typed `SettingsPut` steps after `RestartHome`, as tools/theme.sh does).
Affected: W3 (planner, renderer check), W4 (probe), W8 (Setup and Manage flows, strings), W1
(fixtures `Harness` default the wizard's Wi-Fi to NOT_OWNER where the tests modelled that TV).
Smallest alternatives: for D3, put the wizard into `Choices.kept`, which would show it as a kept row
on S3 and say nothing true; for D4, ask the person to run Setup again, which reinstalls everything
and redoes the takeover for a version bump.
