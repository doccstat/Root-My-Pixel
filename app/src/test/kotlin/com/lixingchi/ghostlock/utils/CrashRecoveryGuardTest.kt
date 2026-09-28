package com.lixingchi.ghostlock.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashRecoveryGuardTest {
    private val command = CrashRecoveryGuard.clearCommand()

    @Test
    fun clearsTheRestartCounterTheWatchdogCounts() {
        assertTrue(
            "PackageWatchdog counts each system_server start as a boot",
            command.contains("setprop ${CrashRecoveryGuard.RESCUE_BOOT_COUNT_PROP} 0"),
        )
    }

    @Test
    fun restartsTheWindowSoEarlierRestartsDoNotAccumulate() {
        assertTrue(
            command.contains("setprop ${CrashRecoveryGuard.RESCUE_BOOT_START_PROP} 0"),
        )
        assertTrue(
            command.contains("setprop ${CrashRecoveryGuard.BOOT_MITIGATION_COUNT_PROP} 0"),
        )
    }

    @Test
    fun reportsSuccessSoTheCallerCanLogIt() {
        assertTrue(command.contains(CrashRecoveryGuard.OK))
    }

    @Test
    fun clearsThePersistedMitigationCounts() {
        // A non-zero observer mitigation count makes the next restart
        // re-mitigate below the five-boot threshold, which is what pushed
        // RescueParty to FACTORY_RESET on yogi.
        assertTrue(command.contains(CrashRecoveryGuard.METADATA_PATH))
        assertTrue(command.contains(CrashRecoveryGuard.WATCHDOG_XML))
    }

    @Test
    fun containsNoSingleQuoteToBreakTheShCWrapper() {
        assertTrue("the cleared command is embedded in sh -c quoting", !command.contains("'"))
    }
}
