package com.lixingchi.ghostlock.domain.usecase

import com.lixingchi.ghostlock.core.Result
import com.lixingchi.ghostlock.domain.model.DeviceSnapshot
import com.lixingchi.ghostlock.domain.model.TargetProfile
import com.lixingchi.ghostlock.domain.repository.PayloadRepository
import com.lixingchi.ghostlock.domain.repository.PayloadError

/**
 * Resolves the best-matching target profile for the current device.
 */
class ResolveTargetUseCase(private val repository: PayloadRepository) {
    suspend operator fun invoke(snapshot: DeviceSnapshot): Result<TargetProfile, PayloadError> {
        return repository.resolveTarget(snapshot)
    }

    suspend operator fun invoke(profileId: String): Result<TargetProfile, PayloadError> {
        return repository.resolveTarget(profileId)
    }
}
