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
require(flow, "for(int ring=1;ring<=radius&&!stopOnCenterTie;ring++)", "motion search must stop only after an exact zero-cost tie makes later candidates irrelevant")
require(flow, "if(max(abs(dx),abs(dy))!=ring) continue", "center-out rings must enumerate each Chebyshev-radius candidate exactly once")
require(flow, "if(any(lessThan(d,lo))||any(greaterThan(d,hi))) continue", "center-out search must retain edge-clipped bounds")
require(flow, "d.y<bestVector.y||(d.y==bestVector.y&&d.x<bestVector.x)", "out-of-order equal-cost winners must retain the legacy row-major tie-break")
require(flow, "sadAt(p,d,sz,best==0?0:second,blockSize)", "zero-cost matches must keep exact SAD pruning while checking for a real tie")
require(flow, "if(best==0&&second==0){ if(all(equal(bestVector,center))){stopOnCenterTie=true;continue;}", "a tied center winner must terminate search without changing its vector or confidence")
require(flow, "d.y>bestVector.y||(d.y==bestVector.y&&d.x>=bestVector.x)", "once a non-center zero-cost tie is known, only lexicographically earlier vectors may affect the legacy tie-break")
require(flow, "int radius=pc.cfg.z", "center-out must retain the original full configured motion radius")
require(flow, "int blockSize=(pc.cfg.w>>8)", "motion search must distinguish fine and coarse block support")
require(flow, "if(blockSize!=4) blockSize=8", "motion search must constrain support to the tested 4x4 or 8x8 sizes")
require(flow, "float(best)/(float(blockSize*blockSize)*255.0)", "flow vectors must normalize SAD by the active block area")
require(synthesis, "vec4 flowAtEdgeAware(vec2 p,int direction)", "synthesis must guide vector sampling by source appearance")
require(synthesis, "vec4 flowAtBilinear(vec2 p,int direction)", "iterative vector refinement must have a low-cost bilinear sampler")
require(synthesis, "bool staticPixel=", "static low-motion pixels should bypass the full-resolution warp path")
require(flow, "layout(set=0,binding=4,rgba16f) readonly uniform image2D sceneTex;", "Vulkan motion search must consume the reduced scene delta")
require(flow, "if(imageLoad(sceneTex,ivec2(0)).r>0.1882353){ imageStore(flowTex,g,vec4(0.0,0.0,1.0,0.0)); return; }", "Vulkan scene cuts must initialize a no-confidence flow field before skipping SAD search")
require(synthesis, "if(cut>0.1882353)", "Vulkan synthesis must use the same scene-cut threshold as the motion-search early-out")
require(SOURCE, "ctx->pass_flow = make_pass(gpu, shader_flow, flow_desc, 5);", "Vulkan flow pass must expose all five descriptors")
require(SOURCE, "{ .object = flow }, { .object = scene_delta },", "Vulkan motion dispatch must bind the reduced scene-delta texture")
opencl_source = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_opencl.c").read_text(encoding="utf-8")
require(opencl_source, "if(scene_delta[0]>0.1882353f){out[i]=(float4)(0.0f,0.0f,1.0f,0.0f);return;}", "OpenCL scene cuts must initialize a no-confidence flow field before skipping SAD search")
require(opencl_source, "if(scene_delta[0]>0.1882353f)", "OpenCL motion search and synthesis must share the same scene-cut threshold")
require(opencl_source, "SET_ARG(c->estimate_kernel, 9, p->scene_delta);\n    SET_ARG(c->estimate_kernel, 10, out);", "OpenCL scene delta and flow output kernel arguments must remain correctly ordered")
require(opencl_source, "float static_luma_delta=", "OpenCL synthesis must gate static pixels before warping")
require(opencl_source, "static_out=convert_uchar4_sat_rte", "OpenCL synthesis must preserve static pixels instead of blending warped neighbors")
require(opencl_source, "float chroma_delta=max(fabs((ca.x-ca.y)-(cb.x-cb.y)),fabs((ca.z-ca.y)-(cb.z-cb.y)))/255.0f", "OpenCL synthesis must measure chroma disagreement between warped source samples")
require(opencl_source, "int chroma_boundary=color_delta>0.12f&&chroma_delta>0.06f", "OpenCL must reject strong chroma-only contour mixing")
require(synthesis, "float cycleQuality=1.0-smoothstep(0.75,4.0,cycle); float matchQuality=1.0-smoothstep(0.025,0.22,flow.z); float uniquenessQuality=smoothstep(0.08,0.30,flow.w)", "Vulkan reliability must combine cycle, residual, and uniqueness confidence")
require(opencl_source, "float cycle_quality=1.0f-smoothstep(0.75f,4.0f,cycle); float match_quality=1.0f-smoothstep(0.025f,0.22f,flow.z); float uniqueness_quality=smoothstep(0.08f,0.30f,flow.w)", "OpenCL reliability must match Vulkan cycle, residual, and uniqueness confidence")
require(opencl_source, "if(confidence<0.28f)", "OpenCL must use Vulkan's conservative low-confidence source-frame fallback")
require(opencl_source, "inline float neighborhood_quality(", "OpenCL must mirror Vulkan local flow-consistency checks")
require(opencl_source, "int neighborhood_conflict=(min(neighborhood0,neighborhood1)<0.30f&&(luma_delta>0.06f||color_delta>0.08f))||(flow_spread>3.0f&&min(neighborhood0,neighborhood1)<0.45f)", "OpenCL neighborhood/flow-spread rejection must match Vulkan")
require(opencl_source, "int boundary_conflict=neighborhood_conflict||luma_delta>0.08f||chroma_boundary||(min(f.w,q.w)<0.08f&&color_delta>0.10f)", "OpenCL must match Vulkan luma, chroma, and ambiguity boundary thresholds")
require(opencl_source, "float4 f=flow_at_bilinear(forward,backward,0,aw,ah,flow_p.x,flow_p.y),q=flow_at_bilinear(forward,backward,1,aw,ah,flow_p.x,flow_p.y)", "OpenCL synthesis must use Vulkan's cheaper bilinear initial-flow sampling")
require(opencl_source, "f=flow_at_edge_aware(forward,backward,luma0,luma1,0,aw,ah,pa.x,pa.y)", "OpenCL must retain final edge-aware refinement after the bilinear fast path")
require(opencl_source, "parent_residual=q.z; parent_uniqueness=q.w", "OpenCL finer levels must retain the coarse SAD and uniqueness signals")
require(opencl_source, "if(has_coarse&&parent_residual>0.12f&&parent_uniqueness>0.0005f)search_radius=min(radius*2,16)", "OpenCL must use the same gated search-expansion policy")
require(synthesis, "dot(fastForward.xy,fastForward.xy)<=0.0025", "the static path must require a near-zero forward vector")
require(synthesis, "dot(fastBackward.xy,fastBackward.xy)<=0.0025", "the static path must require a near-zero backward vector")
require(synthesis, "colorDelta<=0.0039216", "the static path must require an at-most-one-LSB source difference")
require(synthesis, "for(int i=0;i<2;i++){ f=flowAtBilinear(a,0)", "inverse-flow refinement must avoid repeated edge-aware sampling")
require(synthesis, "b=flowAtBilinear(z,1)", "backward inverse-flow refinement must use the same efficient sampler")
require(synthesis, "layout(set=0,binding=5,rgba16f) readonly uniform image2D luma0Tex;", "frame-0 luma binding/format mismatch")
require(synthesis, "layout(set=0,binding=6,rgba16f) readonly uniform image2D luma1Tex;", "frame-1 luma binding/format mismatch")
require(synthesis, "cycleError0=length(f.xy+backAt0.xy)", "forward warp must be checked against reverse flow at its endpoint")
require(synthesis, "cycleError1=length(b.xy+forwardAt1.xy)", "backward warp must be checked against forward flow at its endpoint")
require(synthesis, "pointInBounds(a,motionSize)*pointInBounds(cycle0,motionSize)", "out-of-frame motion endpoints must be rejected")
require(synthesis, "vec2(outSize)/vec2(motionSize)", "motion vectors must scale from the bounded analysis grid to source-resolution pixels")
require(synthesis, "colorAt(frame0,srcA)", "interpolated color must be sampled from the source-resolution input")
require(synthesis, "float confidence=max(confidence0,confidence1)", "visibility must retain the more reliable endpoint")
require(synthesis, "weight0=(1.0-pc.timestep)*visibility0,weight1=pc.timestep*visibility1", "visible endpoint weights must preserve temporal alpha")
require(synthesis, "float uniquenessQuality=smoothstep(0.08,0.30,flow.w)", "endpoint reliability must reject ambiguous motion vectors")
require(synthesis, "return cycleQuality*matchQuality*uniquenessQuality*valid;", "endpoint reliability must include motion uniqueness")
require(synthesis, "if(confidence<0.28)", "unreliable motion must fall back to a real source frame")
require(flow, "float(second-best)/max(float(second),1.0)", "flow vectors must report best-vs-runner-up match uniqueness")
require(flow, "refineAxis(p,bestVector,ivec2(1,0)", "GPU flow vectors must be fractionally refined")
require(flow, "center=clamp(center,-p,sz-block-p)", "coarse predictors must be clamped to the active block before border-local refinement")
require(flow, "bool hasParent=(pc.cfg.w&1)!=0", "the encoded block support must not be confused with the coarse-parent flag")
require(flow, "parentResidual=parentFlow.b; parentUniqueness=parentFlow.a", "finer Vulkan levels must retain the coarse SAD and uniqueness signals")
require(flow, "if(hasParent&&parentResidual>0.12&&parentUniqueness>0.0005)radius=min(radius*2,16)", "only a poor but non-tied parent may double the fine-level search")
require(synthesis, "bool neighborhoodConflict=", "synthesis must detect local motion disagreement")
require(synthesis, "float chromaDelta=max(abs((ca.r-ca.g)-(cb.r-cb.g)),abs((ca.b-ca.g)-(cb.b-cb.g)))", "Vulkan synthesis must measure chroma disagreement between warped source samples")
require(synthesis, "bool chromaBoundary=colorDelta>0.12&&chromaDelta>0.06", "Vulkan synthesis must reject strong chroma-only contour mixing")
require(synthesis, "bool boundaryConflict=neighborhoodConflict||lumaDelta>0.08||chromaBoundary", "motion and color boundaries must select a source sample instead of blending across regions")
require(synthesis, "edgeConfidence0=confidence0*(0.5+0.5*smoothstep(0.02,0.35,f.w))", "edge confidence must still use match uniqueness for boundary conflict resolution")
require(synthesis, "backAt0=flowAtEdgeAware(cycle0,1)", "cycle-consistency verification must retain edge-aware sampling")
require(synthesis, "forwardAt1=flowAtEdgeAware(cycle1,0)", "reverse cycle-consistency verification must retain edge-aware sampling")
assert synthesis.split("void main()", 1)[1].count("flowAtEdgeAware(") == 4, "edge-aware sampling should remain at final warps and cycle checks, not every refinement step"
assert synthesis.split("void main()", 1)[1].count("flowAtBilinear(") == 6, "static probes, initial warp, and inverse-flow refinements should use the bounded bilinear sampler"
color_at_match = re.search(r"vec4 colorAt\(sampler2D s,vec2 p\)\{(.*?)\}", synthesis)
assert color_at_match, "synthesis must provide the source-color sampler"
color_at = color_at_match.group(1)
require(color_at, "p=clamp(p,vec2(0.0),vec2(sz-1))", "source sampling must keep the old clamp-to-edge boundary behavior")
require(color_at, "textureLod(s,(p+vec2(0.5))/vec2(sz),0.0)", "source colors should use exact pixel-center mapping with hardware bilinear filtering")
assert "texelFetch" not in color_at, "warped source colors should not perform four explicit fetches per source frame"

