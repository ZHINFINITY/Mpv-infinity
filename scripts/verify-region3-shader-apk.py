#!/usr/bin/env python3
"""Verify the Native Region3 shader and MPV FongMi MEMC marker in an APK."""
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MPV_LIBRARY = "lib/arm64-v8a/libmpv.so"
NATIVE_LIBRARY = "lib/arm64-v8a/libmedia3flow_vulkan.so"
FONGMI_MEMC_OPTION = b"motion-interpolation"


def region3_shader(root: Path = ROOT) -> bytes:
    source = (root / "app/src/main/cpp/mpvflow/mpvflow_gpu.c").read_text(encoding="utf-8")
    match = re.search(r"static const char shader_synthesize\[\] =\n(.*?);\n", source, re.S)
    if match is None:
        raise AssertionError("MPVFlow shared synthesis shader source was not found")
    shader = "".join(
        ast.literal_eval(part)
        for part in re.findall(r'"(?:\\.|[^"\\])*"', match[1])
    ).encode()
    if b"if(!softBlend) boundaryTrust*=region3LocalTrust*region3TransitionTrust" not in shader:
        raise AssertionError("Native Region3 shader source is missing its expected boundary-trust rule")
    return shader


def verify_apk(apk: Path, root: Path = ROOT) -> dict:
    shader = region3_shader(root)
    with zipfile.ZipFile(apk) as archive:
        try:
            mpv_data = archive.read(MPV_LIBRARY)
            native_data = archive.read(NATIVE_LIBRARY)
        except KeyError as error:
            raise AssertionError(f"APK is missing required native library: {error.args[0]}") from error

    if FONGMI_MEMC_OPTION not in mpv_data:
        raise AssertionError(f"{MPV_LIBRARY} does not contain the FongMi MEMC option marker")
    if shader not in native_data:
        raise AssertionError(f"{NATIVE_LIBRARY} does not contain the exact Native Region3 synthesis shader")

    return {
        "apk": apk.name,
        "mpv_library": MPV_LIBRARY,
        "mpv_memc_marker": FONGMI_MEMC_OPTION.decode("ascii"),
        "native_library": NATIVE_LIBRARY,
        "shader_sha256": hashlib.sha256(shader).hexdigest(),
        "shader_bytes": len(shader),
        "verified_libraries": [MPV_LIBRARY, NATIVE_LIBRARY],
        "commit": os.environ.get("GITHUB_SHA"),
        "claim": "MPV FongMi MEMC option and Native Region3 shader embedding verified; no phone visual/performance claim.",
    }


def main() -> None:
    apk = Path(sys.argv[1])
    result = verify_apk(apk)
    with apk.open("rb") as stream:
        result["apk_sha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
    print(json.dumps(result, indent=2))
    (apk.parent / "region3-shader-verification.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
