#!/usr/bin/env python3
"""Validate MPV Flow's Vulkan GLSL, descriptor bindings, and visibility policy."""
from __future__ import annotations

import ast
import pathlib
import random
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
require(flow, "for(int ring=1;ring<=radius;ring++)", "motion search must prioritize candidates center-out without changing its configured radius")
require(flow, "if(max(abs(dx),abs(dy))!=ring) continue", "center-out rings must enumerate each Chebyshev-radius candidate exactly once")
require(flow, "if(any(lessThan(d,lo))||any(greaterThan(d,hi))) continue", "center-out search must retain edge-clipped bounds")
require(flow, "d.y<bestVector.y||(d.y==bestVector.y&&d.x<bestVector.x)", "out-of-order equal-cost winners must retain the legacy row-major tie-break")
require(flow, "sadAt(p,d,sz,best==0?0:second)", "zero-cost matches must prune only redundant SAD reads while still checking every candidate for an equally good match")
require(flow, "int radius=pc.cfg.z", "center-out must retain the original full configured motion radius")
require(flow, "float(best)/(64.0*255.0)", "flow vectors must retain normalized block-match error")
require(synthesis, "vec4 flowAtEdgeAware(vec2 p,int direction)", "synthesis must guide vector sampling by source appearance")
require(synthesis, "layout(set=0,binding=5,rgba16f) readonly uniform image2D luma0Tex;", "frame-0 luma binding/format mismatch")
require(synthesis, "layout(set=0,binding=6,rgba16f) readonly uniform image2D luma1Tex;", "frame-1 luma binding/format mismatch")
require(synthesis, "cycleError0=length(f.xy+backAt0.xy)", "forward warp must be checked against reverse flow at its endpoint")
require(synthesis, "cycleError1=length(b.xy+forwardAt1.xy)", "backward warp must be checked against forward flow at its endpoint")
require(synthesis, "pointInBounds(a,motionSize)*pointInBounds(cycle0,motionSize)", "out-of-frame motion endpoints must be rejected")
require(synthesis, "vec2(outSize)/vec2(motionSize)", "motion vectors must scale from the bounded analysis grid to source-resolution pixels")
require(synthesis, "colorAt(frame0,srcA)", "interpolated color must be sampled from the source-resolution input")
require(synthesis, "float confidence=max(confidence0,confidence1)", "visibility must retain the more reliable endpoint")
require(synthesis, "weight0=(1.0-pc.timestep)*visibility0,weight1=pc.timestep*visibility1", "visible endpoint weights must preserve temporal alpha")
require(synthesis, "return cycleQuality*matchQuality*uniqueQuality*valid;", "endpoint reliability must include match uniqueness")
require(synthesis, "float uniqueQuality=smoothstep(0.04,0.35,flow.w)", "ambiguous tied matches must not receive a fixed visibility-confidence floor")
require(synthesis, "if(confidence<0.15)", "unreliable motion must fall back to a real source frame")
assert "uniqueQuality=0.5+0.5*smoothstep" not in synthesis, "ambiguous motion must not be promoted to visible by a confidence floor"
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
require(SOURCE, "(w - FLOW_BLOCK + FLOW_STEP - 1) / FLOW_STEP + 1", "motion texture width must contain only valid, unique block origins")
require(SOURCE, "(h - FLOW_BLOCK + FLOW_STEP - 1) / FLOW_STEP + 1", "motion texture height must contain only valid, unique block origins")
assert "r32f" not in SOURCE.lower(), "MPV Flow must not require the optional R32F image format"
assert "glsl.gles" not in SOURCE and "#version 310 es" not in SOURCE, "MPV Flow must not require GLES compute"


def row_major_candidates(center_x: int, center_y: int, lo_x: int, hi_x: int, lo_y: int, hi_y: int):
    return [(x, y) for y in range(lo_y, hi_y + 1) for x in range(lo_x, hi_x + 1)]


def center_out_candidates(center_x: int, center_y: int, lo_x: int, hi_x: int, lo_y: int, hi_y: int, radius: int):
    candidates = [(center_x, center_y)]
    for ring in range(1, radius + 1):
        for dy in range(-ring, ring + 1):
            for dx in range(-ring, ring + 1):
                if max(abs(dx), abs(dy)) != ring:
                    continue
                point = (center_x + dx, center_y + dy)
                if lo_x <= point[0] <= hi_x and lo_y <= point[1] <= hi_y:
                    candidates.append(point)
    return candidates


def search_candidates(center: int, lower: int, upper: int, radius: int):
    low = max(lower, center - radius)
    high = min(upper, center + radius)
    return {center + delta for delta in range(low - center, high - center + 1)}


for radius in range(1, 9):
    for center in range(-12, 13):
        for lower, upper in ((-20, 20), (-5, 7), (0, 15)):
            exhaustive = {
                center + delta
                for delta in range(-8, 9)
                if abs(delta) <= radius and lower <= center + delta <= upper
            }
            assert exhaustive == search_candidates(center, lower, upper, radius), (
                "bounded loop must retain every in-frame candidate from the old masked loop"
            )


