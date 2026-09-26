package com.alex193a.rootmypixel.utils

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

        assertTrue(command.contains("rm -f "))
        assertTrue(command.endsWith("&& echo ${TempRootCleanup.SENTINEL}"))
        assertFalse(command.contains("/data/local/tmp/*"))
    }

    @Test
    fun `command sweeps only this app's tombstones`() {
        val command = TempRootCleanup.cleanupCommand(includeTransport = false)

        assertTrue(command.startsWith(TempRootCleanup.tombstoneSweepCommand()))
        assertTrue(command.contains("/data/tombstones/tombstone_*"))
        assertTrue(command.contains(TempRootCleanup.PACKAGE))
    }
}
