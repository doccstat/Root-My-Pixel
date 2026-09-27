package com.lixingchi.ghostlock.utils

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
    fun `captures the kernelsu and vector root state`() {
        val text = script()

        for (path in listOf(
            "/data/adb/ksu/.allowlist",
            "/data/adb/ksu/.feature_config",
            "/data/adb/modules",
            "/data/adb/lspd/config",
            "/data/adb/post-fs-data.d",
            "/data/adb/service.d",
        )) {
            assertTrue("missing root-state path: $path", text.contains(path))
        }
        assertTrue(text.contains("RMP_RB_OK"))
        assertTrue(text.contains("RMP_RB_FAIL"))
        assertTrue(text.contains("RMP_RR_OK"))
        assertTrue(text.contains("RMP_RR_FAIL"))
    }

    @Test
    fun `records and reapplies runtime permissions`() {
        val text = script()

        assertTrue(text.contains("runtime permissions:"))
        assertTrue(text.contains("permissions.txt"))
        assertTrue(text.contains("pm grant"))
    }

    @Test
    fun `records and reapplies appops and disabled state`() {
        val text = script()

        assertTrue(text.contains("cmd appops get"))
        assertTrue(text.contains("cmd appops set"))
        assertTrue(text.contains("appops.txt"))
        assertTrue(text.contains("pm list packages -d"))
        assertTrue(text.contains("disabled=1"))
        assertTrue(text.contains("pm disable-user"))
    }

    @Test
    fun `rejects wildcard and relative extra paths`() {
        val text = script()

        // Only absolute paths pass the case guard; a bare wildcard is dropped.
        assertTrue(text.contains("case \"\$p\" in"))
        assertTrue(text.contains("/?*)"))
    }
}
