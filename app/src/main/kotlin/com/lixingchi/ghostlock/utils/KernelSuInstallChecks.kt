package com.lixingchi.ghostlock.utils

internal object KernelSuInstallChecks {
    private val versionLine = Regex("(?m)^version:\\s*([1-9][0-9]*)\\s*$")
    private val kernelModuleLine = Regex("(?m)^kernelsu\\s+.+$")
    private val signatureLine = Regex(
        """^size:\s*(0[xX][0-9a-fA-F]+|[0-9]+),\s*hash:\s*([0-9a-fA-F]{64})$""",
    )

    data class ManagerSignature(
        val size: Int,
        val hash: String,
    )

    fun debugInfoShowsActiveKernelSu(output: String): Boolean =
        versionLine.containsMatchIn(output)

    fun procModulesShowsActiveKernelSu(output: String): Boolean =
        kernelModuleLine.containsMatchIn(output)

    fun parseManagerSignature(output: String): ManagerSignature? {
        val match = signatureLine.matchEntire(output.trim()) ?: return null
        val rawSize = match.groupValues[1]
        val size = if (rawSize.startsWith("0x", ignoreCase = true)) {
            rawSize.drop(2).toIntOrNull(16)
        } else {
            rawSize.toIntOrNull()
        } ?: return null

        return ManagerSignature(
            size = size,
            hash = match.groupValues[2].lowercase(),
        )
    }

    fun isTrustedManagerSignature(signature: ManagerSignature): Boolean =
        signature in TRUSTED_MANAGER_SIGNATURES

    /**
     * The DER certificate of whatever signed the manager the driver is built to
     * trust. Our fork pins `com.lixingchi.kernelsu` signed with the GhostLock
     * RSA-2048 key (0x034b bytes, sha256 ff8c...040050), so that is the only
     * trusted entry - the upstream `me.weishu.kernelsu` manager (0x033b,
     * c371...) is a different package and would not be accepted by the driver.
     */
    private val TRUSTED_MANAGER_SIGNATURES = setOf(
        ManagerSignature(
            size = 0x34b,
            hash = "ff8c6f43e0bdd88057103c9faa5d78d7f43ee80a3c1ffa3e20eed06dcb640050",
        ),
    )
}
