package com.lixingchi.ghostlock.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KernelSuAllowlistTest {

    private val packageName = "com.lixingchi.ghostlock"
    private val uid = 10361

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)

    @Test
    fun `header is the magic followed by the v4 format version`() {
        assertArrayEquals(
            byteArrayOf(0x55, 0x53, 0x4b, 0x7f, 0x04, 0x00, 0x00, 0x00),
            KernelSuAllowlist.FILE_HEADER,
        )
        assertEquals(KernelSuAllowlist.FILE_MAGIC, intAt(KernelSuAllowlist.FILE_HEADER, 0))
        assertEquals(4, intAt(KernelSuAllowlist.FILE_HEADER, 4))
    }

    @Test
    fun `sizes match the driver's struct app_profile`() {
        // sizeof(struct app_profile) for KSU_APP_PROFILE_VER == 4 on LP64.
        assertEquals(784, KernelSuAllowlist.APP_PROFILE_SIZE)
        assertEquals(
            KernelSuAllowlist.APP_PROFILE_SIZE,
            KernelSuAllowlist.appProfile(packageName, uid).size,
        )
        // KernelSU writes one grant as 8 (header) + 784 (record) = 792 bytes;
        // a file it can load must be exactly that shape.
        assertEquals(792, KernelSuAllowlist.newFile(packageName, uid).size)
    }

    @Test
    fun `record places the uid grant at the uapi offsets`() {
        val record = KernelSuAllowlist.appProfile(packageName, uid)
        assertEquals(4, intAt(record, 0)) // version
        assertEquals(uid, intAt(record, 260)) // curr_uid
        assertEquals(1, record[264].toInt()) // allow_su
        assertEquals(1, record[KernelSuAllowlist.RP_CONFIG_OFFSET].toInt()) // use_default
        // The key is the NUL-padded package name.
        assertEquals(packageName, String(record, 4, packageName.length, Charsets.UTF_8))
        assertEquals(0, record[4 + packageName.length].toInt())
        // profile_valid rejects an allow_su record whose selinux_domain is
        // empty, so the grant must carry the KSU default domain.
        assertEquals(
            KernelSuAllowlist.DEFAULT_SELINUX_DOMAIN,
            String(record, KernelSuAllowlist.SELINUX_DOMAIN_OFFSET, 10, Charsets.UTF_8),
        )
        assertEquals(0, record[KernelSuAllowlist.SELINUX_DOMAIN_OFFSET + 10].toInt())
    }

    @Test
    fun `appending a grant keeps the header and record boundaries intact`() {
        // preSeedAllowlist appends when the file already exists, so the header
        // must survive and each record must stay whole.
        val first = KernelSuAllowlist.newFile(packageName, uid)
        val merged = first + KernelSuAllowlist.appProfile("com.example.other", 10500)
        assertArrayEquals(KernelSuAllowlist.FILE_HEADER, merged.copyOfRange(0, 8))
        assertEquals(0, (merged.size - 8) % KernelSuAllowlist.APP_PROFILE_SIZE)
        assertEquals(
            10500,
            intAt(merged, 8 + KernelSuAllowlist.APP_PROFILE_SIZE + 260),
        )
    }
}
