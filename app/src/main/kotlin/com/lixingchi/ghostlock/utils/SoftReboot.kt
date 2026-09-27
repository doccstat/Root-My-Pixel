package com.lixingchi.ghostlock.utils

import android.content.Context
import java.io.File

/**
 * A userspace soft reboot that behaves like a reboot without dropping the
 * late-loaded root: module changes, Zygisk injection and Xposed hooks all take
 * effect, and the display comes back lit.
 *
 * Two steps, in this order.
 *
 * 1. `ksud soft-reboot`. Its emulation runs `stop`/`start` and the
 *    post-fs-data and service stages itself, which restarts `zygote64` and
 *    `system_server` (so a Zygisk/Xposed module installed after the LKM load
 *    is re-injected) and is the only window where a module's `service.sh`
 *    starts while `system_server` is down - required for Vector's `vectord` to
 *    claim the `serial` service.
 *
 * 2. Re-initialise the panel, then a sleep/wake cycle. The userspace restart
 *    does not reset the DSI/DPU pipeline, so the cover panel keeps whatever
 *    DPMS/PSR state it had while the display stack was torn down. That is the
 *    three-dot cover-panel artifact and the black screen: the framework comes
 *    back but the panel is never re-driven. `cmd display power-reset <id>`
 *    asks the display stack to drive each connected panel back to the power
 *    state it should have; the `KEYCODE_SLEEP`/`KEYCODE_WAKEUP` pair afterwards
 *    is a belt-and-braces DPMS toggle for builds without `cmd display`.
 *
 * Deliberately **not** here: restarting the composer HAL. It was the only
 * step that ever cleared the cover artifact, but a composer restart issued
 * while the framework is still settling (for example a second soft reboot
 * shortly after the first) crash-loops `system_server` and takes the whole
 * device down through RescueParty/recovery. The panel is re-driven through
 * `cmd display` instead, which never touches the framework.
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
        val command =
            "setsid sh -c '${buildScript(ksudPath)}' </dev/null >> '${log.absolutePath}' 2>&1 &"
        return RootShell.run(command, helper = helper, timeoutSeconds = timeoutSeconds)
    }

    internal fun buildScript(ksudPath: String = DEFAULT_KSUD_PATH): String = listOf(
        "echo \"===== soft reboot start \$(date +%s) =====\"",
        "echo \"[*] before: boot_completed=\$(getprop sys.boot_completed) reason=\$(getprop sys.boot.reason)\"",
        "echo \"[*] boot history:\"",
        "getprop persist.sys.boot.reason.history",
        "echo \"[*] displays:\"",
        "cmd display get-displays --ids-only 2>/dev/null",
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
        "echo \"[*] step 1: ksud soft-reboot\"",
        "if [ -x \"$ksudPath\" ]; then",
        "  \"$ksudPath\" soft-reboot",
        "  echo \"[*] ksud soft-reboot returned \$?\"",
        "else",
        "  echo \"[-] soft reboot: $ksudPath not found, panel re-init only\"",
        "fi",
        "i=0",
        "while [ \$i -lt 180 ]; do",
        "  [ \"\$(getprop sys.boot_completed)\" = \"1\" ] && break",
        "  i=\$((i+1))",
        "  sleep 1",
        "done",
        "echo \"[*] framework back after \${i}s (reason=\$(getprop sys.boot.reason))\"",
        "sleep 10",
        "echo \"[*] step 2: re-initialising panel power\"",
        "for id in \$(cmd display get-displays --ids-only 2>/dev/null); do",
        "  echo \"[*] power-reset display \$id\"",
        "  cmd display power-reset \"\$id\" >/dev/null 2>&1",
        "  echo \"[*] power-reset display \$id returned \$?\"",
        "done",
        "echo \"[*] step 3: DPMS toggle\"",
        "input keyevent KEYCODE_SLEEP",
        "sleep 2",
        "input keyevent KEYCODE_WAKEUP",
        "echo \"===== soft reboot done \$(date +%s) reason=\$(getprop sys.boot.reason) =====\"",
    ).joinToString("\n")
}
