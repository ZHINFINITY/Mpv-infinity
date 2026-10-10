#!/usr/bin/env python3
"""Validate the embedded MPVFlow compute shader sources and bindings."""
from __future__ import annotations

import ast
import pathlib
import re
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE_PATH = ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c"
SOURCE = SOURCE_PATH.read_text(encoding="utf-8")


def shader_source(name: str) -> str:
    match = re.search(
        rf"static const char {re.escape(name)}\[\] =\n(.*?);\n",
        SOURCE,
        re.DOTALL,
    )
    assert match, f"missing embedded shader {name}"
    literals = re.findall(r'"(?:\\.|[^"\\])*"', match.group(1))
    assert literals, f"shader {name} has no C string literals"
    return "".join(ast.literal_eval(literal) for literal in literals)


def require(source: str, fragment: str, message: str) -> None:
    assert fragment in source, message


flow = shader_source("shader_flow")
synthesis = shader_source("shader_synthesize")

require(flow, "float(best)/(64.0*255.0)", "flow vectors must retain normalized block-match error")
require(synthesis, "vec4 flowAtEdgeAware(vec2 p,int direction)", "synthesis must guide vector sampling by source appearance")
require(synthesis, "layout(r32f) readonly uniform highp image2D luma0Tex;", "frame-0 luma must be available to synthesis")
require(synthesis, "layout(r32f) readonly uniform highp image2D luma1Tex;", "frame-1 luma must be available to synthesis")
require(synthesis, "cycleError0=length(f.xy+backAt0.xy)", "forward warp must be checked against reverse flow at its endpoint")
require(synthesis, "cycleError1=length(b.xy+forwardAt1.xy)", "backward warp must be checked against forward flow at its endpoint")
require(synthesis, "pointInBounds(a,cfg.xy)*pointInBounds(cycle0,cfg.xy)", "out-of-frame endpoint samples must be rejected")
require(synthesis, "float confidence=max(confidence0,confidence1)", "visibility must retain the more reliable endpoint")
require(synthesis, "weight0=(1.0-timestep)*visibility0,weight1=timestep*visibility1", "visible endpoint weights must preserve temporal alpha")
require(synthesis, "return cycleQuality*matchQuality*valid;", "endpoint reliability must not share a photometric occlusion penalty")
require(SOURCE, '.name = "luma0Tex", .type = PL_DESC_STORAGE_IMG, .binding = 3', "frame-0 luma descriptor binding mismatch")
require(SOURCE, '.name = "luma1Tex", .type = PL_DESC_STORAGE_IMG, .binding = 4', "frame-1 luma descriptor binding mismatch")
require(SOURCE, '.name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 5', "synthesis output descriptor binding mismatch")
require(SOURCE, "make_pass(gpu, shader_synthesize, synth_desc, 8)", "synthesis pass descriptor count mismatch")
require(SOURCE, "run_pass(ctx, ctx->pass_synthesize, sb, 8, cfg, t, pair->w, pair->h)", "synthesis binding count mismatch")

validator = shutil.which("glslangValidator")
if validator:
    for name, shader in (("flow", flow), ("synthesize", synthesis)):
        with tempfile.NamedTemporaryFile("w", suffix=".comp", encoding="utf-8") as source_file:
            source_file.write(shader)
            source_file.flush()
            result = subprocess.run(
                [validator, "-S", "comp", source_file.name],
                check=False,
                capture_output=True,
                text=True,
            )
        if result.returncode:
            raise SystemExit(f"{name} shader validation failed:\n{result.stdout}{result.stderr}")
        print(f"MPVFlow {name} compute shader compiled")
else:
    print("glslangValidator unavailable; embedded shader contract checks passed (GLSL compilation skipped)")

print("MPVFlow GPU shader tests passed")
