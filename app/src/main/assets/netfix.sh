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
#
# The `node` denial is the dangerous one: netd's libnetd_updatable_init opens a
# loopback socket, gets the sendto denial, reports
# "libnetd_updatable_init: Failed: connect: Connection refused" and aborts.
# init has an `onrestart` rule that SIGKILLs zygote whenever netd restarts, so a
# netd crash-loop turns into a zygote/system_server boot loop, and the device
# only recovers with a full reboot.
#
# These are live, in-memory policy patches: they disappear on the next reboot and
# touch no file, so stock behaviour is restored automatically. Apply every rule
# before using the network; a single missing class is enough to break DNS or
# crash netd.
#
# Emits RMP_NETFIX_OK, or RMP_NETFIX_FAIL:<rule> for the first that failed.
ksud_bin=""
for candidate in /data/adb/ksud /data/adb/ksu/bin/ksud ksud; do
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
    'allow unlabeled node node recvfrom' ; do
    if ! "$ksud_bin" sepolicy patch "$rule" >/dev/null 2>&1; then
        echo "RMP_NETFIX_FAIL:$rule"
        exit 1
    fi
done

echo "RMP_NETFIX_OK"