def manual_bilinear(image, x, y):
    height, width = len(image), len(image[0])
    x = min(max(x, 0.0), width - 1.0)
    y = min(max(y, 0.0), height - 1.0)
    x0, y0 = int(x), int(y)
    x1, y1 = min(x0 + 1, width - 1), min(y0 + 1, height - 1)
    tx, ty = x - x0, y - y0
    return tuple(
        (image[y0][x0][channel] * (1.0 - tx) + image[y0][x1][channel] * tx) * (1.0 - ty)
        + (image[y1][x0][channel] * (1.0 - tx) + image[y1][x1][channel] * tx) * ty
        for channel in range(4)
    )

def linear_sampler_bilinear(image, x, y):
    height, width = len(image), len(image[0])
    x = min(max(x, 0.0), width - 1.0)
    y = min(max(y, 0.0), height - 1.0)
    u, v = (x + 0.5) / width, (y + 0.5) / height
    return manual_bilinear(image, u * width - 0.5, v * height - 0.5)

sampling_rng = random.Random(0xB11A)
for height, width in ((1, 1), (2, 3), (7, 11)):
    pixels = [
        [tuple(sampling_rng.randrange(256) / 255.0 for _ in range(4)) for _ in range(width)]
        for _ in range(height)
    ]
    coordinates = [(-2.25, 0.75), (0.0, 0.0), (width - 1.0, height - 1.0),
                   (width + 1.5, height + 2.0)]
    coordinates.extend(
        (sampling_rng.uniform(-2.0, width + 2.0), sampling_rng.uniform(-2.0, height + 2.0))
        for _ in range(24)
    )
    for x, y in coordinates:
        manual = manual_bilinear(pixels, x, y)
        filtered = linear_sampler_bilinear(pixels, x, y)
        assert max(abs(a - b) for a, b in zip(manual, filtered)) < 1e-12, (
            f"hardware pixel-center mapping differs from the existing manual bilinear sample at {(width, height, x, y)}"
        )

