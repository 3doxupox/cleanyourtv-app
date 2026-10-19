#!/usr/bin/env bash
# Build the bundled launcher APK (com.gathin.tvhome) on forge from the pinned upstream commit, sign
# it with the dedicated launcher release key (PRODUCT-SPEC Q1), and place it in the app's assets.
#
#   scripts/build-launcher-on-forge.sh            # build, sign, verify, copy into assets
#
# The upstream submodule must be checked out at PIN (third_party/cleanyourtv). The build runs in a
# throwaway eclipse-temurin:17-jdk container (upstream uses AGP 8.5 and Gradle 8.9) under the same
# host lock as scripts/build-on-forge.sh. Signing runs in a second throwaway container: the keystore
# and its password travel over the ssh stdin into the container's /dev/shm and never touch forge's
# disk. Key custody: /Users/dev/jarvis/secrets/cleanyourtv-launcher/ on jarvis only.
# After a rebuild, update PinnedLauncher in core/trust with the printed sha256 and signer digest;
# the verifyLauncherPin task fails the build until they agree.
set -euo pipefail
cd "$(dirname "$0")/.."

PIN=1bbf0c939fa7d1c0ed91c5fec37ddb57e749a3fd
FORGE="${FORGE:-builder@build-host}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
SECRETS="${SECRETS:-/Users/dev/jarvis/secrets/cleanyourtv-launcher}"
IMAGE="eclipse-temurin:17-jdk"
VOLUME="mitv-android-cache"
BUILD_TOOLS="34.0.0"
RUN_ID="cyt-launcher-$(date +%Y%m%d%H%M%S)-$$"
REMOTE_DIR="/home/builder/build/$RUN_ID"
LOCK="/home/builder/build/.cyt-forge.lock"
OUT="android/platform/src/main/assets/bundled/cleanyourtv-launcher.apk"
CMDLINE_TOOLS="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
SSH=(ssh -i "$SSH_KEY" -o ConnectTimeout=10 -o BatchMode=yes "$FORGE")

head=$(git -C third_party/cleanyourtv rev-parse HEAD)
[ "$head" = "$PIN" ] || { echo "third_party/cleanyourtv is at $head, expected $PIN" >&2; exit 1; }
[ -z "$(git -C third_party/cleanyourtv status --porcelain)" ] || { echo "submodule has local changes" >&2; exit 1; }
[ -r "$SECRETS/launcher-release.p12" ] && [ -r "$SECRETS/keystore-password.txt" ] || { echo "launcher key not found in $SECRETS" >&2; exit 1; }

cleanup() {
  "${SSH[@]}" "docker rm -f '$RUN_ID' '$RUN_ID-sign' >/dev/null 2>&1 || true; rm -rf '$REMOTE_DIR'" </dev/null || true
}
trap cleanup EXIT INT TERM

echo "==> rsync launcher sources at ${PIN:0:7} to $FORGE:$REMOTE_DIR"
"${SSH[@]}" "mkdir -p '$REMOTE_DIR'" </dev/null
rsync -az --delete -e "ssh -i $SSH_KEY -o BatchMode=yes" \
  --exclude .gradle/ --exclude build/ --exclude local.properties \
  third_party/cleanyourtv/launcher/ "$FORGE:$REMOTE_DIR/"

echo "==> assembleRelease in $IMAGE"
"${SSH[@]}" "flock -w 3600 '$LOCK' docker run --rm --name '$RUN_ID' --memory 3g --cpus 4 \
  -e HOME=/cache/home -e OWNER=\$(id -u):\$(id -g) \
  -v $VOLUME:/cache -v '$REMOTE_DIR':/src -w /src $IMAGE bash -euc '
    export ANDROID_HOME=/cache/sdk GRADLE_USER_HOME=/cache/gradle
    trap \"chown -R \$OWNER /src\" EXIT
    mkdir -p /cache/home
    if [ ! -x \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager ]; then
      mkdir -p \$ANDROID_HOME/cmdline-tools && cd \$ANDROID_HOME/cmdline-tools
      curl -fsSL -o tools.zip $CMDLINE_TOOLS && jar xf tools.zip && rm tools.zip
      rm -rf latest && mv cmdline-tools latest && chmod +x latest/bin/*
      cd /src
    fi
    yes | \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
    \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \"platforms;android-34\" \"build-tools;$BUILD_TOOLS\" >/dev/null
    echo sdk.dir=\$ANDROID_HOME > local.properties
    chmod +x gradlew
    ./gradlew assembleRelease --no-daemon --console=plain -q -Dorg.gradle.jvmargs=-Xmx1536m
    ls -l app/build/outputs/apk/release/
  '" </dev/null

echo "==> sign with the launcher release key (in memory) and verify"
{ cat "$SECRETS/keystore-password.txt"; printf '\n'; base64 < "$SECRETS/launcher-release.p12"; } | \
"${SSH[@]}" "flock -w 3600 '$LOCK' docker run -i --rm --name '$RUN_ID-sign' --memory 1g --cpus 2 \
  -e OWNER=\$(id -u):\$(id -g) \
  -v $VOLUME:/cache -v '$REMOTE_DIR':/src -w /src $IMAGE bash -euc '
    trap \"rm -f /dev/shm/ks.p12 /dev/shm/ks.pass; chown -R \$OWNER /src\" EXIT
    umask 077
    IFS= read -r pass; printf %s \"\$pass\" > /dev/shm/ks.pass; unset pass
    base64 -d > /dev/shm/ks.p12
    BT=/cache/sdk/build-tools/$BUILD_TOOLS
    in=\$(ls app/build/outputs/apk/release/*.apk | head -1)
    \$BT/zipalign -c -p 4 \"\$in\"
    \$BT/apksigner sign --ks /dev/shm/ks.p12 --ks-type PKCS12 --ks-pass file:/dev/shm/ks.pass \
      --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
      --out /src/cleanyourtv-launcher.apk \"\$in\"
    rm -f /dev/shm/ks.p12 /dev/shm/ks.pass
    \$BT/apksigner verify --verbose --print-certs /src/cleanyourtv-launcher.apk | grep -E \"^Verified using|^Signer #1 certificate (DN|SHA-256)|^Number of signers\"
    \$BT/aapt2 dump badging /src/cleanyourtv-launcher.apk | head -1
    sha256sum /src/cleanyourtv-launcher.apk; stat -c \"size %s\" /src/cleanyourtv-launcher.apk
  '"

mkdir -p "$(dirname "$OUT")"
scp -q -i "$SSH_KEY" "$FORGE:$REMOTE_DIR/cleanyourtv-launcher.apk" "$OUT"
echo "==> $OUT"
shasum -a 256 "$OUT"
