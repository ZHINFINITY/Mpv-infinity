#!/usr/bin/env python3
"""Fail if the Android MPV runtime APK omits the patched resident RIFE path."""
from __future__ import annotations

import argparse
from pathlib import Path
from zipfile import BadZipFile, ZipFile


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    args = parser.parse_args()
    if not args.apk.is_file():
        raise SystemExit(f"Runtime APK is missing: {args.apk}")

    library_name = "lib/arm64-v8a/libmpv.so"
    try:
        with ZipFile(args.apk) as apk:
            library = apk.read(library_name)
    except (BadZipFile, KeyError) as error:
        raise SystemExit(f"Runtime APK does not contain {library_name}: {error}")

    required = (
        b"rife-resident",
        b"rife-model-dir",
        b"rife-target-fps",
        b"rife-max-dimension",
        b"AImageReader_acquireLatestImageAsync",
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

    # The old synchronous API string must not be an exact imported symbol name.
    if b"AImageReader_acquireLatestImage\0" in library:
        raise SystemExit("Packaged libmpv still imports synchronous acquireLatestImage")

    print(
        f"Android libmpv artifact passed: root options, async/no-buffer reader, "
        f"AHB import, and submitted resident-frame diagnostics ({args.apk})"
    )


if __name__ == "__main__":
    main()
