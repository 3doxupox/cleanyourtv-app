"""Tests for the manifest parsing in scripts/check-apk.py (no build tools needed).
Run: python3 -m unittest discover -s scripts/tests"""
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("check_apk", os.path.join(HERE, "..", "check-apk.py"))
ca = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ca)

# Shape of `aapt2 dump xmltree --file AndroidManifest.xml` (build tools 36), trimmed.
TREE = '''N: android=http://schemas.android.com/apk/res/android (line=6)
  E: manifest (line=6)
    A: package="tv.cleanyour.app" (Raw: "tv.cleanyour.app")
      E: application (line=21)
        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false
        A: http://schemas.android.com/apk/res/android:networkSecurityConfig(0x01010527)=@0x7f120000
          E: activity (line=27)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="tv.cleanyour.app.MainActivity" (Raw: "tv.cleanyour.app.MainActivity")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
              E: intent-filter (line=30)
                E: action (line=31)
                  A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
          E: service (line=40)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="tv.cleanyour.app.OperationService" (Raw: "tv.cleanyour.app.OperationService")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false
          E: receiver (line=50)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.profileinstaller.ProfileInstallReceiver" (Raw: "androidx.profileinstaller.ProfileInstallReceiver")
            A: http://schemas.android.com/apk/res/android:permission(0x01010006)="android.permission.DUMP" (Raw: "android.permission.DUMP")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
'''


class ManifestParsing(unittest.TestCase):
    def setUp(self):
        self.tree = ca.parse_tree(TREE)

    def test_application_attributes(self):
        app = ca.find(self.tree, "application")[0]
        self.assertEqual("false", app.attrs["allowBackup"])
        self.assertIn("networkSecurityConfig", app.attrs)
        self.assertNotIn("usesCleartextTraffic", app.attrs)

    def test_exported_components_with_clean_names(self):
        exported = ca.exported_components(self.tree)
        names = [c["name"] for c in exported]
        self.assertEqual(["tv.cleanyour.app.MainActivity", "androidx.profileinstaller.ProfileInstallReceiver"], names)
        receiver = exported[1]
        self.assertEqual("android.permission.DUMP", receiver["permission"])

    def test_old_boolean_encoding(self):
        t = ca.parse_tree('  E: manifest (line=1)\n    E: application (line=2)\n'
                          '      A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=(type 0x12)0xffffffff\n')
        self.assertEqual("true", ca.find(t, "application")[0].attrs["debuggable"])


if __name__ == "__main__":
    unittest.main()
