package com.lixingchi.ghostlock.utils

import android.content.Context
import java.io.File

/**
 * Runs a bundled shell asset through the available root transport.
 *
 * The asset is materialised under the app's private files directory instead of
 * /data/local/tmp: the app cannot write to /data/local/tmp without root, and the
 * private directory survives reboots (so it is also the natural home for the
 * selected-app backups). The KernelSU root domain can read it.
 */
object AssetScriptRunner {
    private const val SCRIPT_DIR = "scripts"

    fun stagedFile(context: Context, assetName: String): File {
        val dir = File(context.filesDir, SCRIPT_DIR).apply { mkdirs() }
        return File(dir, assetName)
    }

    /** Writes (or refreshes) the asset next to the app and returns its path. */
    fun stage(context: Context, assetName: String): File {
        val body = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val file = stagedFile(context, assetName)
        if (!file.exists() || file.readText() != body) file.writeText(body)
        file.setExecutable(true, false)
        return file
    }

    fun run(
        context: Context,
        assetName: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        helper: File? = null,
        timeoutSeconds: Long = 120L,
    ): RootShell.Result {
        val script = stage(context, assetName)
        val command = buildString {
            env.forEach { (key, value) ->
                append(key).append('=').append(shellQuote(value)).append(' ')
            }
            append("sh ").append(shellQuote(script.absolutePath))
            args.forEach { append(' ').append(shellQuote(it)) }
        }
        return RootShell.run(command, helper = helper, timeoutSeconds = timeoutSeconds)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
