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
V2 = '''#!/system/bin/sh
# VECTOR_SERVICE_V2: daemon lock, but old mount syntax
MODDIR="${0%/*}"
cd "$MODDIR" || exit 1
MODDIR="$PWD"
(
    unshare -m sh -c 'mount --make-rslave / && exec "$@"' vector-namespace "$MODDIR/daemon" --system-server-max-retry=3 "$@"
    status=$?
    [ "$status" = 0 ] || echo "Daemon launch failed (exit $status)" >&2
) </dev/null &
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
        self.assertIn("unshare -m sh -c '/system/bin/mount -t rootfs -o rslave rootfs / && exec \"$@\"'", text)
        self.assertIn('"$MODDIR/daemon" --system-server-max-retry=3 "$@" &', text)
        self.assertTrue(text.endswith("# keep custom suffix\n"))
        self.assertTrue(os.access(self.script, os.X_OK))
        self.assertFalse(self.stale_lock.exists())
        subprocess.run(["sh", "-n", str(self.script)], check=True)

    def test_v2_mount_is_migrated_without_reverting_diagnostics_or_arguments(self):
        self.script.write_text(V2 + "# keep custom suffix\n")
        self.backup.write_text(self.relocate(LEGACY))
        self.assertIn("RMP_COMPAT_OK:vector-service", self.migrate())
        expected = (V2 + "# keep custom suffix\n").replace(
            "mount --make-rslave /", "/system/bin/mount -t rootfs -o rslave rootfs /"
        )
        self.assertEqual(self.script.read_text(), expected)
        self.assertEqual(self.backup.read_text(), self.relocate(LEGACY))
        self.assertIn("RMP_COMPAT_OK:vector-already", self.migrate())
        self.assertEqual(self.script.read_text(), expected)
        subprocess.run(["sh", "-n", str(self.script)], check=True)

    def test_ambiguous_v2_mount_is_not_rewritten(self):
        original = V2 + V2
        self.script.write_text(original)
        self.assertIn("RMP_COMPAT_FAIL:vector-service-pattern", self.migrate())
        self.assertEqual(self.script.read_text(), original)

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

    def test_stale_directory_blocks_legacy_but_not_migrated_launcher(self):
        self.script.write_text(self.relocate(LEGACY))
        tools = self.root / "bin"
        tools.mkdir()
        trace = self.root / "launch"
        for path, body in (
            (tools / "pidof", "exit 1"),
            (tools / "unshare", '[ "$1" = -m ] || exit 91; shift; exec "$@"'),
            (tools / "mount", '[ "$*" = "-t rootfs -o rslave rootfs /" ]'),
            (self.module / "daemon", 'printf "<%s>\\n" "$@" >> "$TRACE"'),
        ):
            path.write_text("#!/bin/sh\n" + body + "\n")
            path.chmod(0o755)
        env = dict(os.environ, PATH=f"{tools}:/usr/bin:/bin", TRACE=str(trace))

        def launch():
            # Relocate only Android's absolute mount path for host execution.
            launcher = self.module / "host-service.sh"
            launcher.write_text(self.script.read_text().replace(
                "/system/bin/mount", '"' + str(tools / "mount") + '"'
            ))
            return subprocess.run(
                ["sh", str(launcher), "argument with spaces"],
                env=env, capture_output=True, text=True, timeout=5,
            )

        # Reproduce the silent success observed with no daemon and an old mkdir lock.
        self.assertEqual(launch().returncode, 0)
        self.assertFalse(trace.exists())
        self.assertIn("RMP_COMPAT_OK:vector-service", self.migrate())
        # Leftover/restored directories must be irrelevant, not just cleaned once.
        self.stale_lock.mkdir()
        self.assertEqual(launch().returncode, 0)
        self.assertEqual(trace.read_text(), "<--system-server-max-retry=3>\n<argument with spaces>\n")

    def test_modern_launcher_is_not_rebuilt_from_a_legacy_backup(self):
        modern = '#!/system/bin/sh\n# VECTOR_SERVICE_V2\necho newer-launcher\n'
        self.script.write_text(modern)
        self.backup.write_text(self.relocate(LEGACY))
        self.assertIn("RMP_COMPAT_OK:vector-already", self.migrate())
        self.assertEqual(self.script.read_text(), modern)

    def test_absent_module_is_a_noop(self):
        self.assertIn("RMP_COMPAT_SKIP:vector-absent", self.migrate())
        self.assertFalse(self.backup.exists())


if __name__ == "__main__":
    unittest.main()
