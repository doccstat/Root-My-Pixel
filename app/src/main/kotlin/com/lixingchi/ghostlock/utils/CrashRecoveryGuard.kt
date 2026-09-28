package com.lixingchi.ghostlock.utils

/**
 * Keeps Android's CrashRecovery watchdog from mistaking our soft reboot for a
 * boot loop.
 *
 * `PackageWatchdog` (the CrashRecovery mainline module) counts every
 * `system_server` start as a boot - full device reboots are explicitly *not*
 * counted. Five restarts inside ten minutes, or any restart after a mitigation
 * already ran in the window, makes `RollbackPackageHealthObserver` roll back
 * *all* available rollback sessions ("Rolling back all available. Reason:
 * BOOT_LOOP") and, because staged mainline sessions are involved, force a real
 * reboot with reason `Rollback staged install` - which `sys.boot.reason` then
 * reports as `reboot,rollback_staged_install`.
 *
 * A soft reboot *is* a `system_server` restart, so one can push a primed device
 * over the edge: the device true-reboots, drops the LKM, and pending mainline
 * modules get reverted. It gets worse once a mitigation has run, because the
 * watchdog then fires again on the *next* restart
 * (`performedMitigationsDuringWindow() && count > 1`), which the device only
 * survives until RescueParty escalates to a factory reset.
 *
 * `crashrecovery.rescue_boot_count` is that restart counter. It is not a
 * `persist.*` property, so clearing it is safe and is the whole fix: the
 * restarted framework reads it from `PackageWatchdog.noteBoot()` and starts
 * again at one, below every trigger.
 *
 * @see <a href="https://cs.android.com/android/platform/superproject/main/+/main:packages/CrashRecovery/services/module/java/com/android/server/PackageWatchdog.java">PackageWatchdog</a>
 */
object CrashRecoveryGuard {
    /** Emitted when the restart counter was cleared. */
    const val OK = "RMP_CRASHRECOVERY_OK"

    /** Number of `system_server` starts in the current window. */
    const val RESCUE_BOOT_COUNT_PROP = "crashrecovery.rescue_boot_count"

    /** Start of the window, as `uptimeMillis`. */
    const val RESCUE_BOOT_START_PROP = "crashrecovery.rescue_boot_start"

    /** How many boot-loop mitigations already ran in this window. */
    const val BOOT_MITIGATION_COUNT_PROP = "crashrecovery.boot_mitigation_count"

    /**
     * Clears the CrashRecovery restart window. Idempotent, root only.
     *
     * Called immediately before and immediately after the framework restart, so
     * neither the restart we are about to trigger nor the one we just caused
     * counts toward the boot-loop threshold. Resetting the start also restarts
     * the ten-minute window, so a later `system_server` death cannot inherit
     * restarts from an earlier one.
     */
    fun clearCommand(): String = """
        setprop $RESCUE_BOOT_COUNT_PROP 0 2>/dev/null || true
        setprop $RESCUE_BOOT_START_PROP 0 2>/dev/null || true
        setprop $BOOT_MITIGATION_COUNT_PROP 0 2>/dev/null || true
        echo $OK
    """.trimIndent()
}
