package com.lixingchi.ghostlock.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serialises KernelSU's on-disk superuser allowlist.
 *
 * The driver reads `/data/adb/ksu/.allowlist` while the module initialises
 * (`ksu_load_allow_list`, reachable from `ksud insmod`), then resolves every
 * `su` caller against it. A root-on-a-late-loaded-LKM flow therefore has to get
 * its own UID into this file *before* the driver is loaded: as soon as it is,
 * the driver forces SELinux back to enforcing and the exploit transport's
 * socket stops working, leaving `su_compat` as the only remaining channel.
 *
 * The file is `FILE_MAGIC`, `FILE_FORMAT_VERSION`, then a sequence of
 * `struct app_profile` records, all little-endian (see `uapi/app_profile.h`).
 * Layout of a v4 record on LP64:
 *
 * ```
 *   0   u32   version
 *   4   char  key[256]
 *   260 i32   curr_uid
 *   264 u8    allow_su
 *   272 ...   rp_config union (use_default @272, template_name @273, profile @536)
 * ```
 */
object KernelSuAllowlist {

    /** Where the driver expects the allowlist. */
    const val PATH = "/data/adb/ksu/.allowlist"

    /** `FILE_MAGIC` `' KSU'` from `kernel/policy/allowlist.c`. */
    const val FILE_MAGIC = 0x7f4b5355

    /** `FILE_FORMAT_VERSION` / `KSU_APP_PROFILE_VER`. */
    const val FILE_FORMAT_VERSION = 4

    /** `sizeof(struct app_profile)` for [FILE_FORMAT_VERSION]. */
    const val APP_PROFILE_SIZE = 784

    /** `KSU_MAX_PACKAGE_NAME`; the key is NUL-padded to this length. */
    const val MAX_PACKAGE_NAME = 256

    /** Offset of the `rp_config` union inside a record. */
    const val RP_CONFIG_OFFSET = 272

    /**
     * Offset of `rp_config.profile.selinux_domain` inside a record.
     *
     * `root_profile` starts at 536 (after `use_default` + `template_name`,
     * 8-byte aligned); within it `selinux_domain` follows uid/gid/groups/
     * capabilities, i.e. at 168. 536 + 168 = 704.
     */
    const val SELINUX_DOMAIN_OFFSET = 704

    /**
     * `KSU_DEFAULT_SELINUX_DOMAIN` (`"u:r:" KERNEL_SU_DOMAIN ":s0"`).
     *
     * The driver's `profile_valid` rejects an `allow_su` record whose domain is
     * empty, so every grant must carry a domain even though
     * [appProfile] sets `use_default` and lets the driver use its built-in root
     * profile.
     */
    const val DEFAULT_SELINUX_DOMAIN = "u:r:ksu:s0"

    /** `FILE_MAGIC` followed by `FILE_FORMAT_VERSION`, little-endian. */
    val FILE_HEADER: ByteArray = ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(FILE_MAGIC)
        .putInt(FILE_FORMAT_VERSION)
        .array()

    /**
     * One v4 `app_profile` granting [uid] su, with `rp_config.use_default` set
     * so the driver hands back its built-in root profile (uid 0, full
     * capabilities, `u:r:ksu:s0`). [packageName] is stored as the informational
     * key; the driver matches on the UID.
     *
     * [DEFAULT_SELINUX_DOMAIN] must be present: `ksu_set_app_profile` ->
     * `profile_valid` rejects an `allow_su` record with an empty domain, so a
     * zeroed record is silently dropped and the grant never takes effect.
     */
    fun appProfile(packageName: String, uid: Int): ByteArray {
        val record = ByteBuffer.allocate(APP_PROFILE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        record.putInt(FILE_FORMAT_VERSION)

        val key = ByteArray(MAX_PACKAGE_NAME)
        val name = packageName.toByteArray(Charsets.UTF_8)
        System.arraycopy(name, 0, key, 0, minOf(name.size, MAX_PACKAGE_NAME - 1))
        record.put(key)

        record.putInt(uid)
        record.put(1.toByte()) // allow_su
        record.position(RP_CONFIG_OFFSET)
        record.put(1.toByte()) // rp_config.use_default
        val domain = DEFAULT_SELINUX_DOMAIN.toByteArray(Charsets.UTF_8)
        record.position(SELINUX_DOMAIN_OFFSET)
        record.put(domain)
        return record.array()
    }

    /** A brand-new allowlist containing a single grant for [uid]. */
    fun newFile(packageName: String, uid: Int): ByteArray =
        FILE_HEADER + appProfile(packageName, uid)
}
