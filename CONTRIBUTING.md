# Contributing to Clean Your TV

Thanks for helping. Bug reports, TV support requests, profile fixes and code are all welcome. This
app changes other people's TVs, so the bar for safety is high; the rules below exist for that.

By contributing you agree that your contribution is licensed under the [MIT licence](LICENSE) and
that you follow the [code of conduct](CODE_OF_CONDUCT.md). Security problems go through
[SECURITY.md](SECURITY.md), never a public issue.

## Development setup

You need JDK 21 and the Android SDK (compile SDK 37, build tools 36; Gradle downloads what is
missing once the licences are accepted). Linux or macOS.

```bash
git clone --recurse-submodules https://github.com/udhaykumarbala/cleanyourtv-app.git
cd cleanyourtv-app
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew check assembleDebug
```

The submodule `third_party/cleanyourtv` is the upstream launcher at its pinned release; without
it the build stops and tells you to run `git submodule update --init`.

| Command | What it runs |
| :- | :- |
| `./gradlew check` | the module graph rules, the build-logic tests, every unit test, Android Lint and the pin checks |
| `./gradlew verifyRoborazziDebug` | the screenshot tests against the committed goldens |
| `./gradlew :app:assembleDebug` | a debug APK in `app/build/outputs/apk/debug/` |
| `./gradlew :app:assembleRelease :app:checkApkSizeRelease` | the release build through R8 and the 8 MB size gate |
| `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` | the tests of the release and redaction scripts |

CI (`.github/workflows/pr.yml`) runs all of these on every push and pull request; please run at
least `check` before opening one.

Optional helpers: `scripts/build-on-forge.sh` and the other `scripts/*-on-forge.sh` scripts run the
same Gradle tasks, emulator sessions and release builds in throwaway Docker containers on a remote
Linux build host over ssh ("forge"); see `scripts/lib/forge.sh` for the settings. You never need
them for a contribution.

### Tests and screenshot goldens

- Every core interface has a fake in `:testing:fixtures`, and `:testing:fake-adbd` is a fake TV
  that speaks the ADB protocol. Tests never touch a real TV; nothing in the build may need one.
- Screenshot tests are Roborazzi on Robolectric (native graphics) in each `feature/*` module and
  `:ui:design`, in four renderings: dark, light, 200 % font and right to left, with the
  accessibility checks on. Goldens live in `src/test/screenshots/`.
- Changed a screen on purpose? Record the goldens with `./gradlew recordRoborazziDebug`, look at
  every changed PNG, and commit them with the change. Record on Linux x86_64 (as CI does): other
  platforms can render text a pixel differently.
- End to end scenarios against an Android TV emulator are in `:e2e`
  (`scripts/emulator-session.sh 28 ./gradlew :e2e:emulatorTest` on a machine with KVM; nightly CI
  runs them).

## Module rules

The project is split into small modules with one direction of dependency, checked by
`checkModuleGraph` (rules in `build-logic/convention/src/main/kotlin/tv/cleanyour/buildlogic/ModuleRules.kt`):

- `core/*` and `adb/*` are pure Kotlin JVM modules: no Android imports, no Android plugin.
  `:core:model` holds the shared types and interfaces.
- `android/*`, `ui/design`, `feature/*` and `app` are Android modules. A feature module may depend
  only on `:core:flows`, `:core:model` and `:ui:design`, never on another feature or on a lower
  core module.
- No upward edges, no test module in a main configuration, and a third-party library only when it
  is on that module's allowlist. Versions live only in `gradle/libs.versions.toml`, stable releases
  only, and every artifact is checked by `gradle/verification-metadata.xml`
  (`scripts/update-verification-metadata.sh` after a version change).
- Shell lines that reach the TV are built only by `:core:ops` and `:core:probe` (`@RawShell`);
  credential passes only by the gate implementations (`@GateIssuer`). Do not work around either.

