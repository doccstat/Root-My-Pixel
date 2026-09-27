package com.alex193a.rootmypixel.domain.usecase

import com.alex193a.rootmypixel.core.Result
import com.alex193a.rootmypixel.domain.model.TargetProfile
import com.alex193a.rootmypixel.domain.model.VerifiedPayloads
import com.alex193a.rootmypixel.domain.repository.PayloadError
import com.alex193a.rootmypixel.domain.repository.PayloadRepository

/**
 * Extracts bundled exploit and KernelSU payloads for the resolved target profile
 * from APK assets to the app's files directory.
 */
class DownloadPayloadsUseCase(private val repository: PayloadRepository) {
    suspend operator fun invoke(
        profile: TargetProfile,
        onProgress: (String) -> Unit = {},
    ): Result<VerifiedPayloads, PayloadError> {
        return repository.extractPayloads(profile, onProgress)
    }
}
