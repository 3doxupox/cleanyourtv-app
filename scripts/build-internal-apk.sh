#!/usr/bin/env bash
# The internal 0.1 APK from a tagged commit, built on forge in the reproducible-build container
# (PRODUCT-SPEC 7.3 items 1 to 3, W9 definition of done).
#
#   scripts/build-internal-apk.sh v0.1.0             # sign with the app release key
#   scripts/build-internal-apk.sh v0.1.0 --test-key  # throwaway key, proves the pipeline only
#
# Steps: the tag's tree plus the upstream submodule at the commit the tag pins is archived (no
# working-tree files, nothing uncommitted); on forge, under the host build lock, two clean builds
# run in two fresh containers of docker/repro: A with the shared Gradle cache, B with an empty
# Gradle home of its own (so the cache cannot hide a difference); then a third container with a
# tmpfs signs A, compares it against B with apksigcopier and runs scripts/check-apk.py. Results
# land in dist/internal/<tag>/ with the sha256 printed and a release-notes stub. Containers, the
# fresh volume and the remote directory are removed afterwards, also on failure or Ctrl-C.
#
# The release key never touches forge's disk: the keystore and its password file are streamed
# over ssh into the signing container's tmpfs and are gone when it exits. Key locations (jarvis
# only, see docs/runbook-0.1.md "Key custody"):
#   CYT_RELEASE_KEYSTORE      default /Users/dev/jarvis/secrets/cleanyourtv-app/release.p12
#   CYT_RELEASE_KEY_PASS_FILE default /Users/dev/jarvis/secrets/cleanyourtv-app/release.pass
#   CYT_RELEASE_KEY_ALIAS     default cleanyourtv
#   CYT_RELEASE_LINEAGE       optional rotation lineage file
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${1:?usage: build-internal-apk.sh <tag> [--test-key]}"
TEST_KEY=0
[ "${2:-}" = "--test-key" ] && TEST_KEY=1

FORGE="${FORGE:-builder@build-host}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
SSH=(ssh -i "$SSH_KEY" -o ConnectTimeout=10 -o BatchMode=yes "$FORGE")
LOCK=/home/builder/build/.cyt-forge.lock
SECRETS=/Users/dev/jarvis/secrets/cleanyourtv-app
KS="${CYT_RELEASE_KEYSTORE:-$SECRETS/release.p12}"
KS_PASS="${CYT_RELEASE_KEY_PASS_FILE:-$SECRETS/release.pass}"
KS_ALIAS="${CYT_RELEASE_KEY_ALIAS:-cleanyourtv}"
LINEAGE="${CYT_RELEASE_LINEAGE:-}"

COMMIT=$(git rev-parse --verify "$TAG^{commit}")
SUB=$(git ls-tree "$COMMIT" third_party/cleanyourtv | awk '{print $3}')
[ -n "$SUB" ] || { echo "the tag has no third_party/cleanyourtv submodule entry" >&2; exit 1; }
git -C third_party/cleanyourtv cat-file -e "$SUB^{commit}" 2>/dev/null \
  || { echo "submodule commit $SUB is not in the local submodule clone; run git submodule update" >&2; exit 1; }
EPOCH=$(git log -1 --format=%ct "$COMMIT")
VERSION="${TAG#v}"
NAME="cleanyourtv-$VERSION"
if [ "$TEST_KEY" = 0 ]; then
  [ -f "$KS" ] && [ -f "$KS_PASS" ] || {
    echo "no release key at $KS (and $KS_PASS)." >&2
    echo "Generate it offline yourself (docs/runbook-0.1.md, Key custody), or run with --test-key to prove the pipeline." >&2
    exit 1
  }
fi

TAG_IMAGE="cyt-build:$(shasum -a 256 docker/repro/Dockerfile | cut -c1-12)"
RUN_ID="cyt-rel-$(date +%Y%m%d%H%M%S)-$$"
REMOTE="/home/builder/build/$RUN_ID"
SCRATCH=$(mktemp -d)
cleanup() {
  "${SSH[@]}" "docker rm -f $RUN_ID-a $RUN_ID-b $RUN_ID-sign >/dev/null 2>&1; docker volume rm $RUN_ID-gradle >/dev/null 2>&1; rm -rf '$REMOTE'" </dev/null || true
  rm -rf "$SCRATCH"
}
trap cleanup EXIT INT TERM

echo "==> $TAG = $COMMIT (submodule $SUB), SOURCE_DATE_EPOCH=$EPOCH"
git archive --format=tar --prefix=src/ "$COMMIT" > "$SCRATCH/src.tar"
git -C third_party/cleanyourtv archive --format=tar --prefix=src/third_party/cleanyourtv/ "$SUB" > "$SCRATCH/sub.tar"
# The build scripts come from the tag too, so the tagged commit must contain them.
tar -tf "$SCRATCH/src.tar" src/scripts/repro-build.sh >/dev/null 2>&1 \
  || { echo "$TAG predates scripts/repro-build.sh; tag a commit that has the W9 release scripts" >&2; exit 1; }

