#!/usr/bin/env bash
# The signed release APK, built reproducibly on the build host and signed on this machine.
#
#   scripts/release-on-forge.sh              # the committed HEAD, version from the app's versionName
#   scripts/release-on-forge.sh --test-key   # a throwaway key: proves the pipeline, never for users
#
# 1. The committed tree plus the upstream submodule at its pinned commit is archived (no working
#    tree files: the tree must be clean). SOURCE_DATE_EPOCH is the commit time.
# 2. On the build host (scripts/lib/forge.sh), under the host build lock, two clean builds of
#    :app:assembleRelease run in two fresh containers of docker/repro (scripts/repro-build.sh): A
#    with the shared Gradle cache, B with an empty Gradle home of its own, so a cache cannot hide a
#    difference. The two unsigned APKs must be byte identical.
#    BUILD_MEMORY caps each build container (default 4g: a cache-less build with R8 needs more
#    than the 3g of scripts/build-on-forge.sh).
# 3. The unsigned APK and apksigner (from the same pinned build tools image) come back here and the
#    APK is signed HERE with the release key: v2 and v3, v1 off (minSdk 28). The key and its
#    password never leave this machine, not even into a container on the build host.
# 4. apksigner verifies the signature here and the signer certificate must be the release
#    certificate. The signed APK (public data) goes back to a container that runs apksigcopier
#    compare against build B (the F-Droid check: the signed APK is build B plus a signature) and
#    scripts/check-apk.py (manifest, permissions, schemes, signer).
#
# Outputs: dist/cleanyourtv-<version>.apk, .apk.sha256 and .signer-sha256.txt, and the evidence
# (unsigned sha256s, R8 mapping, apksigner and check-apk output, notes) in dist/release-<version>/.
#
# Keys ($CYT_SECRETS, see scripts/lib/forge.sh), overridable one by one:
#   CYT_RELEASE_KEYSTORE      default $CYT_SECRETS/cleanyourtv-release/release.p12 (PKCS#12)
#   CYT_RELEASE_KEY_PASS_FILE default $CYT_SECRETS/cleanyourtv-release/keystore-password.txt
#   CYT_RELEASE_KEY_ALIAS     default cleanyourtv
# The expected signer is docs/release-signer.sha256 (the public certificate's SHA-256).
# JAVA: a JDK 17 or newer here for apksigner (default: java on PATH); KEYTOOL for --test-key
# (default: keytool next to JAVA).
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/forge.sh

TEST_KEY=0
[ "${1:-}" = "--test-key" ] && TEST_KEY=1
JAVA="${JAVA:-java}"
KS="${CYT_RELEASE_KEYSTORE:-$CYT_SECRETS/cleanyourtv-release/release.p12}"
KS_PASS="${CYT_RELEASE_KEY_PASS_FILE:-$CYT_SECRETS/cleanyourtv-release/keystore-password.txt}"
KS_ALIAS="${CYT_RELEASE_KEY_ALIAS:-cleanyourtv}"
EXPECT_CERT=$(tr -d ' \n' < docs/release-signer.sha256)

[ -z "$(git status --porcelain)" ] || { echo "the working tree is not clean; commit first (the build uses the commit)" >&2; exit 2; }
[ -f third_party/cleanyourtv/README.md ] || { echo "upstream submodule is empty; run git submodule update --init" >&2; exit 2; }
"$JAVA" -version >/dev/null 2>&1 || { echo "no JDK here ($JAVA); set JAVA to a JDK 17+ java" >&2; exit 2; }
printf '%s' "$EXPECT_CERT" | grep -Eq '^[0-9a-f]{64}$' || { echo "docs/release-signer.sha256 is not a SHA-256" >&2; exit 2; }
if [ "$TEST_KEY" = 0 ]; then
  [ -r "$KS" ] && [ -r "$KS_PASS" ] || { echo "release key not found ($KS, $KS_PASS)" >&2; exit 2; }
fi

COMMIT=$(git rev-parse HEAD)
SUB=$(git ls-tree "$COMMIT" third_party/cleanyourtv | awk '{print $3}')
EPOCH=$(git log -1 --format=%ct "$COMMIT")
VERSION=$(sed -n 's/.*versionName = "\([0-9][0-9A-Za-z.-]*\)".*/\1/p' build-logic/convention/src/main/kotlin/AndroidConventionPlugins.kt | head -1)
printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' || { echo "versionName '$VERSION' not understood" >&2; exit 2; }
NAME="cleanyourtv-$VERSION"
[ "$TEST_KEY" = 1 ] && NAME="$NAME-TESTKEY-not-for-distribution"

