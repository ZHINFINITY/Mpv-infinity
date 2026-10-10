#!/usr/bin/env python3
"""Overlay a custom arm64 MPV/FFmpeg runtime into MPV∞'s existing AAR."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

REQUIRED_LIBS = {
    "librife_vfi.so",
    "libmpv.so",
    "libplacebo.so",
    "libavcodec.so",
    "libavfilter.so",
    "libavformat.so",
    "libavutil.so",
    "libavdevice.so",
    "libswresample.so",
    "libswscale.so",
    "libc++_shared.so",
}
FLOW_REQUIRED_LIBS = REQUIRED_LIBS - {"librife_vfi.so"}
NEW_RUNTIME_LIBS = {"librife_vfi.so", "libplacebo.so"}
PRESERVE_FROM_MPV_INFINITY = {"libplayer.so"}
KNOWN_SYSTEM_LIBS = {
    "libandroid.so", "libc.so", "libdl.so", "libEGL.so", "libGLESv1_CM.so",
    "libGLESv2.so", "libGLESv3.so", "libjnigraphics.so", "liblog.so", "libm.so",
    "libmediandk.so", "libnativewindow.so", "libOpenSLES.so", "libvulkan.so",
    "libz.so", "libaaudio.so", "libcamera2ndk.so", "libsync.so",
}


def readelf(*args: str) -> str:
    try:
        result = subprocess.run(["readelf", *args], check=True, text=True, capture_output=True)
        return result.stdout
    except FileNotFoundError as exc:
        raise SystemExit("readelf is required (install binutils)") from exc
    except subprocess.CalledProcessError as exc:
        raise SystemExit(f"readelf failed: {exc.stderr}") from exc


def needed_libraries(path: Path) -> set[str]:
    return set(re.findall(r"\(NEEDED\).*Shared library: \[(.+?)\]", readelf("-d", str(path))))


def assert_arm64_library(path: Path) -> None:
    header = readelf("-h", str(path))
    if not re.search(r"Class:\s+ELF64", header) or not re.search(r"Machine:\s+AArch64", header):
        raise SystemExit(f"Expected an arm64-v8a ELF shared library: {path.name}")


def dynamic_defined_symbols(path: Path) -> set[str]:
    text = readelf("--dyn-syms", "--wide", str(path))
    symbols: set[str] = set()
    for line in text.splitlines():
        fields = line.split()
        if (len(fields) >= 8 and fields[-4] in {"GLOBAL", "WEAK"}
                and fields[-3] == "DEFAULT" and fields[-2] != "UND"):
            symbols.add(fields[-1].split("@", 1)[0])
    return symbols


def elf_version_tags(path: Path) -> set[str]:
    text = readelf("--version-info", "--wide", str(path))
    return set(re.findall(r"\bName:\s+(LIBAV[A-Z0-9_]+_\d+)\b", text))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime-apk", required=True, type=Path)
    parser.add_argument("--mpv-aar", required=True, type=Path)
    parser.add_argument(
        "--without-rife",
        action="store_true",
        help="Overlay a standard MPVFlow runtime without requiring or packaging the RIFE library.",
    )
    args = parser.parse_args()
    required_libs = FLOW_REQUIRED_LIBS if args.without_rife else REQUIRED_LIBS
    new_runtime_libs = NEW_RUNTIME_LIBS - ({"librife_vfi.so"} if args.without_rife else set())
    apk_path = args.runtime_apk.resolve()
    aar_path = args.mpv_aar.resolve()
    if not apk_path.is_file() or not aar_path.is_file():
        raise SystemExit("Both --runtime-apk and --mpv-aar must name existing files")

    with zipfile.ZipFile(apk_path) as apk:
        entries = {
            Path(name).name: apk.read(name)
            for name in apk.namelist()
            if name.startswith("lib/arm64-v8a/") and name.endswith(".so")
            and not Path(name).name.lower().startswith("libqnn")
        }
    if not required_libs.issubset(entries):
        missing = sorted(required_libs - entries.keys())
        raise SystemExit(f"MPV arm64 runtime APK is missing required native libraries: {missing}")

    with zipfile.ZipFile(aar_path) as aar:
        original_entries = {name: (info, aar.read(name)) for info in aar.infolist() for name in [info.filename]}
    player_name = "jni/arm64-v8a/libplayer.so"
    if player_name not in original_entries:
        raise SystemExit("The MPV∞ AAR has no arm64 libplayer.so JNI bridge to preserve")
    for name in required_libs - new_runtime_libs:
        if f"jni/arm64-v8a/{name}" not in original_entries:
            raise SystemExit(f"The MPV∞ AAR has no arm64 slot for {name}")

    with tempfile.TemporaryDirectory(prefix="mpv-runtime-aar-") as td:
        temp = Path(td)
        for name, blob in entries.items():
            (temp / name).write_bytes(blob)
            assert_arm64_library(temp / name)
        placebo_exports = dynamic_defined_symbols(temp / "libplacebo.so")
        required_placebo_exports = {
            "pl_vulkan_default_params", "pl_vulkan_create", "pl_vulkan_destroy",
            "pl_vulkan_wrap", "pl_vulkan_hold_ex", "pl_vulkan_release_ex",
        }
        missing_placebo_exports = sorted(required_placebo_exports - placebo_exports)
        if missing_placebo_exports:
            raise SystemExit(
                "The runtime libplacebo.so does not export the Vulkan API required by Media3 JNI: "
                + ", ".join(missing_placebo_exports)
            )
        player_file = temp / "libplayer.so"
        player_file.write_bytes(original_entries[player_name][1])
        assert_arm64_library(player_file)
        final_libs = set(entries) | PRESERVE_FROM_MPV_INFINITY
        for name in entries:
            for dependency in needed_libraries(temp / name):
                if dependency in KNOWN_SYSTEM_LIBS or dependency.startswith(("libgcc", "libunwind")):
                    continue
                if dependency not in final_libs:
                    raise SystemExit(f"Native runtime {name} needs {dependency}, which would not be packaged")
        for dependency in needed_libraries(player_file):
            if dependency in KNOWN_SYSTEM_LIBS or dependency.startswith(("libgcc", "libunwind")):
                continue
            if dependency not in final_libs:
                raise SystemExit(f"Preserved MPV∞ libplayer.so needs missing {dependency}")

        required_tags = elf_version_tags(player_file)
        provided_tags: set[str] = set()
        for name in entries:
            if name.startswith("libav") or name in {"libswresample.so", "libswscale.so"}:
                provided_tags |= elf_version_tags(temp / name)
        missing_tags = sorted(required_tags - provided_tags)
        if missing_tags:
            raise SystemExit(
                "The preserved MPV∞ JNI bridge needs FFmpeg ABI versions not supplied by the runtime: "
                + ", ".join(missing_tags)
                + "; runtime exports: "
                + (", ".join(sorted(provided_tags)) or "no FFmpeg version tags")
            )

    overlay = {
        f"jni/arm64-v8a/{name}": blob
        for name, blob in entries.items()
        if name not in PRESERVE_FROM_MPV_INFINITY
    }
    remove_from_aar = {"jni/arm64-v8a/librife_vfi.so"} if args.without_rife else set()
    fd, temp_name = tempfile.mkstemp(prefix=aar_path.name + ".", suffix=".tmp", dir=aar_path.parent)
    os.close(fd)
    try:
        with zipfile.ZipFile(aar_path) as source, zipfile.ZipFile(temp_name, "w") as output:
            for info in source.infolist():
                if info.filename in remove_from_aar:
                    continue
                if info.filename in overlay:
                    output.writestr(info, overlay.pop(info.filename))
                else:
                    output.writestr(info, source.read(info.filename))
            for name, blob in sorted(overlay.items()):
                info = zipfile.ZipInfo(name)
                info.compress_type = zipfile.ZIP_DEFLATED
                output.writestr(info, blob)
        os.replace(temp_name, aar_path)
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)

    print(f"Overlay complete: {aar_path}")
    print("Preserved MPV∞ libplayer.so; replaced/added arm64 MPV, libplacebo, FFmpeg, and matching libc++_shared runtime libraries.")
    if args.without_rife:
        print("Packaged the MPVFlow runtime without the RIFE native library.")
    print(f"Validated FFmpeg ABI tags: {', '.join(sorted(required_tags)) or 'none exposed by the JNI bridge'}")


if __name__ == "__main__":
    main()
