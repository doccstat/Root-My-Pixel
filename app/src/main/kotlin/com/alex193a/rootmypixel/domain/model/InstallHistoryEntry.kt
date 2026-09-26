package com.alex193a.rootmypixel.domain.model

/**
 * Result of an installation run, persisted across app restarts.
 */
data class InstallHistoryEntry(
    val id: String,
    val startedAtMillis: Long,
    val completedAtMillis: Long?,
    val result: InstallRunResult?,
    val log: String,
)

enum class InstallRunResult { Succeeded, Failed }
