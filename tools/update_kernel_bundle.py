#!/usr/bin/env python3
"""Validate one signed KernelSU APK, then stage it and its own embedded ksud."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

PACKAGE = "com.lixingchi.kernelsu"
SIGNER = "ff8c6f43e0bdd88057103c9faa5d78d7f43ee80a3c1ffa3e20eed06dcb640050"
UPSTREAM = "96770dfb807fea61e06819e4cbb438384df54b63"
UAPI = 6
ASSET = "GhostLock-manager.apk"
DAEMON = "lib/arm64-v8a/libksud.so"


def update_bundle(apk, metadata, assets, apksigner, aapt2):
    info = json.loads(metadata.read_text())
    data = apk.read_bytes()
    expected = {
        "package": PACKAGE,
        "asset": ASSET,
        "upstreamCommit": UPSTREAM,
        "uapiVersion": UAPI,
        "sha256": hashlib.sha256(data).hexdigest(),
        "size": len(data),
    }
    for key, value in expected.items():
        if info.get(key) != value:
            raise ValueError(
                f"KernelSU bundle metadata mismatch: {key}. "
                "A successful KernelSU Build Manager publication is required before rebuilding RMP; "
                "a standalone ksud release is not the matched bundle."
            )
    if not re.fullmatch(r"[0-9a-f]{40}", info.get("sourceCommit", "")):
        raise ValueError("KernelSU bundle has no immutable source commit")

    certs = subprocess.run(
        [str(apksigner), "verify", "--print-certs", str(apk)],
        check=True, capture_output=True, text=True,
    ).stdout
    # SDK 37 uses scheme labels ("V2 Signer:"); collect every certificate digest.
    signers = re.findall(r"^.* certificate SHA-256 digest: (.*)$", certs, re.M)
    if signers != [SIGNER]:
        raise ValueError("KernelSU APK signer does not match the driver")
    badging = subprocess.run(
        [str(aapt2), "dump", "badging", str(apk)],
        check=True, capture_output=True, text=True,
    ).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging, re.M)
    if not package or (package[1], int(package[2]), package[3]) != (
        PACKAGE, info.get("versionCode"), info.get("versionName"),
    ):
        raise ValueError("KernelSU APK package/version does not match metadata")

    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip() is not None:
            raise ValueError("KernelSU APK has duplicate or corrupt entries")
        daemon = archive.read(DAEMON)
    if (len(daemon) < 64 or daemon[:6] != b"\x7fELF\x02\x01" or
            struct.unpack_from("<HH", daemon, 16) not in ((2, 183), (3, 183))):
        raise ValueError("Embedded ksud is not an AArch64 ELF64 executable")

    # All checks precede either replacement; any failure stops the APK build.
    manager = assets / "manager" / ASSET
    ksud = assets / "ksud" / "ksud"
    manager.parent.mkdir(parents=True, exist_ok=True)
    ksud.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(apk, manager)
    manager.chmod(0o644)
    ksud.write_bytes(daemon)
    ksud.chmod(0o755)
    print(f"KernelSU {info['sourceCommit']}: manager + embedded ksud, UAPI {UAPI}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--assets", type=Path, default=Path("app/src/main/assets"))
    parser.add_argument("--apksigner", required=True, type=Path)
    parser.add_argument("--aapt2", required=True, type=Path)
    args = parser.parse_args()
    update_bundle(args.apk, args.metadata, args.assets, args.apksigner, args.aapt2)


if __name__ == "__main__":
    main()