require(SOURCE, 'pl_find_named_fmt(gpu, "rgba16f")', "MPV Flow must use the supported RGBA16F storage path")
require(SOURCE, "gpu->glsl.vulkan", "MPV Flow must reject non-Vulkan compute devices")
require(SOURCE, "gpu->glsl.version < 450", "MPV Flow requires the Vulkan GLSL profile")
require(SOURCE, ".name = \"outTex\", .type = PL_DESC_STORAGE_IMG, .binding = 1", "luma output must use Vulkan binding 1")
require(SOURCE, '.name = "luma0Tex", .type = PL_DESC_STORAGE_IMG, .binding = 5', "frame-0 luma descriptor binding mismatch")
require(SOURCE, '.name = "luma1Tex", .type = PL_DESC_STORAGE_IMG, .binding = 6', "frame-1 luma descriptor binding mismatch")
require(SOURCE, '.name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 7', "synthesis output descriptor binding mismatch")
require(SOURCE, "make_pass(gpu, shader_synthesize, synth_desc, 8)", "synthesis pass descriptor count mismatch")
synthesis_bindings = SOURCE.split("struct pl_desc_binding sb[8] = {", 1)[1].split("};", 1)[0]
for source in ("source0", "source1"):
    require(synthesis_bindings,
            f".object = {source}, .address_mode = PL_TEX_ADDRESS_CLAMP,\n          .sample_mode = PL_TEX_SAMPLE_LINEAR",
            f"{source} must use clamp-to-edge linear filtering for the exact hardware bilinear sampler")
