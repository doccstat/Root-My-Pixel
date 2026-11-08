package com.lixingchi.ghostlock.utils

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SoftRebootScriptTest {
    @Test
    fun delegatesOnlyToTheCoordinatedDaemon() {
        val script = SoftReboot.buildScript()
        assertTrue(script.contains("soft-reboot --check"))
        assertTrue(script.endsWith("'${SoftReboot.DEFAULT_KSUD_PATH}' soft-reboot"))
        for (command in listOf("killall", "ctl.", "post-fs-data", "boot-completed", "modules_update", "setsid")) {
            assertFalse("App must not duplicate daemon lifecycle: $command", script.contains(command))
        }
    }

    @Test
    fun capabilityCheckPreventsOldDaemonsAndAcceptsStagedUpdates() {
        for ((capability, checkStatus, repairStatus) in listOf(
            Triple(SoftReboot.CONTRACT, 0, 0),
            Triple("old-daemon", 0, 0),
            Triple("", 0, 0),
            Triple(SoftReboot.CONTRACT, 1, 0),
            Triple(SoftReboot.CONTRACT, 0, 1),
        )) {
            val directory = Files.createTempDirectory("soft-reboot'").toFile()
            try {
                val daemon = directory.resolve("ksud test")
                val calls = directory.resolve("calls")
                daemon.writeText("""#!/bin/sh
                    if [ "${'$'}2" = --check ]; then
                        printf '%s\n' '$capability'
                        exit $checkStatus
                    else
                        printf 'restart\n' >> "${'$'}CALLS"
                    fi
                """.trimIndent())
                assertTrue(daemon.setExecutable(true))
                val repair = directory.resolve("platform repair.sh")
                repair.writeText("echo repair >> \"\$CALLS\"\nexit $repairStatus\n")
                val process = ProcessBuilder("sh")
                    .redirectErrorStream(true)
                    .apply { environment()["CALLS"] = calls.invariantSeparatorsPath }
                    .start()
                process.outputStream.bufferedWriter().use {
                    it.write(SoftReboot.buildScript(daemon.invariantSeparatorsPath, repair.invariantSeparatorsPath))
                    it.newLine()
                }
                val output = process.inputStream.bufferedReader().readText()
                val accepted = capability == SoftReboot.CONTRACT && checkStatus == 0
                val restarted = accepted && repairStatus == 0
                assertEquals(output, restarted, process.waitFor() == 0)
                val events = if (calls.exists()) calls.readLines() else emptyList()
                val expected = when {
                    restarted -> listOf("repair", "restart")
                    accepted -> listOf("repair")
                    else -> emptyList()
                }
                assertEquals(expected, events)
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
