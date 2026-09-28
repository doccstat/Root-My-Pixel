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

### manager/GhostLock-manager.apk
The GhostLock manager (`com.lixingchi.kernelsu`), built from our KernelSU fork
(`depot/third-party/github/doccstat/KernelSU`) for the same release as
`ksud/ksud`. The manager must match the late-loaded `kernelsu.ko`: the driver
pins the manager's package name *and* its signing certificate, and the manager
and `ksud` speak the same versioned UAPI, so a separately installed (possibly
older or fork) manager can diverge.

The file name is **stable and version-less on purpose**: `build-debug-apk.yml`
replaces this asset in place with whatever the fork last published to its
`ghostlock-latest` release (`GhostLock-manager.apk` + `manager.json`), and
`BundledManager` reads the expected `versionCode` from the APK itself via
`PackageManager.getPackageArchiveInfo` rather than a hardcoded constant. So a
CI-refreshed manager needs no source change in this repo.

sha256 `9b53179e06f3d2a9a0a500cd55a597895e43f345b9e83b6067bea982750c89ae`.
Signer certificate SHA-256 is
`ff8c6f43e0bdd88057103c9faa5d78d7f43ee80a3c1ffa3e20eed06dcb640050` (DER size
`0x34b`), and the LKM is rebuilt for KMI `android16-6.12` with
`KSU_MANAGER_PACKAGE=com.lixingchi.kernelsu`, `KSU_EXPECTED_SIZE=0x034b` and
`KSU_EXPECTED_HASH` set to that same hash - the fork's `kernel/Kbuild` defaults -
so the driver trusts this manager with no runtime registration.

The manager is built with the GhostLock keystore (kept outside Depot at
`/home/doccstat/data/local/ksu-manager/`); the LKM and a `ksud` embedding it come
from the fork's `ghostlock-lkm.yml` GitHub workflow. Drop the built `ksud` into
`ksud/ksud`.

Bump `ksud/ksud` and this APK together; `InstallViewModel` installs/repairs the
manager from this asset after the LKM is loaded.

## Adding a new target

1. Add the target profile to `profiles.json`
2. Build the exploit .so for that target via the payloads/ Makefile
3. Copy the .so to `exploits/<profileId>.so`
4. Set the profile's `kmi` to the target's kernel KMI, so late-load picks the
   right `kernelsu.ko` out of `ksud/ksud`
