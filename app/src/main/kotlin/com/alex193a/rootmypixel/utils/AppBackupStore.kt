package com.alex193a.rootmypixel.utils

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
    private const val BACKUP_DIR = "backups"

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

    fun hasBackup(context: Context, packageName: String): Boolean =
        File(backupRoot(context), packageName).isDirectory

    /** Plan entries that still have an archive on disk. */
    fun restorable(context: Context): List<String> =
        loadPlan(context).filter { hasBackup(context, it) }

    /** Packages that already have an archive, in storage order. */
    fun archivedPackages(context: Context): Set<String> =
        backupRoot(context).listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

    fun totalBackupBytes(context: Context): Long {
        val root = backupRoot(context)
        return runCatching {
            root.walkTopDown().filter(File::isFile).sumOf(File::length)
        }.getOrDefault(0L)
    }
}
