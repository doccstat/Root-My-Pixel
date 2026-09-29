#!/system/bin/sh
# Root-My-Pixel on-disk stage compatibility layer.
#
# KernelSU re-runs the module stage scripts on every userspace start: a plain
# boot, `ksud post-fs-data` + `ksud services`, and especially the emulated soft
# reboot (`ksud soft-reboot` -> on_post_data_fs -> on_services). A repair that
# only happens while this app is in the foreground is therefore missing exactly
# when it is needed, so the durable ones are written to /data/adb here and are
# re-executed by the stages themselves.
#
# Every step is idempotent and keeps a one-time `.rmp-orig` backup of anything
# it edits. `rm -rf /data/adb` (the Unroot path) removes all of it.
#
# Emits RMP_COMPAT_OK:<step>, RMP_COMPAT_SKIP:<step> or RMP_COMPAT_FAIL:<step>.

ZYGISK_MOD=/data/adb/modules/zygisksu
VECTOR_MOD=/data/adb/modules/zygisk_vector

echo "RMP_COMPAT_BEGIN"

guarded() {
    grep -q "$1" "$2" 2>/dev/null
}

backup_once() {
    [ -f "$1.rmp-orig" ] || cp -f "$1" "$1.rmp-orig"
}

# --- /dev/ashmem<boot_id> is missing after a userspace restart --------------
#
# libcutils does not open /dev/ashmem. It opens "/dev/ashmem" + the boot_id that
# /proc/sys/kernel/random/boot_id reports, so a reboot invalidates stale ashmem
# handles; the kernel creates that node once. As soon as a userspace restart
# rotates the boot_id the node no longer matches, ashmem_create_region() fails
# with ENOENT and every ashmem user breaks: libhwui's Bitmap.asShared derefs the
# null region and the app dies with SIGSEGV fault 0x88 in Bitmap_copyAshmem
# (Oura's shortcut registration is the reproducible case), while CursorWindow
# silently falls back to the heap. Recreate the node from /dev/ashmem's own
# major/minor. Idempotent, and a no-op on a cold boot.
repair_ashmem() {
    boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
    case "$boot_id" in
        ""|*[!0-9a-fA-F-]*)
            echo "RMP_COMPAT_FAIL:ashmem-boot-id"
            return 0
            ;;
    esac
    node="/dev/ashmem$boot_id"
    if [ -c "$node" ]; then
        echo "RMP_COMPAT_OK:ashmem-node"
        return 0
    fi
    # Read the device numbers off /dev/ashmem instead of assuming minor 259.
    set -- $(ls -l /dev/ashmem 2>/dev/null | awk '{ gsub(/,/, "", $5); print $5, $6 }')
    if [ -z "$1" ] || [ -z "$2" ]; then
        set -- 10 259
    fi
    mknod "$node" c "$1" "$2" 2>/dev/null
    chmod 666 "$node" 2>/dev/null
    chcon u:object_r:ashmem_libcutils_device:s0 "$node" 2>/dev/null
    if [ -c "$node" ]; then
        echo "RMP_COMPAT_OK:ashmem-node-created"
    else
        echo "RMP_COMPAT_FAIL:ashmem-node"
    fi
}

# --- NeoZygisk post-fs-data is not idempotent -------------------------------
#
# Its `rm -rf $TMP_PATH` unlinks the *live* daemon's `cp64.sock` while the
# daemon still holds the inode, so every connect() from the loader in zygote
# fails with ENOENT and no Zygisk module ever loads. It also starts a second
# `zygisk-ptrace64 monitor` on top of the running one. Both only matter on a
# re-run, which is now the normal path (install the module, then soft reboot).
patch_zygisk() {
    script="$ZYGISK_MOD/post-fs-data.sh"
    if [ ! -f "$script" ]; then
        echo "RMP_COMPAT_SKIP:zygisksu-absent"
        return 0
    fi
    if guarded RMP_ZYGISK_GUARD "$script"; then
        echo "RMP_COMPAT_OK:zygisksu-already"
        return 0
    fi
    backup_once "$script"
    cat > "$ZYGISK_MOD/rmp-zygisk-guard.sh" <<'GUARD'
# Root-My-Pixel: leave an already-running Zygisk layer alone.
for _rmp_p in /proc/[0-9]*/comm; do
    read -r _rmp_c < "$_rmp_p" 2>/dev/null || continue
    case "$_rmp_c" in
        zygisk-ptrace64|zygisk-ptrace32) exit 0 ;;
    esac
