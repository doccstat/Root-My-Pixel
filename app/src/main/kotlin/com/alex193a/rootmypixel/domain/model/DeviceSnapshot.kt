package com.alex193a.rootmypixel.domain.model

/**
 * Snapshot of the current device for profile matching.
 */
data class DeviceSnapshot(
    val kernelRelease: String,
    val kernelVersion: String,
    val buildDisplay: String,
    val sdkVersion: Int,
    val abi: String,
    val pageSize: Int,
    val model: String,
    val device: String,
)
