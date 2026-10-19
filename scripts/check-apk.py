#!/usr/bin/env python3
"""Release APK assertions (PRODUCT-SPEC 7.3 items 3 and 5, SECURITY 6.4 item 1).

Runs aapt2 and apksigner from the build tools and checks:
  - package tv.cleanyour.app, minSdkVersion 28, targetSdkVersion 36;
  - not debuggable, not testOnly, allowBackup false, no cleartext traffic, a network security
    config;
  - exactly the D16 permissions (plus the app's own signature permission that androidx.core adds);
  - one exported component (ProfileInstaller's receiver behind android.permission.DUMP is listed
    separately: only the shell and the system can reach it);
  - signed with v2 and v3, not v1 (minSdk 28), and, when --expect-cert is given, by that
    certificate (SHA-256 of the DER certificate, lower-case hex).

  check-apk.py app-release.apk [--expect-cert HEX] [--unsigned] [--json out.json]

Exit 0 when every check passes, 1 when one fails, 2 on a usage or tool error. Stdlib only.
"""
import argparse
import json
import re
import shutil
import subprocess
import sys

PACKAGE = "tv.cleanyour.app"
MIN_SDK = 28
TARGET_SDK = 36
D16 = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    "android.permission.POST_NOTIFICATIONS",
}
# androidx.core declares and uses <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
# (signature level) for receivers registered at run time; it grants nothing to other apps.
OWN_SIGNATURE_PERMISSION = PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
NEVER = {
    "android.permission.NEARBY_WIFI_DEVICES", "android.permission.ACCESS_WIFI_STATE",
    "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.WAKE_LOCK", "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.CAMERA",
    "android.permission.QUERY_ALL_PACKAGES", "android.permission.RECEIVE_BOOT_COMPLETED",
}
DUMP_GUARDED_OK = {"androidx.profileinstaller.ProfileInstallReceiver"}


def run(cmd):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except FileNotFoundError:
        print(f"error: {cmd[0]} not found; put the build tools on PATH", file=sys.stderr)
        sys.exit(2)
    return p.returncode, p.stdout, p.stderr


def badging(apk):
    code, out, err = run(["aapt2", "dump", "badging", apk])
    if code != 0:
        print(f"error: aapt2 dump badging failed: {err.strip()}", file=sys.stderr)
        sys.exit(2)
    return out


