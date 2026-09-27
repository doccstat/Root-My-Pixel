package com.alex193a.rootmypixel.data.repository

import com.alex193a.rootmypixel.core.Result
import com.alex193a.rootmypixel.data.datasource.PayloadLocalDataSource
import com.alex193a.rootmypixel.data.model.toDomain
import com.alex193a.rootmypixel.domain.model.DeviceSnapshot
import com.alex193a.rootmypixel.domain.model.TargetProfile
import com.alex193a.rootmypixel.domain.model.VerifiedPayloads
import com.alex193a.rootmypixel.domain.repository.PayloadError
import com.alex193a.rootmypixel.domain.repository.PayloadRepository
import java.io.File

/**
 * Repository that reads everything from APK assets.
 * Profiles are loaded from assets/profiles.json, exploit .so and ksud
 * binaries are extracted from assets/ on demand.
 */
class PayloadRepositoryImpl(
    private val localDataSource: PayloadLocalDataSource,
    private val filesDir: File,
) : PayloadRepository {

    private var cachedProfiles: List<TargetProfile>? = null

    override suspend fun resolveTarget(
        snapshot: DeviceSnapshot,
    ): Result<TargetProfile, PayloadError> {
        return when (val result = loadCachedProfiles()) {
            is Result.Error -> result
            is Result.Success -> {
                val profiles = result.data

                val targetCodename = snapshot.device.trim()
                val targetKernelRel = snapshot.kernelRelease.trim()

                // /proc/version appends a Git revision to uname -r (for example,
                // "6.6.118-android15-8-g<sha>"). Profiles intentionally keep the
                // stable release prefix.
                val exactMatch = profiles.find { profile ->
                    targetCodename.isNotEmpty() &&
                        profile.codename.equals(targetCodename, ignoreCase = true) &&
                        (targetKernelRel == profile.kernelRelease ||
                            targetKernelRel.startsWith("${profile.kernelRelease}-"))
                }

                if (exactMatch != null) {
                    Result.Success(exactMatch)
                } else {
                    Result.Error(
                        PayloadError.UnsupportedError(
                            "No profile for $targetCodename / $targetKernelRel"
                        )
                    )
                }
            }
        }
    }

    override suspend fun resolveTarget(
        profileId: String,
    ): Result<TargetProfile, PayloadError> {
        return when (val result = loadCachedProfiles()) {
            is Result.Error -> result
            is Result.Success -> {
                val profile = result.data.find { it.profileId == profileId }
                if (profile != null) {
                    Result.Success(profile)
                } else {
                    Result.Error(PayloadError.UnsupportedError("Profile $profileId not found"))
                }
            }
        }
    }

    override suspend fun extractPayloads(
        profile: TargetProfile,
        onProgress: (String) -> Unit,
    ): Result<VerifiedPayloads, PayloadError> {
        val payloadDir = File(filesDir, "payloads/${profile.profileId}")
        if (!payloadDir.isDirectory && !payloadDir.mkdirs()) {
            return Result.Error(PayloadError.ExtractionError("Unable to create payload directory"))
        }

        // Extract exploit .so from assets
        val exploitFile = File(payloadDir, "exploit.so")
        exploitFile.delete()
        val exploitResult = localDataSource.extractAsset(
            assetPath = profile.exploitAsset,
            destination = exploitFile,
            onProgress = onProgress,
        )
        if (exploitResult.isFailure) {
            return Result.Error(
                PayloadError.ExtractionError(
                    "Failed to extract exploit: ${exploitResult.exceptionOrNull()?.message}"
                )
            )
        }
        if (!exploitFile.setExecutable(true, true)) {
            exploitFile.delete()
            return Result.Error(PayloadError.ExtractionError("Unable to make exploit executable"))
        }

        // Extract ksud binary from assets
        // One binary covers every KMI: it embeds a kernelsu.ko per KMI and picks
        // between them from the --kmi it is passed at late-load, which is where
        // profile.kmi is used.
        val ksudAssetPath = "${PayloadLocalDataSource.KSUD_ASSET_PREFIX}ksud"
        val ksudFile = File(payloadDir, "ksud")
        ksudFile.delete()
        val ksudResult = localDataSource.extractAsset(
            assetPath = ksudAssetPath,
            destination = ksudFile,
            onProgress = onProgress,
        )
        if (ksudResult.isFailure) {
            exploitFile.delete()
            return Result.Error(
                PayloadError.ExtractionError(
                    "Failed to extract ksud: ${ksudResult.exceptionOrNull()?.message}"
                )
            )
        }
        if (!ksudFile.setExecutable(true, true)) {
            exploitFile.delete()
            ksudFile.delete()
            return Result.Error(PayloadError.ExtractionError("Unable to make ksud executable"))
        }

        onProgress("Payloads ready")
        return Result.Success(VerifiedPayloads(exploit = exploitFile, kernelSu = ksudFile, kmi = profile.kmi))
    }

    override suspend fun loadTargets(): Result<List<TargetProfile>, PayloadError> {
        return loadCachedProfiles()
    }

    private fun loadCachedProfiles(): Result<List<TargetProfile>, PayloadError> {
        cachedProfiles?.let { return Result.Success(it) }

        return localDataSource.loadProfiles().fold(
            onSuccess = { dtos ->
                val profiles = dtos.map { it.toDomain() }
                cachedProfiles = profiles
                Result.Success(profiles)
            },
            onFailure = { throwable ->
                Result.Error(
                    PayloadError.ExtractionError(
                        throwable.message ?: "Failed to load profiles.json"
                    )
                )
            }
        )
    }
}
