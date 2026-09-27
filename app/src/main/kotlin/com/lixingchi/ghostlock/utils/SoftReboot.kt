package com.lixingchi.ghostlock.utils

import android.content.Context
import java.io.File

/**
 * A userspace soft reboot that behaves like a reboot without dropping the
 * late-loaded root: module changes, Zygisk injection and Xposed hooks all take
 * effect, and the display comes back lit.
 *
 * Three steps, in this order.
 *
 * 1. Restart the composer HAL. A framework restart on its own (a bare
 *    `killall -9 system_server`) recycles the core services but never the
 *    HALs, so the cover panel keeps showing a frozen fragment until the next
 *    real boot. Killing the composer makes SurfaceFlinger abort and init
 *    re-init the whole display stack, in cascade through `system_server` and
 *    `zygote64` - so this alone already restarts zygote, which is what makes a
 *    Zygisk module installed after the LKM load get re-injected.
 *
 * 2. `ksud soft-reboot`. Its emulation runs the post-fs-data and service
 *    stages itself, which is the only window where a module's `service.sh`
 *    starts while `system_server` is down - required for Vector's `vectord` to
 *    claim the `serial` service. It has to come after step 1, not before: a
 *    composer restart issued while the framework is still settling from a
 *    soft-reboot crash-loops `system_server` and RescueParty reboots the
 *    device (`sys.boot.reason=reboot,rescueparty`).
 *
 * 3. A plain sleep/wake cycle. `ksud soft-reboot`'s `stop`/`start` leaves the
 *    display power state stale (SurfaceFlinger draws while the panel sits at
 *    DPMS off, so the screen looks black). A `KEYCODE_SLEEP`/`KEYCODE_WAKEUP`
 *    pair makes SurfaceFlinger re-apply display power. This is deliberately a
 *    DPMS toggle and not a second composer restart: a second framework restart
 *    this soon is what triggered the RescueParty reboot above.
 *
 * Every step kills the framework (and therefore this app), so the work runs in
 * a detached `setsid` shell rather than in the app process.
 */
object SoftReboot {
    const val LOG_NAME = "soft-reboot.log"
    const val DEFAULT_KSUD_PATH = "/data/adb/ksud"

    fun launch(
        context: Context,
        helper: File?,
        ksudPath: String = DEFAULT_KSUD_PATH,
        timeoutSeconds: Long = 10L,
    ): RootShell.Result {
        val log = File(context.filesDir, LOG_NAME)
        val command =
            "setsid sh -c '${buildScript(ksudPath)}' </dev/null >> '${log.absolutePath}' 2>&1 &"
        return RootShell.run(command, helper = helper, timeoutSeconds = timeoutSeconds)
    }

    internal fun buildScript(ksudPath: String = DEFAULT_KSUD_PATH): String = listOf(
        "echo \"[*] soft reboot: restarting display stack\"",
        "hwc=\$(getprop | grep -o \"vendor.hwcomposer-[0-9]*\" | head -n1)",
        "if [ -n \"\$hwc\" ]; then setprop ctl.restart \"\$hwc\"; else killall -9 system_server; fi",
        "i=0",
        "while [ \$i -lt 180 ]; do",
        "  [ \"\$(getprop sys.boot_completed)\" = \"1\" ] && break",
        "  i=\$((i+1))",
        "  sleep 1",
        "done",
        "sleep 5",
        "if [ -x \"$ksudPath\" ]; then",
        "  echo \"[*] soft reboot: ksud soft-reboot (module stages)\"",
        "  \"$ksudPath\" soft-reboot",
        "else",
        "  echo \"[-] soft reboot: $ksudPath not found, display stack only\"",
        "fi",
        "i=0",
        "while [ \$i -lt 180 ]; do",
        "  [ \"\$(getprop sys.boot_completed)\" = \"1\" ] && break",
        "  i=\$((i+1))",
        "  sleep 1",
        "done",
        "sleep 10",
        "echo \"[*] soft reboot: re-lighting display\"",
        "input keyevent KEYCODE_SLEEP",
        "sleep 2",
        "input keyevent KEYCODE_WAKEUP",
        "echo \"[*] soft reboot: done\"",
    ).joinToString("\n")
}
