package com.lixingchi.ghostlock.utils

import android.content.Context
import java.io.File

/**
 * The KernelSU Manager APK shipped in `assets/`.
 *
 * The prepacked `kernelsu.ko` and the manager speak the same KernelSU version,
 * so the app installs this exact copy instead of letting a user-installed
 * manager (or a download) diverge from the driver it talks to.
 */
object BundledManager {
    /** KernelSU's manager package as upstream names it. */
    const val PACKAGE = "me.weishu.kernelsu"

    /** `versionCode` of [ASSET_PATH]; every install is compared against it. */
    const val VERSION_CODE = 32601L

    const val ASSET_PATH = "manager/KernelSU_v3.3.0_32601-release.apk"

    /** Where the APK is staged for `pm install` by the root shell. */
    private const val STAGED_PATH = "/data/local/tmp/ksu-manager.apk"
    private const val CACHED_APK = "ksu-manager.apk"

    private val SIGNATURE_MISMATCH_MARKERS = listOf(
        "signatures do not match",
        "UPDATE_INCOMPATIBLE",
        "INCONSISTENT_CERTIFICATES",
    )

    fun installedVersionCode(context: Context): Long? = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0).longVersionCode
    }.getOrNull()

    fun isBundledVersionInstalled(context: Context): Boolean =
        installedVersionCode(context) == VERSION_CODE

    /**
     * Installs the bundled manager through the KernelSU root shell. This is the
     * single implementation used both by the install flow and by the retry on
     * the main screen, so there is only one way the manager ever gets installed.
     *
     * @return true when [VERSION_CODE] is installed afterwards.
     */
    fun installViaRoot(
        context: Context,
        helper: File?,
        log: (String) -> Unit = {},
    ): Boolean {
        val installed = installedVersionCode(context)
        if (installed == VERSION_CODE) {
            log("[+] KernelSU Manager $installed already installed")
            return true
        }
        log(
            "[*] Installing bundled KernelSU Manager $VERSION_CODE " +
                "(device has ${installed ?: "none"})...",
        )
        val staged = stage(context, helper, log) ?: return false
        var result = RootShell.run("pm install -r $staged", helper = helper)
        if (!result.isOk &&
            SIGNATURE_MISMATCH_MARKERS.any { result.output.contains(it, ignoreCase = true) }
        ) {
            log("[!] Installed manager has a different signature; replacing it")
            RootShell.run("pm uninstall $PACKAGE", helper = helper)
            result = RootShell.run("pm install -r $staged", helper = helper)
        }
        RootShell.run("rm -f $staged", helper = helper)
        val now = installedVersionCode(context)
        if (result.isOk && now == VERSION_CODE) {
            log("[+] KernelSU Manager $now installed")
            return true
        }
        log(
            "[!] KernelSU Manager install failed (${result.code}): " +
                result.output.ifBlank { "no output" }.take(300),
        )
        return false
    }

    private fun stage(context: Context, helper: File?, log: (String) -> Unit): String? {
        val cached = File(context.cacheDir, CACHED_APK)
        runCatching {
            context.assets.open(ASSET_PATH).use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
        }.getOrElse {
            log("[!] Bundled manager unpack failed: ${it.message}")
            return null
        }
        val copy = RootShell.run(
            "cp '${cached.absolutePath}' $STAGED_PATH && chmod 644 $STAGED_PATH && chown root:root $STAGED_PATH",
            helper = helper,
        )
        if (!copy.isOk) {
            log("[!] Bundled manager staging failed: ${copy.output.take(200)}")
            return null
        }
        return STAGED_PATH
    }
}
