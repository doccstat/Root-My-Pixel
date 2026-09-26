#!/system/bin/sh
# Restore packet traffic after temporary root.
#
# The late-load root flow leaves SELinux SECMARK checks active while no SECMARK
# rules exist, so every unlabelled packet is denied with
#   avc: denied { send/recv } ... tcontext=u:object_r:unlabeled:s0 tclass=packet
# DNS, IPv6 neighbour discovery and app sockets then fail (the visible symptom
# is "internet does not work after rooting"). Allowing the unlabelled packet
# class back for every domain restores stock behaviour: it is a live, in-memory
# policy patch, so it disappears with the next reboot and touches no file.
#
# Emits RMP_NETFIX_OK on success.
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

for rule in 'allow domain unlabeled packet send' 'allow domain unlabeled packet recv'; do
    if ! "$ksud_bin" sepolicy patch "$rule" >/dev/null 2>&1; then
        echo "RMP_NETFIX_FAIL:rule"
        exit 1
    fi
done

echo "RMP_NETFIX_OK"
