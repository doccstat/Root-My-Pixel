#!/system/bin/sh
# Unroot and restore stock state for Root-My-Pixel.
# Structured UNROOT_* markers are consumed by the Android UI.

LOG_FILE="/data/local/tmp/unr00t.log"
echo "=== Unroot started at $(date) ===" > "$LOG_FILE" 2>/dev/null || true

log() {
    echo "[$(date +%T)] $*" | tee -a "$LOG_FILE" 2>/dev/null || echo "[$(date +%T)] $*"
}

exec_root() {
    local cmd="$1"
    local out
    local status

    if [ "$(id -u)" = "0" ]; then
        /system/bin/sh -c "$cmd"
        return $?
    fi

    if command -v su >/dev/null 2>&1; then
        out=$(su -c "$cmd" 2>&1)
        status=$?
        if [ $status -eq 0 ]; then
            [ -n "$out" ] && echo "$out"
            return 0
        fi
    fi

    if [ -x /data/local/tmp/su ] && [ -S /data/local/tmp/temp_su.sock ]; then
        out=$(/data/local/tmp/su -c "$cmd" 2>&1)
        status=$?
        if [ $status -eq 0 ]; then
            [ -n "$out" ] && echo "$out"
            return 0
        fi
    fi

    echo "UNROOT_TRANSPORT_UNAVAILABLE"
    return 1
}

# This command returns zero after it starts as root. Cleanup failures use
# markers, preventing exec_root from repeating destructive work with another
# root provider merely because one cleanup step failed.
ROOT_UNROOT_COMMAND='
failed=0
cleanup_step() {
    name="$1"
    shift
    "$@"
    status=$?
    if [ $status -eq 0 ]; then
        echo "UNROOT_OK:$name"
    else
        echo "UNROOT_FAIL:$name:$status"
        failed=1
    fi
}

echo "UNROOT_IDENTITY:uid=$(id -u):context=$(id -Z 2>/dev/null || true)"
cleanup_step data-adb /system/bin/sh -c '\''rm -rf /data/adb && [ ! -e /data/adb ]'\''
cleanup_step apex-mount /system/bin/sh -c '\''grep -q " /apex/com.android.virt/bin " /proc/mounts 2>/dev/null || exit 0; umount /apex/com.android.virt/bin'\''
cleanup_step selinux /system/bin/sh -c '\''[ "$(getenforce 2>/dev/null)" = "Enforcing" ] || { setenforce 1 && [ "$(getenforce 2>/dev/null)" = "Enforcing" ]; }'\''
cleanup_step cve-app rm -f /data/local/tmp/cve-2026-43499-app.so
cleanup_step cve-root rm -f /data/local/tmp/cve-2026-43499-root
cleanup_step ksud rm -f /data/local/tmp/ksud-pixel
cleanup_step exploit-logs rm -f /data/local/tmp/exploit.log /data/local/tmp/su_daemon.log

if [ "$failed" -ne 0 ]; then
    echo "UNROOT_CLEANUP_PARTIAL"
    exit 0
fi

# Preserve the CVE transport whenever an earlier step fails, so the user can
# cancel and retry. On success this root shell survives deletion long enough
# to submit the reboot request atomically.
cleanup_step root-transport-files rm -f /data/local/tmp/.su.new.* /data/local/tmp/temp_su.sock /data/local/tmp/su
if [ "$failed" -ne 0 ]; then
    echo "UNROOT_CLEANUP_PARTIAL"
    exit 0
fi

sync
echo "UNROOT_CLEANUP_OK"
if svc power reboot || reboot; then
    echo "UNROOT_REBOOT_REQUESTED"
else
    status=$?
    echo "UNROOT_FAIL:reboot:$status"
fi
exit 0
'

log "Cleaning privileged root state and temporary files..."
if exec_root "$ROOT_UNROOT_COMMAND"; then
    log "Unroot command completed"
else
    log "Root execution unavailable; cleanup paused"
fi
