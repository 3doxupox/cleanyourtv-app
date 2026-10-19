#!/usr/bin/env bash
# Build the app on forge in a throwaway JDK 21 container (AGP 9 runs on JDK 17 or newer; the JVM
# modules use a JDK 21 toolchain). jarvis cannot build Android itself.
#
#   scripts/build-on-forge.sh                      # ./gradlew check assembleDebug
#   scripts/build-on-forge.sh checkModuleGraph     # any Gradle tasks and flags instead
#   FETCH_BACK="gradlew gradle/wrapper" scripts/build-on-forge.sh wrapper   # copy files back on success
#
# The checkout (with the upstream submodule's files) is rsynced to a fresh directory on forge, the
# container shares the mitv-android-cache volume (Android SDK and Gradle caches) with the other
# forge builds, runs with a 3 GB memory cap, and both the container and the remote directory are
# removed afterwards, also on failure or Ctrl-C. A debug APK, when built, is copied to dist/.
set -euo pipefail
cd "$(dirname "$0")/.."

FORGE="${FORGE:-builder@build-host}"   # Tailscale address; the LAN address is unreachable from jarvis
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
IMAGE="${IMAGE:-eclipse-temurin:21-jdk}"
VOLUME="mitv-android-cache"
RUN_ID="cyt-$(date +%Y%m%d%H%M%S)-$$"
REMOTE_DIR="/home/builder/build/$RUN_ID"
CONTAINER="$RUN_ID"
CMDLINE_TOOLS="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
# Licences only: AGP downloads the platform and build tools it needs into the shared SDK.
SDK_PACKAGES='"platform-tools"'
SSH=(ssh -i "$SSH_KEY" -o ConnectTimeout=10 -o BatchMode=yes "$FORGE")

TASKS="${*:-check assembleDebug}"

[ -f third_party/cleanyourtv/README.md ] || {
  echo "upstream submodule is empty; run: git submodule update --init third_party/cleanyourtv" >&2
  exit 1
}

cleanup() {
  "${SSH[@]}" "docker rm -f '$CONTAINER' >/dev/null 2>&1 || true; rm -rf '$REMOTE_DIR'" </dev/null || true
}
trap cleanup EXIT INT TERM

echo "==> rsync to $FORGE:$REMOTE_DIR"
"${SSH[@]}" "mkdir -p '$REMOTE_DIR'" </dev/null
rsync -az --delete -e "ssh -i $SSH_KEY -o BatchMode=yes" \
  --exclude .git --exclude .gradle/ --exclude .kotlin/ --exclude build/ --exclude local.properties \
  --exclude dist/ --exclude .idea/ \
  ./ "$FORGE:$REMOTE_DIR/"

echo "==> ./gradlew $TASKS in $IMAGE"
set +e
"${SSH[@]}" "mkdir -p /home/builder/build && flock -w 3600 /home/builder/build/.cyt-forge.lock docker run --rm --name '$CONTAINER' --memory 3g --cpus 4 \
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
    \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager $SDK_PACKAGES >/dev/null
    echo sdk.dir=\$ANDROID_HOME > local.properties
    chmod +x gradlew
    status=0
    ./gradlew $TASKS --console=plain || status=\$?
    echo \"==> test results\"
    find . -path \"*/build/test-results/*\" -name \"*.xml\" -exec cat {} + 2>/dev/null \
      | grep -o \"<testsuite name=[^ ]* tests=[^ ]* skipped=[^ ]* failures=[^ ]* errors=[^ ]*\" || true
    find . -path \"*/build/test-results/*\" -name \"*.xml\" -exec grep -l \"<failure\" {} + 2>/dev/null || true
    for apk in \$(find app/build/outputs/apk -name \"*.apk\" 2>/dev/null); do ls -l \$apk; sha256sum \$apk; done
    exit \$status
  '" </dev/null
STATUS=$?
set -e

if [ "$STATUS" -eq 0 ] && [ -n "${FETCH_BACK:-}" ]; then
  for p in $FETCH_BACK; do
    echo "==> fetch back $p"
    rsync -az -e "ssh -i $SSH_KEY -o BatchMode=yes" "$FORGE:$REMOTE_DIR/$p" "$(dirname "$p")/"
  done
fi
if [ "$STATUS" -eq 0 ]; then
  APK=$("${SSH[@]}" "ls '$REMOTE_DIR'/app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1" </dev/null || true)
  if [ -n "$APK" ]; then
    mkdir -p dist
    scp -q -i "$SSH_KEY" "$FORGE:$APK" dist/cleanyourtv-debug.apk
    shasum -a 256 dist/cleanyourtv-debug.apk
  fi
fi
echo "==> gradle exit $STATUS; tidying forge"
exit "$STATUS"
