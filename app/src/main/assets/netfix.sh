#!/system/bin/sh
# Restore network traffic after temporary root.
#
# Late-loading the KernelSU LKM leaves SELinux's SECMARK/netlabel state
# inconsistent: locally generated packets keep the `unlabeled` security mark and
# interfaces fall back to the generic `netif` type. Three kernel classes are then
# checked on every packet and denied, because the stock policy only grants the
# specific labels the LKM removed:
#
#   packet  send/recv  - DNS/DoT egress and replies  (netd, apps, kernel)
#   netif   ingress/egress - per-interface checks    (netd, system_server, kernel)
#   node    sendto/recvfrom - address-based checks   (netd, imsstack_app)
#   peer    recv       - per-socket peer label check on the receive path
#                        (netd DNS replies, system_server mDNS)
#
# The `node` denial is the dangerous one: netd's libnetd_updatable_init opens a
# loopback socket, the connect is denied and it reports
# "libnetd_updatable_init: Failed: connect: Connection refused".
#
# Measured on yogi (CD1A.260618.001.C3) from the 2026-09-26 boot loop: netd
# itself stays up and starts once. system_server does not fail fast - its main
# thread blocks in SystemServer.startOtherServices ->
# NetworkManagementService.create -> NetdService.get(), which sleeps and retries
# forever. The system_server watchdog then kills and restarts it, bootanimation
# never stops, and the phone only recovers with a reboot. All seven ANRs from
# that loop are this one stack. init's netd `onrestart` is NOT the mechanism
# here: it runs `restart zygote_secondary`, which fails with "service
# zygote_secondary not found" on this build.
#
# The trigger is any zygote soft-restart - the KernelSU manager's restart, or
# installing a Zygisk module - because it re-runs SystemServer bring-up and
# therefore redoes the netd handshake. Apply these rules before any soft restart.
#
# These are live, in-memory policy patches: they disappear on the next reboot and
# touch no file, so stock behaviour is restored automatically. Apply every rule
# before using the network; a single missing class is enough to break DNS or
# crash netd.
#
# The same rules are copied to /data/adb/post-fs-data.d and /data/adb/service.d
# by Root-My-Pixel, because KernelSU re-runs the module stage scripts on every
# emulated soft reboot (`ksud soft-reboot`). Common `.d` scripts run before the
# modules' own scripts in both stages, so the rules are back in place before a
# module `service.sh` can restart system_server and redo the netd handshake.
#
# $KSUD, when set, is tried before the on-disk copies so the script also works
# in the window between `insmod` and `ksud install`.
#
# Emits RMP_NETFIX_OK, or RMP_NETFIX_FAIL:<rule> for the first that failed.
ksud_bin=""
# $KSUD is intentionally unquoted: it is unset when the caller did not stage a
# ksud binary, and an empty word simply drops out of the list. No braces are
# used anywhere in this file (see NetfixScriptTest).
for candidate in $KSUD /data/adb/ksud /data/adb/ksu/bin/ksud ksud; do
    if command -v "$candidate" >/dev/null 2>&1 || [ -x "$candidate" ]; then
        ksud_bin="$candidate"
        break
    fi
done
if [ -z "$ksud_bin" ]; then
    echo "RMP_NETFIX_FAIL:no-ksud"
    exit 1
fi

for rule in \
    'allow domain unlabeled packet send' \
    'allow domain unlabeled packet recv' \
    'allow domain netif netif egress' \
    'allow domain netif netif ingress' \
    'allow unlabeled netif netif ingress' \
    'allow unlabeled netif netif egress' \
    'allow domain node node sendto' \
    'allow domain node node recvfrom' \
    'allow unlabeled node node sendto' \
    'allow unlabeled node node recvfrom' \
    'allow domain unlabeled peer recv' ; do
    if ! "$ksud_bin" sepolicy patch "$rule" >/dev/null 2>&1; then
        echo "RMP_NETFIX_FAIL:$rule"
        exit 1
    fi
done

echo "RMP_NETFIX_OK"
