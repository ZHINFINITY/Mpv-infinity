#!/usr/bin/env python3
"""Compile Media3 Flow's embedded compute shaders and guard its GPU contracts."""
from __future__ import annotations

import pathlib
import re
import shutil
import subprocess
import tempfile
import textwrap

ROOT = pathlib.Path(__file__).resolve().parents[2]
SINK = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt"
GEOMETRY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowGeometry.kt"
source = SINK.read_text(encoding="utf-8")
geometry = GEOMETRY.read_text(encoding="utf-8")

# Kotlin interpolates these numeric constants into the GLSL source at runtime.
subpixel = re.search(r"const val MEDIA3_FLOW_SUBPIXEL_MIN_CURVATURE = ([0-9.]+)f", geometry)
visibility_start = re.search(r"const val MEDIA3_FLOW_VISIBILITY_CONFIDENCE_START = ([0-9.]+)f", geometry)
visibility_end = re.search(r"const val MEDIA3_FLOW_VISIBILITY_CONFIDENCE_END = ([0-9.]+)f", geometry)
assert subpixel and visibility_start and visibility_end, "Media3 shader numeric constants were not found"
values = {
    "MEDIA3_FLOW_SUBPIXEL_MIN_CURVATURE": subpixel.group(1),
    "MEDIA3_FLOW_VISIBILITY_CONFIDENCE_START": visibility_start.group(1),
    "MEDIA3_FLOW_VISIBILITY_CONFIDENCE_END": visibility_end.group(1),
    "INTERFRAME_CHANGE_DIAGNOSTIC_LUMA_THRESHOLD": "0.01",
    "STATIC_BLEND_LUMA_DELTA_THRESHOLD": "0.002",
}

def shader_source(name: str) -> str:
    match = re.search(r"internal const val " + re.escape(name) + r' = """(.*?)"""', source, re.S)
    assert match, f"Embedded shader {name} was not found"
    shader = textwrap.dedent(match.group(1)).strip() + "\n"
    for key, value in values.items():
        shader = shader.replace("${" + key + "}", value)
    leftovers = re.findall(r"\$\{[^}]+\}", shader)
    assert not leftovers, f"Unresolved Kotlin shader interpolation in {name}: {leftovers}"
    return shader

luma = shader_source("LUMA_COMPUTE_SHADER")
flow = shader_source("FLOW_COMPUTE_SHADER")
synthesis = shader_source("SYNTH_COMPUTE_SHADER")

for fragment, message in (
    ("image2D uQuarterLuma", "luma pass must produce the quarter pyramid level"),
):
    assert fragment in luma, message
for fragment, message in (
    ("uPriorCellScale", "motion search must map each child cell into its parent grid"),
    ("float secondBest = 1e20", "motion search must retain a runner-up match"),
    ("vec4(refinedOffset, best, uniqueness)", "motion vectors must carry fractional displacement and ambiguity"),
):
    assert fragment in flow, message
for fragment, message in (
    ("uniqueQuality", "synthesis must use motion-match ambiguity"),
    ("targetBoundaryConflict", "synthesis must classify target-time contour conflicts"),
    ("confidence0 > confidence1 + 0.10", "synthesis must select the more reliable visible endpoint"),
    ("backwardAtForwardEndpoint", "cycle checks must examine forward-warp endpoint flow"),
):
    assert fragment in synthesis, message

validator = shutil.which("glslangValidator")
if not validator:
    raise SystemExit("glslangValidator is required for Media3 Flow shader validation")
for name, shader in (("luma", luma), ("flow", flow), ("synthesize", synthesis)):
    with tempfile.NamedTemporaryFile("w", suffix=".comp", encoding="utf-8") as file:
        file.write(shader)
        file.flush()
        result = subprocess.run([validator, "-S", "comp", file.name], capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(f"Media3 Flow {name} shader failed to compile:\n{result.stdout}{result.stderr}")
    print(f"Media3 Flow {name} compute shader compiled")
print("Media3 Flow GPU shader tests passed")
