#!/usr/bin/env python3
"""Verify MPV AARs contain LGPL-only FFmpeg and a compatible libmpv profile/API."""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PLACEBO_CONFIG_HEADER = ROOT / "app/src/main/cpp/third_party/libplacebo/include/libplacebo/config.h"

ABI_ELF = {
    "arm64-v8a": ("ELF64", "AArch64"),
    "armeabi-v7a": ("ELF32", "ARM"),
    "x86": ("ELF32", "Intel 80386"),
    "x86_64": ("ELF64", "Advanced Micro Devices X86-64"),
}
REQUIRED_LIBS = {
    "libavcodec.so",
    "libavdevice.so",
    "libavfilter.so",
    "libavformat.so",
    "libavutil.so",
    "libc++_shared.so",
    "libmpv.so",
    "libplayer.so",
    "libswresample.so",
    "libswscale.so",
}


def parse_abis(value: str) -> tuple[str, ...]:
    abis = tuple(part.strip() for part in value.split(",") if part.strip())
    if not abis or len(abis) != len(set(abis)):
        raise SystemExit("--abis must be a non-empty, comma-separated list of unique ABIs")
    unknown = sorted(set(abis) - ABI_ELF.keys())
    if unknown:
        raise SystemExit(f"Unsupported ABI(s): {', '.join(unknown)}")
    return abis


def run_tool(args: list[str]) -> str:
    try:
        return subprocess.run(args, check=True, text=True, capture_output=True).stdout
    except FileNotFoundError as exc:
        raise SystemExit(f"Required tool is missing: {args[0]} (install binutils)") from exc
    except subprocess.CalledProcessError as exc:
        raise SystemExit(f"{' '.join(args)} failed: {exc.stderr}") from exc


def assert_abi(path: Path, abi: str) -> None:
    expected_class, expected_machine = ABI_ELF[abi]
    header = run_tool(["readelf", "-h", str(path)])
    if not re.search(rf"Class:\s+{re.escape(expected_class)}\b", header) or not re.search(
        rf"Machine:\s+{re.escape(expected_machine)}\b", header
    ):
        raise SystemExit(f"{path.name} is not a {abi} ELF shared library")


def expected_placebo_api_ver() -> str:
    if not PLACEBO_CONFIG_HEADER.is_file():
        raise SystemExit(f"Cannot read app libplacebo API header: {PLACEBO_CONFIG_HEADER}")
    text = PLACEBO_CONFIG_HEADER.read_text(encoding="utf-8")
    match = re.search(r"^#define PL_API_VER\s+(\d+)\s*$", text, re.MULTILINE)
    if not match:
        raise SystemExit(f"Cannot determine PL_API_VER from {PLACEBO_CONFIG_HEADER}")
    return match.group(1)


def verify_placebo_logger_export(abi: str, path: Path, library_name: str = "libmpv.so") -> None:
    api_ver = expected_placebo_api_ver()
    expected_symbol = f"pl_log_create_{api_ver}"
    exports = run_tool(["nm", "-D", "--defined-only", str(path)])
    if not re.search(rf"\b{re.escape(expected_symbol)}(?:@[^\s]+)?\b", exports):
        found = sorted(set(re.findall(r"\bpl_log_create_\d+\b", exports)))
        raise SystemExit(
            f"Standard {abi}/{library_name} exports {found or 'no versioned logger symbol'}, "
            f"but the app's libplacebo headers require {expected_symbol} (API v{api_ver})"
        )


def verify_mpv(
    abi: str,
    path: Path,
    require_vulkan: bool,
    forbid_vulkan: bool,
    placebo_path: Path | None = None,
) -> None:
    text = run_tool(["strings", "-a", str(path)])
    if re.search(r"--enable-gpl\b", text):
        raise SystemExit(f"GPL-enabled MPV configure marker found in {abi}/libmpv.so")
    marker = "List of enabled features:"
    if marker not in text:
        raise SystemExit(f"Cannot verify enabled features in {abi}/libmpv.so")
    features = text.split(marker, 1)[1][:2048]
    if re.search(r"(?<![A-Za-z0-9_-])gpl(?![A-Za-z0-9_-])", features, re.IGNORECASE):
        raise SystemExit(f"MPV's GPL feature is enabled in {abi}/libmpv.so")
    if re.search(r"rubberband", features, re.IGNORECASE):
        raise SystemExit(f"GPL-only Rubber Band feature is enabled in {abi}/libmpv.so")
    if re.search(r"dvdnav", features, re.IGNORECASE):
        raise SystemExit(f"GPL-only DVD navigation feature is enabled in {abi}/libmpv.so")
    has_vulkan = bool(re.search(r"(?<![A-Za-z0-9_-])vulkan(?![A-Za-z0-9_-])", features, re.IGNORECASE))
    if require_vulkan and not has_vulkan:
        raise SystemExit(f"Standard {abi}/libmpv.so is missing its Vulkan feature")
    if require_vulkan:
        if placebo_path is not None:
            verify_placebo_logger_export(abi, placebo_path, "libplacebo.so")
        else:
            verify_placebo_logger_export(abi, path, "libmpv.so")
    if forbid_vulkan and has_vulkan:
        raise SystemExit(f"No-Vulkan {abi}/libmpv.so still enables Vulkan")


