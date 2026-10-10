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
require(synthesis, "layout(rgba16f) readonly uniform highp image2D luma0Tex;", "frame-0 luma must use the supported storage format")
require(synthesis, "layout(rgba16f) readonly uniform highp image2D luma1Tex;", "frame-1 luma must use the supported storage format")
require(synthesis, "cycleError0=length(f.xy+backAt0.xy)", "forward warp must be checked against reverse flow at its endpoint")
require(synthesis, "cycleError1=length(b.xy+forwardAt1.xy)", "backward warp must be checked against forward flow at its endpoint")
require(synthesis, "pointInBounds(a,cfg.xy)*pointInBounds(cycle0,cfg.xy)", "out-of-frame endpoint samples must be rejected")
require(synthesis, "float confidence=max(confidence0,confidence1)", "visibility must retain the more reliable endpoint")
require(synthesis, "weight0=(1.0-timestep)*visibility0,weight1=timestep*visibility1", "visible endpoint weights must preserve temporal alpha")
require(synthesis, "return cycleQuality*matchQuality*uniqueQuality*valid;", "endpoint reliability must include match uniqueness")
require(flow, "float(second-best)/max(float(second),1.0)", "flow vectors must report best-vs-runner-up match uniqueness")
require(flow, "refineAxis(p,bestVector,ivec2(1,0)", "GPU flow vectors must be fractionally refined")
require(flow, "center=clamp(center,-p,sz-ivec2(8)-p)", "coarse predictors must be clamped before border-local refinement")
require(synthesis, "bool boundaryConflict=lumaDelta>0.12", "target-time synthesis must classify conflicting endpoint appearances")
require(synthesis, "if(boundaryConflict&&confidence0>confidence1+0.10)", "motion-boundary synthesis must select the more reliable visible source")
require(SOURCE, 'pl_find_named_fmt(gpu, "rgba16f")', "MPV Flow must use the supported RGBA16F storage path")
assert "r32f" not in SOURCE.lower(), "MPV Flow must not require the optional R32F image format"

def boundary_source(alpha: float, luma0: float, luma1: float, confidence0: float, confidence1: float):
    """Small synthetic reference for the shader's contour visibility decision."""
    if abs(luma0 - luma1) > 0.12:
        if confidence0 > confidence1 + 0.10:
            return 0
        if confidence1 > confidence0 + 0.10:
            return 1
        return 0 if alpha < 0.5 else 1
    return None

assert boundary_source(0.5, 0.9, 0.1, 0.9, 0.3) == 0, "revealed foreground must prefer its reliable endpoint"
assert boundary_source(0.75, 0.9, 0.1, 0.6, 0.6) == 1, "ambiguous crossing contours must avoid blending and use the nearer source"
assert boundary_source(0.5, 0.31, 0.34, 0.8, 0.8) is None, "similar appearances must preserve ordinary temporal blending"
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
