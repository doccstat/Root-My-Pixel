package com.lixingchi.ghostlock.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class SoftRebootScriptTest {
    private val script = SoftReboot.buildScript()
    private val ksud = SoftReboot.DEFAULT_KSUD_PATH

    @Test
    fun neverRestartsTheComposer() {
        assertTrue(
            "a composer restart escalates to a RescueParty/recovery boot",
            script.indexOf("setprop ctl.restart") == -1,
        )
    }

    @Test
    fun neverTearsDownTheDisplayStack() {
        // init `stop`/`start` is what stales the cover panel; the panel-driver
        // unbind panics the kernel; `cmd display power-reset` is a no-op on the
        // folded cover panel.
        assertTrue(
            "must not stop the display stack",
            !script.contains("surfaceflinger", ignoreCase = true),
        )
        assertTrue("must not unbind the panel driver", script.indexOf("/unbind") == -1)
        assertTrue("must not use the blanket init stop", script.indexOf("\nstop\n") == -1)
        assertTrue("must not use the blanket init start", script.indexOf("\nstart\n") == -1)
        assertTrue("must not use cmd display", script.indexOf("cmd display") == -1)
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
    fun restartsOnlyTheFramework() {
        assertTrue(script.contains("setprop sys.boot_completed 0"))
        assertTrue(
            "must restart the runtime instead of the whole device",
            script.contains("killall -9 system_server"),
        )
    }

    @Test
    fun runsTheKsudModuleStagesInBootOrder() {
        val post = script.indexOf("$ksud\" post-fs-data")
        val services = script.indexOf("$ksud\" services")
        val completed = script.indexOf("$ksud\" boot-completed")
        assertTrue("expected a post-fs-data stage", post >= 0)
        assertTrue("post-fs-data must precede services", services > post)
        assertTrue("services must precede boot-completed", completed > services)
    }

    @Test
    fun postFsDataRunsWhileTheFrameworkIsDown() {
        val kill = script.indexOf("killall -9 system_server")
        val post = script.indexOf("$ksud\" post-fs-data")
        val wait = script.indexOf("while [ \$i -lt 180 ]")
        assertTrue("post-fs-data must follow the framework restart", post > kill)
        assertTrue("post-fs-data must run before waiting for boot_completed", post < wait)
    }

    @Test
    fun waitsForTheFrameworkAfterTheRestart() {
        val waits = script.split("sys.boot_completed").size - 1
        assertTrue("expected a boot_completed guard and wait", waits >= 2)
        assertTrue(
            "the wait must follow the framework restart",
            script.indexOf("killall -9 system_server") < script.indexOf("while [ \$i -lt 180 ]"),
        )
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
    fun clearsTheCrashRecoveryWindowAroundTheRestart() {
        // PackageWatchdog counts a system_server restart as a boot. Without
        // clearing its window our own soft reboot can trip the boot-loop
        // rollback, which reverts the pending mainline modules and force-reboots
        // the device with reason reboot,rollback_staged_install.
        val counter = "setprop ${CrashRecoveryGuard.RESCUE_BOOT_COUNT_PROP} 0"
        val kill = script.indexOf("killall -9 system_server")
        val first = script.indexOf(counter)
        assertTrue("the window must be cleared before the kill", first in 0 until kill)
        assertTrue(
            "the restart we just caused must not count either",
            script.lastIndexOf(counter) > kill,
        )
    }

    @Test
    fun toleratesAMissingKsud() {
        assertTrue(script.contains("not found"))
    }
}
