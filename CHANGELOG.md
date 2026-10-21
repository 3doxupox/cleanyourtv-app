# Changelog

All notable changes to the app. Versions follow [semantic versioning](https://semver.org/).

## 0.2.0 (2026-10-21)

Launcher 2.5 and 42 more TV lists (131 in all), with stricter safety: the home screen switch is
skipped up front on TVs where it could break casting, Wi-Fi or start-up, nothing that the TV
inputs, accessibility or the remote need is switched off by default, and the app says why when a TV
keeps its own home screen or an app stays on.

### Home screen launcher
- Bundles the Clean Your TV launcher 2.5 (upstream v2.5.0), built from source and signed with this
  project's launcher key. New on the TV: edit the dock from the remote, choose the rows under the
  dock and a wallpaper in Launcher settings, and more; every new launcher option is off by default.
- A TV set up with 0.1.0 is offered "Update the home screen" in Manage. The dock, the look and the
  card answers stay.
- The Home screen page says what happens to a dock changed on the TV: the phone cannot see it, a
  new look keeps it, and saving a different dock from the phone replaces it. A new look now sends
  the saved dock as it is, even when an app in it has gone from the TV, so the TV's own dock edit
  stays; a dock of more than 12 apps saved under Six columns keeps its first 12 in a four-tile look.
- The phone reads the settings keys launcher 2.5 added exactly as the launcher does.
- Never switches off an app that asks for the TV input hardware permission (the layer under the
  HDMI, AV and tuner inputs), even when a profile lists it and also during the home screen switch,
  where a TV's own home screen that asks for it stays on. The run goes on without it, and Done and
  Manage say it was kept because it runs the TV's HDMI and tuner inputs.
- After a look change, the launcher's remote button services come back on TVs that kept them
  unbound before (accessibility is switched off and on again around the list, as upstream does).
  Stopping a run in the middle of that step finishes it first, so the TV never stays without its
  accessibility services.
- When the home screen switch fails, Setup no longer names a TV's own fallback placeholder as
  another home screen app.

### TV lists
- Matches the TV against 131 community tested profiles from 46 brands, up from 89, from
  the upstream lists of launcher v2.5.0: 42 new ones, among them the first Fire OS 8 list (Fire
  TV Stick 4K, 2023), three more Fire TV devices, six more TCL sets, the Google TV Streamer,
  XGIMI MoGo 2 and MoGo 3 Pro, Xiaomi's China sets on PatchWall and HyperOS 3, Hisense and Vidda
  VIDAA sets, the Freebox Player POP, and first lists from AIWA, BYINTEK, Cecotec, Challenger,
  Compac, EKO, FFalcon, JVC, Kogan, Motorola, MTC, Polytron, SHARP, Tele System and Verizon.
- Sets where the home screen cannot be switched safely get the clean-up and keep their own home,
  and the "What we found" page says why: TVs with a vendor home the app does not know (Xiaomi
  PatchWall, VIDAA, OQEE, Verizon); the Kalley 43G200, the Kalley R10G and the PRISM+ Q65 Ultra,
  one family whose firmware brings the setup wizard back at every boot and has left a set stuck at
  start-up, also when no list matches the TV exactly; and Google's own Chromecast with Google TV and
  Google TV Streamer, where the switch would cost casting after every reboot.
- On TVs whose home screen switch needs the setup wizard off, the switch is skipped up front when
  the wizard saved the TV's Wi-Fi or the TV cannot say who did: switching the wizard off would
  forget the Wi-Fi, and left on, it takes over the Home button and can crash the TV (upstream
  #506). Setup does the clean-up and says how to get the new home screen later. A TV without a list
  gets the clean-up only whenever its setup wizard is on, since the switch would leave the wizard on
  with the TV's own home screen off.
- TCL Live, a TV input, is a choice on every TCL list instead of off by default, and the broadcast
  tuner inputs on the Sony BRAVIA AE2 and PRISM+ Q65 Ultra lists are always kept.
- TalkBack, the phone remote service and the remote's usage logger are a choice on every list that
  names them, never off by default, and a test keeps any accessibility, remote or pairing package
  from being off by default again.
- The app takes its version from the installed APK, so Settings, diagnostics and "Check for
  updates" always name the version that runs.
- Corrections from upstream: the onn 4K box keeps Google's ambient mode so the Google Photos
  screensaver works, the Philips 65PUS8102 also switches off its reboot logger, and the TCL G07 and
  Xiaomi MSSP3 lists gained notes from more units. Home screen customizers that a TV service or
  the stock home's Inputs button relies on are now a choice rather than off by default on four
  more TVs.
- When two profiles match a TV equally, the one whose firmware fingerprint is named more exactly
  wins, so lists for neighbouring firmware builds each reach their own TVs.
- Sixteen upstream lists are not shipped, each with its reason in
  `core/spec/upstream-feedback/unconverted.txt`: duplicates of a shipped list, lists with nothing
  the app can match on, and a TCL China set whose network debugging needs a computer after every
  reboot.

## 0.1.0 (2026-10-20)

The first public release.

### Setup
- Finds Android TV, Google TV and Fire TV devices on the Wi-Fi and guides you through switching on
  network debugging with the remote, with steps per TV family.
- Reads the TV and matches it against 89 community tested profiles (27 brands), with a careful
  brand fallback for models nobody has profiled yet.
- Shows what it would switch off, grouped and counted, and what it keeps, before anything runs;
  Customise changes the list, the dock and the look, and shows every command in order.
- "Make it clean": saves a restore point, disables the clutter (never uninstalls), installs the
  Clean Your TV launcher 2.4 with a dock of the apps you use, and shows a progress animation on the
  TV while it works. A watchdog on the TV puts its own launcher back if the phone does not confirm
  the new home screen within 150 seconds.
- Asks for the phone's screen lock once per session before the first change; switches network
  debugging off again at the end unless you keep it on.

### Afterwards
- Manage: put back any switched off app, check for leftovers after a TV software update, change
  the home screen, cards and modules, undo everything from the restore point.
- Save a copy of the restore point as a zip with plain restore scripts that work from any computer
  with adb.
- Optional add-on apps for the TV from a signed list, checked by size, SHA-256 and signer before
  install.
- Settings: check for updates (one request to GitHub, on demand), share redacted diagnostics after
  a preview, licences.

### Under the hood
- No accounts, no analytics; internet only to GitHub and only on your action.
- Reproducible release build: two clean container builds byte identical; CI builds the same
  unsigned APK for every tag so the signed release can be checked against it.
- Signed with the release key, certificate SHA-256
  `eaf7af3867bb29ff4bb038854842c93f42ffe4198a087e611cb18f925201cd65`.