require(SOURCE, "!(p0->format->caps & PL_FMT_CAP_LINEAR)", "synthesis must reject formats that cannot safely use linear filtering")
require(SOURCE, "!(p1->format->caps & PL_FMT_CAP_LINEAR)", "both synthesis inputs must support linear filtering")
require(SOURCE, "mpvflow_gpu_prepare_input", "full-size shared frames need GPU downscaling before analysis")
require(SOURCE, "Vulkan fast path: bilinear initial/cycle flow", "Vulkan synthesis must avoid redundant edge-aware exponential lookups")
require(SOURCE, "const int grid_w = (w - block_size + FLOW_STEP - 1) / FLOW_STEP + 1", "motion texture width must contain only valid, unique per-level block origins")
require(SOURCE, "const int grid_h = (h - block_size + FLOW_STEP - 1) / FLOW_STEP + 1", "motion texture height must contain only valid, unique per-level block origins")
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


def block_search(src, dst, p, center, radius, center_out, prune_ambiguous_zero=False, block_size=8):
    height, width = len(src), len(src[0])
    lo_x = max(-p[0], center[0] - radius)
    hi_x = min(width - block_size - p[0], center[0] + radius)
    lo_y = max(-p[1], center[1] - radius)
    hi_y = min(height - block_size - p[1], center[1] + radius)
    best_vector = center
    best, reads = sad_at(src, dst, p, best_vector, 2**31 - 1, block_size)
    second = 2**31 - 1
    candidates = (
        center_out_candidates(*center, lo_x, hi_x, lo_y, hi_y, radius)
        if center_out else row_major_candidates(center[0], center[1], lo_x, hi_x, lo_y, hi_y)
    )
    for candidate in candidates:
        if candidate == center:
            continue
        if prune_ambiguous_zero and best == 0 and second == 0:
            if best_vector == center:
                break
            if (candidate[1], candidate[0]) >= (best_vector[1], best_vector[0]):
                continue
        cost, sample_reads = sad_at(
            src, dst, p, candidate, 0 if center_out and best == 0 else second, block_size
        )
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


