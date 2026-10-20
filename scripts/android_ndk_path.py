#!/usr/bin/env python3
"""Resolve a pinned Android NDK without asking AGP to construct sdkComponents."""

from __future__ import annotations

import re
import os
import sys
from pathlib import Path
from typing import Mapping


_VERSION_RE = re.compile(r"^[0-9]+(?:\.[0-9]+)*$")


def _unescape_property(value: str) -> str:
    result: list[str] = []
    index = 0
    escapes = {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}
    while index < len(value):
        char = value[index]
        if char != "\\":
            result.append(char)
            index += 1
            continue
        index += 1
        if index == len(value):
            break
        escaped = value[index]
        if escaped == "u" and index + 4 < len(value):
            codepoint = value[index + 1 : index + 5]
            try:
                result.append(chr(int(codepoint, 16)))
                index += 5
                continue
            except ValueError:
                pass
        result.append(escapes.get(escaped, escaped))
        index += 1
    return "".join(result)


def _sdk_dir_from_local_properties(project_root: Path) -> Path | None:
    properties_file = project_root / "local.properties"
    if not properties_file.is_file():
        return None

    for raw_line in properties_file.read_text(encoding="utf-8").splitlines():
        line = raw_line.lstrip()
        if not line or line.startswith(("#", "!")):
            continue

        escaped = False
        separator = len(line)
        for index, char in enumerate(line):
            if char in "=: \t\f" and not escaped:
                separator = index
                break
            if char == "\\" and not escaped:
                escaped = True
            else:
                escaped = False

        key = _unescape_property(line[:separator].rstrip())
        if key != "sdk.dir":
            continue

        value = line[separator:]
        while value and value[0] in " \t\f":
            value = value[1:]
        if value.startswith(("=", ":")):
            value = value[1:]
        value = _unescape_property(value.lstrip(" \t\f"))
        if not value:
            return None
        sdk_dir = Path(value).expanduser()
        return sdk_dir if sdk_dir.is_absolute() else project_root / sdk_dir
    return None


def resolve_android_ndk(
    project_root: Path,
    ndk_version: str,
    env: Mapping[str, str] | None = None,
) -> Path:
    """Return <sdk>/ndk/<version> from local.properties or standard SDK env vars."""
    if not _VERSION_RE.fullmatch(ndk_version):
        raise ValueError(f"Invalid pinned Android NDK version: {ndk_version!r}")

    environment = env if env is not None else os.environ
    sdk_roots: list[Path] = []
    local_sdk = _sdk_dir_from_local_properties(project_root)
    if local_sdk is not None:
        sdk_roots.append(local_sdk)
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = environment.get(variable, "").strip()
        if value:
            sdk_roots.append(Path(value).expanduser())

    unique_roots: list[Path] = []
    seen: set[Path] = set()
    for sdk_root in sdk_roots:
        normalized = sdk_root.resolve()
        if normalized not in seen:
            seen.add(normalized)
            unique_roots.append(normalized)

    attempted = [sdk_root / "ndk" / ndk_version for sdk_root in unique_roots]
    for ndk_dir in attempted:
        if ndk_dir.is_dir() and (ndk_dir / "toolchains" / "llvm").is_dir():
            return ndk_dir

    if not unique_roots:
        raise FileNotFoundError(
            "Android SDK path unavailable: set ANDROID_HOME/ANDROID_SDK_ROOT or "
            "provide sdk.dir in the project local.properties"
        )
    tried = ", ".join(str(path) for path in attempted)
    raise FileNotFoundError(
        f"Pinned Android NDK {ndk_version} not found (or missing toolchains/llvm); "
        f"checked: {tried}"
    )


def main(arguments: list[str]) -> int:
    if len(arguments) != 2:
        print("usage: android_ndk_path.py <ndk-version> <project-root>", file=sys.stderr)
        return 2
    version, root = arguments
    try:
        print(resolve_android_ndk(Path(root), version))
    except (FileNotFoundError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
