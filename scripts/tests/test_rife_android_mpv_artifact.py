#!/usr/bin/env python3
"""Fail if an Android APK omits the patched resident RIFE libmpv path."""
from __future__ import annotations

import argparse
from pathlib import Path
from zipfile import BadZipFile, ZipFile


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    args = parser.parse_args()
    if not args.apk.is_file():
        raise SystemExit(f"APK is missing: {args.apk}")

    library_name = "lib/arm64-v8a/libmpv.so"
    try:
        with ZipFile(args.apk) as apk:
            library = apk.read(library_name)
    except (BadZipFile, KeyError) as error:
        raise SystemExit(f"APK does not contain {library_name}: {error}")

    required = (
        b"rife-resident",
        b"rife-model-dir",
        b"rife-target-fps",
        b"rife-max-dimension",
        b"AImageReader has no ready buffer",
        b"RIFE_DIAGNOSTIC event=ahb_input_imported",
        b"RIFE_DIAGNOSTIC event=gpu_resident_frame",
    )
    missing = [marker.decode("ascii") for marker in required if marker not in library]
    if missing:
        raise SystemExit(
            "Packaged libmpv is missing resident-path runtime markers: "
            + ", ".join(missing)
        )

    # Keep the upstream synchronous API: async acquisition requires polling its
    # fence on gpu-next's renderer thread, which this build deliberately avoids.
    if b"AImageReader_acquireLatestImage\0" not in library:
        raise SystemExit("Packaged libmpv is missing synchronous acquireLatestImage")
    if b"AImageReader_acquireLatestImageAsync" in library:
        raise SystemExit("Packaged libmpv still contains async acquire/fence polling")

    print(
        f"Android libmpv artifact passed: root options, synchronous/no-buffer reader, "
        f"AHB import, and submitted resident-frame diagnostics ({args.apk})"
    )


if __name__ == "__main__":
    main()
