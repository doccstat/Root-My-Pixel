package com.alex193a.rootmypixel.utils

import java.util.concurrent.TimeUnit

/**
 * Best-effort "is the KernelSU driver live" check.
 *
 * The native UAPI probe is authoritative when it answers, but recent KernelSU
 * builds select their dispatcher syscall dynamically (`ksu_dispatcher_nr`), so
 * the legacy `reboot(0xDEADBEEF, 0xCAFEBABE, …)` supercall can fail on a
 * perfectly working driver. su_compat is a reliable fallback: a root shell from
 * the literal path `/system/bin/su` proves both the driver and this app's grant.
 */
object KernelSuPresence {
    private const val KERNEL_SU_PATH = "/system/bin/su"
    private const val ROOT_ID_COMMAND = "id -u"
    private const val TIMEOUT_SECONDS = 5L

    fun isActive(nativeStatus: NativeProbe.KernelSuStatus): Boolean =
        nativeStatus.isActive || rootShellViaKernelSu()

    fun rootShellViaKernelSu(): Boolean {
        val process = runCatching {
            ProcessBuilder(listOf(KERNEL_SU_PATH, "-c", ROOT_ID_COMMAND))
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return false
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return finished && RootShellProbe.isRoot(process.exitValue(), output)
    }
}
