package com.alex193a.rootmypixel.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * The KernelSU Manager APK shipped in `assets/`.
 *
 * The prepacked `kernelsu.ko` and the manager speak the same KernelSU version,
 * so the app installs this exact copy instead of sending the user to a download
 * page that could hand back a newer, mismatched manager.
 */
object BundledManager {
    /** KernelSU's manager package as upstream names it. */
    const val PACKAGE = "me.weishu.kernelsu"

    /** `versionCode` of [ASSET_PATH]; the install flow compares against it. */
    const val VERSION_CODE = 32601L

    const val ASSET_PATH = "manager/KernelSU_v3.3.0_32601-release.apk"

    /** Copy unpacked into the app's private storage for out-of-process reads. */
    private const val UNPACKED_APK = "ksu-manager.apk"

    /**
     * Unpacks the bundled manager (once) and returns a `content://` URI the
     * system package installer can read without root. Null when the asset
     * cannot be unpacked.
     */
    fun installerUri(context: Context): Uri? = runCatching {
        val apk = File(context.filesDir, UNPACKED_APK)
        if (!apk.isFile || apk.length() == 0L) {
            context.assets.open(ASSET_PATH).use { input ->
                apk.outputStream().use { output -> input.copyTo(output) }
            }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
    }.getOrNull()
}
