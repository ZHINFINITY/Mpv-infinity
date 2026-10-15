#!/usr/bin/env python3
"""Add the pinned Android libudfread shared libraries to an MPV AAR."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import tempfile
import zipfile

ABI_ARCH = {
    "arm64-v8a": "arm64",
    "armeabi-v7a": "armv7l",
    "x86": "x86",
    "x86_64": "x86_64",
}
LIBRARY_NAME = "libudfread.so"


def parse_abis(value: str) -> tuple[str, ...]:
    abis = tuple(part.strip() for part in value.split(",") if part.strip())
    if not abis or len(abis) != len(set(abis)):
        raise SystemExit("--abis must be a non-empty, comma-separated list of unique ABIs")
    unknown = sorted(set(abis) - ABI_ARCH.keys())
    if unknown:
        raise SystemExit(f"Unsupported ABI(s): {', '.join(unknown)}")
    return abis


def package_udfread(aar_path: Path, prefix_dir: Path, abis: tuple[str, ...]) -> tuple[str, ...]:
    if not aar_path.is_file():
        raise SystemExit(f"MPV AAR does not exist: {aar_path}")

    additions: dict[str, bytes] = {}
    for abi in abis:
        source = prefix_dir / ABI_ARCH[abi] / "lib" / LIBRARY_NAME
        if not source.is_file():
            raise SystemExit(f"Pinned {abi} {LIBRARY_NAME} is missing from the native prefix: {source}")
        additions[f"jni/{abi}/{LIBRARY_NAME}"] = source.read_bytes()

    temporary_path: Path | None = None
    try:
        with zipfile.ZipFile(aar_path, "r") as source_archive:
            infos = source_archive.infolist()
            names = [info.filename for info in infos]
            if len(names) != len(set(names)):
                raise SystemExit(f"MPV AAR contains duplicate ZIP entries: {aar_path}")
            names_set = set(names)
            missing_mpv = [abi for abi in abis if f"jni/{abi}/libmpv.so" not in names_set]
            if missing_mpv:
                raise SystemExit(f"MPV AAR is missing libmpv.so for: {', '.join(missing_mpv)}")

            changed = any(
                entry not in names_set or source_archive.read(entry) != data
                for entry, data in additions.items()
            )
            if not changed:
                return tuple(additions)

            with tempfile.NamedTemporaryFile(
                prefix=f".{aar_path.name}.", suffix=".tmp", dir=aar_path.parent, delete=False
            ) as temporary_file:
                temporary_path = Path(temporary_file.name)

            with zipfile.ZipFile(temporary_path, "w") as output_archive:
                output_archive.comment = source_archive.comment
                for info in infos:
                    data = additions.get(info.filename)
                    if data is None:
                        data = source_archive.read(info.filename)
                    output_archive.writestr(info, data, compress_type=info.compress_type)

                for entry, data in additions.items():
                    if entry in names_set:
                        continue
                    info = zipfile.ZipInfo(entry, date_time=(1980, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_STORED
                    info.create_system = 3
                    info.external_attr = (0o100644 & 0xFFFF) << 16
                    output_archive.writestr(info, data)

        os.replace(temporary_path, aar_path)
        temporary_path = None
        return tuple(additions)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path, help="MPV AAR to update")
    parser.add_argument("prefix_dir", type=Path, help="Android native builder prefix directory")
    parser.add_argument("--abis", required=True, help="Comma-separated Android ABI list")
    args = parser.parse_args()

    packaged = package_udfread(args.aar, args.prefix_dir, parse_abis(args.abis))
    print(f"Packaged {', '.join(packaged)} in {args.aar}")


if __name__ == "__main__":
    main()