Changing a rule, a public interface in `:core:model` or a pin is fine, but say so in the pull
request and explain why; reviewers look at those first.

## Propose a TV profile

The profiles come from the upstream launcher's community lists, so a new TV goes upstream first:

1. **Upstream.** Add `examples/<brand>-<model>/debloat.txt` to
   [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) following its
   [CONTRIBUTING.md](https://github.com/Gathin23/cleanyourtv/blob/main/CONTRIBUTING.md): only
   packages you have disabled and lived with, the model, Android version and firmware in the
   header, and what broke. That list is useful to everyone, with or without this app.
2. **Here, once it is merged upstream.** Open a pull request that moves the
   `third_party/cleanyourtv` submodule to the upstream commit with your list, and adds the TV's
   entry to `core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json`: how to
   recognise it (`match`: model or device names, brand, SDK range), its platform, stock launcher,
   Live TV app and setup wizard, `neverDisable` for anything that must stay on, a category for
   each group of the list, and `tested` (who, which firmware, how many days, whether a reboot was
   tried). The build converts the list at build time and fails on anything it does not
   understand. Bundled launcher changes need the maintainer (the APK is re-signed).

No upstream list yet? Open a "TV support request" issue with the model, Android version and the
output the issue template asks for; someone with the same TV may pick it up.

## Commits and pull requests

- Small, focused commits with plain messages that say what changed and why.
- No em dashes and no double hyphen sequences in prose, comments, strings, docs or commit
  messages (real command line flags are fine). Use a period, comma, colon or a single hyphen.
- No generated banners or trailers in commit messages.
- User facing text is plain, calm English (see the strings in `feature/*/src/main/res/values/`).
  Explain what happens to the TV, not how the code works.
- Tests come with the change. A bug fix starts with a test that fails without it.

## Security rules every change must respect

- **Reversible only.** Packages are disabled for the user (`pm disable-user`), never uninstalled.
  Every write to the TV is recorded so it can be undone, and a restore point is saved before the
  first one.
- **Show before run.** Every command reaches the TV through the plan the user saw ("Show
  commands"). No hidden commands, no shell strings assembled outside `:core:ops` and `:core:probe`.
- **The protected list stays protected.** `Denylist` in `:core:ops` (Play services, the Play
  Store, Wi-Fi, Bluetooth for the remote, the TV's inputs, the shell and adbd) is never relaxed
  for convenience.
- **The watchdog stays in front of every home screen change.**
- **Network.** The app talks to the chosen TV on the LAN and, on the user's action only, to the
  GitHub hosts in `DownloadHosts` (the network security config lists the same). No analytics, no
  crash reporting, no new hosts or permissions without an explicit review.
- **Downloads are pinned.** Anything installed on a TV is checked by size, SHA-256 and signer
  (`:core:trust`) before it is installed. The bundled launcher and TV curtain are pinned in
  `Pins.kt` and checked at build time.
- **No secrets and no personal data in the repository.** Never commit keys, keystores, passwords,
  tokens, or a real TV's serial, MAC or IP address. Captures from a real TV go through
  `scripts/import-mitv-capture.py` and `scripts/check-redaction.py` before they become fixtures.
- **Logs and diagnostics are redacted.** Addresses, serials, keys and codes never reach a log line
  or the diagnostics zip.

## Releases (maintainers)

Releases are built by `scripts/release-on-forge.sh`: two clean builds in the pinned container
(`docker/repro`) must be byte identical, the APK is signed with the release key on the
maintainer's machine only (apksigner, v2 and v3), and `scripts/verify-release.sh` checks the
signature, the signer (`docs/release-signer.sha256`), `apksigcopier compare` against the unsigned
build and the APK assertions of `scripts/check-apk.py`. CI never holds a key: on a `v*` tag,
`.github/workflows/release.yml` builds the unsigned APK twice and publishes it as an artifact, and
its verify job checks the signed APK attached to the GitHub Release against that build.
