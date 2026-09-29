package com.lixingchi.ghostlock.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TempRootCleanupTest {
    @Test
    fun `removes the payloads, logs and the apex su by default`() {
        val files = TempRootCleanup.files(includeTransport = false)

        assertTrue(files.contains("/data/local/tmp/cve-2026-43499-app.so"))
        assertTrue(files.contains("/data/local/tmp/ksud-pixel"))
        assertTrue(files.contains("/data/local/tmp/kernelsu-payload.ko"))
        assertTrue(files.contains("/data/local/tmp/ksu-manager.apk"))
        assertTrue(files.contains("/data/local/tmp/unr00t.log"))
        assertTrue(files.contains("/data/local/tmp/rmp-backup"))
        assertTrue(files.contains("/data/local/tmp/rt.log*"))
        assertTrue(files.contains("/data/local/tmp/.su.new*"))
        assertTrue(files.contains(TempRootCleanup.APEX_SU))
        assertTrue(files.contains("/data/local/tmp/module_compat.sh"))
        assertTrue(files.contains("/data/local/tmp/netfix.sh"))
        assertFalse(files.contains("/data/local/tmp/su"))
        assertFalse(files.contains("/data/local/tmp/temp_su.sock"))
    }

    @Test
    fun `adds the su transport only when requested`() {
        val files = TempRootCleanup.files(includeTransport = true)

        assertTrue(files.contains("/data/local/tmp/su"))
        assertTrue(files.contains("/data/local/tmp/temp_su.sock"))
    }

    @Test
    fun `command ends with the sentinel and never targets the whole tmp dir`() {
        val command = TempRootCleanup.cleanupCommand(includeTransport = true)

        assertTrue(command.contains("rm -rf "))
        assertTrue(command.endsWith("echo ${TempRootCleanup.SENTINEL}; }"))
        assertFalse(command.contains("/data/local/tmp/*"))
    }

    @Test
    fun `command unmounts the stacked exploit overlay over the virt apex`() {
        val command = TempRootCleanup.cleanupCommand(includeTransport = false)

        // Deleting APEX_SU is not enough: the tmpfs the exploit mounted over
        // the directory shadows virtualizationservice until it is unmounted.
        assertTrue(command.contains("umount ${TempRootCleanup.APEX_BIN}"))
        // The overlay stacks once per exploit run, so a single umount leaves a
        // layer behind; the loop is what fully exposes the APEX payload.
        assertTrue(command.contains("for _ in 1 2 3 4 5 6 7 8; do"))
        // Never block the payload sweep on a busy or absent overlay.
        assertTrue(command.contains("|| break"))
    }

    @Test
    fun `the sentinel is emitted only once the overlay is gone`() {
        val command = TempRootCleanup.cleanupCommand(includeTransport = true)
        val guard =
            "grep -q \" ${TempRootCleanup.APEX_BIN} \" /proc/[0-9]*/mountinfo 2>/dev/null || " +
                "echo ${TempRootCleanup.SENTINEL}"

        // A best-effort unmount that leaves a layer behind - in any namespace -
        // must not read as a successful cleanup.
        assertTrue(command.contains(guard))
    }

    @Test
    fun `the overlay teardown sweeps every mount namespace`() {
        val unmount = TempRootCleanup.unmountApexOverlayCommand()

        // The overlay can survive as a *copy* in zygote's namespace and in every
        // app namespace cloned from it, where an init-only teardown never looks.
        // Each namespace is visited once, keyed by its mount-namespace inode.
        assertTrue(unmount.contains(TempRootCleanup.APEX_SU))
        assertTrue(unmount.contains("readlink /proc/\$pid/ns/mnt"))
        assertTrue(unmount.contains("nsenter -t \"\$pid\" -m umount ${TempRootCleanup.APEX_BIN}"))
        // Namespaces whose `su` was already unlinked (the caller's own) are
        // still covered by the init leg.
        assertTrue(unmount.contains("nsenter -t 1 -m umount ${TempRootCleanup.APEX_BIN}"))
        assertTrue(unmount.contains("|| umount ${TempRootCleanup.APEX_BIN}"))
    }

    @Test
    fun `a busy overlay is detached lazily`() {
        val unmount = TempRootCleanup.unmountApexOverlayCommand()

        // The exploit's own su daemon keeps its executable mapped from the
        // mount, so a plain umount can fail with EBUSY; the lazy detach still
        // removes the layer from the namespace.
        assertTrue(unmount.contains("umount -l ${TempRootCleanup.APEX_BIN}"))
    }

    @Test
    fun `unmount command is a no-op loop that cannot fail the chain`() {
        val unmount = TempRootCleanup.unmountApexOverlayCommand()

        // Two passes: an app that forks from a dirty parent mid-sweep clones the
        // overlay after the first pass has looked, and only the second catches it.
        assertTrue(unmount.startsWith("for _pass in 1 2; do seen=; for s in /proc/[0-9]*/root"))
        assertTrue(unmount.endsWith("done"))
        assertFalse(unmount.contains("exit"))
    }

    @Test
    fun `extra paths are appended to the sweep`() {
        val command = TempRootCleanup.cleanupCommand(
            includeTransport = false,
            extraPaths = listOf("/data/data/com.lixingchi.ghostlock/files/exploit.log"),
        )

        assertTrue(command.contains("/data/data/com.lixingchi.ghostlock/files/exploit.log"))
    }

    @Test
    fun `command sweeps only this app's tombstones`() {
        val command = TempRootCleanup.cleanupCommand(includeTransport = false)

        assertTrue(command.startsWith(TempRootCleanup.tombstoneSweepCommand()))
        assertTrue(command.contains("/data/tombstones/tombstone_*"))
        assertTrue(command.contains(TempRootCleanup.PACKAGE))
    }
}
