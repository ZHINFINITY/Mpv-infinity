#!/usr/bin/env python3
"""Verify and stage the pinned RIFE-v4.6 model files for the experimental APK."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import shutil

MODEL_HASHES = {
    "flownet.param": "724569596bcd1e7b9fa50455c604777ebed99746d2ef40aa86e31b5725f1053c",
    "flownet.bin": "f334ed2260149ce0188a6dcf049844e8b0cdd912e01cbcfb63553157d2508958",
}
UPSTREAM_COMMIT = "a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--license", required=True, type=Path)
    parser.add_argument("--ncnn-license", required=True, type=Path)
    parser.add_argument("--webp-license", required=True, type=Path)
    parser.add_argument("--destination", required=True, type=Path)
    args = parser.parse_args()

    for license_path in (args.license, args.ncnn_license, args.webp_license):
        if not license_path.is_file():
            raise SystemExit(f"Missing upstream license file: {license_path}")
    args.destination.mkdir(parents=True, exist_ok=True)
    for filename, expected in MODEL_HASHES.items():
        source = args.source / filename
        if not source.is_file():
            raise SystemExit(f"Missing RIFE-v4.6 model file: {source}")
        actual = sha256(source)
        if actual != expected:
            raise SystemExit(f"Unexpected SHA-256 for {filename}: {actual}")
        shutil.copy2(source, args.destination / filename)

    shutil.copy2(args.license, args.destination / "RIFE-LICENSE")
    shutil.copy2(args.ncnn_license, args.destination / "NCNN-LICENSE.txt")
    shutil.copy2(args.webp_license, args.destination / "LIBWEBP-COPYING")
    (args.destination / "MODEL-SOURCE.txt").write_text(
        "RIFE-ncnn-vulkan\n"
        f"Upstream commit: {UPSTREAM_COMMIT}\n"
        "Model directory: models/rife-v4.6\n"
        "SHA-256 values are enforced by scripts/stage-rife-model.py and RifeModelInstaller.kt.\n"
    )
    print(f"Staged verified RIFE-v4.6 model assets from {UPSTREAM_COMMIT} into {args.destination}")


if __name__ == "__main__":
    main()
