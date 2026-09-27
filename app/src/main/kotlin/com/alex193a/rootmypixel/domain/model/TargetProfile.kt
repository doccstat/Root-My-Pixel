package com.alex193a.rootmypixel.domain.model

/**
 * A supported firmware profile for a specific Pixel device/build.
 * All payloads are bundled in the APK assets.
 */
data class TargetProfile(
    val profileId: String,
    val codename: String,
    val kernelRelease: String,
    val buildDisplay: String,
    /** Path to the exploit .so inside app assets, e.g. "exploits/frankel-CP2A.260605.012.so" */
    val exploitAsset: String,
    /** KMI tag that selects the bundled ksud binary, e.g. "android15-6.6" */
    val kmi: String,
)
