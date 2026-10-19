#!/usr/bin/env bash
# One clean release build inside the reproducible-build container (docker/repro). Run by
# release.yml and scripts/build-internal-apk.sh, twice per release in two fresh containers, with
# the checkout always at /src (machine paths must not differ between the two).
#
#   scripts/repro-build.sh <out-dir>
#
# Needs SOURCE_DATE_EPOCH (the tagged commit's time). Writes <out-dir>/app-release-unsigned.apk,
# its sha256 and the R8 mapping. The build is unsigned: signing happens after the comparison,
# outside Gradle, so the signing key never enters a build container.
set -euo pipefail
OUT="${1:?usage: repro-build.sh <out-dir>}"
: "${SOURCE_DATE_EPOCH:?set SOURCE_DATE_EPOCH to the tagged commit time}"
[ "$PWD" = "/src" ] || { echo "run from /src so both builds see the same paths" >&2; exit 2; }
mkdir -p "$OUT"
export TZ=UTC LC_ALL=C.UTF-8
echo "sdk.dir=$ANDROID_HOME" > local.properties
# SEED_GRADLE: a read-only Gradle home to copy the wrapper's distribution zip from (nothing else).
# The wrapper checks the zip against distributionSha256Sum before it unpacks it.
if [ -n "${SEED_GRADLE:-}" ] && [ -d "$SEED_GRADLE/wrapper/dists" ]; then
  ( cd "$SEED_GRADLE" && find wrapper/dists -name '*.zip' | while read -r z; do
      mkdir -p "$GRADLE_USER_HOME/$(dirname "$z")" && cp "$z" "$GRADLE_USER_HOME/$z"; done )
fi
chmod +x gradlew

# No build cache and no configuration cache: every task runs from the sources in this container.
./gradlew --no-daemon --no-build-cache --no-configuration-cache --console=plain \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=768m" \
  :app:assembleRelease :app:checkApkSizeRelease

APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
cp "$APK" "$OUT/app-release-unsigned.apk"
if [ -d app/build/outputs/mapping/release ]; then
  mkdir -p "$OUT/mapping" && cp -r app/build/outputs/mapping/release/. "$OUT/mapping/"
fi
zipalign -c 4 "$OUT/app-release-unsigned.apk" >/dev/null && echo "zipalign: ok"
( cd "$OUT" && sha256sum app-release-unsigned.apk | tee app-release-unsigned.apk.sha256 )
