#!/usr/bin/env bash
# Inside the reproducible-build container (docker/repro), after two clean builds:
#   1. the two unsigned APKs must be byte identical;
#   2. sign build A with apksigner: v2 and v3 (with the rotation lineage when one exists), v1 off
#      because minSdk is 28;
#   3. `apksigcopier compare` the signed APK with build B (signature copied over, then compared),
#      the check F-Droid runs;
#   4. scripts/check-apk.py on the signed APK; SHA256SUMS.
#
#   scripts/sign-and-compare.sh <build-a-dir> <build-b-dir> <out-dir> <apk-name>
#
# Key: KS (keystore), KS_ALIAS, KS_PASS_FILE (one line, the store and key password), optional
# LINEAGE and EXPECT_CERT. The key is expected on a tmpfs (scripts/build-internal-apk.sh streams
# it in; release.yml decodes it from the release environment's secrets) and is never written to a
# disk of the build host. TEST_KEY=1 generates a throwaway key here instead and marks every output
# as not for distribution.
set -euo pipefail
A="${1:?build A dir}"; B="${2:?build B dir}"; OUT="${3:?out dir}"; NAME="${4:?apk name, e.g. cleanyourtv-0.1.0}"
mkdir -p "$OUT"
UA="$A/app-release-unsigned.apk"; UB="$B/app-release-unsigned.apk"

echo "==> 1. unsigned builds"
sha256sum "$UA" "$UB"
if cmp -s "$UA" "$UB"; then echo "identical: yes"; else echo "identical: NO, the build is not reproducible" >&2; exit 1; fi

if [ "${TEST_KEY:-0}" = "1" ]; then
  NAME="$NAME-TESTKEY-not-for-distribution"
  KS=/keys/test.jks KS_ALIAS=test KS_PASS_FILE=/keys/test.pass
  mkdir -p /keys && head -c 24 /dev/urandom | base64 > "$KS_PASS_FILE"
  keytool -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$KS_ALIAS" -keyalg RSA -keysize 3072 \
    -validity 2 -dname "CN=Clean Your TV throwaway test key, O=not for distribution" \
    -storepass:file "$KS_PASS_FILE" -keypass:file "$KS_PASS_FILE" >/dev/null 2>&1
  echo "==> throwaway test key generated in the container"
fi
: "${KS:?KS (keystore path) not set}" "${KS_ALIAS:?KS_ALIAS not set}" "${KS_PASS_FILE:?KS_PASS_FILE not set}"

echo "==> 2. sign build A"
SIGNED="$OUT/$NAME.apk"
apksigner sign --ks "$KS" --ks-key-alias "$KS_ALIAS" --ks-pass "file:$KS_PASS_FILE" --key-pass "file:$KS_PASS_FILE" \
  --min-sdk-version 28 --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  ${LINEAGE:+--lineage "$LINEAGE"} --out "$SIGNED" "$UA"
rm -f "$SIGNED.idsig"
apksigner verify --verbose --print-certs "$SIGNED" | grep -E "Verified using|Signer #1 certificate (DN|SHA-256)|Number of signers" | tee "$OUT/apksigner-verify.txt"
CERT=$(apksigner verify --print-certs "$SIGNED" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)

echo "==> 3. apksigcopier compare (signed A against unsigned B)"
apksigcopier compare "$SIGNED" --unsigned "$UB" && echo "apksigcopier compare: match" | tee "$OUT/apksigcopier.txt"

echo "==> 4. APK assertions"
set +e
python3 scripts/check-apk.py "$SIGNED" --expect-cert "${EXPECT_CERT:-$CERT}" --json "$OUT/check-apk.json" | tee "$OUT/check-apk.txt"
CHECK=${PIPESTATUS[0]}
set -e

( cd "$OUT" && sha256sum "$NAME.apk" > SHA256SUMS && cat SHA256SUMS )
echo "signer certificate SHA-256: $CERT" | tee "$OUT/signer.txt"
if [ "$CHECK" != 0 ]; then
  echo "check-apk.py found failures (see check-apk.txt); the APK is built and signed but not releasable" >&2
  exit 3
fi
