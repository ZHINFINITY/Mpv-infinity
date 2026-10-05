#!/usr/bin/env python3
"""Restore FFmpeg's capability-tested symbol-versioning path on modern Android."""
from __future__ import annotations

import argparse
from pathlib import Path

ANDROID_CASE = "    android)\n        disable symver\n        enable section_data_rel_ro\n"
PATCHED_ANDROID_CASE = (
    "    android)\n"
    "        # Bionic supports GNU ELF symbol versioning from API level 23.\n"
    "        enable section_data_rel_ro\n"
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("configure", type=Path, help="path to the downloaded FFmpeg configure script")
    configure = parser.parse_args().configure
    if not configure.is_file():
        raise SystemExit(f"FFmpeg configure script not found: {configure}")

    source = configure.read_text()
    if source.count(ANDROID_CASE) == 1 and PATCHED_ANDROID_CASE not in source:
        source = source.replace(ANDROID_CASE, PATCHED_ANDROID_CASE, 1)
        configure.write_text(source)
        print("Restored FFmpeg's compiler/linker-tested symver path for Android API 23+")
    elif source.count(PATCHED_ANDROID_CASE) == 1 and ANDROID_CASE not in source:
        print("FFmpeg Android symver capability probe already enabled")
    else:
        raise SystemExit(
            f"Expected exactly one pristine FFmpeg 8.1 Android symver block in {configure}; refusing unsafe patch"
        )


if __name__ == "__main__":
    main()
