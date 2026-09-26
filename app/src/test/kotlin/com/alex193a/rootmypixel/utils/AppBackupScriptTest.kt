package com.alex193a.rootmypixel.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

class AppBackupScriptTest {

    private fun script(): String {
        val candidates = listOf(
            File("src/main/assets/app_backup.sh"),
            File("app/src/main/assets/app_backup.sh"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw FileNotFoundException("app_backup.sh not found from ${File("").absolutePath}")
        return file.readText()
    }

    @Test
    fun `exposes extra-path modes and markers`() {
        val text = script()

        assertTrue(text.contains("extra-backup"))
        assertTrue(text.contains("extra-restore"))
        assertTrue(text.contains("RMP_XB_OK"))
        assertTrue(text.contains("RMP_XB_FAIL"))
        assertTrue(text.contains("RMP_XR_OK"))
        assertTrue(text.contains("RMP_XR_FAIL"))
        assertTrue(text.contains("RMP_EXTRA_PATHS"))
    }

    @Test
    fun `removes the originals only after the archive exists`() {
        val text = script()

        val tar = text.indexOf("tar -czf")
        val remove = text.indexOf("rm -rf \"\$p\"")
        assertTrue("archive must precede removal", tar >= 0 && remove > tar)
    }

    @Test
    fun `rejects wildcard and relative extra paths`() {
        val text = script()

        // Only absolute paths pass the case guard; a bare wildcard is dropped.
        assertTrue(text.contains("case \"\$p\" in"))
        assertTrue(text.contains("/?*)"))
    }
}
