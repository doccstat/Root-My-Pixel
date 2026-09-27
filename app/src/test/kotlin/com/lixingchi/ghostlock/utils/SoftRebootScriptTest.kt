package com.lixingchi.ghostlock.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class SoftRebootScriptTest {
    private val script = SoftReboot.buildScript()

    @Test
    fun neverRestartsTheComposer() {
        // A composer restart while the framework is settling is what escalated
        // to a RescueParty/recovery boot, and the panel is now re-driven with
        // `cmd display power-reset` instead.
        assertTrue(
            "the soft reboot must not restart the composer HAL",
            script.indexOf("setprop ctl.restart") == -1,
        )
    }

    @Test
    fun containsNoSingleQuoteToBreakTheShCWrapper() {
        // SoftReboot.launch embeds this script inside `sh -c '...'`, so any
        // single quote in the script (a `tr '\n'` or a `case ... in ''`) ends
        // the wrapper early and the whole script dies with a shell parse error.
        assertTrue(
            "the generated script must not contain a single quote",
            !script.contains("'"),
        )
    }

    @Test
    fun softRebootsThroughKsud() {
        val ksud = script.indexOf("/data/adb/ksud\" soft-reboot")
        assertTrue("expected a ksud soft-reboot in the script", ksud >= 0)
    }

    @Test
    fun waitsForTheFrameworkAfterKsud() {
        val waits = script.split("sys.boot_completed").size - 1
        assertTrue("expected a boot_completed guard and wait", waits >= 2)
        assertTrue(script.indexOf("sys.boot_completed") < script.indexOf("soft-reboot"))
    }

    @Test
    fun abortsWhenTheFrameworkIsNotUp() {
        assertTrue(script.contains("framework is not up"))
        assertTrue(script.contains("exit 1"))
    }

    @Test
    fun refusesToRunAgainTooSoon() {
        // Two soft reboots close together wedged the boot: the second one ran
        // while the framework was still settling from the first.
        assertTrue(script.contains(SoftReboot.LOCK_PATH))
        assertTrue(script.contains(SoftReboot.MIN_INTERVAL_SECONDS.toString()))
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
    fun reInitialisesPanelPowerAfterKsud() {
        val ksud = script.indexOf("/data/adb/ksud\" soft-reboot")
        val reset = script.indexOf("cmd display power-reset")
        assertTrue("expected a panel power reset", reset >= 0)
        // `ksud soft-reboot` stales the panel, so the reset has to run after it
        // (and it must be a power-reset, not another composer restart).
        assertTrue("panel power reset must follow ksud", reset > ksud)
        assertTrue(
            "power reset must enumerate the connected displays",
            script.contains("get-displays --ids-only"),
        )
    }

    @Test
    fun toleratesAMissingKsud() {
        assertTrue(script.contains("not found"))
    }
}