"${SSH[@]}" "mkdir -p '$REMOTE/a' '$REMOTE/b' '$REMOTE/out'" </dev/null
scp -q -i "$SSH_KEY" "$SCRATCH/src.tar" "$SCRATCH/sub.tar" "$FORGE:$REMOTE/"
tar -xOf "$SCRATCH/src.tar" src/docker/repro/Dockerfile > "$SCRATCH/Dockerfile"

echo "==> image $TAG_IMAGE"
"${SSH[@]}" "docker image inspect '$TAG_IMAGE' >/dev/null 2>&1 || docker build -q -t '$TAG_IMAGE' -" < "$SCRATCH/Dockerfile"

# One build in one fresh container: unpack the archive at /src, build, copy the outputs out.
build() {  # <name> <gradle home mount>
  local n="$1" home="$2"
  echo "==> build $n ($home)"
  "${SSH[@]}" "flock -w 3600 $LOCK docker run --rm --name $RUN_ID-$n --memory 3g --memory-swap 3g --cpus 4 \
    -e SOURCE_DATE_EPOCH=$EPOCH -e OWNER=\$(id -u):\$(id -g) -v $home -v '$REMOTE':/in:ro -v '$REMOTE/$n':/out \
    '$TAG_IMAGE' bash -euc 'tar -xf /in/src.tar -C / && tar -xf /in/sub.tar -C / && cd /src \
      && scripts/repro-build.sh /out && chown -R \$OWNER /out'" </dev/null
}
build a "mitv-android-cache:/cache -e GRADLE_USER_HOME=/cache/gradle"
build b "$RUN_ID-gradle:/gradle"

echo "==> sign and compare"
KEYTAR="$SCRATCH/keys.tar"
if [ "$TEST_KEY" = 0 ]; then
  mkdir "$SCRATCH/keys" && cp "$KS" "$SCRATCH/keys/release.ks" && cp "$KS_PASS" "$SCRATCH/keys/release.pass"
  [ -n "$LINEAGE" ] && cp "$LINEAGE" "$SCRATCH/keys/lineage"
  tar -cf "$KEYTAR" -C "$SCRATCH/keys" . && rm -rf "$SCRATCH/keys"
else
  tar -cf "$KEYTAR" -T /dev/null
fi
set +e
"${SSH[@]}" "flock -w 3600 $LOCK docker run --rm -i --name $RUN_ID-sign --memory 1g --tmpfs /keys:size=8m,mode=0700 \
  -e OWNER=\$(id -u):\$(id -g) -e TEST_KEY=$TEST_KEY -e KS=/keys/release.ks -e KS_ALIAS=$KS_ALIAS -e KS_PASS_FILE=/keys/release.pass \
  $( [ -n "$LINEAGE" ] && echo "-e LINEAGE=/keys/lineage" ) \
  -v '$REMOTE':/in:ro -v '$REMOTE/out':/out '$TAG_IMAGE' bash -euc 'tar -xf - -C /keys \
    && tar -xf /in/src.tar -C / && cd /src \
    && { scripts/sign-and-compare.sh /in/a /in/b /out $NAME; s=\$?; chown -R \$OWNER /out; exit \$s; }'" < "$KEYTAR"
SIGN_STATUS=$?
set -e
rm -f "$KEYTAR"

DEST="dist/internal/$TAG"
mkdir -p "$DEST"
rsync -az -e "ssh -i $SSH_KEY -o BatchMode=yes" "$FORGE:$REMOTE/out/" "$DEST/"
rsync -az -e "ssh -i $SSH_KEY -o BatchMode=yes" "$FORGE:$REMOTE/a/mapping/" "$DEST/mapping/" 2>/dev/null || true

APK_FILE=$(ls "$DEST"/*.apk 2>/dev/null | head -1 || true)
if [ -n "$APK_FILE" ]; then
  SUM=$(shasum -a 256 "$APK_FILE" | cut -d' ' -f1)
  cat > "$DEST/RELEASE-NOTES.md" <<EOF
# Clean Your TV $VERSION (internal)

- Tag: $TAG
- Commit: $COMMIT
- Upstream launcher submodule: $SUB
- APK: $(basename "$APK_FILE")
- SHA-256: $SUM
- Signer certificate SHA-256: $(sed -n 's/^signer certificate SHA-256: //p' "$DEST/signer.txt" 2>/dev/null)
- Reproducible: two clean container builds byte identical; apksigcopier compare matched
- Checks: $(tail -1 "$DEST/check-apk.txt" 2>/dev/null)
$( [ "$TEST_KEY" = 1 ] && echo "- SIGNED WITH A THROWAWAY TEST KEY: not for installation on anyone's phone" )
EOF
  echo
  echo "$SUM  $APK_FILE"
fi
echo "==> sign and compare exit $SIGN_STATUS; outputs in $DEST"
exit "$SIGN_STATUS"
