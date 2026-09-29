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
        assertTrue(command.contains("grep -q \" ${TempRootCleanup.APEX_BIN} \" /proc/mounts"))
        // The overlay stacks once per exploit run, so a single umount leaves a
        // layer behind; the loop is what fully exposes the APEX payload.
        assertTrue(command.contains("for _ in 1 2 3 4 5 6 7 8; do"))
        // Never block the payload sweep on a busy or absent overlay.
        assertTrue(command.contains("|| break"))
    }

    @Test
    fun `unmount command is a no-op loop that cannot fail the chain`() {
        val unmount = TempRootCleanup.unmountApexOverlayCommand()

        assertTrue(unmount.startsWith("for _ in 1 2 3 4 5 6 7 8; do "))
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
