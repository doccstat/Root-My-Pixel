package com.alex193a.rootmypixel.data.model

import com.alex193a.rootmypixel.domain.model.TargetProfile

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
