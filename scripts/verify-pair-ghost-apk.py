#!/usr/bin/env python3
"""Verify the experimental pair policy is actually packaged for MPV and Media3."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import zipfile
MARKERS = (
    b"MPVFLOW_PAIR_GHOST_EXPERIMENT_V1",
    b"counts/float(sz.x*sz.y)",
    b"weak>=.55&&bad>=.25",
    b"bool pairGhost=(pc.cfg.x&0x40000000)==0",
    b"pair_ghost_scene_merge",
)
LIBS = ("lib/arm64-v8a/libmpv.so", "lib/arm64-v8a/libmedia3flow_vulkan.so")
def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", type=Path)
    args = ap.parse_args()
    records = []
    with zipfile.ZipFile(args.apk) as z:
        for name in LIBS:
            if name not in z.namelist():
                raise SystemExit("pair-ghost check missing packaged library: " + name)
            data = z.read(name)
            for marker in MARKERS:
                if marker not in data:
                    raise SystemExit(f"{name} lacks pair policy evidence: {marker.decode()}")
            records.append({"library": name, "sha256": hashlib.sha256(data).hexdigest(),
                            "bytes": len(data), "verified_markers": [m.decode() for m in MARKERS]})
    report = {"experiment": "pair-ghost-v1", "apk": args.apk.name,
              "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "libraries": records,
              "limits": "Packaging proof only; not Poco performance or visual proof."}
    dest = args.apk.parent / "pair-ghost-verification.json"
    dest.write_text(json.dumps(report, indent=2) + "\n")
    print("Pair-wide ghost policy verified in MPV and Media3 arm64 native libraries")
    print(dest)
if __name__ == "__main__":
    main()
