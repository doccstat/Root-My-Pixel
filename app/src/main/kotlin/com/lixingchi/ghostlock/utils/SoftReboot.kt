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
 * 3. Re-initialise the panel, then a sleep/wake cycle.
 *
 *    The userspace restart in step 2 does not reset the DSI/DPU pipeline, so
 *    the cover panel keeps whatever DPMS/PSR state it had while the display
 *    stack was torn down. That is the three-dot cover-panel artifact and the
 *    black screen: SurfaceFlinger comes back but the panel is never re-driven.
 *
 *    Step 1's composer restart fixes that, but it runs *before* step 2, and
 *    step 2 stales the panel again. So the panel is re-initialised once more at
 *    the end with `cmd display power-reset <id>` for every connected display.
 *    `power-reset` asks SurfaceFlinger to drive the panel back to the power
 *    state it should have, without restarting the composer - the second
 *    framework restart that would trigger the RescueParty reboot above. The
 *    `KEYCODE_SLEEP`/`KEYCODE_WAKEUP` pair afterwards stays as a belt-and-braces
 *    DPMS toggle for any build where `cmd display` is unavailable.
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
        "echo \"[*] soft reboot: re-initialising panel power\"",
        "for id in \$(cmd display get-displays --ids-only 2>/dev/null); do",
        "  cmd display power-reset \"\$id\" >/dev/null 2>&1",
        "done",
        "echo \"[*] soft reboot: re-lighting display\"",
        "input keyevent KEYCODE_SLEEP",
        "sleep 2",
        "input keyevent KEYCODE_WAKEUP",
        "echo \"[*] soft reboot: done\"",
    ).joinToString("\n")
}
