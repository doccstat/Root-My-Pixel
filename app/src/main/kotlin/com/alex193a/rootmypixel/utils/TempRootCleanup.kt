package com.alex193a.rootmypixel.utils

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Removes the temporary files the exploit leaves behind.
 *
 * Cleanup must go through KernelSU's `su`. Its su_compat hooks intercept
 * `execve` of the literal path `/system/bin/su` for granted UIDs even though no
 * such file exists on disk. The bare name `su` must not be used: the exploit
 * installs its own `su` at [APEX_SU], which precedes `/system/bin` in PATH and
 * shadows KernelSU's, and that copy only works through a daemon socket that
 * SELinux blocks once the exploit restores enforcing mode.
 */
object TempRootCleanup {
    const val SENTINEL = "RMP_CLEANUP_OK"

    /** KernelSU's su_compat path first, then a plain `su` for other setups. */
    private val SU_CANDIDATES = listOf("/system/bin/su", "su")

    /** The exploit's "adb-visible" su, mounted over the virt apex. */
    const val APEX_SU = "/apex/com.android.virt/bin/su"

    private val BASE_FILES = listOf(
        "/data/local/tmp/cve-2026-43499-app.so",
        "/data/local/tmp/cve-2026-43499-root",
        "/data/local/tmp/ksud-pixel",
        "/data/local/tmp/exploit.log",
        "/data/local/tmp/paint.log",
        "/data/local/tmp/su_daemon.log",
        APEX_SU,
    )

    private val TRANSPORT_FILES = listOf(
        "/data/local/tmp/su",
        "/data/local/tmp/temp_su.sock",
    )

    data class Outcome(val success: Boolean, val output: String)

    /**
     * Deletes the payloads and logs, and with [includeTransport] also the
     * `su` + socket pair that bootstraps the exploit. [helper] is the app's
     * bundled CVE helper, used as a last resort while the exploit transport is
     * still alive.
     */
    fun run(
        includeTransport: Boolean,
        helper: File? = null,
        timeoutSeconds: Long = 10L,
    ): Outcome {
        val command = cleanupCommand(includeTransport)

        for (su in SU_CANDIDATES) {
            val result = exec(listOf(su, "-c", command), timeoutSeconds)
            if (result != null && result.output.contains(SENTINEL)) {
                return Outcome(true, result.output)
            }
        }
        if (helper != null && helper.exists()) {
            val result = exec(listOf(helper.absolutePath, "-c", command), timeoutSeconds)
            if (result != null && result.output.contains(SENTINEL)) {
                return Outcome(true, result.output)
            }
        }
        return Outcome(false, "")
    }

    /** The exact shell command handed to KernelSU's `su`. */
    fun cleanupCommand(includeTransport: Boolean): String {
        return "rm -f " + files(includeTransport).joinToString(" ") + " && echo $SENTINEL"
    }

    fun files(includeTransport: Boolean): List<String> =
        if (includeTransport) BASE_FILES + TRANSPORT_FILES else BASE_FILES

    private data class CommandResult(val code: Int, val output: String)

    private fun exec(command: List<String>, timeoutSeconds: Long): CommandResult? {
        val process = runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return CommandResult(if (finished) process.exitValue() else 124, output)
    }
}
