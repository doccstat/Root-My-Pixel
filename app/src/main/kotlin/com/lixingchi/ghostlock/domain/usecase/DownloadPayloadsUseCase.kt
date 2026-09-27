package com.lixingchi.ghostlock.domain.usecase

import com.lixingchi.ghostlock.core.Result
import com.lixingchi.ghostlock.domain.model.TargetProfile
import com.lixingchi.ghostlock.domain.model.VerifiedPayloads
import com.lixingchi.ghostlock.domain.repository.PayloadError
import com.lixingchi.ghostlock.domain.repository.PayloadRepository

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