def sad_at(src, dst, p, displacement, cutoff, block_size=8):
    cost = 0
    reads = 0
    for y in range(block_size):
        for x in range(block_size):
            cost += abs(src[p[1] + y][p[0] + x] - dst[p[1] + displacement[1] + y][p[0] + displacement[0] + x])
            reads += 1
            if cost > cutoff:
                return cost, reads
    return cost, reads


def confidence_guided_radius(base_radius, parent_residual, parent_uniqueness):
    if parent_residual > 0.12 and parent_uniqueness > 0.0005:
        return min(base_radius * 2, 16)
    return base_radius


edge_rng = random.Random(0xED6E)
edge_background = [[edge_rng.randrange(120) for _ in range(32)] for _ in range(24)]
edge_frame0 = [row[:] for row in edge_background]
edge_frame1 = [row[:] for row in edge_background]
for y in range(8, 10):
    for x in range(8, 10):
        edge_frame0[y][x] = 255
    for x in range(9, 11):
        edge_frame1[y][x] = 255
coarse_edge_match = block_search(edge_frame0, edge_frame1, (8, 8), (0, 0), 2, True, block_size=8)
fine_edge_match = block_search(edge_frame0, edge_frame1, (8, 8), (0, 0), 2, True, block_size=4)
assert coarse_edge_match[0] == (0, 0), "the regression scene must expose a background-dominated 8x8 match"
assert fine_edge_match[0] == (1, 0), "4x4 support should recover the one-pixel foreground translation"
print("Fine-level motion support regression: a thin moving foreground selects its own vector")


def static_pixel_bypass(forward, backward, color0, color1):
    color_delta = max(abs(a - b) for a, b in zip(color0, color1))
    return (
        sum(value * value for value in forward) <= 0.0025
        and sum(value * value for value in backward) <= 0.0025
        and color_delta <= 1.0 / 255.0
    )


assert static_pixel_bypass((0.0, 0.0), (0.0, 0.0), (0.4, 0.5, 0.6), (0.4, 0.5, 0.6)), "identical static pixels should be safe for direct temporal blending"
assert static_pixel_bypass((0.04, 0.0), (0.03, 0.0), (0.4, 0.5, 0.6), (0.4, 0.5, 0.6)), "sub-threshold motion remains eligible for the fast path"
assert not static_pixel_bypass((1.0, 0.0), (-1.0, 0.0), (0.4, 0.5, 0.6), (0.4, 0.5, 0.6)), "moving edges must retain full bidirectional warping"
assert not static_pixel_bypass((0.0, 0.0), (0.0, 0.0), (0.4, 0.5, 0.6), (0.4, 0.51, 0.6)), "chroma or luma changes above one LSB must not use the fast path"
print("Static-pixel fast-path regression: exact still regions bypass warping without masking motion or color changes")


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
case_rng = random.Random(0xA11CE)
randomized_cases = []
for index in range(24):
    dx, dy = case_rng.randrange(-4, 5), case_rng.randrange(-4, 5)
    px, py = case_rng.randrange(25), case_rng.randrange(17)
    cx = min(max(case_rng.randrange(-3, 4), -px), len(random_texture[0]) - 8 - px)
    cy = min(max(case_rng.randrange(-3, 4), -py), len(random_texture) - 8 - py)
    randomized_cases.append(
        (
            random_texture,
            shifted(random_texture, dx, dy),
            (px, py),
            (cx, cy),
            4,
            f"seeded random translated block {index}",
        )
    )
exact_cases += tuple(randomized_cases)

adaptive_rng = random.Random(94127)
adaptive_image = [[adaptive_rng.randrange(256) for _ in range(64)] for _ in range(48)]
adaptive_origin = (24, 16)
for dx, dy in ((9, 0), (12, 3)):
    adaptive_target = shifted(adaptive_image, dx, dy)
    parent = block_search(adaptive_image, adaptive_target, adaptive_origin, (0, 0), 8, True, prune_ambiguous_zero=True)
    parent_residual = parent[1] / (8 * 8 * 255.0)
    radius = confidence_guided_radius(8, parent_residual, parent[2])
    baseline = block_search(adaptive_image, adaptive_target, adaptive_origin, parent[0], 8, True, prune_ambiguous_zero=True)
    refined = block_search(adaptive_image, adaptive_target, adaptive_origin, parent[0], radius, True, prune_ambiguous_zero=True)
    assert radius == 16 and baseline[0] != (dx, dy), f"translation {(dx, dy)} must exercise recovery outside the base radius"
    assert refined[0] == (dx, dy) and refined[1] == 0, f"confidence-guided expansion must recover exact translation {(dx, dy)}"

