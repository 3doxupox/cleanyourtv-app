# W8 decisions: flows, screens, Android glue

Stream W8, 2026-10-19. Built against section 5 as fixed by R0 and the W1 fakes. RFCs:
`docs/rfcs/W8.md`; R5 review: `reviews/R5-W8.md`.

## Shape

- D-W8-1 Machines live in `:core:flows`, pure Kotlin, one per screen family: `SetupMachine`
  (S1 to S5), `ManageMachine` (M1), `SwitchedOffMachine` (M4), `LeftoversMachine` (M5),
  `HomeScreenMachine` (M2), `ModulesMachine` (M3 family), `RestorePointMachine` (M6),
  `TvSettingsMachine` (M7), `AppSettingsMachine` (M8). All follow the frozen `Machine` contract.
- D-W8-2 Shared flow services in `:core:flows`: `GateKeeper` (D17, one prompt per TV for 10
  minutes, NONE pass on phones without a lock), `TvAccess` (returning writes: live session reuse,
  port probe, the M1a debugging step via `reach`, D19 subnet check, D37 expected id), `PlanRunner`
  (plan, gate, start, follow), `TvWriter` (the Manage-family write), `RunLedger` (which plans ran
  on which TV, `ChangeRecord`s derived from the journals, the resume counts), `SessionEnd` (D7:
  debug_off at Finish and after 10 minutes in the background, confirmed by a failed probe),
  `RestorePointWriter` (D44 after every plan), `DocumentTvRepository` (tv.json, schema 1),
  `SecureIdGen` (ULID and run token for the planner), `resolveStart` (ARCHITECTURE 2.2).
- D-W8-3 Copy: every machine-produced sentence goes through `FlowCopy` and `CopyKey`; the app maps
  each key to the resource in the module that shows it (`ResourceFlowCopy`, an exhaustive `when`).
  Strings are ICU MessageFormat with named arguments, formatted with `android.icu.text.MessageFormat`
  (`msg()` in every feature module); no `%s` and no Android plurals.
- D-W8-4 Screens are stateless composables over a machine's state; `:app` hosts each machine in a
  `MachineHost` ViewModel (it only owns the scope) behind a Navigation 3 entry. Keys carry ids only.
- D-W8-5 Pre-W7 components: each feature module has `PendingDesign.kt`, thin stand-ins named
  `Pending<Component>` with W7's parameter lists (ui/design/API.md). Merge swap: delete the file,
  drop the prefix, import `tv.cleanyour.ui.design.component.*`, and `PendingAppTheme` becomes
  `CytTheme`. `Modifier.rejectObscuredTouches()` (SECURITY 4.3) has no W7 equivalent and should
  move to `:ui:design`.
- D-W8-6 Other streams' implementations enter through `Seams` (app). `Seams.unwired()` are inert
  stand-ins until W2 to W6 merge; the checklist is in `Seams.kt`.

## Security and Android

- D-W8-7 Manifest: one exported component (MainActivity, `taskAffinity=""`, singleTask, reads no
  extras); permissions exactly D16; androidx.core's DYNAMIC_RECEIVER permission and profileinstaller's
  exported receiver are removed with `tools:node="remove"`; `allowBackup=false`,
  `dataExtractionRules` and `fullBackupContent` exclude every domain; cleartext off; network
  security config with the D41 hosts and system anchors only (matches W5's `DownloadHosts.ALL`).
  `ManifestTest` checks the merged manifest under Robolectric.
- D-W8-8 D35: `FLAG_SECURE` plus `accessibilityDataSensitive` (API 34) only on the Allow phase of
  S2c, the "Reset my key" confirmation and while the restore zip is written. Confirming buttons
  drop touches that arrive through an overlay (`FLAG_WINDOW_IS_OBSCURED` and, API 29+,
  `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`).
- D-W8-9 `AndroidCredentialGate` uses the platform `BiometricPrompt` (no androidx.biometric): API
  30+ strong biometrics or device credential, API 29 credential fallback, API 28 the keyguard
  confirm screen.
- D-W8-10 `OperationService` (connectedDevice FGS) holds nothing: it mirrors the engine's run as a
  notice published by `CytApplication`; a plan with a transfer, an install, a guarded section or
  more than 5 steps starts it; the end of the run stops it.
- D-W8-11 `NsdDiscovery`: five service types, resolves one at a time, a 5555 probe of IPv4 literals
  only (never a name lookup), no multicast lock; network state by `ConnectivityManager`, never the
  SSID.
- D-W8-12 Logging: `CytLog` redacts before logcat and before the diagnostics ring (blobs, MD5
  fingerprints, IPv4 but the last octet, serials, 16 hex ids, 6 digit codes, Wi-Fi names); release
  builds strip debug logs with R8.

## Tests

- Coroutine tests run with `runTest(timeout = 30.seconds)`; the 5 minute Gradle test timeout comes
  from build-logic (T1), no module sets its own. Machines run endless poll and tick loops in `backgroundScope`, so tests
  never wait on Turbine (`drain()` reads what is there) and any retry loop must contain a delay.

## Merge of main (M5)

- D-W8-13 RFC-W8-1 as ruled: `SetupIntent.StopAction(action)` and `SetupIntent.RejectIdentity` are
  in the frozen sealed set (`StopAction` moved to Flows.kt beside it); `SetupActions` keeps only
  `intent`, `back(): Boolean` and `dismissCard`.
- D-W8-14 RFC-W8-4: `GateKeeper` never caches an `APP_GATE_ID` pass, prompted or handed in.
- D-W8-15 RFC-W8-5: the D24 restart test plans `PlanRequest.Reboot` and runs it through
  `PlanRunner` (journaled, no prompt: `GateKeeper.needsPass` mirrors the engine's REBOOT rule). The
  3 minute note is a timer beside the run; a run that pauses (the boot wait of 5 minutes ran out)
  is abandoned, since the reboot needs no undo and an open run would refuse every later write, and
  the not-back card shows. "It's on again" is only a boot wait.
- D-W8-16 W3 notes: `Finished(ABANDONED, home = CONNECTION_LOST)` of a plan with no steps (step R
  rebuilt from the journal, D-W3-2) and `Finished(home = CONNECTION_LOST)` show the new growable
  case `HOME_PUT_BACK` ("We put the old home screen back", Try the switch again, Leave it as is).
  R-11: "Stop here" or "Leave it as is" with a HOME section open while the TV is away keeps the
  stop card with "Reconnect to the TV to put the old home screen back", waits for the TV through
  `TvAccess` (the debugging step shows), resumes the run if the engine has not loaded it, then
  abandons. R-12: the only typed name (M7 rename) is trimmed; no 0.1 plan writes `device_name`.
- D-W8-17 R5-W8 O2: `TvWriter` publishes a paused or already open run to `OpenWrites` (one per
  graph, in `ManageDeps`) instead of a failed snackbar, and does not plan or prompt while one is
  open; a RUN_OPEN refusal after process death loads the run with `engine.resume` first. The app
  lays `OpenWriteSheet` over any screen: Carry on, Undo what we did, Stop here
  (`TvWriter.resolveOpen`: reconnect, the D17 pass when the plan is gated, then follow the run; an
  action the engine does not take within 5 s shows the reconnect line). A SETUP run opens the
  wizard's resume card instead. The S4e card for a Manage kind says "We were in the middle of a
  change on {tv}", and such a run ends in Manage, never on Done.