def block_search(src, dst, p, center, radius, center_out):
    height, width = len(src), len(src[0])
    lo_x = max(-p[0], center[0] - radius)
    hi_x = min(width - 8 - p[0], center[0] + radius)
    lo_y = max(-p[1], center[1] - radius)
    hi_y = min(height - 8 - p[1], center[1] + radius)
    best_vector = center
    best, reads = sad_at(src, dst, p, best_vector, 2**31 - 1)
    second = 2**31 - 1
    candidates = (
        center_out_candidates(*center, lo_x, hi_x, lo_y, hi_y, radius)
        if center_out else row_major_candidates(center[0], center[1], lo_x, hi_x, lo_y, hi_y)
    )
    for candidate in candidates:
        if candidate == center:
            continue
        cost, sample_reads = sad_at(src, dst, p, candidate, 0 if center_out and best == 0 else second)
        reads += sample_reads
        if center_out and best == 0:
            if cost == 0:
                second = 0
                if best_vector != center and (candidate[1], candidate[0]) < (best_vector[1], best_vector[0]):
                    best_vector = candidate
            elif second != 0:
                # With an integer, non-negative SAD and best==0, every positive
                # runner-up produces the same exact normalized uniqueness (1).
                second = 1
        elif cost < best:
            second, best, best_vector = best, cost, candidate
        elif cost == best:
            if second > cost:
                second = cost
            # The reference scans row-major and never replaces the center on a tie.
            if best_vector != center and (candidate[1], candidate[0]) < (best_vector[1], best_vector[0]):
                best_vector = candidate
        elif cost < second:
            second = cost
    confidence = max(0.0, min(1.0, (second - best) / max(second, 1)))
    return best_vector, best, confidence, reads


def sad_at(src, dst, p, displacement, cutoff):
    cost = 0
    reads = 0
    for y in range(8):
        for x in range(8):
            cost += abs(src[p[1] + y][p[0] + x] - dst[p[1] + displacement[1] + y][p[0] + displacement[0] + x])
            reads += 1
            if cost > cutoff:
                return cost, reads
    return cost, reads


def shifted(image, dx, dy):
    height, width = len(image), len(image[0])
    out = [[0 for _ in range(width)] for _ in range(height)]
    for y in range(height):
        for x in range(width):
            sx, sy = x - dx, y - dy
            if 0 <= sx < width and 0 <= sy < height:
                out[y][x] = image[sy][sx]
    return out


rng = random.Random(0xF10A)
random_texture = [[rng.randrange(256) for _ in range(32)] for _ in range(24)]
repetitive_texture = [[(255 if (x // 2) % 2 else 0) for x in range(32)] for _ in range(24)]
ambiguous_texture = [[127 for _ in range(32)] for _ in range(24)]
exact_cases = (
    (random_texture, shifted(random_texture, 3, -2), (12, 8), (2, -1), 8, "translated texture"),
    (random_texture, shifted(random_texture, -3, 2), (0, 0), (0, 0), 8, "clipped top-left border"),
    (repetitive_texture, shifted(repetitive_texture, 2, 0), (8, 8), (0, 0), 8, "repetitive stripes"),
    (ambiguous_texture, ambiguous_texture, (24, 16), (0, 0), 8, "ambiguous flat patch"),
)
optimized_reads = reference_reads = 0
for src, dst, p, center, radius, label in exact_cases:
    height, width = len(src), len(src[0])
    lo_x, hi_x = max(-p[0], center[0] - radius), min(width - 8 - p[0], center[0] + radius)
    lo_y, hi_y = max(-p[1], center[1] - radius), min(height - 8 - p[1], center[1] + radius)
    reference_set = set(row_major_candidates(center[0], center[1], lo_x, hi_x, lo_y, hi_y))
    optimized_order = center_out_candidates(center[0], center[1], lo_x, hi_x, lo_y, hi_y, radius)
    assert len(optimized_order) == len(set(optimized_order)), f"{label}: center-out order must not duplicate candidates"
    assert set(optimized_order) == reference_set, f"{label}: center-out order must retain the entire clipped candidate set"
    reference = block_search(src, dst, p, center, radius, center_out=False)
    optimized = block_search(src, dst, p, center, radius, center_out=True)
    assert optimized[:3] == reference[:3], f"{label}: vector, normalized best SAD and confidence must exactly match the row-major reference"
    reference_reads += reference[3]
    optimized_reads += optimized[3]

print(f"Exact center-out SAD regression: candidates and vectors match; pixel comparisons {reference_reads}->{optimized_reads}")


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

def smoothstep(edge0: float, edge1: float, value: float) -> float:
    t = max(0.0, min(1.0, (value - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)

flat_match_uniqueness = block_search(ambiguous_texture, ambiguous_texture, (24, 16), (0, 0), 8, center_out=True)[2]
assert flat_match_uniqueness == 0.0, "flat/tied blocks must be recognized as ambiguous"
assert smoothstep(0.04, 0.35, flat_match_uniqueness) == 0.0, "ambiguous motion must be excluded and use a source-frame fallback"
assert smoothstep(0.04, 0.35, 0.35) == 1.0, "distinctive motion matches must remain fully eligible"

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
