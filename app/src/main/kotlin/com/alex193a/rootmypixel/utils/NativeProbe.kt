package com.alex193a.rootmypixel.utils

import java.io.File

/**
 * Native probe companion. Uses JNI to read device information and check
 * KernelSU status. The actual native implementation is in src/main/cpp/.
 */
object NativeProbe {
    init {
        System.loadLibrary("pixel_native")
    }

    /**
     * Run the native probe: returns device information as a text dump.
     */
    external fun run(): String

    /** Returns the KernelSU driver status obtained through the official supercall UAPI. */
    external fun getKernelSuInfoNative(): String

    /**
     * Check if KernelSU/ReSukiSU is active via kernel driver syscall in a fork-isolated process.
     * Kept for callers that only require a Boolean.
     */
    external fun isKernelSuActiveNative(): Boolean

    /**
     * Check the actual KernelSU/ReSukiSU kernel driver. This deliberately does
     * not treat `su`, a temporary CVE daemon, or filesystem paths as proof that
     * KernelSU is loaded.
     */
    fun kernelSuStatus(): KernelSuStatus =
        runCatching { KernelSuStatus.parse(getKernelSuInfoNative()) }
            .getOrDefault(KernelSuStatus())

    fun isKernelSuActive(): Boolean = kernelSuStatus().isActive

    data class KernelSuStatus(
        val probeCompleted: Boolean = false,
        val driverPresent: Boolean = false,
        val driverResponsive: Boolean = false,
        val appRootGranted: Boolean = false,
        val version: UInt = 0u,
        val flags: UInt = 0u,
        val features: UInt = 0u,
        val uapiVersion: UInt = 0u,
    ) {
        val isActive: Boolean
            get() = probeCompleted && driverPresent && driverResponsive && version > 0u

        val isLateLoad: Boolean
            get() = flags and FLAG_LATE_LOAD != 0u

        companion object {
            private val FLAG_LATE_LOAD: UInt = 1u shl 2

            fun parse(raw: String): KernelSuStatus {
                val values = raw.split(';')
                    .mapNotNull { entry ->
                        entry.split('=', limit = 2).takeIf { it.size == 2 }
                    }
                    .associate { (key, value) -> key to value }

                fun boolean(key: String) = values[key] == "1"
                fun unsigned(key: String) = values[key]?.toUIntOrNull() ?: 0u

                return KernelSuStatus(
                    probeCompleted = boolean("probe"),
                    driverPresent = boolean("present"),
                    driverResponsive = boolean("responsive"),
                    appRootGranted = boolean("granted"),
                    version = unsigned("version"),
                    flags = unsigned("flags"),
                    features = unsigned("features"),
                    uapiVersion = unsigned("uapi"),
                )
            }
        }
    }

    /**
     * Read current device snapshot from /proc and system properties.
     */
    fun readDeviceSnapshot(): DeviceInfo {
        val kernelRelease = runCatching {
            File("/proc/version").readText().trim()
        }.getOrElse {
            System.getProperty("os.version") ?: ""
        }
        val versionParts = kernelRelease.split(" ")
        val release = if (versionParts.size >= 3) versionParts[2] else kernelRelease

        val buildDisplay = android.os.Build.DISPLAY.takeIf { it.isNotBlank() } ?: runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "ro.build.display.id"))
                .inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")

        val model = android.os.Build.MODEL.takeIf { it.isNotBlank() } ?: runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "ro.product.model"))
                .inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")

        val device = android.os.Build.DEVICE.takeIf { it.isNotBlank() } ?: runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "ro.product.device"))
                .inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")

        val sdkVersion = if (android.os.Build.VERSION.SDK_INT > 0) {
            android.os.Build.VERSION.SDK_INT
        } else {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("getprop", "ro.build.version.sdk"))
                    .inputStream.bufferedReader().readText().trim().toInt()
            }.getOrDefault(0)
        }

        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "ro.product.cpu.abi"))
                .inputStream.bufferedReader().readText().trim()
        }.getOrDefault("arm64-v8a")

        val pageSize = runCatching {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE).toInt()
        }.getOrDefault(4096)

        return DeviceInfo(
            kernelRelease = release,
            kernelVersion = kernelRelease,
            buildDisplay = buildDisplay,
            sdkVersion = sdkVersion,
            abi = abi,
            pageSize = pageSize,
            model = model,
            device = device,
        )
    }
}

data class DeviceInfo(
    val kernelRelease: String,
    val kernelVersion: String,
    val buildDisplay: String,
    val sdkVersion: Int,
    val abi: String,
    val pageSize: Int,
    val model: String,
    val device: String,
)
