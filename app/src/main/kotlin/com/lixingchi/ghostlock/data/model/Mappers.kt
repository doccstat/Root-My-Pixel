package com.lixingchi.ghostlock.data.model

import com.lixingchi.ghostlock.domain.model.TargetProfile

/**
 * Maps bundled DTOs to domain models.
 */
fun BundledProfileDto.toDomain(): TargetProfile = TargetProfile(
    profileId = profileId,
    codename = codename,
    kernelRelease = kernelRelease,
    buildDisplay = buildDisplay,
    exploitAsset = exploitAsset,
    kmi = kmi,
)
