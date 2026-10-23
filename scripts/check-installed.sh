#!/usr/bin/env bash
# PRODUCT-SPEC 7.3 item 3, on a device: the installed app is the release (same sha256), its
# manifest passes scripts/check-apk.py, and `dumpsys package` shows no debuggable or backup flag
# and only the D16 permissions. For the user's phone during the session (runbook section B) and
# for the phone emulators in nightly.yml.
#
#   scripts/check-installed.sh [expected-apk-sha256]      # adb must reach exactly one device, or set ANDROID_SERIAL
#   CYT_FLAVOUR=play scripts/check-installed.sh            # an install from Google Play (default: full)
set -euo pipefail
cd "$(dirname "$0")/.."
PKG=tv.cleanyour.app
EXPECT="${1:-}"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

path=$(adb shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | head -1)
[ -n "$path" ] || { echo "$PKG is not installed" >&2; exit 1; }
adb pull "$path" "$TMP/base.apk" >/dev/null
sum=$( (sha256sum "$TMP/base.apk" 2>/dev/null || shasum -a 256 "$TMP/base.apk") | cut -d' ' -f1)
echo "installed APK sha256: $sum"
status=0
if [ -n "$EXPECT" ] && [ "$sum" != "$EXPECT" ]; then echo "FAIL installed APK is not the release ($EXPECT)"; status=1; fi

python3 scripts/check-apk.py "$TMP/base.apk" --flavour "${CYT_FLAVOUR:-full}" || status=1

adb shell dumpsys package "$PKG" | tr -d '\r' > "$TMP/dumpsys.txt"
flags=$(grep -m1 -E '^\s+pkgFlags=' "$TMP/dumpsys.txt" || true)
echo "dumpsys $flags"
case "$flags" in *DEBUGGABLE*) echo "FAIL debuggable"; status=1;; esac
case "$flags" in *ALLOW_BACKUP*) echo "FAIL allowBackup"; status=1;; esac
granted=$(sed -n '/requested permissions:/,/install permissions:/p' "$TMP/dumpsys.txt" | grep -o 'android\.permission\.[A-Z_]*' | sort -u)
echo "requested permissions:"; echo "$granted" | sed 's/^/  /'
expected="android.permission.ACCESS_NETWORK_STATE
android.permission.CHANGE_WIFI_MULTICAST_STATE
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE
android.permission.INTERNET
android.permission.POST_NOTIFICATIONS"
if [ "$granted" != "$expected" ]; then echo "FAIL requested permissions differ from D16"; status=1; fi
[ "$status" = 0 ] && echo "check-installed: ok" || echo "check-installed: FAILED"
exit "$status"