def manifest_tree(apk):
    code, out, err = run(["aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
    if code != 0:
        print(f"error: aapt2 dump xmltree failed: {err.strip()}", file=sys.stderr)
        sys.exit(2)
    return out


class Node:
    def __init__(self, tag, depth):
        self.tag, self.depth, self.attrs, self.children = tag, depth, {}, []


def parse_tree(text):
    """aapt2 xmltree output into a small element tree (E: element, A: attribute lines)."""
    root = Node("#root", -1)
    stack = [root]
    for line in text.splitlines():
        m = re.match(r"^(\s*)E: (\S+)", line)
        if m:
            depth = len(m.group(1))
            while stack[-1].depth >= depth:
                stack.pop()
            n = Node(m.group(2), depth)
            stack[-1].children.append(n)
            stack.append(n)
            continue
        m = re.match(r"^\s*A: (?:http://schemas.android.com/apk/res/android:)?([A-Za-z]+)(?:\(0x[0-9a-f]+\))?=(.*)$", line)
        if m and len(stack) > 1:
            name, raw = m.group(1), m.group(2).strip()
            val = raw
            q = re.match(r'^"(.*)"', raw)
            if q:
                val = q.group(1)
            elif re.match(r"^\(type 0x12\)0x0$", raw):
                val = "false"
            elif re.match(r"^\(type 0x12\)0x[f]+$", raw):
                val = "true"
            elif raw.startswith("@"):
                val = raw
            stack[-1].attrs[name] = val
    return root


def find(node, tag):
    out = []
    for c in node.children:
        if c.tag == tag:
            out.append(c)
        out.extend(find(c, tag))
    return out


def exported_components(tree):
    app = (find(tree, "application") or [None])[0]
    result = []
    if app is None:
        return result
    for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
        for c in find(app, kind):
            name = c.attrs.get("name", "?")
            exported = c.attrs.get("exported")
            has_filter = bool(find(c, "intent-filter"))
            is_exported = exported == "true" or (exported is None and has_filter)
            if is_exported:
                result.append({"kind": kind, "name": name, "permission": c.attrs.get("permission")})
    return result


def signatures(apk):
    code, out, err = run(["apksigner", "verify", "--verbose", "--print-certs", apk])
    info = {"verified": code == 0, "error": err.strip() if code else ""}
    for scheme in ("v1", "v2", "v3", "v3.1", "v4"):
        m = re.search(rf"Verified using {re.escape(scheme)} scheme \(.*?\): (true|false)", out)
        info[scheme] = m and m.group(1) == "true"
    info["certs"] = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", out)
    return info


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("apk")
    ap.add_argument("--expect-cert", help="signer certificate SHA-256 (hex)")
    ap.add_argument("--unsigned", action="store_true", help="skip the signature checks (unsigned build output)")
    ap.add_argument("--json", help="write the findings as JSON here")
    a = ap.parse_args()

    checks = []

    def check(name, ok, detail=""):
        checks.append({"check": name, "ok": bool(ok), "detail": detail})

    b = badging(a.apk)
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", b)
    check("package name", pkg and pkg.group(1) == PACKAGE, pkg.group(1) if pkg else "missing")
    min_sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", b, re.M)
    check("minSdkVersion 28", min_sdk and int(min_sdk.group(1)) == MIN_SDK, min_sdk.group(1) if min_sdk else "missing")
    tgt = re.search(r"^targetSdkVersion:'(\d+)'", b, re.M)
    check("targetSdkVersion 36", tgt and int(tgt.group(1)) == TARGET_SDK, tgt.group(1) if tgt else "missing")
    check("not debuggable", "application-debuggable" not in b)
    check("not testOnly", "testOnly" not in b)

    perms = set(re.findall(r"^uses-permission: name='([^']+)'", b, re.M))
    extra = sorted(perms - D16 - {OWN_SIGNATURE_PERMISSION})
    missing = sorted(D16 - perms)
    check("permissions equal D16", not extra and not missing, f"extra={extra} missing={missing}")
    check("no forbidden permission", not (perms & NEVER), ",".join(sorted(perms & NEVER)))

    tree = parse_tree(manifest_tree(a.apk))
    app = (find(tree, "application") or [Node("application", 0)])[0]
    check("usesCleartextTraffic not true", app.attrs.get("usesCleartextTraffic") != "true", app.attrs.get("usesCleartextTraffic", "absent"))
    check("network security config present", "networkSecurityConfig" in app.attrs, app.attrs.get("networkSecurityConfig", "absent"))
    check("allowBackup false", app.attrs.get("allowBackup") == "false", app.attrs.get("allowBackup", "absent"))

    exported = exported_components(tree)
    dump_guarded = [c for c in exported if c["name"] in DUMP_GUARDED_OK and c["permission"] == "android.permission.DUMP"]
    others = [c for c in exported if c not in dump_guarded]
    check("one exported component", len(others) == 1,
          "; ".join(f"{c['kind']} {c['name']}" for c in others) +
          (f" (plus {len(dump_guarded)} behind DUMP: {', '.join(c['name'] for c in dump_guarded)})" if dump_guarded else ""))

    if not a.unsigned:
        s = signatures(a.apk)
        check("apksigner verify", s["verified"], s["error"])
        check("v1 signing off (minSdk 28)", not s["v1"])
        check("v2 signature", s["v2"])
        check("v3 signature", s["v3"])
        if a.expect_cert:
            check("signer certificate", a.expect_cert.lower() in s["certs"], ",".join(s["certs"]))

    width = max(len(c["check"]) for c in checks)
    for c in checks:
        print(f"{'ok  ' if c['ok'] else 'FAIL'} {c['check']:<{width}}  {c['detail']}".rstrip())
    failed = [c for c in checks if not c["ok"]]
    print(f"{len(checks) - len(failed)} of {len(checks)} checks passed for {a.apk}")
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"apk": a.apk, "checks": checks}, f, indent=2)
    return 1 if failed else 0


if __name__ == "__main__":
    if not shutil.which("aapt2"):
        print("error: aapt2 not on PATH (build-tools)", file=sys.stderr)
        sys.exit(2)
    sys.exit(main())
