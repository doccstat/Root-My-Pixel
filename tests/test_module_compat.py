"""Exercise the actual legacy Vector migration with disposable host fixtures."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


COMPAT = Path(__file__).resolve().parents[1] / "app/src/main/assets/module_compat.sh"
PLAIN = '''# original launcher
MODDIR="${0%/*}"
cd "$MODDIR" || exit 1
unshare --propagation slave -m "$MODDIR/daemon" --system-server-max-retry=3 "$@" &
'''
LEGACY = '''#!/system/bin/sh
MODDIR="${0%/*}"
cd "$MODDIR" || exit 1
# RMP_VECTOR_UNSHARE
unshare() {
    command unshare "$@"
}
# RMP_VECTOR_SINGLETON
if pidof vectord >/dev/null 2>&1; then
    exit 0
fi
_VECTOR_START_LOCK=/data/adb/lspd/.vectord-start
if ! mkdir "$_VECTOR_START_LOCK" 2>/dev/null; then
    exit 0
fi
(
    trap 'rmdir "$_VECTOR_START_LOCK" 2>/dev/null' EXIT
    if pidof vectord >/dev/null 2>&1; then
        exit 0
    fi
    unshare --propagation slave -m "$MODDIR/daemon" --system-server-max-retry=3 "$@"
) &
'''


class VectorMigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="module-compat-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = self.root / "modules/zygisk_vector"
        self.module.mkdir(parents=True)
        self.script = self.module / "service.sh"
        self.backup = self.module / "service.sh.rmp-orig"
        self.stale_lock = self.root / "lspd/.vectord-start"
        self.stale_lock.mkdir(parents=True)
        # Only call the Vector function; never execute ashmem or relabel steps on the host.
        # Redirect every Android state path, including literals within the awk matcher.
        self.functions = COMPAT.read_text().split("\nrepair_ashmem\n", 1)[0]
        self.functions = self.relocate(self.functions)

    def relocate(self, text):
        return text.replace("/data/adb", str(self.root))

    def migrate(self):
        result = subprocess.run(
            ["sh"], input=self.functions + "\npatch_vector\n",
            capture_output=True, text=True, timeout=5,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.module / "service.sh.rmp-tmp").exists())
        return result.stdout

    def test_upstream_launcher_is_migrated_and_arguments_preserved(self):
        self.script.write_text(PLAIN + "# keep custom suffix\n")
        self.assertIn("RMP_COMPAT_OK:vector-service", self.migrate())
        text = self.script.read_text()
        self.assertTrue(text.startswith("#!/system/bin/sh\n# VECTOR_SERVICE_V2"))
        self.assertIn("unshare -m sh -c 'mount --make-rslave / && exec \"$@\"'", text)
        self.assertIn('"$MODDIR/daemon" --system-server-max-retry=3 "$@" &', text)
        self.assertTrue(text.endswith("# keep custom suffix\n"))
        self.assertTrue(os.access(self.script, os.X_OK))
        self.assertFalse(self.stale_lock.exists())
        subprocess.run(["sh", "-n", str(self.script)], check=True)

    def test_legacy_guard_removed_and_second_run_is_idempotent(self):
        original = self.relocate(LEGACY)
        self.script.write_text(original)
        self.assertIn("RMP_COMPAT_OK:vector-service", self.migrate())
        migrated = self.script.read_text()
        self.assertNotIn("_VECTOR_START_LOCK", migrated)
        self.assertNotIn("--propagation", migrated)
        self.assertIn("pidof vectord", migrated)
        self.assertEqual(self.backup.read_text(), original)
        self.assertIn("RMP_COMPAT_OK:vector-already", self.migrate())
        self.assertEqual(self.script.read_text(), migrated)
        self.assertEqual(self.backup.read_text(), original)

    def test_migration_uses_current_script_not_old_backup(self):
        self.script.write_text(PLAIN + "# new module setting\n")
        self.backup.write_text("# much older version\n")
        self.assertIn("RMP_COMPAT_OK:vector-service", self.migrate())
        self.assertIn("# new module setting", self.script.read_text())
        self.assertEqual(self.backup.read_text(), "# much older version\n")

    def test_unknown_or_ambiguous_launchers_are_left_unchanged(self):
        for original in ("#!/bin/sh\necho custom\n", PLAIN + PLAIN):
            with self.subTest(original=original):
                self.script.write_text(original)
                self.assertIn("RMP_COMPAT_FAIL:vector-service-pattern", self.migrate())
                self.assertEqual(self.script.read_text(), original)
                self.assertTrue(self.stale_lock.exists())

    def test_malformed_legacy_guard_is_left_unchanged(self):
        original = self.relocate(LEGACY.replace('    exit 0\nfi\n(', '    exit 7\nfi\n('))
        self.script.write_text(original)
        self.assertIn("RMP_COMPAT_FAIL:vector-service-pattern", self.migrate())
        self.assertEqual(self.script.read_text(), original)
        self.assertTrue(self.stale_lock.exists())

    def test_absent_module_is_a_noop(self):
        self.assertIn("RMP_COMPAT_SKIP:vector-absent", self.migrate())
        self.assertFalse(self.backup.exists())


if __name__ == "__main__":
    unittest.main()
