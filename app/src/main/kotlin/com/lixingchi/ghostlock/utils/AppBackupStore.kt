package com.lixingchi.ghostlock.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the selected-app plan and tracks which packages have an archive.
 *
 * Both live in the app's private files directory so they survive the unroot
 * reboot (the app itself is not removed by removing root) and are invisible to
 * other apps.
 */
object AppBackupStore {
    private const val PLAN_FILE = "selected_apps.json"
    private const val EXTRA_PATHS_FILE = "extra_paths.json"
    private const val BACKUP_DIR = "backups"
    private const val PENDING_FILE = "restore_pending"
    private const val RESTORE_DONE = "0"
    private const val PREFS = "rmp_backup_prefs"
    private const val KEY_RESTORE_ON_ROOT = "restore_on_root"

    fun planFile(context: Context): File = File(context.filesDir, PLAN_FILE)

    fun backupRoot(context: Context): File =
        File(context.filesDir, BACKUP_DIR).apply { mkdirs() }

    fun loadPlan(context: Context): List<String> {
        val file = planFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("packages") ?: JSONArray()
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
    }

    fun savePlan(context: Context, packages: List<String>) {
        val json = JSONObject().put(
            "packages",
            JSONArray(packages.distinct().sorted()),
        )
        planFile(context).writeText(json.toString())
    }

    fun extraPathsFile(context: Context): File = File(context.filesDir, EXTRA_PATHS_FILE)

    /**
     * User-specified absolute directories archived and removed alongside the
     * selected apps. Blank, duplicate and non-absolute entries are dropped;
     * the engine cannot handle paths with whitespace and neither does this.
     */
    fun loadExtraPaths(context: Context): List<String> {
        val file = extraPathsFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("paths") ?: JSONArray()
            (0 until array.length())
                .mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
                .filter { it.startsWith("/") && it.length > 1 && !it.any(Char::isWhitespace) }
                .distinct()
        }.getOrDefault(emptyList())
    }

    fun saveExtraPaths(context: Context, paths: List<String>) {
        val json = JSONObject().put(
            "paths",
            JSONArray(paths.distinct().sorted()),
        )
        extraPathsFile(context).writeText(json.toString())
    }

    /** True when the extra-directory bucket holds an archive to restore. */
    fun hasExtraBackup(context: Context): Boolean =
        File(backupRoot(context), "_extra/data.tgz").isFile

    /** True when the KernelSU/Vector root state holds an archive to restore. */
    fun hasRootStateBackup(context: Context): Boolean =
        File(backupRoot(context), "_rootstate/data.tgz").isFile

    fun hasBackup(context: Context, packageName: String): Boolean =
        File(backupRoot(context), packageName).isDirectory

    /** Plan entries that still have an archive on disk. */
    fun restorable(context: Context): List<String> =
        loadPlan(context).filter { hasBackup(context, it) }

    /**
     * Packages that already have an archive, in storage order. The `_`-prefixed
     * buckets (`_rootstate`, `_extra`) hold KernelSU/Vector state, not apps, so
     * they must not be counted or listed as restorable packages.
     */
    fun archivedPackages(context: Context): Set<String> =
        backupRoot(context).listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('_') }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

    fun totalBackupBytes(context: Context): Long {
        val root = backupRoot(context)
        return runCatching {
            root.walkTopDown().filter(File::isFile).sumOf(File::length)
        }.getOrDefault(0L)
    }

    /**
     * Whether the install flow should restore the backup once root is up.
     * Off means "start fresh": the archive stays on disk but is not applied.
     */
    fun restoreOnRoot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESTORE_ON_ROOT, true)

    fun setRestoreOnRoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RESTORE_ON_ROOT, enabled)
            .apply()
    }

    /**
     * True while a backup has been taken but not yet applied. The marker is
     * only cleared by a completed restore, so a restore that is skipped
     * (toggle off) or fails is still offered on the next root window.
     *
     * A missing marker means an install from before this flag existed: treat
     * an archive on disk as pending so the first run still restores it.
     */
    fun isRestorePending(context: Context): Boolean {
        val marker = File(context.filesDir, PENDING_FILE)
        if (marker.isFile) {
            return marker.readText().trim() != RESTORE_DONE
        }
        return hasRootStateBackup(context) || archivedPackages(context).isNotEmpty()
    }

    fun markRestorePending(context: Context) {
        runCatching { File(context.filesDir, PENDING_FILE).writeText("1") }
    }

    fun clearRestorePending(context: Context) {
        runCatching { File(context.filesDir, PENDING_FILE).writeText(RESTORE_DONE) }
    }
}
