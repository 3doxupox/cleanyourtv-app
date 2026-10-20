# cleanyourtv-app (internal)

Android phone app "Clean Your TV" (`tv.cleanyour.app`): sets up an Android TV over network
debugging from the phone. Private repository; not announced.

## Layout

- `build-logic/` convention plugins (`cleanyourtv.jvm`, `cleanyourtv.android.lib`,
  `cleanyourtv.android.compose`, `cleanyourtv.android.feature`, `cleanyourtv.android.app`) and the
  `checkModuleGraph` rules (`ModuleRules.kt`).
- `core/*`, `adb/*`: pure Kotlin JVM modules. `core:model` holds every shared type and seam.
- `android/*`, `ui/design`, `feature/*`, `app`: Android modules.
- `testing/fixtures`: a fake for every core interface, fixture loaders and shared samples.
- `fixtures/`: the reference TV capture (redacted) and the module card test app sources.
- `third_party/cleanyourtv`: the launcher repository as a submodule, pinned to `1bbf0c9` (bumped by W5 for the bundled launcher).
- `tv/curtain`: the TV curtain (`tv.cleanyour.curtain`, RFC-CURTAIN-1), a small Java app the Setup plan
  shows on the TV while the phone works. `scripts/build-curtain-on-forge.sh` builds it twice, compares
  the builds, signs it with the launcher key and places it in `android/platform` assets; update
  `PinnedCurtain` with the printed size and sha256. `scripts/tests/curtain-cues.sh` cues it by hand.

Clone with `git clone --recurse-submodules`, or run `git submodule update --init` afterwards.

## Build

Android builds run on forge, never on jarvis:

```bash
scripts/build-on-forge.sh                                   # check assembleDebug
scripts/build-on-forge.sh :app:assembleRelease :app:checkApkSizeRelease
scripts/build-on-forge.sh checkModuleGraph
```

Each run uses a throwaway `eclipse-temurin:21-jdk` container with the shared Gradle and SDK
cache volume and removes its container and checkout afterwards. A debug APK lands in `dist/`.

`check` runs the module graph rules, the build-logic tests, every unit test and Android Lint.
Pull requests run the same through `.github/workflows/pr.yml`.

Toolchain pins and the reasons for them: `gradle/libs.versions.toml` and `DECISIONS-W1.md`.
Who owns which module and how interface changes are made: `CONTRIBUTING-agents.md`.
