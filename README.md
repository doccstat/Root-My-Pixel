# Root My Pixel

**Root My Pixel** is an Android application designed to automate root access on **Google Pixel** devices leveraging the **NebuSec IonStack** exploit (CVE-2026-43499) and integrating **KernelSU**.

---

## How the Application Works

Root My Pixel lets you *temporarily* gain root access with KernelSU in just one tap.

### Installation Workflow

1. **Device Detection & Profiling**
   - At startup, the app uses native JNI (`NativeProbe`), `/proc/version` queries, and system properties to detect the device codename, kernel version, CPU ABI, memory page size, and build display ID.
   - Via `ResolveTargetUseCase`, it matches the device details against supported target profiles defined in `assets/profiles.json`.

2. **Shizuku Integration**
   - The app uses **Shizuku** (UID 2000) to acquire ADB shell privileges without needing initial root access, which is required to stage and execute payload binaries in `/data/local/tmp`.
   - A managed `ExploitService` is bound via Binder IPC to stream exploit execution logs to the UI in real time.

3. **Exploit Payload Extraction & Execution**
   - Precompiled binary payloads (`.so`) corresponding to each supported build and the native helper tool (`libcve43499root.so`) are extracted from APK assets to `/data/local/tmp`.
   - The IonStack exploit (CVE-2026-43499) is executed to establish a local root daemon socket (`temp_su.sock`), acquiring full `root` privileges.

4. **KernelSU Integration**
   - Staging of the `ksud` binary matching the device's Kernel Module Interface (KMI, e.g., `android15-6.6`).
   - Extracts the selected embedded `kernelsu.ko` and loads it with `ksud insmod ... allow_shell=1`, then repairs the platform before replaying module stages.
   - Verifies KernelSU through its UAPI, with `ksud debug info` and `/proc/modules` as compatibility fallbacks.
   - Registers the installed KernelSU Manager only after validating its production APK signature.

5. **User Interface & Management Tools**
   - Real-time live log progress monitoring.
   - Handy actions for **Soft Reboot** (restarting `system_server`) and **Log Exporting** for debugging purposes.

---

## Supported Devices & Build Profiles

| Device                | Codename   | Supported Builds   | Kernel KMI      | Tested |
|:----------------------|:-----------|:------------------|:----------------|:--------|
| **Pixel 11**          | `cubs`     | `CD1A.260618.001.C2` | `android16-6.12`| ✅      |
| **Pixel 11 Pro**      | `grizzly`  | `CD1A.260618.001.C2` | `android16-6.12`| ✅      |
| **Pixel 11 Pro XL**   | `kodiak`   | `CD1A.260618.001.C2` | `android16-6.12`| ✅      |
| **Pixel 11 Pro Fold** | `yogi`     | `CD1A.260618.001.C3` | `android16-6.12`| ✅      |
| **Pixel 10**          | `frankel`  | `CP2A.260705.006` | `android15-6.6` | ✅      |
| **Pixel 10 Pro**      | `blazer`   | `CP2A.260705.006` | `android15-6.6` | ✅      |
| **Pixel 10 Pro XL**   | `mustang`  | `CP2A.260705.006`<br>`CP2A.260805.005` | `android15-6.6` | ✅      |
| **Pixel 10 Pro Fold** | `rango`    | `CP2A.260705.006` | `android15-6.6` | ✅      |
| **Pixel 10a**         | `stallion` | `CP2A.260705.006`<br>`CP2A.260805.005` | `android14-6.1` | ✅      |
| **Pixel 9 Pro Fold**  | `comet`    | `CP2A.260705.006` | `android15-6.1` | ✅      |
| **Pixel 9 Pro**       | `caiman`   | `CP2A.260705.006` | `android15-6.1` | ✅      |
| **Pixel 9 Pro XL**    | `komodo`   | `CP2A.260705.006` | `android15-6.1` | ✅      |
| **Pixel 9**           | `tokay`    | `CP2A.260705.006`<br>`AD1A.240905.004` | `android14-6.1` | ✅      |
| **Pixel 9a**          | `tegu`     | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 8 Pro**       | `husky`    | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 8**           | `shiba`    | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 8a**          | `akita`    | `CP2A.260805.005` | `android14-6.1` | ✅      |
| **Pixel 7a**          | `lynx`     | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 7 Pro**       | `cheetah`  | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 7**           | `panther`  | `CP2A.260705.006`<br>`BP2A.250705.008` | `android14-6.1` | ✅      |
| **Pixel 6a**          | `bluejay`  | `CP2A.260705.006`<br>`CP1A.260405.005` | `android14-6.1` | ✅      |
| **Pixel 6**           | `oriole`   | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 6 Pro**       | `raven`    | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel Fold**        | `felix`    | `CP2A.260605.012`<br>`CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel Tablet**      | `tangorpro`| `BP1A.250405.007`<br>`CP2A.260705.006` | `android14-6.1` | ✅      |

---

## Prerequisites

1. A supported Google Pixel device listed in the table above.
2. **Shizuku** installed and running via ADB (`adb shell sh /sdcard/Android/data/rikka.shizuku/starter.sh` or Wireless Debugging).
3. The matching **KernelSU Manager** is bundled and installed for user 0. Do not substitute another fork's manager or use its boot-image installer on a locked device.

---

## Building from Source

The fork now targets KernelSU upstream `96770dfb807fea61e06819e4cbb438384df54b63`
(UAPI 6), retaining `com.lixingchi.kernelsu`, the existing signer and display-safe
Soft Reboot. Yogi `CD1A.260618.001.C3` stays on its stock 6.12.69 kernel and
`android16-6.12` KMI. This source update is not a device-validation result.

CI fetches one signed manager APK and extracts its embedded `ksud`, including
the matching LKMs. Missing, stale or invalid release assets fail the build;
they no longer silently fall back to the historical committed binaries. For a
local build, first refresh both assets using the commands in
[`app/src/main/assets/README.md`](app/src/main/assets/README.md).

An existing older loaded driver is not replaced by opening this app. Manager
repair refuses a version/UAPI mismatch. To adopt the new root stack after its
artifacts are built, fully reboot and root with the new RMP build; Soft Reboot
preserves the kernel and both KernelSU/SUSFS LKMs. Never hot-unload KernelSU
beneath SUSFS.

To compile the entire project (native helper, exploit payloads for all targets, and the final debug APK):

### Build Requirements
- Android NDK r25+ (`ANDROID_NDK_HOME` set or present in Android SDK)
- macOS (arm64/x86_64) or Linux (x86_64) host
- Java 17+ and Gradle Wrapper

### Build Command
```bash
./build-all.sh
```

The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

To install it on a connected device via ADB:
```bash
adb -s <device-serial> install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

⚠️ Keep in mind that the exploit does not have a 100% success rate. Many users have had to try more than 20 times. Many report higher success by opening the app immediately after a reboot, while others find better results 10–40 minutes after rebooting.

🚨 If you root your phone, you might run into trouble making payments with Google Wallet and using RCS messages. Once you unroot, you'll have to wait a couple of days before everything works properly again.

---

## Credits

- Exploit: [NebuSec IonStack](https://github.com/NebuSec/CyberMeowfia)
- App architecture: Inspired and adapted from [Root My Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy)
- KernelSU (https://github.com/tiann/KernelSU)
