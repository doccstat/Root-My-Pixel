package com.alex193a.rootmypixel.utils

import android.content.Context
import java.io.File

/**
 * Drives `assets/app_backup.sh` through the root transport and parses its
 * line-oriented markers.
 */
object AppBackupRunner {
    private const val ASSET = "app_backup.sh"

    private const val BK_OK = "RMP_BK_OK:"
    private const val BK_FAIL = "RMP_BK_FAIL:"
    private const val BK_DONE = "RMP_BK_DONE:"
    private const val RS_OK = "RMP_RS_OK:"
    private const val RS_FAIL = "RMP_RS_FAIL:"
    private const val RS_DONE = "RMP_RS_DONE:"
    private const val XB_OK = "RMP_XB_OK:"
    private const val XB_FAIL = "RMP_XB_FAIL:"
    private const val XB_DONE = "RMP_XB_DONE:"
    private const val XR_OK = "RMP_XR_OK:"
    private const val XR_FAIL = "RMP_XR_FAIL:"
    private const val XR_DONE = "RMP_XR_DONE:"

    data class Outcome(
        val succeeded: List<String>,
        val failed: Map<String, String>,
        val raw: String,
    ) {
        /**
         * A run that produced no `OK` line at all is not complete: the script
         * never reached its success marker, so treating "no failures" as
         * success would let a caller remove state it never archived.
         */
        val isComplete: Boolean get() = succeeded.isNotEmpty() && failed.isEmpty()
        val summary: String
            get() = "ok=${succeeded.size} failed=${failed.size}"
    }

    fun backup(
        context: Context,
        packages: List<String>,
        helper: File?,
        timeoutSeconds: Long = 900L,
    ): Outcome = run(context, "backup", packages, helper, timeoutSeconds, BK_OK, BK_FAIL, BK_DONE)

    fun restore(
        context: Context,
        packages: List<String>,
        helper: File?,
        timeoutSeconds: Long = 900L,
    ): Outcome = run(context, "restore", packages, helper, timeoutSeconds, RS_OK, RS_FAIL, RS_DONE)

    /** Archives the user-specified directories, then removes the originals. */
    fun backupExtras(
        context: Context,
        paths: List<String>,
        helper: File?,
        timeoutSeconds: Long = 600L,
    ): Outcome = runExtras(context, "extra-backup", paths, helper, timeoutSeconds, XB_OK, XB_FAIL, XB_DONE)

    /** Recreates the archived directories and their recorded ownership. */
    fun restoreExtras(
        context: Context,
        paths: List<String>,
        helper: File?,
        timeoutSeconds: Long = 600L,
    ): Outcome = runExtras(context, "extra-restore", paths, helper, timeoutSeconds, XR_OK, XR_FAIL, XR_DONE)

    private fun runExtras(
        context: Context,
        mode: String,
        paths: List<String>,
        helper: File?,
        timeoutSeconds: Long,
        okPrefix: String,
        failPrefix: String,
        donePrefix: String,
    ): Outcome {
        val result = AssetScriptRunner.run(
            context = context,
            assetName = ASSET,
            args = listOf(mode),
            env = mapOf(
                "RMP_BACKUP_ROOT" to AppBackupStore.backupRoot(context).absolutePath,
                "RMP_EXTRA_PATHS" to paths.joinToString("\n"),
            ),
            helper = helper,
            timeoutSeconds = timeoutSeconds,
        )
        return parse(result.output, okPrefix, failPrefix, donePrefix)
    }

    private fun run(
        context: Context,
        mode: String,
        packages: List<String>,
        helper: File?,
        timeoutSeconds: Long,
        okPrefix: String,
        failPrefix: String,
        donePrefix: String,
    ): Outcome {
        if (packages.isEmpty()) return Outcome(emptyList(), emptyMap(), "nothing selected")
        val result = AssetScriptRunner.run(
            context = context,
            assetName = ASSET,
            args = listOf(mode) + packages,
            env = mapOf("RMP_BACKUP_ROOT" to AppBackupStore.backupRoot(context).absolutePath),
            helper = helper,
            timeoutSeconds = timeoutSeconds,
        )
        return parse(result.output, okPrefix, failPrefix, donePrefix)
    }

    fun parse(
        output: String,
        okPrefix: String = BK_OK,
        failPrefix: String = BK_FAIL,
        donePrefix: String = BK_DONE,
    ): Outcome {
        val ok = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()
        output.lineSequence().map(String::trim).forEach { line ->
            when {
                line.startsWith(okPrefix) -> ok += line.removePrefix(okPrefix)
                line.startsWith(failPrefix) -> {
                    val rest = line.removePrefix(failPrefix)
                    failed[rest.substringBefore(':')] = rest.substringAfter(':', "unknown")
                }
            }
        }
        return Outcome(ok, failed, output)
    }
}
