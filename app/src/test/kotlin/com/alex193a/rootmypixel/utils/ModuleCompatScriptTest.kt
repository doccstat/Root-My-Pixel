package com.alex193a.rootmypixel.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * Guards the on-disk stage repairs that KernelSU re-executes on every userspace
 * start (see `installDurableStageFixes` in the install view model).
 */
class ModuleCompatScriptTest {

    private fun script(): String {
        val candidates = listOf(
            File("src/main/assets/module_compat.sh"),
            File("app/src/main/assets/module_compat.sh"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw FileNotFoundException("module_compat.sh not found")
        return file.readText()
    }

    @Test
    fun `patches both module stage scripts`() {
        val text = script()
        assertTrue(text.contains("/data/adb/modules/zygisksu"))
        assertTrue(text.contains("/data/adb/modules/zygisk_vector"))
        assertTrue(text.contains("post-fs-data.sh"))
        assertTrue(text.contains("service.sh"))
    }

    @Test
    fun `stops neozygisk from wiping a live work dir`() {
        val text = script()
        // It has to find the exact line it rewrites and skip while a monitor is
        // running, or KernelSU's soft reboot unlinks the live daemon's socket
        // and no Zygisk module is ever loaded.
        assertTrue(text.contains("if [ -d \$TMP_PATH ]; then"))
        assertTrue(text.contains("RMP_ZYGISK_GUARD"))
        assertTrue(text.contains("zygisk-ptrace64|zygisk-ptrace32"))
    }

    @Test
    fun `gives vector a busybox unshare`() {
        val text = script()
        assertTrue(text.contains("RMP_VECTOR_UNSHARE"))
        assertTrue(text.contains("/data/adb/ksu/bin/busybox unshare"))
        assertTrue(text.contains("command unshare"))
    }

    @Test
    fun `is idempotent and reversible`() {
        val text = script()
        assertTrue(text.contains(".rmp-orig"))
        assertTrue(text.contains("guarded RMP_ZYGISK_GUARD"))
        assertTrue(text.contains("guarded RMP_VECTOR_UNSHARE"))
    }

    @Test
    fun `signals each outcome`() {
        val text = script()
        assertTrue(text.contains("RMP_COMPAT_OK:"))
        assertTrue(text.contains("RMP_COMPAT_SKIP:"))
        assertTrue(text.contains("RMP_COMPAT_FAIL:"))
    }
}
