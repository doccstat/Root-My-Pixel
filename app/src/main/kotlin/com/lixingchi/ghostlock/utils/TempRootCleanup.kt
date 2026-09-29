package com.lixingchi.ghostlock.utils

import android.content.Context
import java.io.File

/**
 * Removes the temporary files the exploit leaves behind.
 *
 * The exploit also mounts a tmpfs *over* `/apex/com.android.virt/bin` so its
 * `su` client precedes `/system/bin` in PATH; deleting the file leaves the
 * mount shadowing the real APEX payload, so the cleanup also unmounts the
 * overlay (see [unmountApexOverlayCommand]).
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

    /** The tmpfs mountpoint the exploit stacks its [APEX_SU] over. */
    const val APEX_BIN = "/apex/com.android.virt/bin"

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
        // RMP's own stage scripts do not belong in world-shared storage. The
        // app stages them under its private dir (and copies them into
        // /data/adb), but a manual device push leaves a root-owned copy here
        // that names this app.
        "/data/local/tmp/module_compat.sh",
        "/data/local/tmp/netfix.sh",
        APEX_SU,
    )

    private val TRANSPORT_FILES = listOf(
        "/data/local/tmp/su",
        "/data/local/tmp/temp_su.sock",
    )

    /** The app that owns the tombstone sweep below. */
    const val PACKAGE = "com.lixingchi.ghostlock"

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
            targets.joinToString(" ") +
            " && { " + unmountApexOverlayCommand() + "; echo $SENTINEL; }"
    }

    /**
     * Unmounts the exploit's tmpfs overlay from [APEX_BIN].
     *
     * The exploit does not just drop its daemon-backed `su` client there - it
     * mounts a tmpfs *over the directory* so the copy precedes `/system/bin` in
     * PATH. Deleting the file leaves the mount in place, and the mount shadows
     * the real APEX payload, so `virtualizationservice` cannot be exec'd.
     * `init` then logs `Cannot find '/apex/com.android.virt/bin/
     * virtualizationservice': No such file or directory` and every lazy-service
     * start for it dies with `PROP_ERROR_HANDLE_CONTROL_MESSAGE`: the AVF
     * KeyMint `/avf` remote-provisioning service the rkpdapp polls, and the
     * AppSearch `virtualizationmaintenance` service `IsolatedStorageService`
     * uses, both disappear until a real reboot. The exploit mounts the overlay
     * once per run, so repeated exploit attempts leave it *stacked*; unmounting
     * in a loop is required to fully expose the payload again. A no-op when the
     * overlay is absent, and never fails the surrounding cleanup chain.
     */
    fun unmountApexOverlayCommand(): String =
        "for _ in 1 2 3 4 5 6 7 8; do " +
            "grep -q \" $APEX_BIN \" /proc/mounts 2>/dev/null || break; " +
            "umount $APEX_BIN 2>/dev/null || break; " +
            "done"

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
            // Legacy: an older build unpacked the bundled manager here.
            File(context.filesDir, "ksu-manager.apk"),
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
