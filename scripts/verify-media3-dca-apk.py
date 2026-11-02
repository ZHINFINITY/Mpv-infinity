#!/usr/bin/env python3
"""Verify arm64 APK packaging for the dynamically linked Media3 FFmpeg audio fallback.

Checks linking, LGPL-only licensing, notices, and that every FFmpeg decoder FfmpegLibrary maps
(DTS, TrueHD, AC-3/E-AC-3, AAC, MP3, Opus, FLAC, ...) is really compiled into libavcodec.so.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

from ffmpeg_decoder_inventory import InventoryError, verify_library
from ffmpeg_runtime_config import RuntimeEvidenceError, verify_runtime_evidence


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
        try:
            effective_flags = verify_runtime_evidence(runtime)
        except RuntimeEvidenceError as error:
            raise SystemExit(f"APK's FFmpeg runtime failed licensing/DCA verification: {error}") from error
        try:
            decoders = verify_library(extracted["libavcodec.so"])
        except InventoryError as error:
            raise SystemExit(f"APK's FFmpeg runtime cannot serve the Native audio fallback: {error}") from error

        notice_path = "assets/licenses/media3/NOTICE.txt"
        license_path = "assets/licenses/media3/APACHE-2.0.txt"
        if entries.count(notice_path) != 1 or entries.count(license_path) != 1:
            raise SystemExit("APK is missing the Media3 FFmpeg audio notice or Apache-2.0 license")
        notice = apk.read(notice_path).decode("utf-8")
        apache_license = apk.read(license_path).decode("utf-8")
        if "Apache License 2.0" not in notice or "LGPL-3.0-or-later" not in notice:
            raise SystemExit("Packaged Media3 notice does not identify both wrapper/runtime licenses")
        if "Apache License" not in apache_license:
            raise SystemExit("Packaged Apache license text is invalid")

        print(
            f"OK {apk_path}: arm64 Media3 wrapper dynamically uses one FFmpeg runtime "
            f"(CONFIG_GPL={effective_flags['CONFIG_GPL']}, "
            f"CONFIG_NONFREE={effective_flags['CONFIG_NONFREE']}); LGPL DCA evidence, "
            "no duplicate decoder libraries; notices packaged; "
            f"{len(decoders)} mapped audio decoders present ({', '.join(decoders)})"
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