forge_init -o ServerAliveInterval=30 -o ServerAliveCountMax=4
TAG_IMAGE="cyt-build:$(shasum -a 256 docker/repro/Dockerfile | cut -c1-12)"
RUN_ID="cyt-rel-$(date +%Y%m%d%H%M%S)-$$"
REMOTE="$FORGE_ROOT/$RUN_ID"
SCRATCH=$(mktemp -d)
cleanup() {
  "${SSH[@]}" "docker rm -f $RUN_ID-a $RUN_ID-b $RUN_ID-check >/dev/null 2>&1; docker volume rm $RUN_ID-gradle >/dev/null 2>&1; rm -rf '$REMOTE'" </dev/null || true
  rm -rf "$SCRATCH"
}
trap cleanup EXIT INT TERM

echo "==> $NAME from $COMMIT (submodule $SUB), SOURCE_DATE_EPOCH=$EPOCH"
git archive --format=tar --prefix=src/ "$COMMIT" > "$SCRATCH/src.tar"
git -C third_party/cleanyourtv archive --format=tar --prefix=src/third_party/cleanyourtv/ "$SUB" > "$SCRATCH/sub.tar"
"${SSH[@]}" "mkdir -p '$REMOTE/a' '$REMOTE/b' '$REMOTE/out'" </dev/null
scp -q -i "$SSH_KEY" -o BatchMode=yes "$SCRATCH/src.tar" "$SCRATCH/sub.tar" "$FORGE:$REMOTE/"

echo "==> image $TAG_IMAGE"
"${SSH[@]}" "docker image inspect '$TAG_IMAGE' >/dev/null 2>&1 || docker build -q -t '$TAG_IMAGE' -" < docker/repro/Dockerfile

build() {  # <name> <gradle home mount and env>
  local n="$1" home="$2"
  echo "==> clean build $n"
  "${SSH[@]}" "flock -w 3600 '$LOCK' docker run --rm --name $RUN_ID-$n --memory ${BUILD_MEMORY:-4g} --memory-swap ${BUILD_MEMORY:-4g} --cpus 4 \
    -e SOURCE_DATE_EPOCH=$EPOCH -e OWNER=\$(id -u):\$(id -g) -v $home -v '$REMOTE':/in:ro -v '$REMOTE/$n':/out \
    '$TAG_IMAGE' bash -euc 'tar -xf /in/src.tar -C / && tar -xf /in/sub.tar -C / && cd /src \
      && { scripts/repro-build.sh /out; s=\$?; chown -R \$OWNER /out; exit \$s; }'" </dev/null
}
build a "mitv-android-cache:/cache -e GRADLE_USER_HOME=/cache/gradle"
# B: an empty Gradle home; only the wrapper's distribution zip is seeded (checked against
# distributionSha256Sum by the wrapper before it unpacks it).
build b "$RUN_ID-gradle:/gradle -v mitv-android-cache:/seed:ro -e SEED_GRADLE=/seed/gradle"

echo "==> compare the two unsigned builds"
"${SSH[@]}" "cd '$REMOTE' && sha256sum a/app-release-unsigned.apk b/app-release-unsigned.apk && cmp a/app-release-unsigned.apk b/app-release-unsigned.apk" </dev/null \
  || { echo "the two clean builds differ: not reproducible, nothing signed" >&2; exit 1; }

DEST="dist/release-$VERSION"
rm -rf "$DEST" && mkdir -p "$DEST"
scp -q -i "$SSH_KEY" -o BatchMode=yes "$FORGE:$REMOTE/a/app-release-unsigned.apk" "$DEST/$NAME-unsigned.apk"
rsync -az -e "ssh -i $SSH_KEY -o BatchMode=yes" "$FORGE:$REMOTE/a/mapping/" "$DEST/mapping/" 2>/dev/null || true
# apksigner from the pinned build tools of the same image, so signing uses the version CI verifies with.
"${SSH[@]}" "docker run --rm '$TAG_IMAGE' cat /opt/android-sdk/build-tools/36.0.0/lib/apksigner.jar" </dev/null > "$SCRATCH/apksigner.jar"
APKSIGNER=("$JAVA" -jar "$SCRATCH/apksigner.jar")

