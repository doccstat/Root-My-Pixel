#!/system/bin/sh
# Root-My-Pixel selected-app backup/restore engine. Must run as root.
#
# Why this exists: the point of temporary root is that the clean state must be
# absolutely clean, with no root/Xposed-adjacent apps installed. Rather than
# re-download everything afterwards, the app records a plan of chosen packages,
# this script archives each one (APKs + /data/data + device-encrypted + external
# data) and the plan is replayed after the next re-root.
#
# Usage:
#   RMP_BACKUP_ROOT=<dir> app_backup.sh backup  <pkg>...
#   RMP_BACKUP_ROOT=<dir> app_backup.sh restore <pkg>...
#   RMP_BACKUP_ROOT=<dir> app_backup.sh status  <pkg>...
#   RMP_BACKUP_ROOT=<dir> RMP_EXTRA_PATHS=<paths> app_backup.sh extra-backup
#   RMP_BACKUP_ROOT=<dir> app_backup.sh extra-restore
#
# Output is line-oriented so the app can parse it:
#   RMP_BK_OK:<pkg> | RMP_BK_FAIL:<pkg>:<reason> | RMP_BK_DONE:ok=<n>:fail=<m>
#   RMP_RS_OK:<pkg> | RMP_RS_FAIL:<pkg>:<reason> | RMP_RS_DONE:ok=<n>:fail=<m>
#   RMP_BK_STATE:<pkg>:<present|missing>
#   RMP_XB_OK:extra | RMP_XB_FAIL:extra:<reason> | RMP_XB_DONE:ok=<n>:fail=<m>
#   RMP_XR_OK:extra | RMP_XR_FAIL:extra:<reason> | RMP_XR_DONE:ok=<n>:fail=<m>
#
# RMP_EXTRA_PATHS is a newline- or colon-separated list of absolute directories
# the user wants archived and removed along with the selected apps. Keep one
# path per line; paths containing whitespace are not supported.
set -u

MODE="${1:-}"
[ -n "$MODE" ] && shift
BACKUP_ROOT="${RMP_BACKUP_ROOT:-/data/local/tmp/rmp-backup}"

dir_for() { echo "$BACKUP_ROOT/$1"; }

# Directories that make up a package's on-device state.
pkg_dirs() {
    for d in "/data/data/$1" "/data/user_de/0/$1" \
             "/data/media/0/Android/data/$1" "/data/media/0/Android/obb/$1"; do
        [ -e "$d" ] && echo "$d"
    done
}

backup_one() {
    pkg="$1"
    dir="$(dir_for "$pkg")"
    rm -rf "$dir" 2>/dev/null
    mkdir -p "$dir/apk" 2>/dev/null || { echo "RMP_BK_FAIL:$pkg:mkdir"; return 1; }

    paths="$(pm path "$pkg" 2>/dev/null | sed 's/^package://')"
    if [ -z "$paths" ]; then
        echo "RMP_BK_FAIL:$pkg:not-installed"
        rm -rf "$dir"
        return 1
    fi
    n=0
    for p in $paths; do
        n=$((n + 1))
        cp -f "$p" "$dir/apk/$n.apk" 2>/dev/null || {
            echo "RMP_BK_FAIL:$pkg:apk-copy"
            return 1
        }
    done

    rel=""
    for d in $(pkg_dirs "$pkg"); do
        rel="$rel ${d#/}"
    done
    if [ -n "$rel" ]; then
        # -C / keeps absolute layout so restore can untar straight into /.
        tar -czf "$dir/data.tgz" -C / $rel 2>/dev/null || {
            echo "RMP_BK_FAIL:$pkg:tar"
            return 1
        }
    fi

    uid="$(stat -c %u "/data/data/$pkg" 2>/dev/null || echo 0)"
    {
        echo "pkg=$pkg"
        echo "uid=$uid"
        echo "apks=$n"
        echo "stamp=$(date +%s)"
    } > "$dir/meta.txt" 2>/dev/null

    echo "RMP_BK_OK:$pkg"
    return 0
}

