# App Assets

This directory contains bundled payload files shipped inside the APK.

## Required files

### profiles.json
The device profile feed. Maps each supported Pixel firmware to its asset paths.

### explores/*.so
Pre-compiled exploit payloads (CVE-2026-43499 APP_PAYLOAD variant) for each
target. Built from the payloads/ directory with:
```
make TARGET=frankel-CP2A.260605.012 ANDROID_NDK_HOME=...
```

The output `cve-2026-43499-app.release.so` goes into `app/src/main/assets/exploits/<target>.so`.

### ksud/ksud
The KernelSU late-load binary, downloaded from official KernelSU releases.
Pinned to upstream `tiann/KernelSU` v3.3.0 (`ksud-aarch64-linux-android`,
sha256 `8614de6cdc2233c71fd0d1c64381ea10fbe6658651bae9b5a8dab4fe08e6344b`).

One binary covers every KMI. It embeds a `kernelsu.ko` per KMI (`android12-5.10`
through `android16-6.12`) and selects between them from the `--kmi` it is passed
at late-load, which is what a profile's `kmi` field supplies. This used to be
shipped as one file per KMI; those copies were byte-identical, so they were
merged into this one.

### manager/KernelSU_v3.3.0_32601-release.apk
The official KernelSU manager for the same release as `ksud/ksud`. The manager
must match the late-loaded `kernelsu.ko`: the driver is bound to the manager's
signing certificate, and `ksud` and the manager speak the same versioned UAPI,
so a separately installed (possibly older or fork) manager can diverge.

Pinned to upstream `tiann/KernelSU` v3.3.0 (`KernelSU_v3.3.0_32601-release.apk`,
sha256 `c197060ecb89702e7d54a4c95e29cf5e8d97369bbbb436979ab7fd6bcde7b077`).
Signer certificate SHA-256 is
`c371061b19d8c7d7d6133c6a9bafe198fa944e50c1b31c9d8daa8d7f1fc2d2d6`, byte-for-byte
the hash the upstream LKM embeds as `EXPECTED_HASH`, so the driver trusts it
without runtime registration.

Bump `ksud/ksud` and this APK together; `InstallViewModel` installs/repairs the
manager from this asset after the LKM is loaded.

## Adding a new target

1. Add the target profile to `profiles.json`
2. Build the exploit .so for that target via the payloads/ Makefile
3. Copy the .so to `exploits/<profileId>.so`
4. Set the profile's `kmi` to the target's kernel KMI, so late-load picks the
   right `kernelsu.ko` out of `ksud/ksud`
