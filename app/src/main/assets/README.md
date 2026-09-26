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
The ReSukiSU late-load binary, downloaded from official ReSukiSU releases.

One binary covers every KMI. It embeds a `kernelsu.ko` per KMI (`android12-5.10`
through `android16-6.12`) and selects between them from the `--kmi` it is passed
at late-load, which is what a profile's `kmi` field supplies. This used to be
shipped as one file per KMI; those copies were byte-identical, so they were
merged into this one.

## Adding a new target

1. Add the target profile to `profiles.json`
2. Build the exploit .so for that target via the payloads/ Makefile
3. Copy the .so to `exploits/<profileId>.so`
4. Set the profile's `kmi` to the target's kernel KMI, so late-load picks the
   right `kernelsu.ko` out of `ksud/ksud`