def verify_ffmpeg(abi: str, name: str, path: Path) -> None:
    text = run_tool(["strings", "-a", str(path)])
    if re.search(r"--enable-gpl\b", text):
        raise SystemExit(f"GPL-enabled FFmpeg configure marker found in {abi}/{name}")
    if re.search(r"--enable-nonfree\b", text):
        raise SystemExit(f"Nonfree FFmpeg configure marker found in {abi}/{name}")
    if re.search(r"license:\s*GPL\b", text, re.IGNORECASE):
        raise SystemExit(f"GPL license marker found in {abi}/{name}")
    if name == "libavcodec.so":
        markers = re.findall(r"libavcodec license:\s*([^\r\n]+)", text, re.IGNORECASE)
        if not markers or not any("lgpl" in marker.lower() for marker in markers):
            raise SystemExit(f"Could not verify LGPL libavcodec in {abi}; markers: {markers or 'none'}")
        if re.search(r"--enable-mbedtls\b", text) and not re.search(r"--enable-version3\b", text):
            raise SystemExit(f"{abi} FFmpeg enables Apache-2.0 mbedTLS without the required --enable-version3")
        if re.search(r"--enable-mbedtls\b", text) and not re.search(
            r"\bLGPL\s+(?:version\s+)?3\b|\bLGPLv3\b", " ".join(markers), re.IGNORECASE
        ):
            raise SystemExit(f"{abi} FFmpeg with mbedTLS is not marked LGPLv3-or-later: {markers}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("aar", type=Path)
    parser.add_argument("--abis", default="arm64-v8a,armeabi-v7a,x86,x86_64")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--require-vulkan", action="store_true")
    mode.add_argument("--forbid-vulkan", action="store_true")
    parser.add_argument(
        "--only-selected-abis",
        action="store_true",
        help="Fail if the AAR also contains other JNI ABI directories.",
    )
    args = parser.parse_args()
    abis = parse_abis(args.abis)
    if not args.aar.is_file():
        raise SystemExit(f"AAR does not exist: {args.aar}")

    with zipfile.ZipFile(args.aar) as aar:
        names = set(aar.namelist())
        found_abis = {
            name.split("/")[1]
            for name in names
            if name.startswith("jni/") and len(name.split("/")) >= 3
        }
        missing_abis = sorted(set(abis) - found_abis)
        if missing_abis:
            raise SystemExit(f"AAR is missing selected ABI(s): {', '.join(missing_abis)}")
        if args.only_selected_abis:
            extra_abis = sorted(found_abis - set(abis))
            if extra_abis:
                raise SystemExit(f"AAR still contains unselected ABI(s): {', '.join(extra_abis)}")

        with tempfile.TemporaryDirectory(prefix="mpv-aar-license-check-") as td:
            temp = Path(td)
            for abi in abis:
                entries = {
                    Path(name).name: aar.read(name)
                    for name in names
                    if name.startswith(f"jni/{abi}/") and name.endswith(".so")
                }
                missing = sorted(REQUIRED_LIBS - entries.keys())
                if missing:
                    raise SystemExit(f"AAR is missing required {abi} libraries: {', '.join(missing)}")
                paths: dict[str, Path] = {}
                for name, data in entries.items():
                    path = temp / f"{abi}-{name}"
                    path.write_bytes(data)
                    assert_abi(path, abi)
                    paths[name] = path

                for name, path in paths.items():
                    if name.startswith("libav"):
                        verify_ffmpeg(abi, name, path)
                    elif name == "libmpv.so":
                        verify_mpv(
                            abi,
                            path,
                            args.require_vulkan,
                            args.forbid_vulkan,
                            paths.get("libplacebo.so"),
                        )

    print(f"Verified {args.aar}: FFmpeg is LGPL-only and MPV's GPL, Rubber Band, and DVD navigation features are disabled for {', '.join(abis)}.")
    if args.only_selected_abis:
        print("The AAR contains only the selected ABI directories.")
    if args.require_vulkan:
        print("The Standard profile retains MPV's Vulkan feature.")
    if args.forbid_vulkan:
        print("The No-Vulkan profile has MPV's Vulkan feature disabled.")


if __name__ == "__main__":
    main()
