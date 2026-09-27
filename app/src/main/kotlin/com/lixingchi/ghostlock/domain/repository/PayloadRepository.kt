package com.lixingchi.ghostlock.domain.repository

import com.lixingchi.ghostlock.core.Error
import com.lixingchi.ghostlock.core.Result
import com.lixingchi.ghostlock.domain.model.DeviceSnapshot
import com.lixingchi.ghostlock.domain.model.TargetProfile
import com.lixingchi.ghostlock.domain.model.VerifiedPayloads

/**
 * Repository for resolving target profiles and extracting bundled payloads.
 * Everything ships inside the APK.
 */
interface PayloadRepository {
    /**
     * Resolve the best-matching target profile for the given device snapshot.
     */
    suspend fun resolveTarget(snapshot: DeviceSnapshot): Result<TargetProfile, PayloadError>

    /**
     * Resolve a specific profile by ID (manual selection).
     */
    suspend fun resolveTarget(profileId: String): Result<TargetProfile, PayloadError>

    /**
     * Extract bundled payload artifacts for a resolved profile from APK assets.
     * [onProgress] is called with description strings during extraction.
     */
    suspend fun extractPayloads(
        profile: TargetProfile,
        onProgress: (String) -> Unit,
    ): Result<VerifiedPayloads, PayloadError>

    /**
     * Load all available target profiles bundled in the app.
     */
    suspend fun loadTargets(): Result<List<TargetProfile>, PayloadError>
}

sealed interface PayloadError : Error {
    data class UnsupportedError(override val message: String) : PayloadError
    data class ExtractionError(override val message: String) : PayloadError
    data class UnknownError(override val message: String) : PayloadError
}
