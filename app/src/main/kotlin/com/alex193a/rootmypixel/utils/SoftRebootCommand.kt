package com.alex193a.rootmypixel.utils

/**
 * Shell command that restarts the framework without rebooting the kernel.
 *
 * Killing `system_server` alone leaves the composer HAL, SurfaceFlinger and
 * `pixeldisplayservice` running with stale state, which leaves the cover panel
 * showing a frozen artifact until the next real boot. Restarting the composer
 * HAL tears the display stack down in the correct order; SurfaceFlinger and the
 * framework then restart as a consequence, while the kernel (and therefore the
 * late-loaded driver and root) survives.
 */
object SoftRebootCommand {
    const val RESTART_DISPLAY_STACK: String =
        "hwc=${'$'}(getprop | grep -o 'vendor.hwcomposer-[0-9]*' | head -n1); " +
            "if [ -n \"${'$'}hwc\" ]; then setprop ctl.restart \"${'$'}hwc\"; " +
            "else killall -9 system_server; fi; true"
}
