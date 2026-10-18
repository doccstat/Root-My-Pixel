# App assets

## Device payloads

`profiles.json` maps supported firmware to `exploits/*.so` and its kernel KMI.
Yogi `CD1A.260618.001.C3` uses `android16-6.12`; Android 17 userspace does not
change that KMI. The upstream exploit payloads and profile feed are unchanged.

## Matched KernelSU bundle

- `manager/GhostLock-manager.apk`: visible **KernelSU**, package
  `com.lixingchi.kernelsu`. The legacy filename is a stable download contract.
- `ksud/ksud`: extracted from that APK's `lib/arm64-v8a/libksud.so`, including
  its matching KernelSU LKMs. RMP extracts the selected KMI's `.ko` and uses
  `ksud insmod ... allow_shell=1`; it does not patch a boot partition.

The source integration is upstream
`96770dfb807fea61e06819e4cbb438384df54b63` / UAPI **6**, with the fork's signer,
package and framework-only restart preserved. Driver `KSU_PACKAGE_NAME` is
`com.lixingchi.kernelsu`, `KSU_EXPECTED_SIZE=0x034b`, and `KSU_EXPECTED_HASH` is
`ff8c6f43e0bdd88057103c9faa5d78d7f43ee80a3c1ffa3e20eed06dcb640050` (DER size
`0x34b`). The signing key is never bundled in source.

The committed binaries are historical: official v3.3.0 `ksud` SHA-256
`8614de6cdc2233c71fd0d1c64381ea10fbe6658651bae9b5a8dab4fe08e6344b` and the older
manager SHA-256
`9b53179e06f3d2a9a0a500cd55a597895e43f345b9e83b6067bea982750c89ae`.
They are **not the updated pair**. CI must refresh them before building, and
fails rather than use either old binary when publication is missing or invalid.

For a local build, after the fork's signed Build Manager workflow publishes:

```sh
cache="${XDG_CACHE_HOME:-$HOME/.cache}/root-my-pixel"
mkdir -p "$cache"
base=https://github.com/doccstat/KernelSU/releases/download/ghostlock-latest
curl -fL "$base/GhostLock-manager.apk" -o "$cache/GhostLock-manager.apk" &&
curl -fL "$base/manager.json" -o "$cache/manager.json" &&
python3 tools/update_kernel_bundle.py \
  --apk "$cache/GhostLock-manager.apk" --metadata "$cache/manager.json" \
  --apksigner "$ANDROID_HOME/build-tools/37.0.0/apksigner" \
  --aapt2 "$ANDROID_HOME/build-tools/37.0.0/aapt2"
```

Run from the project root. This explicitly replaces both bundled assets only
after checking metadata, checksum/size, actual APK package/version, signing
certificate and the embedded daemon's architecture. No downloaded executable is
run. A publication race fails the check; retry once publication has finished.
The separately published `ksud` asset is not used by this workflow.

`BundledManager` reads the APK version rather than a hardcoded version code.
Manager repair checks the loaded driver's version and UAPI before installation,
uses user 0, and never uninstalls a differently signed manager automatically.
Updating a loaded temporary driver requires a full reboot and a fresh RMP root,
not a soft reboot or a live unload.

## Adding a target

1. Add the exact firmware profile to `profiles.json`.
2. Build its exploit payload from `Root-My-Pixel-Payloads/` with the Android NDK.
3. Copy the payload to the profile's `exploits/<profileId>.so` path.
4. Select the kernel KMI and ensure the matched KernelSU bundle embeds it.
