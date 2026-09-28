package com.lixingchi.ghostlock.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * The KernelSU Manager APK shipped in `assets/`.
 *
 * The prepacked `kernelsu.ko` and the manager speak the same KernelSU version,
 * so the app installs this exact copy instead of letting a user-installed
 * manager (or a download) diverge from the driver it talks to.
 */
object BundledManager {
    /**
     * The GhostLock manager package - our KernelSU fork builds the manager
     * under this name so the driver trusts it (see `kernel/Kbuild` in the fork,
     * which pins `KSU_MANAGER_PACKAGE` and our signer certificate).
     */
    const val PACKAGE = "com.lixingchi.kernelsu"

    /**
     * Stable asset name for the bundled manager. The Root-My-Pixel CI replaces
     * this file in place with whatever the KernelSU fork last published, so the
     * name must not carry a version: the APK itself is the version source.
     */
    const val ASSET_PATH = "manager/GhostLock-manager.apk"

    /** Where the APK is staged for `pm install` by the root shell. */
    private const val STAGED_PATH = "/data/local/tmp/ksu-manager.apk"
    private const val CACHED_APK = "ksu-manager.apk"

    @Volatile
    private var bundledVersionCodeCache: Long? = null

    private val SIGNATURE_MISMATCH_MARKERS = listOf(
        "signatures do not match",
        "UPDATE_INCOMPATIBLE",
        "INCONSISTENT_CERTIFICATES",
    )

    fun installedVersionCode(context: Context): Long? = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0).longVersionCode
    }.getOrNull()

    /**
     * `versionCode` of the APK bundled in [ASSET_PATH], read from the archive
     * itself so refreshing the asset in CI needs no code change. Returns null
     * only when the asset is missing or unreadable.
     */
    fun bundledVersionCode(context: Context): Long? {
        bundledVersionCodeCache?.let { return it }
        val cached = ensureCached(context) ?: return null
        val code = archiveVersionCode(context, cached.absolutePath)
        if (code != null) bundledVersionCodeCache = code
        return code
    }

    fun isBundledVersionInstalled(context: Context): Boolean {
        val bundled = bundledVersionCode(context) ?: return false
        return installedVersionCode(context) == bundled
    }

    /**
     * Installs the bundled manager through the KernelSU root shell. This is the
     * single implementation used both by the install flow and by the retry on
     * the main screen, so there is only one way the manager ever gets installed.
     *
     * @return true when the bundled manager is installed afterwards.
     */
    fun installViaRoot(
        context: Context,
        helper: File?,
        log: (String) -> Unit = {},
    ): Boolean {
        val target = bundledVersionCode(context)
        val installed = installedVersionCode(context)
        if (target != null && installed == target) {
            log("[+] KernelSU Manager $installed already installed")
            return true
        }
        log(
            "[*] Installing bundled KernelSU Manager ${target ?: "(unreadable version)"} " +
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
        if (result.isOk && now != null && (target == null || now == target)) {
            log("[+] KernelSU Manager $now installed")
            return true
        }
        log(
            "[!] KernelSU Manager install failed (${result.code}): " +
                result.output.ifBlank { "no output" }.take(300),
        )
        return false
    }

    private fun archiveVersionCode(context: Context, path: String): Long? = runCatching {
        val flags = PackageManager.PackageInfoFlags.of(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(path, flags)?.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(path, 0)?.longVersionCode
        }
    }.getOrNull()

    /** Extracts the bundled APK into the app cache so `pm` can read it. */
    private fun ensureCached(context: Context): File? = runCatching {
        val cached = File(context.cacheDir, CACHED_APK)
        context.assets.open(ASSET_PATH).use { input ->
            cached.outputStream().use { output -> input.copyTo(output) }
        }
        cached
    }.getOrNull()

    private fun stage(context: Context, helper: File?, log: (String) -> Unit): String? {
        val cached = ensureCached(context)
        if (cached == null) {
            log("[!] Bundled manager unpack failed (asset ${ASSET_PATH} unreadable)")
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
