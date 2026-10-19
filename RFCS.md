# RFCs from W8 (flows, screens, Android glue)

Each one has a local workaround in W8's own code, so nothing is blocked; the frozen section 5
text is unchanged apart from the growable types R0: 2 allows.

## RFC-W8-1 SetupIntent misses some section 4 buttons

What: section 4 names buttons with no `SetupIntent`: "Stop here" (S4d), "Leave it as is" (S4e),
"Use the new home screen" and "Keep Projectivy" (S4c other launcher), "Share diagnostics" and
"OK" (S4c unknown), "Something looked wrong" (S4c user said No), "No" on the identity line (S2d),
the TV-not-found card's "Keep looking", and system back on 4a and 4b (UX 6.1: back only highlights
the way out). Workaround: `SetupMachine` implements `SetupActions` with three extra members,
`back(): Boolean`, `stopAction(StopAction)` (PRIMARY, SECONDARY, TERTIARY, mapped per `StopCase`
inside the machine, reusing `CarryOn`, `UndoWhatWeDid`, `TryWithWizardOff`, `LeaveOldHome` where
they exist) and `rejectIdentity()`. Proposal: add `SetupIntent.StopAction(action)`,
`SetupIntent.RejectIdentity` and `SetupIntent.Back` so the intent set is complete. Affects W8 only.

## RFC-W8-2 SetupState has no room for presentation details

What: the frozen states lack fields the screens must show: the S1c sheet on Welcome and the picker
(only `Found` has `sheet`), inline stop cards on Welcome and the door (UX 4.1, 4.2, identity, no
shell v2), the Allow retry count (UX 4.4 after 3 retries), the S2d screenshot and serial, the D17
grey line, the "Make it clean" loading state, the S4d reconnect spinner, the "Show commands" lines
(D21), the dock rows (D4), the theme list, the D7 "Switching debugging off... done" state, the
UX 4.8 three minute note and "came back on" count. Workaround: `SetupMachine.extras:
StateFlow<SetupExtras>`, derived only, never a source of truth. Proposal: either bless the side
state or add an `extras` field to each `SetupState` subtype. Affects W8 only.

## RFC-W8-3 (no change needed) StopCase and StopCard grown

`StopCase` gained `TV_NOT_FOUND, WRONG_NETWORK, TV_NOT_SUPPORTED, HOME_TIMED_OUT, STEP_FAILED,
UNDONE, PROBE_FAILED, NOT_BACK_AFTER_RESTART` and `StopCard` gained `tertiary: String? = null`, as
R0: 2 allows for these types ("W8 may add fields, never remove"). Listed for the reviewer.

## RFC-W8-4 Credential gate for app-level actions

What: `CredentialGate.confirm(tvId, ...)` is per TV, but "Reset my key" and "Delete everything" (D17)
belong to no TV. Workaround: those prompts use `APP_GATE_ID = TvId("0000000000000000")` and are
always prompted (no cached pass). Proposal: `CredentialGate.confirmApp(title, subtitle): Boolean`,
or keep the placeholder id and document it. Affects W8 and the fake.

## RFC-W8-5 No PlanRequest for the restart test

What: D24's restart test reboots the TV, but there is no `PlanRequest.Reboot`. Workaround: the Done
screen calls `TvSession.reboot()` and `awaitBoot()` directly (no journal record, no gate; it changes
nothing that needs undoing). Proposal: `PlanRequest.Reboot` with `PlanKind.REBOOT` so the reboot is
journaled like every other write (ARCHITECTURE 2.4 lists `Reboot` as its own plan). Affects W3.

## RFC-W8-6 Module card answer detection

What: F9 says the M3c state is detected "by screenshot probe", but no section 5 call reads the
launcher's card answer. Workaround: `CardAnswerDetector` seam in `:core:flows` (default: unknown)
plus two manual outcome buttons on M3c. Proposal: `Prober.cardAnswer(session, pkg): CardAnswer?`
once W4 or the 0.2 provider can answer it. Affects W4.

## RFC-W8-7 D16 misses USE_BIOMETRIC

What: D17 requires `BiometricPrompt` (BIOMETRIC_STRONG or DEVICE_CREDENTIAL); the platform API
throws without `android.permission.USE_BIOMETRIC`, an install-time permission with no prompt (lint
reports MissingPermission). D16's list does not name it. Workaround: the manifest declares it and
`ManifestTest` pins the list as D16 plus this one. Alternative: the keyguard confirm-credential
screen on every API level (no permission, but no biometrics and deprecated since API 29). Proposal:
amend D16 to add `USE_BIOMETRIC`; the `aapt2 dump badging` assertion (W9) must list it. Affects W9.
