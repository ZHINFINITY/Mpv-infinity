#!/usr/bin/env python3
"""Verify the DTS bridge can reuse the existing LGPL-only FFmpeg runtime."""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


REQUIRED_LIBS = ("avcodec", "avutil", "swresample")
REQUIRED_AVCODEC_SYMBOLS = (
    "avcodec_find_decoder",
    "avcodec_find_decoder_by_name",
    "avcodec_alloc_context3",
    "avcodec_open2",
    "avcodec_send_packet",
    "avcodec_receive_frame",
    "avcodec_flush_buffers",
    "avcodec_free_context",
    "av_packet_alloc",
    "av_packet_free",
)
REQUIRED_AVUTIL_SYMBOLS = (
    "av_channel_layout_default",
    "av_frame_alloc",
    "av_frame_free",
    "av_get_bytes_per_sample",
    "av_malloc",
    "av_samples_get_buffer_size",
    "av_strerror",
)
REQUIRED_SWRESAMPLE_SYMBOLS = (
    "swr_alloc_set_opts2",
    "swr_init",
    "swr_convert",
    "swr_get_out_samples",
    "swr_free",
)


def run(*command: str) -> str:
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    return result.stdout


def default_tool(preferred: str, alternative: str) -> str:
    return shutil.which(preferred) or shutil.which(alternative) or preferred


def verify(aar_path: Path, abis: list[str], readelf: str, strings: str) -> None:
    with zipfile.ZipFile(aar_path) as archive, tempfile.TemporaryDirectory() as temp_dir:
        for abi in abis:
            extracted: dict[str, Path] = {}
            archive_entries = archive.namelist()
            for library in REQUIRED_LIBS:
                archive_name = f"jni/{abi}/lib{library}.so"
                if archive_entries.count(archive_name) != 1:
                    raise SystemExit(f"Expected one {archive_name} in {aar_path}")
                path = Path(temp_dir) / f"{abi}-{library}.so"
                path.write_bytes(archive.read(archive_name))
                extracted[library] = path

            avcodec = extracted["avcodec"]
            text = run(strings, "-a", str(avcodec))
            configuration = next(
                (line for line in text.splitlines() if "--disable-gpl" in line),
                "",
            )
            required_text = {
                "FFmpeg n9.0": "FFmpeg version n9.0" in text,
                "LGPLv3-or-later license": "libavcodec license: LGPL version 3 or later" in text,
                "GPL explicitly disabled": "--disable-gpl" in configuration,
                "nonfree explicitly disabled": "--disable-nonfree" in configuration,
                "version-3 LGPL mode": "--enable-version3" in configuration,
                "DCA decoder marker": "DCA (DTS Coherent Acoustics)" in text,
            }
            missing = [name for name, found in required_text.items() if not found]
            if missing:
                raise SystemExit(f"{abi}: missing runtime evidence: {', '.join(missing)}")
            if "--enable-gpl" in configuration or re.search(r"(?<!L)GPL version", text):
                raise SystemExit(f"{abi}: GPL-enabled or GPL-licensed FFmpeg runtime detected")

            symbol_groups = (
                ("avcodec", REQUIRED_AVCODEC_SYMBOLS),
                ("avutil", REQUIRED_AVUTIL_SYMBOLS),
                ("swresample", REQUIRED_SWRESAMPLE_SYMBOLS),
            )
            for library, symbols in symbol_groups:
                exported = run(readelf, "-Ws", str(extracted[library]))
                for symbol in symbols:
                    if not re.search(rf"\b{re.escape(symbol)}(?:@@|\s)", exported):
                        raise SystemExit(f"{abi}: {library} does not export JNI-required {symbol}")

            dynamic = run(readelf, "-d", str(avcodec))
            for dependency in ("libswresample.so", "libavutil.so"):
                if f"Shared library: [{dependency}]" not in dynamic:
                    raise SystemExit(f"{abi}: libavcodec is missing dynamic dependency {dependency}")

            print(
                f"OK {abi}: FFmpeg n9.0; LGPL-3.0-or-later; GPL/nonfree disabled; "
                "DCA marker and all JNI-required dynamic symbols present"
            )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path, help="MPV runtime AAR to inspect")
    parser.add_argument(
        "--abis",
        nargs="+",
        default=["arm64-v8a"],
        help="ABIs to verify (default: arm64-v8a)",
    )
    parser.add_argument("--readelf", help="ELF inspection tool (defaults to readelf/llvm-readelf)")
    parser.add_argument("--strings", help="strings tool (defaults to strings/llvm-strings)")
    args = parser.parse_args()
    verify(
        args.aar,
        args.abis,
        args.readelf or default_tool("readelf", "llvm-readelf"),
        args.strings or default_tool("strings", "llvm-strings"),
    )


if __name__ == "__main__":
    main()