restore_one() {
    pkg="$1"
    dir="$(dir_for "$pkg")"
    if [ ! -d "$dir" ]; then
        echo "RMP_RS_FAIL:$pkg:no-backup"
        return 1
    fi

    if ls "$dir/apk"/*.apk >/dev/null 2>&1; then
        # Split APKs install in one session; pm sorts base/feature APKs itself.
        pm install -r -d -t --user 0 $(ls "$dir/apk"/*.apk) >/dev/null 2>&1 || {
            echo "RMP_RS_FAIL:$pkg:install"
            return 1
        }
    fi

    if [ -f "$dir/data.tgz" ]; then
        tar -xzf "$dir/data.tgz" -C / 2>/dev/null || {
            echo "RMP_RS_FAIL:$pkg:untar"
            return 1
        }
    fi

    # The uid assigned on (re)install can differ from the archived owner, and
    # metadata must match the new install for the app sandbox to accept it.
    uid="$(stat -c %u "/data/data/$pkg" 2>/dev/null || echo "")"
    if [ -n "$uid" ]; then
        for d in $(pkg_dirs "$pkg"); do
            chown -R "$uid:$uid" "$d" 2>/dev/null
            restorecon -R "$d" >/dev/null 2>&1
        done
    fi

    echo "RMP_RS_OK:$pkg"
    return 0
}

# Absolute directories from RMP_EXTRA_PATHS, one per line, blanks and
# relative entries dropped.
extra_paths() {
    printf '%s\n' "${RMP_EXTRA_PATHS:-}" \
        | tr ':\t' '\n\n' \
        | sed 's/^ *//; s/ *$//' \
        | while IFS= read -r p; do
              [ -n "$p" ] || continue
              case "$p" in
                  /?*) printf '%s\n' "$p" ;;
              esac
          done
}

# Some paths are not packages: arbitrary directories the user named. Archive
# them once into a shared _extra bucket, then remove the originals so the clean
# state does not keep them. A failed tar leaves the originals untouched.
extra_backup() {
    dir="$BACKUP_ROOT/_extra"
    rm -rf "$dir" 2>/dev/null
    mkdir -p "$dir" 2>/dev/null || { echo "RMP_XB_FAIL:extra:mkdir"; return 1; }

    rel=""
    for p in $(extra_paths); do
        rel="$rel ${p#/}"
    done
    if [ -z "$rel" ]; then
        rm -rf "$dir" 2>/dev/null
        echo "RMP_XB_OK:extra"
        return 0
    fi

    if ! tar -czf "$dir/data.tgz" -C / $rel 2>/dev/null; then
        echo "RMP_XB_FAIL:extra:tar"
        rm -rf "$dir" 2>/dev/null
        return 1
    fi

    : > "$dir/meta.txt"
    for p in $(extra_paths); do
        printf '%s %s\n' "${p#/}" "$(stat -c '%u:%g %a' "$p" 2>/dev/null || echo '0:0 644')" \
            >> "$dir/meta.txt"
    done

    for p in $(extra_paths); do
        rm -rf "$p" 2>/dev/null
    done

    echo "RMP_XB_OK:extra"
    return 0
}

extra_restore() {
    dir="$BACKUP_ROOT/_extra"
    if [ ! -f "$dir/data.tgz" ]; then
        echo "RMP_XR_FAIL:extra:no-backup"
        return 1
    fi
    if ! tar -xzf "$dir/data.tgz" -C / 2>/dev/null; then
        echo "RMP_XR_FAIL:extra:untar"
        return 1
    fi
    if [ -f "$dir/meta.txt" ]; then
        while read -r rel owner mode; do
            [ -n "$rel" ] || continue
            [ -e "/$rel" ] || continue
            chown "$owner" "/$rel" 2>/dev/null
            chmod "$mode" "/$rel" 2>/dev/null
            restorecon -R "/$rel" >/dev/null 2>&1
        done < "$dir/meta.txt"
    fi
    echo "RMP_XR_OK:extra"
    return 0
}

# Extra-path modes take no package arguments and own their own terminators.
case "$MODE" in
    extra-backup)
        if extra_backup; then echo "RMP_XB_DONE:ok=1:fail=0"; else echo "RMP_XB_DONE:ok=0:fail=1"; fi
        exit 0
        ;;
    extra-restore)
        if extra_restore; then echo "RMP_XR_DONE:ok=1:fail=0"; else echo "RMP_XR_DONE:ok=0:fail=1"; fi
        exit 0
        ;;
esac

ok=0
fail=0
for pkg in "$@"; do
    case "$MODE" in
        backup)
            if backup_one "$pkg"; then ok=$((ok + 1)); else fail=$((fail + 1)); fi
            ;;
        restore)
            if restore_one "$pkg"; then ok=$((ok + 1)); else fail=$((fail + 1)); fi
            ;;
        status)
            if [ -d "$(dir_for "$pkg")" ]; then
                echo "RMP_BK_STATE:$pkg:present"
            else
                echo "RMP_BK_STATE:$pkg:missing"
            fi
            ;;
        *)
            echo "RMP_BK_FAIL:$pkg:bad-mode"
            fail=$((fail + 1))
            ;;
    esac
done

case "$MODE" in
    backup) echo "RMP_BK_DONE:ok=$ok:fail=$fail" ;;
    restore) echo "RMP_RS_DONE:ok=$ok:fail=$fail" ;;
esac
