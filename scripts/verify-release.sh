#!/usr/bin/env bash
# Check a signed release APK against an unsigned reproducible build of the same commit. Used by
# scripts/release-on-forge.sh after signing, by the verify job of .github/workflows/release.yml,
# and by anyone who rebuilt the app and wants to confirm the published APK is that build.
#
#   scripts/verify-release.sh <signed.apk> <unsigned.apk> [expected-signer-sha256] [out-dir]
#
# Needs the Android build tools on PATH (apksigner, aapt2; the docker/repro image has them),
# apksigcopier and python3. The expected signer defaults to docs/release-signer.sha256.
#   1. apksigner: the signature verifies, v2 and v3 present, one signer, the release certificate;
#   2. apksigcopier compare: the signed APK is exactly the unsigned build plus a signature, so the
#      published APK contains nothing the public source did not build;
#   3. scripts/check-apk.py: package, SDK levels, permissions, exported components, schemes, signer.
# Exit 0 when all pass.
set -euo pipefail
SIGNED="${1:?usage: verify-release.sh <signed.apk> <unsigned.apk> [expected-signer-sha256] [out-dir]}"
UNSIGNED="${2:?unsigned build APK}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
EXPECT="${3:-$(tr -d ' \n' < "$HERE/docs/release-signer.sha256")}"
OUT="${4:-$(mktemp -d)}"
mkdir -p "$OUT"
printf '%s' "$EXPECT" | grep -Eq '^[0-9a-f]{64}$' || { echo "expected signer is not a SHA-256 in lower-case hex" >&2; exit 2; }

echo "==> 1. apksigner verify"
apksigner verify --verbose --print-certs --min-sdk-version 28 "$SIGNED" > "$OUT/apksigner-full.txt"
grep -E "^Verifie|Verified using|Number of signers|Signer #1 certificate (DN|SHA-256)" "$OUT/apksigner-full.txt" | tee "$OUT/apksigner-verify.txt"
grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" "$OUT/apksigner-full.txt" || { echo "FAIL: no v2 signature" >&2; exit 1; }
grep -q "Verified using v3 scheme (APK Signature Scheme v3): true" "$OUT/apksigner-full.txt" || { echo "FAIL: no v3 signature" >&2; exit 1; }
grep -q "Number of signers: 1" "$OUT/apksigner-full.txt" || { echo "FAIL: not exactly one signer" >&2; exit 1; }
CERT=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$OUT/apksigner-full.txt")
[ "$CERT" = "$EXPECT" ] || { echo "FAIL: signer $CERT is not the expected $EXPECT" >&2; exit 1; }
echo "signer: $CERT (expected)"

echo "==> 2. apksigcopier compare (signed against the unsigned build)"
if ! apksigcopier compare "$SIGNED" --unsigned "$UNSIGNED"; then
  echo "FAIL: the signed APK is not the unsigned build plus a signature" >&2
  exit 1
fi
echo "apksigcopier compare: match" | tee "$OUT/apksigcopier.txt"

echo "==> 3. APK assertions"
python3 "$HERE/scripts/check-apk.py" "$SIGNED" --expect-cert "$EXPECT" --json "$OUT/check-apk.json" | tee "$OUT/check-apk.txt"
echo "==> verified: $(sha256sum "$SIGNED" 2>/dev/null || shasum -a 256 "$SIGNED")"
