"""Offline bundle refresh tests; never execute a payload or contact a phone."""

import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("bundle", ROOT / "tools/update_kernel_bundle.py")
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class KernelBundleTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.apk = self.root / bundle.ASSET
        self.metadata = self.root / "manager.json"
        self.assets = self.root / "assets"
        self.manager = self.assets / "manager" / bundle.ASSET
        self.ksud = self.assets / "ksud/ksud"
        for path in (self.manager, self.ksud):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"old")
        self.daemon = bytearray(64)
        self.daemon[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<HH", self.daemon, 16, 3, 183)
        self.write_apk()
        self.certs = f"Signer #1 certificate SHA-256 digest: {bundle.SIGNER}\n"
        self.badging = f"package: name='{bundle.PACKAGE}' versionCode='32700' versionName='test'\n"

    def write_apk(self, member=bundle.DAEMON):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"fixture")
            archive.writestr(member, self.daemon)
        self.info = dict(package=bundle.PACKAGE, asset=bundle.ASSET,
                         upstreamCommit=bundle.UPSTREAM, sourceCommit="a" * 40,
                         uapiVersion=bundle.UAPI, versionCode=32700, versionName="test",
                         sha256=hashlib.sha256(self.apk.read_bytes()).hexdigest(),
                         size=self.apk.stat().st_size)
        self.write_metadata()

    def write_metadata(self):
        self.metadata.write_text(json.dumps(self.info))

    def run_update(self):
        def tool(command, **kwargs):
            self.assertTrue(kwargs["check"])
            self.assertEqual(command[-1], str(self.apk))
            return subprocess.CompletedProcess(command, 0, self.certs if command[0] == "apksigner" else self.badging)
        with patch.object(bundle.subprocess, "run", side_effect=tool):
            bundle.update_bundle(self.apk, self.metadata, self.assets, Path("apksigner"), Path("aapt2"))

    def assert_unchanged(self):
        self.assertEqual(self.manager.read_bytes(), b"old")
        self.assertEqual(self.ksud.read_bytes(), b"old")

    def test_matched_manager_and_embedded_daemon(self):
        self.run_update()
        self.assertEqual(self.manager.read_bytes(), self.apk.read_bytes())
        self.assertEqual(self.ksud.read_bytes(), self.daemon)
        self.assertEqual(self.ksud.stat().st_mode & 0o777, 0o755)
        self.assertEqual(self.manager.stat().st_mode & 0o777, 0o644)

    def test_stale_or_mixed_metadata_never_replaces_either_asset(self):
        for key, invalid in (("upstreamCommit", "0" * 40), ("uapiVersion", 4),
                             ("sha256", "0" * 64), ("size", 0), ("package", "me.weishu.kernelsu"),
                             ("sourceCommit", "main"), ("versionCode", 1), ("asset", "other.apk")):
            with self.subTest(key=key):
                saved = self.info[key]
                self.info[key] = invalid
                self.write_metadata()
                with self.assertRaises(ValueError):
                    self.run_update()
                self.assert_unchanged()
                self.info[key] = saved

    def test_old_publication_explains_the_manager_dependency(self):
        del self.info["upstreamCommit"]
        self.write_metadata()
        with self.assertRaisesRegex(ValueError, "successful KernelSU Build Manager publication"):
            self.run_update()
        self.assert_unchanged()

    def test_wrong_signer_or_package_is_rejected(self):
        original = self.certs
        self.certs = "Signer #1 certificate SHA-256 digest: " + "0" * 64
        with self.assertRaises(ValueError):
            self.run_update()
        self.certs = original
        self.badging = self.badging.replace(bundle.PACKAGE, "me.weishu.kernelsu")
        with self.assertRaises(ValueError):
            self.run_update()
        self.assert_unchanged()

    def test_missing_or_wrong_architecture_daemon_is_rejected(self):
        self.write_apk(member="lib/x86_64/libksud.so")
        with self.assertRaises(KeyError):
            self.run_update()
        struct.pack_into("<H", self.daemon, 18, 62)
        self.write_apk()
        with self.assertRaises(ValueError):
            self.run_update()
        self.assert_unchanged()

    def test_verifier_failure_is_not_ignored(self):
        with patch.object(bundle.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "apksigner")):
            with self.assertRaises(subprocess.CalledProcessError):
                bundle.update_bundle(self.apk, self.metadata, self.assets, Path("apksigner"), Path("aapt2"))
        self.assert_unchanged()

    def test_ci_and_runtime_do_not_mix_or_force_replace(self):
        workflow = (ROOT / ".github/workflows/build-debug-apk.yml").read_text()
        self.assertNotIn('$KSU_RELEASE_BASE/ksud"', workflow)
        self.assertNotIn("keeping the committed", workflow)
        self.assertIn("tools/update_kernel_bundle.py", workflow)
        self.assertIn("types: [ghostlock-manager-published]", workflow)
        self.assertNotIn("ghostlock-ksud-published", workflow)
        manager = (ROOT / "app/src/main/kotlin/com/lixingchi/ghostlock/utils/BundledManager.kt").read_text()
        self.assertLess(manager.index("driverMatchesBundle("), manager.index("val staged = stage("))
        self.assertIn("pm install --user 0 -r", manager)
        self.assertNotIn("pm uninstall", manager)


if __name__ == "__main__":
    unittest.main()
