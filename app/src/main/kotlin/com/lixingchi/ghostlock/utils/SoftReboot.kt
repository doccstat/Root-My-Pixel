package com.lixingchi.ghostlock.utils

import android.content.Context
import com.lixingchi.ghostlock.R
import java.io.File

/** KernelSU owns runtime quiescence, module activation, recovery and diagnostics. */
object SoftReboot {
    const val LOG_NAME = "soft-reboot.log"
    const val DEFAULT_KSUD_PATH = "/data/adb/ksud"
    const val CONTRACT = "ksud-framework-restart-v1"

    fun launch(
        context: Context,
        helper: File?,
        ksudPath: String = DEFAULT_KSUD_PATH,
        timeoutSeconds: Long = 10L,
    ): RootShell.Result {
        val capability = RootShell.run(
            "${quote(ksudPath)} soft-reboot --check",
            helper = helper,
            timeoutSeconds = timeoutSeconds,
        )
        if (!capability.isOk || capability.output.trim() != CONTRACT) {
            return RootShell.Result(1, context.getString(R.string.soft_reboot_update_required))
        }
        // Keep the existing platform repairs shared by both restart entry points.
        val compat = runCatching { AssetScriptRunner.stage(context, "module_compat.sh") }
            .getOrElse { return RootShell.Result(1, context.getString(R.string.soft_reboot_failed)) }
        // Never fall back to the old kill-and-replay script or stock blanket restart.
        val result = RootShell.run(buildScript(ksudPath, compat.absolutePath), helper, timeoutSeconds)
        runCatching { File(context.filesDir, LOG_NAME).appendText(result.output + "\n") }
        return result
    }

    internal fun buildScript(
        ksudPath: String = DEFAULT_KSUD_PATH,
        compatScript: String? = null,
    ): String =
        "capability=\$(${quote(ksudPath)} soft-reboot --check) && " +
            "[ \"\$capability\" = ${quote(CONTRACT)} ] && " +
            (compatScript?.let { "sh ${quote(it)} && " } ?: "") +
            "${quote(ksudPath)} soft-reboot"

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
