# fdroiddata merge request: New app: Clean Your TV

The text below is what goes into the "New App" merge request on
[fdroiddata](https://gitlab.com/fdroid/fdroiddata), together with `metadata/tv.cleanyour.app.yml`
(the draft is [tv.cleanyour.app.yml](tv.cleanyour.app.yml) next to this file). It is not submitted
yet.

Before submitting:

1. Tag the release whose commit contains `scripts/fdroid-build-tv-apks.sh` and
   `android/platform/signatures/`, publish its signed APK on GitHub, and check that the
   `fdroid-check` workflow passed on that tag with "match" (`gh workflow run fdroid.yml --ref vX.Y.Z`
   after the Release is published).
2. In the metadata, replace `commit: v0.3.0` with the full commit hash of the tag (fdroiddata wants
   hashes, not tags), and versionName, versionCode, CurrentVersion and CurrentVersionCode if the
   first F-Droid version is not 0.3.0.
3. Run `fdroid readmeta`, `fdroid rewritemeta tv.cleanyour.app` and `fdroid lint tv.cleanyour.app`
   in the fdroiddata fork, and `fdroid build -v -l tv.cleanyour.app` if a build server is at hand.
4. Fill in the template checklist (the boxes below), then paste everything from the title down.

***

Title: **New app: Clean Your TV**

## Abstract for new app inclusion

### What the app does

Clean Your TV cleans up an Android TV from a phone. The phone and the TV are on the same Wi-Fi; the
app finds the TV, shows how to switch on network debugging with the remote, and connects to the
TV's ADB over the network (the TV asks the user to allow the phone). It then reads which apps are
on the TV, shows a plan (what it would switch off, what it keeps) and, when the user taps "Make it
clean", saves a restore point, switches off the pre-installed apps the user does not want
(`pm disable-user`, never uninstall) and installs the Clean Your TV home screen on the TV. Every
change can be undone from the app, one app at a time or all at once.

The TV lists come from the community lists of [Clean Your TV](https://github.com/Gathin23/cleanyourtv)
(MIT), the TV launcher project the app is built around. Screenshots are in the app's
`fastlane/metadata/android/en-US/images/phoneScreenshots/`.

### Why it exists

Clean Your TV on its own needs a computer: you run its scripts or hand its prompt to a coding agent.
This app does the same job from an Android phone, for people who will never install ADB. Unlike the
on-device app managers already in F-Droid (Canta, for example), it manages another device, the TV,
over the network, with no root, no Shizuku and nothing to install on the TV by hand.

## Things a reviewer will want to know

### Two APKs inside the app, built from source

The app carries two small TV apps in `android/platform/src/main/assets/bundled/`, which it installs
on the TV over ADB (never on the phone):

- `cleanyourtv-launcher.apk`: the Clean Your TV launcher (`com.gathin.tvhome`), built from the
  upstream source in the `third_party/cleanyourtv` git submodule at the pinned release commit;
- `cleanyourtv-curtain.apk`: the screen the TV shows while the phone works (`tv.cleanyour.curtain`),
  the `:tv:curtain` module of this repository.

The recipe removes both (`rm`) and rebuilds them from source in the `build` step with
[`scripts/fdroid-build-tv-apks.sh`](https://github.com/udhaykumarbala/cleanyourtv-app/blob/HEAD/scripts/fdroid-build-tv-apks.sh):
the launcher with its own Gradle 8.9 build in a copy of the submodule (upstream's
`signingConfig signingConfigs.debug` line is removed, as fdroidserver also does), the curtain with
`:tv:curtain:assembleRelease`, both unsigned.

Both TV apps must keep one fixed signature: the phone app pins each of them by size, SHA-256 and
signer (`core/trust/.../Pins.kt`), and a TV only accepts an update of an installed app signed with
the same key. So the script puts the committed APK Signing Blocks back on the unsigned rebuilds with
`apksigcopier patch` (the same tool and the same mechanism F-Droid uses for reproducible builds). The
signature blocks are in `android/platform/signatures/<asset>/` (made with `apksigcopier extract`):
certificates and signatures only, no code. They are the only `scanignore` entries. The script then
checks the result against the pins, and the Gradle build checks it again before it packages anything
(`verifyLauncherPin`, `verifyCurtainPin`); a signature only verifies when the rebuilt bytes are
exactly the bytes that were signed, so the build stops unless the two APKs are byte for byte what
the source builds.

### Reproducible build, our signature

Every release is built twice in a pinned container and must be byte identical before it is signed;
the signing key never enters CI. `Binaries` points at the signed APK of each GitHub Release and
`AllowedAPKSigningKeys` is the release certificate, so F-Droid can publish the same APK users get
from GitHub and Google Play, and installs from any channel update each other.

We run this recipe ourselves before every release: the `fdroid-check` job
([`.github/workflows/fdroid.yml`](https://github.com/udhaykumarbala/cleanyourtv-app/blob/HEAD/.github/workflows/fdroid.yml))
runs fdroidserver's `fdroid build` on the recipe in the `fdroidserver:buildserver-trixie` image and
`apksigcopier compare`s the result with the published APK. A local run of `fdroid build` with this
recipe on version 0.2.0 matched the published 0.2.0 APK ("compared built binary to supplied
reference binary successfully") on 2 CPUs, and the unsigned build was the same on 2, 4 and 8 CPUs, on
Debian's and Temurin's JDK 21, and in two different checkout paths.

### Network and permissions

The app talks to the chosen TV on the local network. On the user's action only, it asks GitHub
whether a newer version exists ("Check for updates" shows the version; it downloads nothing) and,
if the user opts in, downloads optional TV add-ons from a signed index on GitHub, checked by size,
SHA-256 and signer before they are installed on the TV. No analytics, no crash reporting, no
Google Play Services, no trackers. Permissions: internet, network state, Wi-Fi multicast (to find
the TV), a connected-device foreground service while it works on the TV, notifications, and the
biometric prompt (the phone's own screen lock asks "Confirm it's you" before the first change to a TV).

### Name and package IDs

The app is a community project for Clean Your TV, not the official one. The Clean Your TV name and
the `tv.cleanyour.*` package IDs (under `cleanyour.tv`, the upstream author's domain) are used with
the permission of Gathin, the author of Clean Your TV, recorded in the README:
https://github.com/udhaykumarbala/cleanyourtv-app/blob/56802ac04c3917e5ece5a086ef8c2133b32377a6/README.md?plain=1#L109-L110

### Assets

Everything in the app is MIT, except the Outfit typeface (SIL Open Font License 1.1, in the app
and the launcher). The one picture taken from upstream is the "aurora" theme wallpaper
(`themes/aurora/wallpaper.jpg`), added by the upstream author to his MIT repository; the upstream
repository has no separate licence or credit for it.
<!-- If Gathin's confirmation that the theme wallpapers are his own work under the repository's MIT
licence is public by the time this is submitted, link it here. -->

### How the app was made

This app was developed with AI coding agents under human review: the maintainer set the design and
the safety rules, and reviewed and accepted every change and every release.

## Checklist

### Policy

* [ ] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy).
* [ ] The original app author has been notified (and does not oppose the inclusion). I am the
  author of this app; the author of the bundled launcher gave permission for the name and IDs (link above).
* [ ] The upstream app source code repo contains the app metadata in a Fastlane folder structure
  (`fastlane/metadata/android/en-US/`).

### Docs

* [ ] I read the guide, the templates, the Build Metadata Reference and the Quick Start Guide.

### Merge Request Setup

* [ ] The title follows "New app: app name".
* [ ] My fdroiddata fork is public and the branch is not protected.
* [ ] Only one app in this MR.

### Metadata

* [ ] Metadata is in `metadata/tv.cleanyour.app.yml`, valid YAML with LF line endings.
* [ ] No summary, description, changelog or images in the MR (they come from the upstream Fastlane folder).
* [ ] Releases are tagged and auto update is enabled.
* [ ] There is an issue tracker and an AuthorName.
* [ ] External repos are git submodules (the upstream launcher is `third_party/cleanyourtv`).
* [ ] Reproducible Builds are enabled (Binaries and AllowedAPKSigningKeys).
* [ ] Only the latest version is in the metadata, and `commit` is the full hash.

### Pipeline

* [ ] All pipelines pass, and every warning in the Reports tab is fixed or explained.

/label ~"New App"
