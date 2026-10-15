#!/usr/bin/env python3
"""Verify arm64 APK packaging for the dynamically linked Media3 DTS bridge."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


ABI = "arm64-v8a"
LIBRARIES = (
    "libffmpegJNI.so",
    "libavcodec.so",
    "libavutil.so",
    "libswresample.so",
    "libc++_shared.so",
)


def run(*command: str) -> str:
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    return result.stdout


def default_tool(preferred: str, alternative: str) -> str:
    return shutil.which(preferred) or shutil.which(alternative) or preferred


def verify(apk_path: Path, readelf: str, strings: str) -> None:
    with zipfile.ZipFile(apk_path) as apk, tempfile.TemporaryDirectory() as temp_dir:
        entries = apk.namelist()
        extracted: dict[str, Path] = {}
        for library in LIBRARIES:
            entry = f"lib/{ABI}/{library}"
            count = entries.count(entry)
            if count != 1:
                raise SystemExit(f"Expected exactly one {entry} in APK; found {count}")
            path = Path(temp_dir) / library
            path.write_bytes(apk.read(entry))
            extracted[library] = path

        wrapper_dynamic = run(readelf, "-d", str(extracted["libffmpegJNI.so"]))
        for dependency in ("libavcodec.so", "libavutil.so", "libswresample.so"):
            if f"Shared library: [{dependency}]" not in wrapper_dynamic:
                raise SystemExit(f"Media3 JNI wrapper is not dynamically linked to {dependency}")

        avcodec_dynamic = run(readelf, "-d", str(extracted["libavcodec.so"]))
        for dependency in ("libswresample.so", "libavutil.so"):
            if f"Shared library: [{dependency}]" not in avcodec_dynamic:
                raise SystemExit(f"Packaged libavcodec.so is missing {dependency}")

        runtime = run(strings, "-a", str(extracted["libavcodec.so"]))
        required_runtime = (
            "FFmpeg version n9.0",
            "libavcodec license: LGPL version 3 or later",
            "--disable-gpl",
            "--disable-nonfree",
            "DCA (DTS Coherent Acoustics)",
        )
        for marker in required_runtime:
            if marker not in runtime:
                raise SystemExit(f"APK's FFmpeg runtime is missing verified marker: {marker}")

        notice_path = "assets/licenses/media3/NOTICE.txt"
        license_path = "assets/licenses/media3/APACHE-2.0.txt"
        if entries.count(notice_path) != 1 or entries.count(license_path) != 1:
            raise SystemExit("APK is missing the Media3 DTS notice or Apache-2.0 license")
        notice = apk.read(notice_path).decode("utf-8")
        apache_license = apk.read(license_path).decode("utf-8")
        if "Apache License 2.0" not in notice or "LGPL-3.0-or-later" not in notice:
            raise SystemExit("Packaged Media3 notice does not identify both wrapper/runtime licenses")
        if "Apache License" not in apache_license:
            raise SystemExit("Packaged Apache license text is invalid")

        print(
            f"OK {apk_path}: arm64 Media3 wrapper dynamically uses one existing "
            "LGPL FFmpeg DCA runtime; no duplicate decoder libraries; notices packaged"
        )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--readelf", help="ELF inspection tool (defaults to readelf/llvm-readelf)")
    parser.add_argument("--strings", help="strings tool (defaults to strings/llvm-strings)")
    args = parser.parse_args()
    verify(
        args.apk,
        args.readelf or default_tool("readelf", "llvm-readelf"),
        args.strings or default_tool("strings", "llvm-strings"),
    )


if __name__ == "__main__":
    main()
