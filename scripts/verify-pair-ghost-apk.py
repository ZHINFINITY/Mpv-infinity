#!/usr/bin/env python3
"""Verify Native Media3 pair-ghost policy and MPV FongMi MEMC in an APK."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

MPV_LIBRARY = "lib/arm64-v8a/libmpv.so"
NATIVE_LIBRARY = "lib/arm64-v8a/libmedia3flow_vulkan.so"
FONGMI_MEMC_OPTION = b"motion-interpolation"
PAIR_GHOST_MARKERS = (
    b"MPVFLOW_PAIR_GHOST_SPATIAL_GAP_V3",
    b"scene.r>=.003&&weak>=.04&&weak<=.25&&supported>=.008",
    b"counts/float(sz.x*sz.y)",
    b"weak>=.10&&bad>=.01",
    b"bool pairGhost=(pc.cfg.x&0x40000000)==0",
    b"pair_ghost_scene_merge",
)


def verify_apk(apk: Path) -> dict:
    with zipfile.ZipFile(apk) as archive:
        try:
            mpv_data = archive.read(MPV_LIBRARY)
            native_data = archive.read(NATIVE_LIBRARY)
        except KeyError as error:
            raise AssertionError("pair-ghost check missing packaged library: " + error.args[0]) from error

    if FONGMI_MEMC_OPTION not in mpv_data:
        raise AssertionError(f"{MPV_LIBRARY} lacks the FongMi MEMC option marker")
    for marker in PAIR_GHOST_MARKERS:
        if marker not in native_data:
            raise AssertionError(f"{NATIVE_LIBRARY} lacks pair policy evidence: {marker.decode()}")

    records = [
        {
            "library": MPV_LIBRARY,
            "sha256": hashlib.sha256(mpv_data).hexdigest(),
            "bytes": len(mpv_data),
            "verified_markers": [FONGMI_MEMC_OPTION.decode("ascii")],
        },
        {
            "library": NATIVE_LIBRARY,
            "sha256": hashlib.sha256(native_data).hexdigest(),
            "bytes": len(native_data),
            "verified_markers": [marker.decode() for marker in PAIR_GHOST_MARKERS],
        },
    ]
    return {
        "experiment": "native-pair-ghost-v3",
        "apk": apk.name,
        "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "libraries": records,
        "limits": "Packaging proof only; MPV FongMi MEMC and Native pair-policy checks are separate, not phone visual/performance proof.",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    report = verify_apk(args.apk)
    destination = args.apk.parent / "pair-ghost-verification.json"
    destination.write_text(json.dumps(report, indent=2) + "\n")
    print("Native pair-ghost policy and MPV FongMi MEMC verified in separate arm64 libraries")
    print(destination)


if __name__ == "__main__":
    main()
