# Changelog

All notable changes to the app. Versions follow [semantic versioning](https://semver.org/).

## Unreleased (0.2.0)

### TV lists
- Matches the TV against 131 community tested profiles from 46 brands, up from 89, from
  the upstream lists of launcher v2.5.0: 42 new ones, among them the first Fire OS 8 list (Fire
  TV Stick 4K, 2023), three more Fire TV devices, six more TCL sets, the Google TV Streamer,
  XGIMI MoGo 2 and MoGo 3 Pro, Xiaomi's China sets on PatchWall and HyperOS 3, Hisense and Vidda
  VIDAA sets, the Freebox Player POP, and first lists from AIWA, BYINTEK, Cecotec, Challenger,
  Compac, EKO, FFalcon, JVC, Kogan, Motorola, MTC, Polytron, SHARP, Tele System and Verizon.
- Sets where the home screen cannot be switched safely get the clean-up and keep their own home:
  TVs with a vendor home the app does not know (Xiaomi PatchWall, VIDAA, OQEE, Verizon), a Kalley
  set whose firmware brings the setup wizard back at boot, and the Google TV Streamer, where the
  switch would cost casting after every reboot.
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