flat_source = [[127 for _ in range(64)] for _ in range(48)]
flat_target = [[0 for _ in range(64)] for _ in range(48)]
ambiguous_parent = block_search(flat_source, flat_target, adaptive_origin, (0, 0), 8, True, prune_ambiguous_zero=True)
assert confidence_guided_radius(8, ambiguous_parent[1] / (8 * 8 * 255.0), ambiguous_parent[2]) == 8, "a high-residual but exactly tied parent must not spend work on a wider ambiguous search"
clear_parent = block_search(adaptive_image, adaptive_image, adaptive_origin, (0, 0), 8, True, prune_ambiguous_zero=True)
assert confidence_guided_radius(8, clear_parent[1] / (8 * 8 * 255.0), clear_parent[2]) == 8, "a reliable parent must retain the base search radius"
print("Confidence-guided pyramid regression: poor distinct matches widen only the local search; tied and reliable parents do not")

optimized_reads = reference_reads = center_out_reads = 0
for src, dst, p, center, radius, label in exact_cases:
    height, width = len(src), len(src[0])
    lo_x, hi_x = max(-p[0], center[0] - radius), min(width - 8 - p[0], center[0] + radius)
    lo_y, hi_y = max(-p[1], center[1] - radius), min(height - 8 - p[1], center[1] + radius)
    reference_set = set(row_major_candidates(center[0], center[1], lo_x, hi_x, lo_y, hi_y))
    optimized_order = center_out_candidates(center[0], center[1], lo_x, hi_x, lo_y, hi_y, radius)
    assert len(optimized_order) == len(set(optimized_order)), f"{label}: center-out order must not duplicate candidates"
    assert set(optimized_order) == reference_set, f"{label}: center-out order must retain the entire clipped candidate set"
    reference = block_search(src, dst, p, center, radius, center_out=False)
    center_out = block_search(src, dst, p, center, radius, center_out=True)
    optimized = block_search(src, dst, p, center, radius, center_out=True, prune_ambiguous_zero=True)
    assert optimized[:3] == reference[:3], f"{label}: vector, normalized best SAD and confidence must exactly match the row-major reference"
    assert optimized[:3] == center_out[:3], f"{label}: exact tie pruning must preserve the full center-out result"
    assert optimized[3] <= center_out[3], f"{label}: exact tie pruning must not add SAD pixel comparisons"
    if label in ("repetitive stripes", "ambiguous flat patch"):
        assert optimized[3] < center_out[3], f"{label}: proven zero-cost ambiguity must prune later SAD comparisons"
    reference_reads += reference[3]
    optimized_reads += optimized[3]
    center_out_reads += center_out[3]

print(f"Exact center-out SAD regression: candidates and vectors match; pixel comparisons {reference_reads}->{optimized_reads}")
print(f"Exact ambiguous-tie SAD pruning: pixel comparisons {center_out_reads}->{optimized_reads}")


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

def endpoint_reliability(cycle_error: float, residual: float, uniqueness: float, valid: float = 1.0) -> float:
    return (
        (1.0 - smoothstep(0.75, 4.0, cycle_error))
        * (1.0 - smoothstep(0.025, 0.22, residual))
        * smoothstep(0.08, 0.30, uniqueness)
        * valid
    )

assert endpoint_reliability(0.75, 0.025, 0.30) == 1.0, "reliable, distinctive in-bounds flow should retain full confidence at curve starts"
assert endpoint_reliability(0.75, 0.025, 0.0) == 0.0, "tied motion matches must not be trusted for synthesis"
assert endpoint_reliability(4.0, 0.025, 0.30) == 0.0, "large forward/backward cycle errors must reject an endpoint"
assert endpoint_reliability(0.75, 0.22, 0.30) == 0.0, "high normalized SAD must reject an endpoint"
assert endpoint_reliability(0.75, 0.025, 0.30, valid=0.0) == 0.0, "out-of-bounds flow endpoints must be rejected"

