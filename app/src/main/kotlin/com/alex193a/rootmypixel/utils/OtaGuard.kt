package com.alex193a.rootmypixel.utils

/**
 * Keeps the device from ever booting a staged system update.
 *
 * A Pixel virtual-A/B update is applied by the *reboot itself*: update_engine's
 * CleanupPreviousUpdateAction calls snapshot->InitiateMerge(). Disabling the
 * updater components stops new downloads, but a package that is already staged
 * still merges on reboot, so the staged state has to be cancelled as well.
 *
 * Both commands are idempotent and safe to repeat after every root.
 */
object OtaGuard {
    /** Emitted when every updater component was disabled and settings applied. */
    const val BLOCK_OK = "RMP_OTA_BLOCK_OK"

    /** Emitted when at least one component could not be disabled. */
    const val BLOCK_PARTIAL = "RMP_OTA_BLOCK_PARTIAL"

    /** Emitted when no staged payload/snapshot remains. */
    const val PURGE_OK = "RMP_OTA_PURGE_OK"

    /** Emitted when a staged artefact survived the purge. */
    const val PURGE_PARTIAL = "RMP_OTA_PURGE_PARTIAL"

    const val FACTORY_OTA_PACKAGE = "com.google.android.factoryota"

    /**
     * GMS update entry points. The GMS package itself must stay enabled; only
     * these components are disabled for user 0.
     */
    val GMS_UPDATE_COMPONENTS = listOf(
        "com.google.android.gms/com.google.android.gms.update.SystemUpdateActivity",
        "com.google.android.gms/com.google.android.gms.update.SystemUpdateService",
        "com.google.android.gms/com.google.android.gms.update.SystemUpdateGcmTaskService",
        "com.google.android.gms/com.google.android.gms.update.SystemUpdatePersistentListenerService",
        "com.google.android.gms/com.google.android.gms.update.OtaSuggestionActivity",
        "com.google.android.gms/com.google.android.gms.update.OtaSuggestionSummaryProvider",
        "com.google.android.gms/com.google.android.gms.update.UpdateFromSdCardActivity",
        "com.google.android.gms/com.google.android.gms.update.phone.PopupDialog",
        "com.google.android.gms/com.google.android.gms.update.resumeonreboot.ResumeOnRebootEscrowService",
        "com.google.android.gms/com.google.android.gms.update.UucNotificationReceiverService",
    )

    /** Shell that disables the updater and records the two OTA global settings. */
    fun blockCommand(): String {
        val disableComponents = GMS_UPDATE_COMPONENTS.joinToString("\n") {
            "pm disable --user 0 $it >/dev/null 2>&1 || failed=1"
        }
        return """
            failed=0
            $disableComponents
            pm disable-user --user 0 $FACTORY_OTA_PACKAGE >/dev/null 2>&1 || true
            settings put global ota_disable_automatic_update 1 >/dev/null 2>&1 || true
            settings put global ota_disable_automatic_install 1 >/dev/null 2>&1 || true
            if [ "${'$'}failed" -eq 0 ]; then echo $BLOCK_OK; else echo $BLOCK_PARTIAL; fi
        """.trimIndent()
    }

    /**
     * Cancels a staged virtual-A/B update: stop the daemon, drop the payload,
     * the daemon's prefs/tmp and the /metadata snapshot state, then restart it.
     * A surviving artefact is reported instead of silently rebooting into it.
     */
    fun purgeStagedCommand(): String = """
        stop update_engine >/dev/null 2>&1 || true
        rm -rf /data/ota_package/* /data/misc/update_engine/prefs/* \
               /data/misc/update_engine/tmp/* /metadata/ota/* 2>/dev/null || true
        sync
        start update_engine >/dev/null 2>&1 || true
        if [ -z "${'$'}(ls -A /data/ota_package 2>/dev/null)" ] && \
           [ -z "${'$'}(ls -A /metadata/ota/snapshots 2>/dev/null)" ]; then
            echo $PURGE_OK
        else
            echo $PURGE_PARTIAL
        fi
    """.trimIndent()
}
