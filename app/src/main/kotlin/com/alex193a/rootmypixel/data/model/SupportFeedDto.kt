package com.alex193a.rootmypixel.data.model

import kotlinx.serialization.Serializable

/**
 * JSON schema for the bundled profiles feed (profiles.json in assets).
 */
@Serializable
data class BundledProfilesFeed(
    val profiles: List<BundledProfileDto>,
)

@Serializable
data class BundledProfileDto(
    val profileId: String,
    val codename: String,
    val kernelRelease: String,
    val buildDisplay: String,
    val exploitAsset: String,
    val kmi: String,
)
