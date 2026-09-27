package com.lixingchi.ghostlock.utils

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a shell command through the best available root channel.
 *
 * KernelSU's su_compat is preferred by absolute path (`/system/bin/su`) because
 * a bare `su` can be shadowed by an exploit-installed copy earlier in PATH. The
 * app's bundled CVE helper is the fallback while the exploit transport is alive.
 */
object RootShell {
    const val KERNEL_SU_PATH = "/system/bin/su"
    val SU_CANDIDATES = listOf(KERNEL_SU_PATH, "su")

    data class Result(val code: Int, val output: String) {
        val isOk: Boolean get() = code == 0
    }

    /**
     * Tries each root provider in turn and returns the first success. When every
     * provider fails, the last result is returned so callers can log it.
     */
    fun run(
        command: String,
        helper: File? = null,
        timeoutSeconds: Long = 30L,
    ): Result {
        val candidates = buildList {
            addAll(SU_CANDIDATES)
            helper?.takeIf { it.exists() }?.let { add(it.absolutePath) }
        }
        var last = Result(-1, "no root provider available")
        for (binary in candidates) {
            val result = exec(listOf(binary, "-c", command), timeoutSeconds) ?: continue
            if (result.isOk) return result
            last = result
        }
        return last
    }

    private fun exec(command: List<String>, timeoutSeconds: Long): Result? {
        val process = runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return Result(if (finished) process.exitValue() else 124, output)
    }
}
