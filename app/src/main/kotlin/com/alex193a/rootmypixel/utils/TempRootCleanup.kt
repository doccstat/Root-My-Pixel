package com.alex193a.rootmypixel.utils

import java.io.File

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

    /** The exploit's "adb-visible" su, mounted over the virt apex. */
    const val APEX_SU = "/apex/com.android.virt/bin/su"

    private val BASE_FILES = listOf(
        "/data/local/tmp/cve-2026-43499-app.so",
        "/data/local/tmp/cve-2026-43499-root",
        "/data/local/tmp/ksud-pixel",
        "/data/local/tmp/ksu-manager.apk",
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
        val result = RootShell.run(command, helper = helper, timeoutSeconds = timeoutSeconds)
        return Outcome(result.output.contains(SENTINEL), result.output)
    }

    /** The exact shell command handed to KernelSU's `su`. */
    fun cleanupCommand(includeTransport: Boolean): String {
        return "rm -f " + files(includeTransport).joinToString(" ") + " && echo $SENTINEL"
    }

    fun files(includeTransport: Boolean): List<String> =
        if (includeTransport) BASE_FILES + TRANSPORT_FILES else BASE_FILES

}
