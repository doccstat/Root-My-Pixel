package com.lixingchi.ghostlock.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class SoftRebootScriptTest {
    private val script = SoftReboot.buildScript()

    @Test
    fun restartsTheComposerBeforeKsudSoftReboot() {
        val composer = script.indexOf("setprop ctl.restart")
        val ksud = script.indexOf("/data/adb/ksud\" soft-reboot")
        assertTrue("expected a composer restart in the script", composer >= 0)
        assertTrue("expected a ksud soft-reboot in the script", ksud >= 0)
        // A composer restart after a fresh soft reboot crash-loops the framework
        // and RescueParty reboots the device, so the order must not flip.
        assertTrue(
            "the composer restart must come first",
            composer < ksud,
        )
    }

    @Test
    fun waitsForTheFrameworkBeforeEachStage() {
        val waits = script.split("sys.boot_completed").size - 1
        assertTrue("expected a boot_completed wait before and after ksud", waits >= 2)
        assertTrue(script.indexOf("sys.boot_completed") < script.indexOf("soft-reboot"))
    }

    @Test
    fun reLightsTheDisplayAfterKsudWithADpmsToggle() {
        val ksud = script.indexOf("/data/adb/ksud\" soft-reboot")
        val sleep = script.indexOf("KEYCODE_SLEEP")
        val wake = script.indexOf("KEYCODE_WAKEUP")
        assertTrue("expected a DPMS off", sleep > ksud)
        assertTrue("expected a DPMS on after it", wake > sleep)
        // Must not start a second composer restart after ksud.
        assertTrue(
            "no composer restart may follow ksud",
            script.indexOf("setprop ctl.restart", ksud) == -1,
        )
    }

    @Test
    fun fallsBackWhenKsudOrTheComposerIsMissing() {
        assertTrue(script.contains("killall -9 system_server"))
        assertTrue(script.contains("not found"))
    }
}
