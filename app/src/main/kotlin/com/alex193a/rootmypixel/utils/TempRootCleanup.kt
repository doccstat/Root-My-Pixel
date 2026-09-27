package com.alex193a.rootmypixel.utils

import android.content.Context
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
        "/data/local/tmp/kernelsu-payload.ko",
        "/data/local/tmp/ksu-manager.apk",
        "/data/local/tmp/exploit.log",
        "/data/local/tmp/paint.log",
        "/data/local/tmp/su_daemon.log",
        "/data/local/tmp/unr00t.log",
        "/data/local/tmp/rmp-backup",
        // A half-written transport rename (`su.new.<pid>` -> `su`) can survive
        // an aborted exploit; unroot.sh already sweeps it.
        "/data/local/tmp/.su.new*",
        // Capture-side logcat dump, including every rotation, because a root
        // trace can land in any of them.
        "/data/local/tmp/rt.log*",
        APEX_SU,
    )

    private val TRANSPORT_FILES = listOf(
        "/data/local/tmp/su",
        "/data/local/tmp/temp_su.sock",
    )

    /** The app that owns the tombstone sweep below. */
    const val PACKAGE = "com.alex193a.rootmypixel"

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
        extraPaths: List<String> = emptyList(),
    ): Outcome {
        val command = cleanupCommand(includeTransport, extraPaths)
        val result = RootShell.run(command, helper = helper, timeoutSeconds = timeoutSeconds)
        return Outcome(result.output.contains(SENTINEL), result.output)
    }

    /** The exact shell command handed to KernelSU's `su`. */
    fun cleanupCommand(includeTransport: Boolean, extraPaths: List<String> = emptyList()): String {
        val targets = files(includeTransport) + extraPaths
        return tombstoneSweepCommand() + "; rm -rf " +
            targets.joinToString(" ") + " && echo $SENTINEL"
    }

    /**
     * Crash dumps of the forked native probe are the one artefact that outlives
     * a reboot and names this app from outside `/data/local/tmp`. Only dumps
     * that mention [PACKAGE] are removed so unrelated crashes are preserved;
     * the sweep is best-effort and never blocks the payload deletion.
     */
    fun tombstoneSweepCommand(): String =
        "for t in /data/tombstones/tombstone_*; do " +
            "case \"${'$'}t\" in *.pb) continue;; esac; " +
            "grep -qF $PACKAGE \"${'$'}t\" 2>/dev/null && " +
            "rm -f \"${'$'}t\" \"${'$'}t.pb\"; done"

    fun files(includeTransport: Boolean): List<String> =
        if (includeTransport) BASE_FILES + TRANSPORT_FILES else BASE_FILES

    /**
     * Deletes the exploit copies this app keeps inside its own private files
     * directory. This runs as the app's own uid: the KernelSU `su` domain may
     * be denied unlink on another app's MLS-categorised data dir, so the root
     * shell's `app-payloads` / `app-scripts` / `app-log` steps are not
     * trustworthy. Never touches the restore sources (`backups/`,
     * `selected_apps.json`, `extra_paths.json`).
     *
     * @return the names that were removed, for logging.
     */
    fun purgeAppArtifacts(context: Context): List<String> {
        val targets = listOf(
            File(context.filesDir, "payloads"),
            File(context.filesDir, "scripts"),
            File(context.filesDir, "exploit.log"),
        )
        val removed = mutableListOf<String>()
        for (target in targets) {
            val existed = target.exists()
            val gone = runCatching {
                if (target.isDirectory) target.deleteRecursively() else target.delete()
            }.getOrDefault(false)
            if (existed && (gone || !target.exists())) removed += target.name
        }
        return removed
    }

}
