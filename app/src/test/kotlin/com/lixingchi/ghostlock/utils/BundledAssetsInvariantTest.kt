package com.lixingchi.ghostlock.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins the fork-specific invariants of the payloads shipped in `assets/`.
 *
 * This is a monorepo of forks (KernelSU, the manager, NeoZygisk, Vector), and
 * the failures we actually hit are drift, not logic bugs: a profile pointing at
 * an exploit that was never bundled, a manager that got rebuilt without the
 * rebrand, an asset of the wrong architecture, or a signer hash that changed in
 * code but not in the docs. Each assertion below maps to one of those.
 */
class BundledAssetsInvariantTest {

    private val assets: File = listOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory }
        ?: throw FileNotFoundException("assets dir not found from ${File("").absolutePath}")

    private val readme: String get() = File(assets, "README.md").readText()
    private val profilesJson: String get() = File(assets, "profiles.json").readText()

    private fun collect(pattern: Regex): List<String> =
        pattern.findAll(profilesJson).map { it.groupValues[1] }.toList()

    private val profileIds = collect(Regex("\"profileId\"\\s*:\\s*\"([^\"]+)\""))
    private val exploitAssets = collect(Regex("\"exploitAsset\"\\s*:\\s*\"([^\"]+)\""))
    private val kmis = collect(Regex("\"kmi\"\\s*:\\s*\"([^\"]+)\""))

    /** KMIs the KernelSU fork's `build-lkm.yml` publishes (and ksud embeds). */
    private val supportedKmis = setOf(
        "android12-5.10",
        "android13-5.10",
        "android13-5.15",
        "android14-5.15",
        "android14-6.1",
        "android15-6.6",
        "android16-6.12",
        "android17-6.18",
    )

    private fun firstBytes(path: String, count: Int): ByteArray =
        File(assets, path).inputStream().use { it.readNBytes(count) }

    @Test
    fun `profile feed is well formed and complete`() {
        assertTrue("profiles.json does not look like JSON", profilesJson.trimStart().startsWith("{"))
        assertTrue(profileIds.isNotEmpty())
        // Every entry must carry all three fields, so a half-edited block fails
        // here instead of at install time.
        assertEquals(profileIds.size, exploitAssets.size)
        assertEquals(profileIds.size, kmis.size)
        assertEquals(profileIds.distinct().size, profileIds.size)
    }

    @Test
    fun `every profile points at a bundled non-empty exploit`() {
        for (ref in exploitAssets) {
            val payload = File(assets, ref)
            assertTrue("profile references missing asset $ref", payload.isFile)
            assertTrue("profile references empty asset $ref", payload.length() > 0)
        }
    }

    @Test
    fun `no exploit payload is orphaned from the profile feed`() {
        val referenced = exploitAssets.map { it.substringAfterLast('/') }.toSet()
        val bundled = File(assets, "exploits").listFiles()?.map { it.name }?.toSet().orEmpty()
        assertEquals("bundled exploits no longer reachable from profiles.json", emptySet<String>(), bundled - referenced)
    }

    @Test
    fun `every profile selects a KMI the LKM fork actually builds`() {
        val unknown = kmis.filterNot { it in supportedKmis }
        assertEquals("profiles select KMIs with no published kernelsu.ko", emptyList<String>(), unknown)
    }

    @Test
    fun `bundled ksud is an aarch64 android ELF`() {
        val head = firstBytes("ksud/ksud", 20)
        assertEquals(20, head.size)
        assertEquals(0x7f, head[0].toInt() and 0xff)
        assertEquals('E'.code, head[1].toInt() and 0xff)
        assertEquals('L'.code, head[2].toInt() and 0xff)
        assertEquals('F'.code, head[3].toInt() and 0xff)
        // e_machine at offset 18, little-endian: 0xB7 == EM_AARCH64.
        val machine = ByteBuffer.wrap(head, 18, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        assertEquals("ksud is not an aarch64 binary (e_machine=$machine)", 0xB7, machine)
    }

    @Test
    fun `bundled manager is an APK of a plausible size`() {
        val head = firstBytes(BundledManager.ASSET_PATH, 2)
        assertEquals(2, head.size)
        assertEquals('P'.code, head[0].toInt() and 0xff)
        assertEquals('K'.code, head[1].toInt() and 0xff)
        assertTrue(File(assets, BundledManager.ASSET_PATH).length() > 1_000_000)
    }

    @Test
    fun `the manager is our rebrand everywhere it is named`() {
        assertEquals("com.lixingchi.kernelsu", BundledManager.PACKAGE)
        assertTrue("README does not document the GhostLock manager package", readme.contains(BundledManager.PACKAGE))
        // Teardown has to know which package to purge, including ours.
        val unroot = File(assets, "unroot.sh").readText()
        assertTrue("unroot.sh never cleans the GhostLock manager", unroot.contains(BundledManager.PACKAGE))
    }

    @Test
    fun `the trusted manager signer in code is the one documented`() {
        val signatures = KernelSuInstallChecks.TRUSTED_MANAGER_SIGNATURES
        assertTrue(signatures.isNotEmpty())
        for (signature in signatures) {
            assertTrue(
                "README does not document trusted signer ${signature.hash}",
                readme.contains(signature.hash),
            )
            assertTrue(
                "README does not document signer DER size ${signature.size}",
                readme.contains("0x%03x".format(signature.size)) || readme.contains("%d".format(signature.size)),
            )
        }
    }
}
