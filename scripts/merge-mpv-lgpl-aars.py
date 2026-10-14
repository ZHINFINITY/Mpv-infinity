#!/usr/bin/env python3
"""Merge four independently built single-ABI MPV AARs into two full AARs."""
from __future__ import annotations

import argparse
from copy import copy
from pathlib import Path
import sys
import zipfile

SUPPORTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
DEFAULT_ABIS = ",".join(SUPPORTED_ABIS)


def parse_abis(value: str) -> tuple[str, ...]:
    abis = tuple(part.strip() for part in value.split(",") if part.strip())
    if len(abis) != len(set(abis)) or set(abis) != set(SUPPORTED_ABIS):
        raise SystemExit(
            f"--abis must list each supported ABI exactly once: {DEFAULT_ABIS}"
        )
    return tuple(abi for abi in SUPPORTED_ABIS if abi in abis)


def find_unique(root: Path, name: str) -> Path:
    matches = sorted(root.rglob(name))
    if len(matches) != 1:
        raise SystemExit(
            f"Expected exactly one {name} under {root}, found {len(matches)}"
        )
    return matches[0]


def read_single_abi_aar(
    path: Path, expected_abi: str
) -> tuple[dict[str, tuple[zipfile.ZipInfo, bytes]], dict[str, tuple[zipfile.ZipInfo, bytes]]]:
    try:
        with zipfile.ZipFile(path) as archive:
            bad_member = archive.testzip()
            if bad_member is not None:
                raise SystemExit(f"Corrupt ZIP member {bad_member!r} in {path}")
            infos = [info for info in archive.infolist() if not info.is_dir()]
            filenames = [info.filename for info in infos]
            if len(filenames) != len(set(filenames)):
                raise SystemExit(f"Duplicate ZIP member in {path}")

            found_abis = {
                parts[1]
                for name in filenames
                if name.startswith("jni/")
                for parts in (name.split("/", 2),)
                if len(parts) == 3
            }
            if found_abis != {expected_abi}:
                raise SystemExit(
                    f"{path} must contain only {expected_abi}; found "
                    f"{', '.join(sorted(found_abis)) or 'no JNI ABI'}"
                )

            common: dict[str, tuple[zipfile.ZipInfo, bytes]] = {}
            native: dict[str, tuple[zipfile.ZipInfo, bytes]] = {}
            for info in infos:
                name = info.filename
                data = archive.read(info)
                if name.startswith("jni/"):
                    parts = name.split("/", 2)
                    if len(parts) != 3 or parts[1] != expected_abi:
                        raise SystemExit(f"Unexpected JNI path {name!r} in {path}")
                    native[name] = (info, data)
                else:
                    common[name] = (info, data)
            if not native:
                raise SystemExit(f"No native files found in {path}")
            return common, native
    except zipfile.BadZipFile as exc:
        raise SystemExit(f"Invalid AAR ZIP {path}: {exc}") from exc


def merge_profile(
    artifacts_root: Path, output: Path, abis: tuple[str, ...], filename_pattern: str
) -> None:
    common_by_abi: dict[str, dict[str, tuple[zipfile.ZipInfo, bytes]]] = {}
    native_by_abi: dict[str, dict[str, tuple[zipfile.ZipInfo, bytes]]] = {}

    for abi in abis:
        filename = filename_pattern.format(abi=abi)
        aar = find_unique(artifacts_root, filename)
        common, native = read_single_abi_aar(aar, abi)
        common_by_abi[abi] = common
        native_by_abi[abi] = native

    first_abi = abis[0]
    first_common = common_by_abi[first_abi]
    expected_names = set(first_common)
    for abi in abis[1:]:
        common = common_by_abi[abi]
        if set(common) != expected_names:
            missing = sorted(expected_names - set(common))
            extra = sorted(set(common) - expected_names)
            raise SystemExit(
                f"Common AAR metadata file set differs for {abi}; "
                f"missing={missing}, extra={extra}"
            )
        mismatched = sorted(
            name
            for name in expected_names
            if common[name][1] != first_common[name][1]
        )
        if mismatched:
            raise SystemExit(
                f"Common AAR metadata differs for {abi}: {', '.join(mismatched)}"
            )

    output.parent.mkdir(parents=True, exist_ok=True)
    written: set[str] = set()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as merged:
        for name in sorted(first_common):
            info, data = first_common[name]
            merged.writestr(copy(info), data)
            written.add(name)
        for abi in abis:
            for name in sorted(native_by_abi[abi]):
                if name in written:
                    raise SystemExit(f"Duplicate merged AAR member: {name}")
                info, data = native_by_abi[abi][name]
                merged.writestr(copy(info), data)
                written.add(name)

    with zipfile.ZipFile(output) as merged:
        bad_member = merged.testzip()
        if bad_member is not None:
            raise SystemExit(f"Merged AAR is corrupt at {bad_member!r}: {output}")
    print(f"Merged {len(abis)} ABI archives into {output}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifacts_root", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--abis", default=DEFAULT_ABIS)
    args = parser.parse_args()
    if not args.artifacts_root.is_dir():
        raise SystemExit(f"Matrix artifacts directory does not exist: {args.artifacts_root}")
    abis = parse_abis(args.abis)
    merge_profile(
        args.artifacts_root,
        args.output_dir / "mpvlib.aar",
        abis,
        "mpvlib-{abi}.aar",
    )
    merge_profile(
        args.artifacts_root,
        args.output_dir / "mpvlib-no-vulkun.aar",
        abis,
        "mpvlib-no-vulkun-{abi}.aar",
    )


if __name__ == "__main__":
    main()
