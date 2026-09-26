package com.alex193a.rootmypixel.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackupRunnerTest {

    @Test
    fun `parses package backup markers`() {
        val outcome = AppBackupRunner.parse(
            output = """
                RMP_BK_OK:com.example.one
                RMP_BK_FAIL:com.example.two:apk-copy
                RMP_BK_DONE:ok=1:fail=1
            """.trimIndent(),
        )

        assertEquals(listOf("com.example.one"), outcome.succeeded)
        assertEquals(mapOf("com.example.two" to "apk-copy"), outcome.failed)
        assertFalse(outcome.isComplete)
    }

    @Test
    fun `a run with no ok line is never complete`() {
        // The script failed before reaching any marker: treating "no failures"
        // as success would let the caller remove state it never archived.
        val outcome = AppBackupRunner.parse(output = "sh: app_backup.sh: not found")

        assertFalse(outcome.isComplete)
        assertEquals(emptyList<String>(), outcome.succeeded)
        assertEquals(emptyMap<String, String>(), outcome.failed)
    }

    @Test
    fun `parses extra-path markers with their own prefixes`() {
        val outcome = AppBackupRunner.parse(
            output = """
                RMP_XB_OK:extra
                RMP_XB_DONE:ok=1:fail=0
            """.trimIndent(),
            okPrefix = "RMP_XB_OK:",
            failPrefix = "RMP_XB_FAIL:",
            donePrefix = "RMP_XB_DONE:",
        )

        assertTrue(outcome.isComplete)
        assertEquals(listOf("extra"), outcome.succeeded)
    }

    @Test
    fun `parses root-state markers with their own prefixes`() {
        val outcome = AppBackupRunner.parse(
            output = """
                RMP_RR_OK:rootstate
                RMP_RR_DONE:ok=1:fail=0
            """.trimIndent(),
            okPrefix = "RMP_RR_OK:",
            failPrefix = "RMP_RR_FAIL:",
            donePrefix = "RMP_RR_DONE:",
        )

        assertTrue(outcome.isComplete)
        assertEquals(listOf("rootstate"), outcome.succeeded)
    }
}
