package com.alex193a.rootmypixel.domain.usecase

import com.alex193a.rootmypixel.core.Result
import com.alex193a.rootmypixel.domain.model.DeviceSnapshot
import com.alex193a.rootmypixel.domain.model.TargetProfile
import com.alex193a.rootmypixel.domain.repository.PayloadRepository
import com.alex193a.rootmypixel.domain.repository.PayloadError

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
