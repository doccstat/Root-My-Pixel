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

    /**
     * The exact shell command handed to KernelSU's `su`.
     *
     * [SENTINEL] is the success marker [run] keys on, so it is emitted only
     * when the virt-apex overlay is actually gone: a best-effort unmount that
     * leaves a layer behind must not read as "cleaned". The earlier form echoed
     * it unconditionally, so a busy layer (the exploit's `su` daemon keeps its
     * executable mapped from the mount) could survive while cleanup reported
     * success.
     */
    fun cleanupCommand(includeTransport: Boolean, extraPaths: List<String> = emptyList()): String {
        val targets = files(includeTransport) + extraPaths
        return tombstoneSweepCommand() + "; rm -rf " +
            targets.joinToString(" ") +
            " && { " + unmountApexOverlayCommand() + "; " +
            apexOverlayPresentCommand() + " || echo $SENTINEL; }"
    }

    /**
     * Unmounts the exploit's tmpfs overlay from [APEX_BIN] in every mount
     * namespace that still carries it.
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
     * in a loop is required to fully expose the payload again. A layer kept
     * busy by the exploit's own `su` daemon (its executable is mapped from the
     * mount) is detached with `umount -l`, which removes it from the namespace
     * without waiting for the last user.
     *
     * Acting in init's namespace alone is not enough. When the overlay is
     * mounted in the shared namespace every app namespace is cloned from
     * (zygote's), each app that starts afterwards unshares a *copy*. Private
     * mounts are independent, so unmounting init's copy leaves the zygote and
     * per-app copies in place: a detector running in an app - or the app itself
     * - keeps seeing `/apex/com.android.virt/bin/su`, and AVF stays broken for
     * those processes. The sweep walks every namespace that exposes the overlay
     * (or its `su` file), deduplicates by mount-namespace inode, and detaches
     * the layers inside each. It is a no-op when the overlay is absent and never
     * fails the surrounding cleanup chain.
     *
     * The sweep runs twice. The namespace list is snapshotted when each pass
     * starts, so an app that forks from a still-dirty parent *during* a pass
     * clones the overlay after that pass has looked, and keeps it: on yogi one
     * `com.google.android.videos` process survived exactly that way. The second
     * pass, by which point the parent is clean, catches the straggler.
     */
    fun unmountApexOverlayCommand(): String =
        "for _pass in 1 2; do " + namespaceOverlaySweep() + "; done"

    /**
     * One pass of [unmountApexOverlayCommand].
     *
     * A process whose mount namespace still has the overlay exposes it as
     * `/proc/<pid>/root$APEX_SU`, so that path is a cheap proxy for "this
     * namespace has the overlay": the file only exists inside the tmpfs. A
     * namespace whose `su` was already unlinked - the caller's own, because
     * [cleanupCommand] ran `rm -rf $APEX_SU` first - is covered by
     * [initOverlayTeardownLoop].
     */
    private fun namespaceOverlaySweep(): String =
        "seen=; " +
            "for s in /proc/[0-9]*/root$APEX_SU; do " +
            "[ -e \"\$s\" ] || continue; " +
            "pid=\${s#/proc/}; pid=\${pid%%/*}; " +
            "ns=\$(readlink /proc/\$pid/ns/mnt 2>/dev/null) || continue; " +
            "case \" \$seen \" in *\" \$ns \"*) continue;; esac; " +
            "seen=\"\$seen \$ns\"; " +
            "for _ in 1 2 3 4 5 6 7 8; do " +
            "nsenter -t \"\$pid\" -m umount $APEX_BIN 2>/dev/null || " +
            "nsenter -t \"\$pid\" -m umount -l $APEX_BIN 2>/dev/null || break; " +
            "done; " +
            "done; " +
            initOverlayTeardownLoop()

    /**
     * The init/shared-namespace leg of [unmountApexOverlayCommand].
     *
     * The caller usually runs in init's shared namespace (adbd does), so the
     * `rm -rf $APEX_SU` in [cleanupCommand] has already removed the only file
     * the namespace sweep keys on. This detaches the mount there directly, with a
     * fallback to the caller's namespace for when KernelSU's `su` opened a
     * private one.
     */
    private fun initOverlayTeardownLoop(): String =
        "for _ in 1 2 3 4 5 6 7 8; do " +
            inInitMountNs("grep -q \" $APEX_BIN \" /proc/mounts") + " || break; " +
            inInitMountNs("umount $APEX_BIN") + " || " +
            inInitMountNs("umount -l $APEX_BIN") + " || break; " +
            "done"

    /**
     * True (exit 0) when any mount namespace still exposes the overlay, so
     * [cleanupCommand] emits [SENTINEL] only when the overlay is really gone.
     */
    private fun apexOverlayPresentCommand(): String =
        "grep -q \" $APEX_BIN \" /proc/[0-9]*/mountinfo 2>/dev/null"

    /** `nsenter` into init's (shared) mount namespace. */
    private const val INIT_MNT_NS = "nsenter -t 1 -m"

    /**
     * Runs [command] first in init's mount namespace and, if that is
     * unavailable, in the caller's.
     *
     * KernelSU's `su` can run a shell in a *private* mount namespace - the
     * per-app "individual" mount-namespace mode in the manager. A plain
     * `umount` then only detaches the caller's copy while the shared mount the
     * exploit created survives, so cleanup reports success and AVF stays
     * broken. adbd shares init's namespace on yogi (`mnt:[4026531841]`), so
     * targeting init covers the leg that a private shell would otherwise miss;
     * [unmountApexOverlayCommand] handles the namespace copies themselves.
     */
    private fun inInitMountNs(command: String): String =
        "$INIT_MNT_NS $command 2>/dev/null || $command 2>/dev/null"

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
