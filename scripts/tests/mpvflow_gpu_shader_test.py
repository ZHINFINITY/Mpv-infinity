#!/usr/bin/env python3
"""Validate MPV Flow's Vulkan GLSL, descriptor bindings, and visibility policy."""
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


shader_names = (
    "shader_preprocess",
    "shader_luma",
    "shader_downsample",
    "shader_reduce_first",
    "shader_reduce",
    "shader_flow",
    "shader_synthesize",
)
shaders = {name: shader_source(name) for name in shader_names}
for name, shader in shaders.items():
    require(shader, "#version 450", f"{name} must target Vulkan GLSL 4.50")
    require(shader, "layout(push_constant) uniform FlowPush", f"{name} must use pl_pass push constants")
    assert "#version 310 es" not in shader and "precision highp" not in shader, f"{name} still uses GLES GLSL"

preprocess = shaders["shader_preprocess"]
require(preprocess, "layout(set=0,binding=0) uniform sampler2D inTex", "preprocess input binding mismatch")
require(preprocess, "layout(set=0,binding=1,rgba8) writeonly uniform image2D outTex", "preprocess output binding mismatch")
require(preprocess, "texture(inTex,uv)", "preprocess must keep scaling on the GPU")

flow = shaders["shader_flow"]
synthesis = shaders["shader_synthesize"]
require(flow, "float(best)/(64.0*255.0)", "flow vectors must retain normalized block-match error")
require(synthesis, "vec4 flowAtEdgeAware(vec2 p,int direction)", "synthesis must guide vector sampling by source appearance")
require(synthesis, "layout(set=0,binding=5,rgba16f) readonly uniform image2D luma0Tex;", "frame-0 luma binding/format mismatch")
require(synthesis, "layout(set=0,binding=6,rgba16f) readonly uniform image2D luma1Tex;", "frame-1 luma binding/format mismatch")
require(synthesis, "cycleError0=length(f.xy+backAt0.xy)", "forward warp must be checked against reverse flow at its endpoint")
require(synthesis, "cycleError1=length(b.xy+forwardAt1.xy)", "backward warp must be checked against forward flow at its endpoint")
require(synthesis, "pointInBounds(a,pc.cfg.xy)*pointInBounds(cycle0,pc.cfg.xy)", "out-of-frame endpoint samples must be rejected")
require(synthesis, "float confidence=max(confidence0,confidence1)", "visibility must retain the more reliable endpoint")
require(synthesis, "weight0=(1.0-pc.timestep)*visibility0,weight1=pc.timestep*visibility1", "visible endpoint weights must preserve temporal alpha")
require(synthesis, "return cycleQuality*matchQuality*uniqueQuality*valid;", "endpoint reliability must include match uniqueness")
require(flow, "float(second-best)/max(float(second),1.0)", "flow vectors must report best-vs-runner-up match uniqueness")
require(flow, "refineAxis(p,bestVector,ivec2(1,0)", "GPU flow vectors must be fractionally refined")
require(flow, "center=clamp(center,-p,sz-ivec2(8)-p)", "coarse predictors must be clamped before border-local refinement")
require(synthesis, "bool boundaryConflict=lumaDelta>0.12", "target-time synthesis must classify conflicting endpoint appearances")
require(synthesis, "if(boundaryConflict&&confidence0>confidence1+0.10)", "motion-boundary synthesis must select the more reliable visible source")
require(SOURCE, 'pl_find_named_fmt(gpu, "rgba16f")', "MPV Flow must use the supported RGBA16F storage path")
require(SOURCE, "gpu->glsl.vulkan", "MPV Flow must reject non-Vulkan compute devices")
require(SOURCE, "gpu->glsl.version < 450", "MPV Flow requires the Vulkan GLSL profile")
require(SOURCE, ".name = \"outTex\", .type = PL_DESC_STORAGE_IMG, .binding = 1", "luma output must use Vulkan binding 1")
require(SOURCE, '.name = "luma0Tex", .type = PL_DESC_STORAGE_IMG, .binding = 5', "frame-0 luma descriptor binding mismatch")
require(SOURCE, '.name = "luma1Tex", .type = PL_DESC_STORAGE_IMG, .binding = 6', "frame-1 luma descriptor binding mismatch")
require(SOURCE, '.name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 7', "synthesis output descriptor binding mismatch")
require(SOURCE, "make_pass(gpu, shader_synthesize, synth_desc, 8)", "synthesis pass descriptor count mismatch")
require(SOURCE, "mpvflow_gpu_prepare_input", "full-size shared frames need GPU downscaling before analysis")
assert "r32f" not in SOURCE.lower(), "MPV Flow must not require the optional R32F image format"
assert "glsl.gles" not in SOURCE and "#version 310 es" not in SOURCE, "MPV Flow must not require GLES compute"


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

validator = shutil.which("glslangValidator")
if validator:
    with tempfile.TemporaryDirectory(prefix="mpvflow-vulkan-shaders-") as temp_dir:
        temp_path = pathlib.Path(temp_dir)
        for name, shader in shaders.items():
            source_file = temp_path / f"{name}.comp"
            output_file = temp_path / f"{name}.spv"
            source_file.write_text(shader, encoding="utf-8")
            result = subprocess.run(
                [validator, "-V", "--target-env", "vulkan1.2", "-S", "comp", "-o", str(output_file), str(source_file)],
                check=False,
                capture_output=True,
                text=True,
            )
            if result.returncode:
                raise SystemExit(f"{name} Vulkan SPIR-V validation failed:\n{result.stdout}{result.stderr}")
            print(f"MPV Flow {name} Vulkan SPIR-V compiled")
else:
    print("glslangValidator unavailable; Vulkan GLSL compilation skipped")

print("MPV Flow Vulkan shader tests passed")
