#!/usr/bin/env python3
"""Overlay and license-check MPV/FFmpeg runtime libraries in an Android AAR."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

REQUIRED_LIBS = {
    "libmpv.so",
    "libplacebo.so",
    "libavcodec.so",
    "libavfilter.so",
    "libavformat.so",
    "libavutil.so",
    "libavdevice.so",
    "libswresample.so",
    "libswscale.so",
    "libudfread.so",
    "libc++_shared.so",
}
REQUIRED_MPVFLOW_MARKERS = (
    "mpvflow-target-fps",
    "mpvflow-max-dimension",
    "mpvflow-quality",
    "mpvflow-compute-api",
    "MPVFlow Synth:",
)
PRESERVE_FROM_MPV_INFINITY = {"libplayer.so"}
KNOWN_SYSTEM_LIBS = {
    "libandroid.so", "libc.so", "libdl.so", "libEGL.so", "libGLESv1_CM.so",
    "libGLESv2.so", "libGLESv3.so", "libjnigraphics.so", "liblog.so", "libm.so",
    "libmediandk.so", "libnativewindow.so", "libOpenSLES.so", "libvulkan.so",
    "libz.so", "libaaudio.so", "libcamera2ndk.so", "libsync.so",
}
ABI_ELF = {
    "arm64-v8a": ("ELF64", "AArch64"),
    "armeabi-v7a": ("ELF32", "ARM"),
    "x86": ("ELF32", "Intel 80386"),
    "x86_64": ("ELF64", "Advanced Micro Devices X86-64"),
}
DEFAULT_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")


def readelf(*args: str) -> str:
    try:
        result = subprocess.run(["readelf", *args], check=True, text=True, capture_output=True)
        return result.stdout
    except FileNotFoundError as exc:
        raise SystemExit("readelf is required (install binutils)") from exc
    except subprocess.CalledProcessError as exc:
        raise SystemExit(f"readelf failed: {exc.stderr}") from exc


def strings(path: Path) -> str:
    try:
        result = subprocess.run(["strings", "-a", str(path)], check=True, text=True, capture_output=True)
        return result.stdout
    except FileNotFoundError as exc:
        raise SystemExit("strings is required (install binutils)") from exc
    except subprocess.CalledProcessError as exc:
        raise SystemExit(f"strings failed for {path}: {exc.stderr}") from exc


def needed_libraries(path: Path) -> set[str]:
    return set(re.findall(r"\(NEEDED\).*Shared library: \[(.+?)\]", readelf("-d", str(path))))


def assert_abi_library(path: Path, abi: str) -> None:
    elf_class, machine = ABI_ELF[abi]
    header = readelf("-h", str(path))
    if not re.search(rf"Class:\s+{re.escape(elf_class)}\b", header) or not re.search(
        rf"Machine:\s+{re.escape(machine)}\b", header
    ):
        raise SystemExit(f"Expected a {abi} ELF shared library: {path.name}")


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


def assert_lgpl_runtime(abi: str, entries: dict[str, bytes], temp: Path) -> None:
    for name, blob in entries.items():
        if not name.endswith(".so"):
            continue
        path = temp / f"{abi}-{name}"
        path.write_bytes(blob)
        if name.startswith("libav"):
            text = strings(path)
            if re.search(r"--enable-gpl\b", text):
                raise SystemExit(f"GPL-enabled FFmpeg configure flags found in {abi}/{name}")
            if re.search(r"license:\s*GPL\b", text, re.IGNORECASE):
                raise SystemExit(f"GPL license marker found in {abi}/{name}")
            if name == "libavcodec.so":
                license_markers = re.findall(r"libavcodec license:\s*([^\r\n]+)", text, re.IGNORECASE)
                if not license_markers or not any("lgpl" in marker.lower() for marker in license_markers):
                    raise SystemExit(
                        f"Could not verify an LGPL libavcodec license for {abi}; markers: {license_markers or 'none'}"
                    )
                if re.search(r"--enable-mbedtls\b", text) and not re.search(r"--enable-version3\b", text):
                    raise SystemExit(
                        f"{abi} FFmpeg enables Apache-2.0 mbedTLS without the required --enable-version3"
                    )
                if re.search(r"--enable-mbedtls\b", text) and not re.search(
                    r"\bLGPL\s+(?:version\s+)?3\b|\bLGPLv3\b", " ".join(license_markers), re.IGNORECASE
                ):
                    raise SystemExit(
                        f"{abi} FFmpeg with mbedTLS is not marked LGPLv3-or-later: {license_markers}"
                    )
        elif name == "libmpv.so":
            text = strings(path)
            if re.search(r"--enable-gpl\b", text):
                raise SystemExit(f"GPL-enabled configure marker found in {abi}/{name}")
            feature_marker = "List of enabled features:"
            if feature_marker not in text:
                raise SystemExit(f"Cannot verify enabled MPV features in {abi}/{name}")
            feature_block = text.split(feature_marker, 1)[1][:2048]
            if re.search(r"(?<![A-Za-z0-9_-])gpl(?![A-Za-z0-9_-])", feature_block, re.IGNORECASE):
                raise SystemExit(f"MPV was built with its GPL feature enabled for {abi}")
            missing_flow = [marker for marker in REQUIRED_MPVFLOW_MARKERS if marker not in text]
            if missing_flow:
                raise SystemExit(
                    f"{abi}/libmpv.so is missing MPVFlow option/OSD markers required to apply interpolation at init: "
                    + ", ".join(missing_flow)
                )


def parse_abis(value: str) -> tuple[str, ...]:
    abis = tuple(part.strip() for part in value.split(",") if part.strip())
    if not abis or len(set(abis)) != len(abis):
        raise SystemExit("--abis must contain a non-empty, comma-separated list of unique Android ABIs")
    unknown = sorted(set(abis) - ABI_ELF.keys())
    if unknown:
        raise SystemExit(f"Unsupported ABI name(s): {', '.join(unknown)}")
    return abis


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime-apk", required=True, type=Path)
    parser.add_argument("--mpv-aar", required=True, type=Path)
    parser.add_argument(
        "--abis",
        default=",".join(DEFAULT_ABIS),
        help="Comma-separated ABIs to replace (default: all four Android ABIs).",
    )
    parser.add_argument(
        "--prune-abis",
        action="store_true",
        help="Remove every non-selected ABI directory from the output AAR.",
    )
    parser.add_argument(
        "--without-vulkan",
        action="store_true",
        help="Overlay a no-Vulkan runtime and skip Vulkan-only libplacebo export checks.",
    )
    args = parser.parse_args()
    abis = parse_abis(args.abis)
    required_libs = REQUIRED_LIBS
    apk_path = args.runtime_apk.resolve()
    aar_path = args.mpv_aar.resolve()
    if not apk_path.is_file() or not aar_path.is_file():
        raise SystemExit("Both --runtime-apk and --mpv-aar must name existing files")

    runtime_by_abi: dict[str, dict[str, bytes]] = {}
    with zipfile.ZipFile(apk_path) as apk:
        for abi in abis:
            prefix = f"lib/{abi}/"
            runtime_by_abi[abi] = {
                Path(name).name: apk.read(name)
                for name in apk.namelist()
                if name.startswith(prefix) and name.endswith(".so")
                and not Path(name).name.lower().startswith("libqnn")
            }

    with zipfile.ZipFile(aar_path) as aar:
        original_entries = {
            info.filename: (info, aar.read(info.filename))
            for info in aar.infolist()
        }

    with tempfile.TemporaryDirectory(prefix="mpv-runtime-aar-") as td:
        temp = Path(td)
        for abi in abis:
            entries = runtime_by_abi[abi]
            missing = sorted(required_libs - entries.keys())
            if missing:
                raise SystemExit(f"MPV {abi} runtime APK is missing required native libraries: {missing}")

            player_name = f"jni/{abi}/libplayer.so"
            if player_name not in original_entries:
                raise SystemExit(f"The MPV∞ AAR has no {abi} libplayer.so JNI bridge to preserve")
            # Runtime APKs may contain shared libraries that the old AAR never
            # packaged (notably libplacebo.so). The overlay writer below
            # replaces present members and appends new ones; only libplayer.so
            # is required from the original AAR because it is app-specific.

            for name, blob in entries.items():
                library = temp / f"{abi}-{name}"
                library.write_bytes(blob)
                assert_abi_library(library, abi)
            assert_lgpl_runtime(abi, entries, temp)

            placebo = temp / f"{abi}-libplacebo.so"
            if not args.without_vulkan:
                required_placebo_exports = {
                    "pl_vulkan_default_params", "pl_vulkan_create", "pl_vulkan_destroy",
                    "pl_vulkan_wrap", "pl_vulkan_hold_ex", "pl_vulkan_release_ex",
                }
                missing_placebo_exports = sorted(required_placebo_exports - dynamic_defined_symbols(placebo))
                if missing_placebo_exports:
                    raise SystemExit(
                        f"{abi} libplacebo.so is missing Vulkan API exports required by Media3 JNI: "
                        + ", ".join(missing_placebo_exports)
                    )

            player_file = temp / f"{abi}-libplayer.so"
            player_file.write_bytes(original_entries[player_name][1])
            assert_abi_library(player_file, abi)
            final_libs = set(entries) | PRESERVE_FROM_MPV_INFINITY
            for name in entries:
                for dependency in needed_libraries(temp / f"{abi}-{name}"):
                    if dependency in KNOWN_SYSTEM_LIBS or dependency.startswith(("libgcc", "libunwind")):
                        continue
                    if dependency not in final_libs:
                        raise SystemExit(f"{abi}/{name} needs {dependency}, which would not be packaged")
            for dependency in needed_libraries(player_file):
                if dependency in KNOWN_SYSTEM_LIBS or dependency.startswith(("libgcc", "libunwind")):
                    continue
                if dependency not in final_libs:
                    raise SystemExit(f"Preserved {abi} libplayer.so needs missing {dependency}")

            required_tags = elf_version_tags(player_file)
            provided_tags: set[str] = set()
            for name in entries:
                if name.startswith("libav") or name in {"libswresample.so", "libswscale.so"}:
                    provided_tags |= elf_version_tags(temp / f"{abi}-{name}")
            missing_tags = sorted(required_tags - provided_tags)
            if missing_tags:
                raise SystemExit(
                    f"{abi} libplayer.so needs FFmpeg ABI versions not supplied by runtime: "
                    + ", ".join(missing_tags)
                    + "; runtime exports: "
                    + (", ".join(sorted(provided_tags)) or "no FFmpeg version tags")
                )

    overlay = {
        f"jni/{abi}/{name}": blob
        for abi, entries in runtime_by_abi.items()
        for name, blob in entries.items()
        if name != "libplayer.so"
    }
    # The app now ships only MPVFlow; remove any stale RIFE library from every ABI,
    # including ABIs not selected for this runtime overlay.
    remove_from_aar = {
        name for name in original_entries
        if name.startswith("jni/") and name.endswith("/librife_vfi.so")
    }
    if args.prune_abis:
        remove_from_aar.update(
            name for name in original_entries
            if name.startswith("jni/") and len(name.split("/")) >= 3
            and name.split("/")[1] not in abis
        )

    fd, temp_name = tempfile.mkstemp(prefix=aar_path.name + ".", suffix=".tmp", dir=aar_path.parent)
    os.close(fd)
    try:
        with zipfile.ZipFile(aar_path) as source, zipfile.ZipFile(temp_name, "w") as output:
            for info in source.infolist():
                name = info.filename
                if name in remove_from_aar:
                    continue
                if name in overlay:
                    output.writestr(info, overlay.pop(name))
                else:
                    output.writestr(info, source.read(name))
            for name, blob in sorted(overlay.items()):
                info = zipfile.ZipInfo(name)
                info.compress_type = zipfile.ZIP_DEFLATED
                output.writestr(info, blob)
        os.replace(temp_name, aar_path)
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)

    print(f"Overlay complete: {aar_path}")
    print(f"Replaced and license-checked runtime libraries for: {', '.join(abis)}")
    print("Preserved each ABI's MPV∞ libplayer.so and validated FFmpeg ABI tags plus the selected runtime profile.")
    if args.prune_abis:
        print("Removed all non-selected ABI entries from the AAR.")
    if args.without_vulkan:
        print("Packaged a no-Vulkan MPV runtime.")


if __name__ == "__main__":
    main()
