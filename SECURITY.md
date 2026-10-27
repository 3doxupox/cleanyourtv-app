# Security policy

Clean Your TV changes a TV over its debugging connection, so we take reports seriously and
answer them first.

## Reporting a vulnerability

Please report privately through GitHub:
[Report a vulnerability](https://github.com/udhaykumarbala/cleanyourtv-app/security/advisories/new)
(Security tab, "Report a vulnerability"). Do not open a public issue, pull request or discussion
for a security problem.

Useful to include: the app version (shown in the app settings), the phone and TV models with their Android
versions, what an attacker can do and from where (same Wi-Fi, the TV, a malicious app on the
phone, the network path to GitHub), and the steps to reproduce. Redact your TV's serial number
and your network addresses; we never need them.

What happens next:

- We confirm we have it within 7 days.
- We agree on a fix and a disclosure date with you; 90 days is the default, sooner when a fix
  ships sooner.
- Fixes ship as a new signed release; the advisory credits you unless you prefer otherwise.

## Scope

In scope: this app and everything in this repository, including the release pipeline, the
signed module index and its keys, the bundled launcher and TV curtain as this app pins and
installs them, and the restore scripts it writes.

Problems in the launcher itself (the TV home screen) belong to the upstream project,
[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv); report them there privately.

## Supported versions

Only the latest release gets security fixes. Android refuses updates signed by another key, so
updating in place is always safe; the release certificate SHA-256 is in
[docs/release-signer.sha256](docs/release-signer.sha256).

## The bundled launcher: what it asks the TV for

The app installs one launcher build, pinned by its SHA-256 (core/trust `PinnedLauncher`). Each
upstream bump is reviewed against the previous pin; the changes in the manifest are listed here.

Launcher 3.0 (upstream main after v3.0.0, commit b06ab21, versionCode 9; was 2.7, v2.7.0,
versionCode 8). The manifest at this commit is the same as at the v3.0.0 tag:

- New exported activity `AppLaunchActivity` for `VIEW` with `BROWSABLE` on `market://launch?id=<package>`,
  with `Theme.NoDisplay`. It opens an installed app by its package name (its TV launch entry, else
  its phone one) and closes; it takes no other data. It serves the "open app" request of the
  Android TV Remote service, which the stock launcher normally answers. This app never sends it.
- New notification listener `GuardRevival` (behind `BIND_NOTIFICATION_LISTENER_SERVICE`). It reads
  no notification; the system only binds it after `cmd notification allow_listener
  com.gathin.tvhome/.GuardRevival`, and then rebinds it after a force stop, which brings Home back
  on firmware that kills it. **This app never sends that command**, so on a TV set up with this
  app the listener stays unbound.
- New receiver `GuardConfig` for `com.gathin.tvhome.GUARD`, protected by `WRITE_SECURE_SETTINGS`:
  only a sender holding that permission (the ADB shell) can reach it. It switches the opt-in "leave
  the home helper off in standby", and acting on it needs the launcher itself to hold
  `WRITE_SECURE_SETTINGS`. **This app never grants that** (`pm grant`) and never sends the broadcast.
- New package visibility query for `android.settings.APPLICATION_DETAILS_SETTINGS` (scheme
  `package`): the tile menu and the app library offer App info only where it resolves. It grants
  nothing.
- No new `uses-permission`. Everything else is as in 2.7.

The core/ops `RendererPropertyTest.noOpApprovesGrantsOrSwitchesOnTheLaunchersHelpers` pins these
statements: no op renders `cmd notification`, `pm grant`, `WRITE_SECURE_SETTINGS` or a broadcast to
the launcher, and the only accessibility writes are the two accessibility settings with the op's
own value, which the planner uses only to put back the list the TV had.

Launcher 2.7 (upstream v2.7.0, commit 3e34f40, versionCode 8; was 2.5, v2.5.0, versionCode 6):

- New permission `android.permission.WRITE_SECURE_SETTINGS`. It is a protected permission that
  only `pm grant` over ADB can give; installing the APK does not. Upstream uses it for one optional
  feature (`restore_key_services`, the `ServiceRepair` class): after a force stop, put the
  launcher's own key helpers back on the enabled accessibility list. Without the grant the code
  returns at once. This app never runs `pm grant` for it, so on a TV set up with this app the
  launcher does not hold it and the feature stays off.
- New permission `yandex.tv.permission.ACCESS_TV_SOURCE` (protection level normal, Yandex TV OS
  only): lets the Inputs menu open HDMI and the tuner there. It does nothing on other TVs.
- New package visibility queries: the standard settings screens, the HOME intent, the TCL and Fire
  TV settings packages and two Coocaa (Skyworth) input providers. Queries only make those apps
  visible to the launcher; they grant nothing.
- New exported activity `InputsRequestActivity` for `com.android.tv.action.VIEW_INPUTS`, disabled
  in the manifest. The launcher switches it on only when its config sets `handle_view_inputs`,
  which this app does not set. It opens the launcher's own Inputs menu and takes no data.
- New activities `ClockActivity` and `ProbeActivity`, not exported.
- `HomeKeyService` is now direct boot aware (it can start before the first unlock), and both key
  services no longer ask for window events: they receive
  key events only, which is less than 2.5 received. `stock_home_redirect` would add window events
  at run time; this app does not set it.
- Unchanged: the HOME activity, the theme provider (exported, guarded by
  `WRITE_SECURE_SETTINGS`), the screensaver behind `BIND_DREAM_SERVICE`, both key services behind
  `BIND_ACCESSIBILITY_SERVICE`, and the network, Wi-Fi and location permissions.