done
unset _rmp_p _rmp_c
GUARD
    chmod 644 "$ZYGISK_MOD/rmp-zygisk-guard.sh"
    awk -v guard='. "$MODDIR/rmp-zygisk-guard.sh"' '
        !done && index($0, "if [ -d $TMP_PATH ]; then") == 1 {
            print "# RMP_ZYGISK_GUARD"
            print guard
            done = 1
        }
        { print }
    ' "$script.rmp-orig" > "$script.rmp-tmp"
    if [ -s "$script.rmp-tmp" ] && guarded RMP_ZYGISK_GUARD "$script.rmp-tmp"; then
        mv -f "$script.rmp-tmp" "$script"
        chmod 755 "$script"
        echo "RMP_COMPAT_OK:zygisksu-guarded"
    else
        rm -f "$script.rmp-tmp"
        echo "RMP_COMPAT_FAIL:zygisksu-pattern"
    fi
}

# --- Vector's service.sh cannot unshare on this device ----------------------
#
# `/system/bin/unshare` is toybox and rejects `--propagation`, so `vectord`
# never starts; KernelSU's own busybox supports it. `unshare` is shadowed with
# a shell function so a Vector update is re-patched on the next root without
# rewriting the rest of the file.
patch_vector() {
    script="$VECTOR_MOD/service.sh"
    if [ ! -f "$script" ]; then
        echo "RMP_COMPAT_SKIP:vector-absent"
        return 0
    fi
    if guarded RMP_VECTOR_UNSHARE "$script"; then
        echo "RMP_COMPAT_OK:vector-already"
        return 0
    fi
    backup_once "$script"
    cat > "$VECTOR_MOD/.rmp-unshare-prefix" <<'PREFIX'
# RMP_VECTOR_UNSHARE: toybox unshare rejects --propagation; use KernelSU busybox.
unshare() {
    if [ -x /data/adb/ksu/bin/busybox ]; then
        /data/adb/ksu/bin/busybox unshare "$@"
    else
        command unshare "$@"
    fi
}
PREFIX
    head -n 1 "$script.rmp-orig" > "$script.rmp-tmp"
    cat "$VECTOR_MOD/.rmp-unshare-prefix" >> "$script.rmp-tmp"
    tail -n +2 "$script.rmp-orig" >> "$script.rmp-tmp"
    rm -f "$VECTOR_MOD/.rmp-unshare-prefix"
    if [ -s "$script.rmp-tmp" ] && guarded RMP_VECTOR_UNSHARE "$script.rmp-tmp"; then
        mv -f "$script.rmp-tmp" "$script"
        chmod 755 "$script"
        echo "RMP_COMPAT_OK:vector-unshare"
    else
        rm -f "$script.rmp-tmp"
        echo "RMP_COMPAT_FAIL:vector-write"
    fi
}

# --- Vector's manager.apk must stay readable by the shell host --------------
#
# KernelSU leaves /data/adb/modules labelled `adb_data_file`, but Vector's
# parasitic manager is injected into com.android.shell (u:r:shell:s0) and the
# binder hand-off of manager.apk is judged against the *receiver*. shell may not
# read adb_data_file, so the kernel drops the transfer, the host parses a null
# PackageInfo and the manager dies with a NullPointerException
# ("Parasitic injection failed") - the "Vector manager does not open" symptom.
# Upstream's Magisk installer leaves the module tree `system_file`, which every
# appdomain and coredomain may read; KernelSU does not, and a module reinstall
# or a `/data/adb` restore resets it. Re-assert the label here, from the root
# stage, because the daemon runs as system and cannot.
#
# `bin/` keeps Vector's own `xposed_file` label (its dex2oat wrappers).
relabel_vector() {
    if [ ! -d "$VECTOR_MOD" ]; then
        echo "RMP_COMPAT_SKIP:vector-relabel-absent"
        return 0
    fi
    chcon -R u:object_r:system_file:s0 "$VECTOR_MOD" 2>/dev/null
    if [ -d "$VECTOR_MOD/bin" ]; then
        chcon -R u:object_r:xposed_file:s0 "$VECTOR_MOD/bin" 2>/dev/null
    fi
    echo "RMP_COMPAT_OK:vector-relabel"
}

repair_ashmem
patch_zygisk
patch_vector
relabel_vector
echo "RMP_COMPAT_END"
