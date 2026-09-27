package com.lixingchi.ghostlock.utils

import android.content.Context
import androidx.annotation.StringRes
import com.lixingchi.ghostlock.R

enum class UnrootIssue(
    val marker: String,
    @StringRes val labelRes: Int,
) {
    RootTransport("root-transport", R.string.unroot_issue_root_transport),
    KernelSuData("data-adb", R.string.unroot_issue_data_adb),
    ApexMount("apex-mount", R.string.unroot_issue_apex_mount),
    Selinux("selinux", R.string.unroot_issue_selinux),
    ExploitPayload("cve-app", R.string.unroot_issue_cve_app),
    RootHelper("cve-root", R.string.unroot_issue_cve_root),
    KernelSuLoader("ksud", R.string.unroot_issue_ksud),
    ExploitLogs("exploit-logs", R.string.unroot_issue_exploit_logs),
    RootTransportFiles("root-transport-files", R.string.unroot_issue_root_transport_files),
    AppPayloads("app-payloads", R.string.unroot_issue_app_payloads),
    AppScripts("app-scripts", R.string.unroot_issue_app_scripts),
    AppLog("app-log", R.string.unroot_issue_app_log),
    TempLogs("tmp-logs", R.string.unroot_issue_tmp_logs),
    OtaStaged("ota-", R.string.unroot_issue_ota),
    Backup("backup", R.string.unroot_issue_backup),
    Reboot("reboot", R.string.unroot_issue_reboot),
    /**
     * The root shell that runs the unroot steps disappeared before the script
     * reached its `UNROOT_CLEANUP_OK` / `UNROOT_CLEANUP_PARTIAL` terminator.
     * Nothing can be asserted about the steps that never reported.
     */
    Incomplete("incomplete", R.string.unroot_issue_incomplete),
    Unknown("unknown", R.string.unroot_issue_unknown),
    ;

    companion object {
        fun fromMarker(marker: String): UnrootIssue =
            entries.firstOrNull { it.marker == marker }
                ?: entries.firstOrNull {
                    it.marker.endsWith("-") && marker.startsWith(it.marker)
                }
                ?: Unknown

        val affectedByMissingTransport: List<UnrootIssue> = listOf(
            RootTransport,
            KernelSuData,
            ApexMount,
            Selinux,
            ExploitPayload,
            RootHelper,
            KernelSuLoader,
            ExploitLogs,
            RootTransportFiles,
            OtaStaged,
            Backup,
        )
    }
}

data class UnrootCommandOutcome(
    val cleanupComplete: Boolean,
    val rebootRequested: Boolean,
    val transportUnavailable: Boolean,
    val issues: List<UnrootIssue>,
    val hasStructuredOutput: Boolean,
) {
    fun failedItemsText(context: Context): String = issues
        .ifEmpty { listOf(UnrootIssue.Unknown) }
        .distinct()
        .joinToString(separator = "\n") { issue ->
            "• ${context.getString(issue.labelRes)}"
        }

    companion object {
        fun parse(output: String): UnrootCommandOutcome {
            val issues = output.lineSequence()
                .map(String::trim)
                .filter { it.startsWith(FAILURE_PREFIX) }
                .map { line ->
                    UnrootIssue.fromMarker(
                        line.removePrefix(FAILURE_PREFIX).substringBefore(':'),
                    )
                }
                .toList()

            val transportUnavailable = output.contains(TRANSPORT_UNAVAILABLE)
            val structured = output.contains(MARKER_PREFIX)
            val finished = output.contains(CLEANUP_OK) ||
                output.contains(CLEANUP_PARTIAL) ||
                transportUnavailable
            val parsedIssues = buildList {
                addAll(issues)
                if (transportUnavailable) addAll(UnrootIssue.affectedByMissingTransport)
                // The root shell started the unroot steps but never reached a
                // terminator: report that explicitly instead of leaving the UI
                // to guess "could not be verified" from an empty failure list.
                if (structured && !finished) add(UnrootIssue.Incomplete)
            }.distinct()

            return UnrootCommandOutcome(
                cleanupComplete = output.contains(CLEANUP_OK),
                rebootRequested = output.contains(REBOOT_REQUESTED),
                transportUnavailable = transportUnavailable,
                issues = parsedIssues,
                hasStructuredOutput = output.contains(MARKER_PREFIX),
            )
        }

        private const val MARKER_PREFIX = "UNROOT_"
        private const val FAILURE_PREFIX = "UNROOT_FAIL:"
        private const val TRANSPORT_UNAVAILABLE = "UNROOT_TRANSPORT_UNAVAILABLE"
        private const val CLEANUP_OK = "UNROOT_CLEANUP_OK"
        private const val CLEANUP_PARTIAL = "UNROOT_CLEANUP_PARTIAL"
        private const val REBOOT_REQUESTED = "UNROOT_REBOOT_REQUESTED"
    }
}
