#!/system/bin/sh
# Unroot and restore stock state for Root-My-Pixel.
# Structured UNROOT_* markers are consumed by the Android UI.
#
# Deliberately writes no log file of its own: /data/local/tmp is world-shared
# and a root-owned file there is a trace that then has to be swept again. The
# app captures this script's stdout and persists it to its own private
# storage (files/unroot.log), which survives the reboot and needs no cleanup.

log() {
    echo "[$(date +%T)] $*"
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

# The app's private tree keeps a copy of the payloads, the staged scripts and
# the install log. None of it is inside /data/adb, so the sweep above misses
# it; remove it here while keeping files/backups and the plan files, which are
# the restore source. An unlinked script that is already running still works.
cleanup_step app-payloads rm -rf /data/data/com.alex193a.rootmypixel/files/payloads
cleanup_step app-scripts rm -rf /data/data/com.alex193a.rootmypixel/files/scripts
cleanup_step app-log rm -f /data/data/com.alex193a.rootmypixel/files/exploit.log
cleanup_step tmp-logs rm -f /data/local/tmp/unr00t.log /data/local/tmp/rt.log*

# A staged virtual-A/B update is applied by the reboot itself: update_engine's
# CleanupPreviousUpdateAction calls snapshot->InitiateMerge() and the device
# boots the new build even though nothing was ever "installed". Cancel the state
# first, and let a failure block the reboot rather than risk applying it.
ota_staged=0
[ -n "$(ls -A /data/ota_package 2>/dev/null)" ] && ota_staged=1
[ -n "$(ls -A /metadata/ota/snapshots 2>/dev/null)" ] && ota_staged=1
echo "UNROOT_OTA_STAGED:$ota_staged"

cleanup_step ota-stop-engine /system/bin/sh -c '\''stop update_engine'\''
cleanup_step ota-payload /system/bin/sh -c '\''rm -rf /data/ota_package/*'\''
cleanup_step ota-prefs /system/bin/sh -c '\''rm -rf /data/misc/update_engine/prefs/* /data/misc/update_engine/tmp/*'\''
cleanup_step ota-metadata /system/bin/sh -c '\''rm -rf /metadata/ota/*'\''
cleanup_step ota-sync /system/bin/sh -c '\''sync'\''
cleanup_step ota-start-engine /system/bin/sh -c '\''start update_engine'\''

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

# The KernelSU manager is itself a root app, and the clean state must not keep
# one installed. Phase 1 reinstalls the bundled, version-matched copy on the
# next root, so removing it here loses nothing. Best-effort per package: an
# absent or foreign manager must not block the reboot.
for manager in me.weishu.kernelsu com.resukisu.resukisu com.sukisu.ultra; do
    if pm uninstall --user 0 "$manager" >/dev/null 2>&1; then
        echo "UNROOT_MANAGER_REMOVED:$manager"
    fi
done

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
