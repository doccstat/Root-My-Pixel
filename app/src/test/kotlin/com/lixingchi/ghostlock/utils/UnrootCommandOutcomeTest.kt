package com.lixingchi.ghostlock.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnrootCommandOutcomeTest {

    @Test
    fun parse_reportsEveryFailedCleanupStep() {
        val outcome = UnrootCommandOutcome.parse(
            """
            UNROOT_OK:data-adb
            UNROOT_FAIL:apex-mount:1
            UNROOT_FAIL:selinux:126
            UNROOT_CLEANUP_PARTIAL
            """.trimIndent(),
        )

        assertFalse(outcome.cleanupComplete)
        assertFalse(outcome.rebootRequested)
        assertEquals(listOf(UnrootIssue.ApexMount, UnrootIssue.Selinux), outcome.issues)
    }

    @Test
    fun parse_distinguishesCleanupAndRebootSuccess() {
        val cleanupOnly = UnrootCommandOutcome.parse("UNROOT_CLEANUP_OK")
        assertTrue(cleanupOnly.cleanupComplete)
        assertFalse(cleanupOnly.rebootRequested)

        val rebooted = UnrootCommandOutcome.parse(
            "UNROOT_CLEANUP_OK\nUNROOT_REBOOT_REQUESTED",
        )
        assertTrue(rebooted.cleanupComplete)
        assertTrue(rebooted.rebootRequested)
    }

    @Test
    fun parse_reportsUnavailableRootTransport() {
        val outcome = UnrootCommandOutcome.parse("UNROOT_TRANSPORT_UNAVAILABLE")

        assertTrue(outcome.transportUnavailable)
        assertEquals(UnrootIssue.affectedByMissingTransport, outcome.issues)
    }

    @Test
    fun parse_mapsStagedUpdateCleanupFailuresToTheOtaIssue() {
        val outcome = UnrootCommandOutcome.parse(
            """
            UNROOT_OTA_STAGED:1
            UNROOT_FAIL:ota-payload:1
            UNROOT_FAIL:ota-metadata:1
            UNROOT_CLEANUP_PARTIAL
            """.trimIndent(),
        )

        assertFalse(outcome.cleanupComplete)
        assertEquals(listOf(UnrootIssue.OtaStaged), outcome.issues)
    }

    @Test
    fun parse_mapsAppStorageCleanupFailuresToTheirOwnIssues() {
        val outcome = UnrootCommandOutcome.parse(
            """
            UNROOT_FAIL:app-payloads:1
            UNROOT_FAIL:app-scripts:1
            UNROOT_FAIL:tmp-logs:1
            UNROOT_CLEANUP_PARTIAL
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                UnrootIssue.AppPayloads,
                UnrootIssue.AppScripts,
                UnrootIssue.TempLogs,
            ),
            outcome.issues,
        )
    }

    @Test
    fun parse_flagsATruncatedRootShellAsIncomplete() {
        val outcome = UnrootCommandOutcome.parse(
            """
            UNROOT_IDENTITY:uid=0:context=u:r:ksu:s0
            UNROOT_OK:data-adb
            UNROOT_OK:selinux
            """.trimIndent(),
        )

        assertFalse(outcome.cleanupComplete)
        assertEquals(listOf(UnrootIssue.Incomplete), outcome.issues)
    }

    @Test
    fun parse_treatsAFinishedRunWithOnlyTheStagedMarkerAsInformational() {
        val outcome = UnrootCommandOutcome.parse(
            "UNROOT_OTA_STAGED:0\nUNROOT_CLEANUP_OK",
        )

        assertTrue(outcome.issues.isEmpty())
        assertTrue(outcome.hasStructuredOutput)
        assertTrue(outcome.cleanupComplete)
    }
}