def edge_decision(alpha: float, luma_delta: float, color_delta: float, chroma_delta: float,
                  confidence0: float, confidence1: float,
                  uniqueness0: float, uniqueness1: float) -> str:
    ambiguous = min(uniqueness0, uniqueness1) < 0.08
    chroma_boundary = color_delta > 0.12 and chroma_delta > 0.06
    conflict = luma_delta > 0.08 or chroma_boundary or (ambiguous and color_delta > 0.10)
    if not conflict:
        return "interpolate"
    edge0 = confidence0 * (0.5 + 0.5 * smoothstep(0.02, 0.35, uniqueness0))
    edge1 = confidence1 * (0.5 + 0.5 * smoothstep(0.02, 0.35, uniqueness1))
    if edge0 > edge1 + 0.10:
        return "frame0"
    if edge1 > edge0 + 0.10:
        return "frame1"
    return "frame0" if alpha < 0.5 else "frame1"

def neighborhood_boundary_conflict(neighborhood0: float, neighborhood1: float,
                                   flow_spread: float, luma_delta: float,
                                   color_delta: float, chroma_delta: float,
                                   uniqueness0: float, uniqueness1: float) -> bool:
    local = (
        min(neighborhood0, neighborhood1) < 0.30
        and (luma_delta > 0.06 or color_delta > 0.08)
    ) or (flow_spread > 3.0 and min(neighborhood0, neighborhood1) < 0.45)
    chroma_boundary = color_delta > 0.12 and chroma_delta > 0.06
    ambiguous_color = min(uniqueness0, uniqueness1) < 0.08 and color_delta > 0.10
    return local or luma_delta > 0.08 or chroma_boundary or ambiguous_color

assert neighborhood_boundary_conflict(0.25, 0.80, 0.0, 0.07, 0.09, 0.0, 0.35, 0.35), "weak local flow plus modest appearance change must reject a contour mix"
assert neighborhood_boundary_conflict(0.40, 0.80, 3.1, 0.01, 0.01, 0.0, 0.35, 0.35), "large forward/backward flow spread in a weak neighborhood must trigger fallback"
assert neighborhood_boundary_conflict(0.80, 0.80, 0.0, 0.09, 0.01, 0.0, 0.35, 0.35), "luma conflicts between 0.08 and 0.12 must be caught"
assert neighborhood_boundary_conflict(0.80, 0.80, 0.0, 0.01, 0.11, 0.0, 0.0, 0.35), "ambiguous motion with color disagreement between 0.10 and 0.18 must be caught"
assert neighborhood_boundary_conflict(0.80, 0.80, 0.0, 0.01, 0.13, 0.07, 0.35, 0.35), "chroma-only boundaries must remain protected"
assert not neighborhood_boundary_conflict(0.80, 0.80, 0.0, 0.01, 0.02, 0.01, 0.35, 0.35), "consistent flow and similar source samples should still interpolate"

flat_match_uniqueness = block_search(ambiguous_texture, ambiguous_texture, (24, 16), (0, 0), 8, center_out=True)[2]
assert flat_match_uniqueness == 0.0, "flat/tied blocks must be recognized as ambiguous"
assert smoothstep(0.04, 0.35, flat_match_uniqueness) == 0.0, "tied matches must remain identifiable for local edge decisions"
assert smoothstep(0.04, 0.35, 0.35) == 1.0, "distinctive motion matches must remain fully eligible"
repeat_match_uniqueness = block_search(repetitive_texture, shifted(repetitive_texture, 2, 0), (24, 16), (0, 0), 8, center_out=True)[2]
assert repeat_match_uniqueness == 0.0, "the periodic-pattern regression must expose a genuine tied-vector case"
assert edge_decision(0.5, 0.0, 0.0, 0.0, 1.0, 1.0, flat_match_uniqueness, flat_match_uniqueness) == "interpolate", "low uniqueness alone must not disable temporal synthesis"
assert edge_decision(0.5, 0.0, 0.25, 0.0, 1.0, 1.0, 0.0, 0.35) == "frame1", "a visible chroma conflict should prefer the distinctive endpoint"
assert edge_decision(0.75, 0.0, 0.25, 0.10, 1.0, 1.0, 0.35, 0.35) == "frame1", "chroma-only hair, eye, and clothing boundaries must not mix even with distinctive vectors"
assert edge_decision(0.25, 0.02, 0.08, 0.03, 1.0, 1.0, 0.35, 0.35) == "interpolate", "small chroma variation must not disable ordinary temporal synthesis"

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