echo "==> sign here (v2 and v3, v1 off)"
SIGNED="dist/$NAME.apk"
if [ "$TEST_KEY" = 1 ]; then
  KS="$SCRATCH/test.p12" KS_PASS="$SCRATCH/test.pass" KS_ALIAS=test
  head -c 24 /dev/urandom | base64 > "$KS_PASS"
  "${KEYTOOL:-$(dirname "$(command -v "$JAVA")")/keytool}" -genkeypair -keystore "$KS" -storetype PKCS12 -alias test -keyalg RSA -keysize 3072 -validity 2 \
    -dname "CN=Clean Your TV throwaway test key, O=not for distribution" \
    -storepass:file "$KS_PASS" -keypass:file "$KS_PASS" >/dev/null 2>&1
fi
# --min-sdk-version 24 only steers the scheme choice (with 28 apksigner writes v3 alone);
# --alignment-preserved keeps the zip exactly as built, so apksigcopier can compare it with build B.
"${APKSIGNER[@]}" sign --ks "$KS" --ks-type PKCS12 --ks-key-alias "$KS_ALIAS" --ks-pass "file:$KS_PASS" \
  --min-sdk-version 24 --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --alignment-preserved --out "$SIGNED" "$DEST/$NAME-unsigned.apk"
rm -f "$SIGNED.idsig"

echo "==> verify here"
"${APKSIGNER[@]}" verify --verbose --print-certs --min-sdk-version 28 "$SIGNED" \
  | grep -E "^Verifie|Verified using|Number of signers|Signer #1 certificate (DN|SHA-256)" | tee "$DEST/apksigner-verify.txt"
grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" "$DEST/apksigner-verify.txt" || { echo "no v2 signature" >&2; exit 1; }
grep -q "Verified using v3 scheme (APK Signature Scheme v3): true" "$DEST/apksigner-verify.txt" || { echo "no v3 signature" >&2; exit 1; }
CERT=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$DEST/apksigner-verify.txt")
if [ "$TEST_KEY" = 0 ] && [ "$CERT" != "$EXPECT_CERT" ]; then
  echo "signer $CERT is not the release certificate $EXPECT_CERT" >&2; exit 1
fi

echo "==> apksigcopier compare with build B, APK assertions (in the build image)"
scp -q -i "$SSH_KEY" -o BatchMode=yes "$SIGNED" "$FORGE:$REMOTE/out/signed.apk"
"${SSH[@]}" "flock -w 3600 '$LOCK' docker run --rm --name $RUN_ID-check --memory 1g -e OWNER=\$(id -u):\$(id -g) \
  -v '$REMOTE':/in:ro -v '$REMOTE/out':/out '$TAG_IMAGE' bash -euc 'tar -xf /in/src.tar -C / && cd /src \
    && { scripts/verify-release.sh /in/out/signed.apk /in/b/app-release-unsigned.apk $CERT /out; s=\$?; chown -R \$OWNER /out; exit \$s; }'" </dev/null
rsync -az -e "ssh -i $SSH_KEY -o BatchMode=yes" --exclude signed.apk "$FORGE:$REMOTE/out/" "$DEST/"

SUM=$(shasum -a 256 "$SIGNED" | cut -d' ' -f1)
( cd dist && shasum -a 256 "$NAME.apk" > "$NAME.apk.sha256" )
echo "$CERT" > "dist/$NAME.signer-sha256.txt"
UNSIGNED_SUM=$(shasum -a 256 "$DEST/$NAME-unsigned.apk" | cut -d' ' -f1)
cat > "$DEST/NOTES.md" <<EOF
# Clean Your TV $VERSION

- Commit: $COMMIT
- Upstream launcher submodule: $SUB
- APK: $NAME.apk, $(wc -c < "$SIGNED" | tr -d ' ') bytes
- SHA-256: $SUM
- Signer certificate SHA-256: $CERT
- Unsigned reproducible build SHA-256 (both clean builds): $UNSIGNED_SUM
- Checks: $(tail -1 "$DEST/check-apk.txt" 2>/dev/null)
$( [ "$TEST_KEY" = 1 ] && echo "- SIGNED WITH A THROWAWAY TEST KEY: not for anyone's phone" )
EOF
echo
echo "$SUM  $SIGNED"
echo "signer certificate SHA-256: $CERT"
echo "==> done; evidence in $DEST"
