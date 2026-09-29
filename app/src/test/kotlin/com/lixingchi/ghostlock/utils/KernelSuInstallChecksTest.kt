package com.lixingchi.ghostlock.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelSuInstallChecksTest {

    @Test
    fun `debug info requires a positive KernelSU version`() {
        assertTrue(
            KernelSuInstallChecks.debugInfoShowsActiveKernelSu(
                """
                version: 35040
                full_version: v3.3.0-32601@KernelSU
                runtime_mode: late-load
                """.trimIndent(),
            ),
        )
        assertFalse(KernelSuInstallChecks.debugInfoShowsActiveKernelSu("version: 0"))
        assertFalse(KernelSuInstallChecks.debugInfoShowsActiveKernelSu("connection refused"))
    }

    @Test
    fun `proc modules fallback requires the exact kernelsu module`() {
        assertTrue(
            KernelSuInstallChecks.procModulesShowsActiveKernelSu(
                "kernelsu 114688 1 - Live 0x0000000000000000",
            ),
        )
        assertFalse(
            KernelSuInstallChecks.procModulesShowsActiveKernelSu(
                "not_kernelsu 114688 1 - Live 0x0000000000000000",
            ),
        )
    }

    @Test
    fun `trusted KernelSU manager signature is parsed from ksud output`() {
        val signature = KernelSuInstallChecks.parseManagerSignature(
            "size: 0x34b, hash: ff8c6f43e0bdd88057103c9faa5d78d7f43ee80a3c1ffa3e20eed06dcb640050",
        )

        assertEquals(0x34b, signature?.size)
        assertTrue(signature != null && KernelSuInstallChecks.isTrustedManagerSignature(signature))
    }

    @Test
    fun `unknown or malformed manager signatures are rejected`() {
        val unknown = KernelSuInstallChecks.parseManagerSignature(
            "size: 887, hash: a3469712b6214462764a1d8d3e5cbe1d6819a0b629791b9f4101867821f1df64",
        )

        assertTrue(unknown != null)
        assertFalse(KernelSuInstallChecks.isTrustedManagerSignature(unknown!!))
        assertNull(KernelSuInstallChecks.parseManagerSignature("signature unavailable"))
    }

    @Test
    fun `the module is loaded with the shell grant`() {
        assertEquals(
            "/data/adb/ksud insmod /data/adb/modules/kernelsu.ko allow_shell=1",
            KernelSuInstallChecks.insmodCommand(
                "/data/adb/ksud",
                "/data/adb/modules/kernelsu.ko",
            ),
        )
    }
}
