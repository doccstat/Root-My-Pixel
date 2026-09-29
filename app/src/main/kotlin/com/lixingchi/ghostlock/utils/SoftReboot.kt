package com.lixingchi.ghostlock.utils

import android.content.Context
import java.io.File

/**
 * A userspace soft reboot that behaves like a reboot without dropping the
 * late-loaded root: module changes, Zygisk injection and Xposed hooks all take
 * effect, and the display comes back lit.
 *
 * The restart is deliberately narrow. `ksud soft-reboot` (and the KernelSU
 * manager's button) runs init's blanket `stop`/`start`, which tears the whole
 * display stack down and leaves the cover panel of this foldable in a stale
 * DPMS/PSR state - the three-dot cover artifact. Clearing that afterwards is
 * not an option: a composer HAL restart escalates to a RescueParty/recovery
 * boot, and unbinding the panel driver panics the kernel. So the panel is never
 * staled in the first place.
 *
 * 1. `setprop sys.boot_completed 0`, then `killall -9 system_server`. The
 *    runtime restart re-forks `system_server` and restarts `zygote64` (so a
 *    Zygisk/Xposed module installed after the LKM load is re-injected) while
 *    SurfaceFlinger and the composer HAL keep running.
 *
 * 2. The KernelSU module stages then run explicitly, in boot order:
 *    `ksud post-fs-data` while the framework is still down, then
 *    `ksud services` and `ksud boot-completed` once it is back. That is the
 *    part a bare `system_server` restart misses, and why KernelSU ships its own
 *    `soft-reboot`.
 *
 * Deliberately **not** here: init `stop`/`start` (stales the panel), a composer
 * restart (recovery boot), a panel-driver unbind (kernel panic) and
 * `cmd display power-reset` (a no-op on the folded cover panel).
 *
 * Every step kills the framework (and therefore this app), so the work runs in
 * a detached `setsid` shell rather than in the app process.
 */
object SoftReboot {
    const val LOG_NAME = "soft-reboot.log"
    const val DEFAULT_KSUD_PATH = "/data/adb/ksud"

    /** Paths the guard below reads to detect a soft reboot already in flight. */
    const val LOCK_PATH = "/data/adb/soft-reboot.lock"

    /** Refuse a new soft reboot until this many seconds after the last start. */
    const val MIN_INTERVAL_SECONDS = 90L

    fun launch(
        context: Context,
        helper: File?,
        ksudPath: String = DEFAULT_KSUD_PATH,
        timeoutSeconds: Long = 10L,
    ): RootShell.Result {
        val log = File(context.filesDir, LOG_NAME)
        // Re-stage the platform repairs so this run uses the copy from the
        // installed app rather than one left on /data/adb by an older build:
        // an app update can add a repair (the ashmem boot-id node was one) and
        // a soft reboot has to apply it. It runs before the framework restart,
        // so no app can come up against stale device nodes.
        val compat = runCatching {
            AssetScriptRunner.stage(context, "module_compat.sh").absolutePath
        }.getOrNull()
        val command =
            "setsid sh -c '${buildScript(ksudPath, compat)}' </dev/null >> '${log.absolutePath}' 2>&1 &"
        return RootShell.run(command, helper = helper, timeoutSeconds = timeoutSeconds)
    }

    internal fun buildScript(
        ksudPath: String = DEFAULT_KSUD_PATH,
        compatScript: String? = null,
    ): String {
        val steps = mutableListOf<String>()
        steps += "echo \"===== soft reboot start \$(date +%s) =====\""
        if (compatScript != null) {
            // No single quotes: the whole script is embedded in `sh -c '...'`.
            steps += "echo \"[*] step 0: platform repairs before the framework restart\""
            steps += "sh \"$compatScript\" || echo \"[-] module_compat.sh exited \$?\""
        }
        steps += listOf(
        "echo \"[*] before: boot_completed=\$(getprop sys.boot_completed) reason=\$(getprop sys.boot.reason)\"",
        "echo \"[*] boot history:\"",
        "getprop persist.sys.boot.reason.history",
        "if [ \"\$(getprop sys.boot_completed)\" != \"1\" ]; then",
        "  echo \"[-] soft reboot: framework is not up, aborting\"",
        "  exit 1",
        "fi",
        "now=\$(date +%s)",
        "last=0",
        "if [ -f $LOCK_PATH ]; then",
        "  last=\$(cat $LOCK_PATH 2>/dev/null)",
        "fi",
        "case \"\$last\" in *[!0-9]*) last=0 ;; esac",
        "[ -n \"\$last\" ] || last=0",
        "if [ \$((now - last)) -lt $MIN_INTERVAL_SECONDS ]; then",
        "  echo \"[-] soft reboot: another soft reboot started \${last}s ago, aborting\"",
        "  exit 1",
        "fi",
        "echo \"\$now\" > $LOCK_PATH 2>/dev/null || true",
        // A system_server restart counts as a boot for CrashRecovery's watchdog,
        // so clear the restart window before and after ours; otherwise the
        // watchdog rolls back the pending mainline modules and force-reboots.
        "echo \"[*] step 0: clearing the CrashRecovery restart window\"",
        CrashRecoveryGuard.clearCommand(),
        "echo \"[*] step 1: restarting the framework only (zygote + system_server)\"",
        "setprop sys.boot_completed 0",
        "killall -9 system_server",
        "i=0",
        "while [ \$i -lt 30 ]; do",
        "  if [ -z \"\$(pidof system_server)\" ]; then break; fi",
        "  i=\$((i+1))",
        "  sleep 1",
        "done",
        "echo \"[*] system_server down after \${i} ticks\"",
        "echo \"[*] step 2: KernelSU post-fs-data stage\"",
        "if [ -x \"$ksudPath\" ]; then",
        "  \"$ksudPath\" post-fs-data",
        "  echo \"[*] post-fs-data returned \$?\"",
        "else",
        "  echo \"[-] soft reboot: $ksudPath not found, framework restart only\"",
        "fi",
        "i=0",
        "while [ \$i -lt 180 ]; do",
        "  [ \"\$(getprop sys.boot_completed)\" = \"1\" ] && break",
        "  i=\$((i+1))",
        "  sleep 1",
        "done",
        "echo \"[*] framework back after \${i}s (reason=\$(getprop sys.boot.reason))\"",
        "echo \"[*] clearing the CrashRecovery restart window again\"",
        CrashRecoveryGuard.clearCommand(),
        "if [ -x \"$ksudPath\" ]; then",
        "  echo \"[*] step 3: KernelSU services + boot-completed stages\"",
        "  \"$ksudPath\" services",
        "  echo \"[*] services returned \$?\"",
        "  \"$ksudPath\" boot-completed",
        "  echo \"[*] boot-completed returned \$?\"",
        "fi",
        "echo \"===== soft reboot done \$(date +%s) reason=\$(getprop sys.boot.reason) =====\"",
        )
        return steps.joinToString("\n")
    }
}
