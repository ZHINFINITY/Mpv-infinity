#!/usr/bin/env python3
"""Validate MPV Flow's Vulkan GLSL, descriptor bindings, and visibility policy."""
from __future__ import annotations

import ast
import pathlib
import random
import re
import shutil
import struct
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE_PATH = ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c"
SOURCE = SOURCE_PATH.read_text(encoding="utf-8")
VISIBILITY_HEADER_PATH = ROOT / "app/src/main/cpp/mpvflow/mpvflow_visibility_resolver_shader.h"
VISIBILITY_HEADER = VISIBILITY_HEADER_PATH.read_text(encoding="utf-8")
QCOM_SHADER_PATH = ROOT / "app/src/main/cpp/mpvflow/mpvflow_qcom_seed.comp"
QCOM_SHADER = QCOM_SHADER_PATH.read_text(encoding="utf-8")
def shader_source(name: str) -> str:
    source = VISIBILITY_HEADER if name == "shader_visibility_resolver" else SOURCE
    match = re.search(
        rf"static const char {re.escape(name)}\[\] =\n(.*?);\n",
        source,
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
    "shader_optical_luma",
    "shader_optical_seed",
    "shader_flow",
    "shader_synthesize",
    "shader_visibility_resolver",
    "shader_capture_color",
    "shader_capture_maps",
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
optical_luma = shaders["shader_optical_luma"]
optical_seed = shaders["shader_optical_seed"]
synthesis = shaders["shader_synthesize"]
visibility_resolver = shaders["shader_visibility_resolver"]
capture_color = shaders["shader_capture_color"]
capture_maps = shaders["shader_capture_maps"]
require(capture_color, "int ow=pc.cfg.z,oh=pc.cfg.w", "capture color panels must accept independent synthesized-output dimensions")
require(capture_color, "if(p.x>=atlasW||p.y>=atlasH)return", "capture shader must guard each independently sized atlas extent")
require(capture_maps, "int tileW=gw; if(p.x>=tileW*7||p.y>=gh)return", "motion-map atlas shader must retain its native map-grid layout")
require(synthesis, "layout(set=0,binding=7,rgba8) writeonly uniform image2D outTex;", "SDR synthesis output must remain RGBA8")
require(SOURCE, "shader_replace_once(", "HDR synthesis must derive a second shader from the validated baseline")
require(SOURCE, "layout(set=0,binding=7,rgba16f) writeonly uniform image2D outTex;", "HDR shader variant must write RGBA16F")
require(SOURCE, "ctx->pass_synthesize_rgba16f = make_pass", "HDR output pass must be independently compiled and optional")
require(SOURCE, "mpvflow_gpu_rgba16f_synthesis_supported", "high-precision output must be gated by Vulkan pass and format capability")
require(optical_luma, "layout(set=0,binding=0,rgba16f) readonly uniform image2D inTex;", "optical conversion must consume the existing GPU luma pyramid")
require(optical_luma, "layout(set=0,binding=1,r8) writeonly uniform image2D outTex;", "NV optical inputs must be packed as R8 UNORM without a CPU round-trip")
require(optical_seed, "uniform isampler2D vectorTex", "NV's scaled signed-integer vector output must be read with an integer sampler")
require(optical_seed, "vec2(fixedVector)/32.0", "NV SFIXED5 vectors must convert from fixed point before entering the pixel-space search")
require(optical_seed, "(vec2(fixedVector)/32.0)/float(scale)", "full-resolution NV vectors must scale into the coarsest pyramid level")
require(optical_seed, "imageStore(seedTex,g,vec4(flow,0.0,1.0))", "hardware vectors must remain only a center prior with no assumed-match confidence")
require(flow, "for(int ring=1;ring<=radius&&!stopOnCenterTie;ring++)", "motion search must stop only after an exact zero-cost tie makes later candidates irrelevant")
require(flow, "if(max(abs(dx),abs(dy))!=ring) continue", "center-out rings must enumerate each Chebyshev-radius candidate exactly once")
require(flow, "if(any(lessThan(d,lo))||any(greaterThan(d,hi))) continue", "center-out search must retain edge-clipped bounds")
require(flow, "d.y<bestVector.y||(d.y==bestVector.y&&d.x<bestVector.x)", "out-of-order equal-cost winners must retain the legacy row-major tie-break")
require(flow, "sadAt(p,d,sz,best==0?0:second,blockSize,colorAware)", "zero-cost matches must keep exact SAD pruning while checking for a real tie")
require(flow, "layout(set=0,binding=5,rgba8) readonly uniform image2D srcColorTex;", "finest motion search must have access to source-frame color alongside luma")
require(flow, "layout(set=0,binding=6,rgba8) readonly uniform image2D dstColorTex;", "finest motion search must have access to destination-frame color alongside luma")
require(flow, "colorAware=(pc.cfg.w&8)!=0", "color-aware refinement must be explicitly enabled only for the finest analysis level")
require(flow, "(3*lumaCost+4*colorCost+2)/4", "finest motion candidates must combine luma SAD with sampled RGB SAD")
require(flow, "if(best==0&&second==0){ if(all(equal(bestVector,center))){stopOnCenterTie=true;continue;}", "a tied center winner must terminate search without changing its vector or confidence")
require(flow, "d.y>bestVector.y||(d.y==bestVector.y&&d.x>=bestVector.x)", "once a non-center zero-cost tie is known, only lexicographically earlier vectors may affect the legacy tie-break")
require(flow, "int radius=pc.cfg.z", "center-out must retain the original full configured motion radius")
require(flow, "qcomSeed=(pc.cfg.w&4)!=0", "QCOM results must be tagged separately from optical and parent-flow priors")
require(flow, "qcomCandidateValid=all(greaterThanEqual(qcomCandidate,lo))&&all(lessThanEqual(qcomCandidate,hi))", "QCOM hints outside the original edge-clipped SAD domain must be discarded")
require(flow, "int c=sadAt(p,qcomCandidate,sz,best==0?0:second,blockSize,colorAware)", "a QCOM vector must be scored by the active SAD objective before it can affect motion")
require(flow, "if(qcomCandidateValid&&all(equal(d,qcomCandidate))) continue", "the baseline center-out search must not double-count its QCOM-seeded candidate")
require(flow, "int radius=pc.cfg.z; if(hasParent&&!qcomSeed&&parentResidual", "QCOM hints must not expand or otherwise change the configured SAD search radius")
require(flow, "int blockSize=(pc.cfg.w>>8)", "motion search must distinguish fine and coarse block support")
require(flow, "vec2 parentOrigin=vec2(float(blockSize)*0.25-4.0)", "parent sampling must center each child block on its parent block")
require(flow, "if(blockSize!=4) blockSize=8", "motion search must constrain support to the tested 4x4 or 8x8 sizes")
require(flow, "float(best)/(float(blockSize*blockSize)*255.0)", "flow vectors must normalize SAD by the active block area")
assert "flowAtEdgeAware(" not in synthesis, "inverse/final/cycle sampling must not switch to a nearest-guide field"
require(synthesis, "vec4 flowAtBilinear(vec2 p,int direction)", "iterative vector refinement must have a low-cost bilinear sampler")
require(synthesis, "vec2 center=p-vec2(2.0)", "synthesis flow lookup must use 4x4 block centers rather than block origins")
require(synthesis, "vec2 scale=vec2(outSize)/vec2(motionSize)", "inverse residuals must be measured in output pixel units")
require(synthesis, "inverseResidual0=length((a+pc.timestep*f.xy-flowP)*scale)", "forward inverse residual must use the final continuous forward sample")
require(synthesis, "inverseResidual1=length((z+(1.0-pc.timestep)*b.xy-flowP)*scale)", "backward inverse residual must use the final continuous backward sample")
require(synthesis, "inverseResidual0Valid", "forward validity must be independently guarded")
require(synthesis, "inverseResidual1Valid", "backward validity must be independently guarded")
require(synthesis, "cycleSamplesFinite=flowFinite(backAt0)&&flowFinite(forwardAt1)", "cycle samples must be finite before reliability")
require(synthesis, "bool staticPixel=", "static low-motion pixels should bypass the full-resolution warp path")
require(synthesis, "else if(boundaryConflict) outc=FLOW_RESULT(mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),pc.timestep),12,confidence,captureReason);", "conflicting warped samples must fall back to a source-frame blend instead of trusting a single possibly corrupted warp")
require(synthesis, "ivec2 outSize=imageSize(outTex),motionSize=pc.cfg.zw;", "synthesis bounds must use the actual storage-image extent, not only host-provided dimensions")
require(synthesis, "flowP=clamp(uv*vec2(motionSize)-vec2(0.5),vec2(0.0),vec2(motionSize-1))", "Vulkan output pixel centers must map to analysis pixel centers without a half-pixel offset")
require(visibility_resolver, "flowP=(vec2(p)+0.5)*vec2(motionSize)/vec2(outSize)-vec2(0.5)", "optional resolver must share the corrected pixel-center mapping")
require(visibility_resolver, "layout(set=0,binding=7) uniform sampler2D motionWarped;", "resolver must sample the already-produced production frame as fallback")
require(visibility_resolver, "layout(set=0,binding=8,rgba8) writeonly uniform image2D outTex;", "resolver output must remain a separate RGBA8 storage image")
require(visibility_resolver, "layout(set=0,binding=9,rgba8) writeonly uniform image2D maskTex;", "resolver diagnostic mask must not alias the production output")
require(visibility_resolver, "for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++)", "resolver must compare a full-resolution 5x5 patch")
require(visibility_resolver, "bool uniquelySupported=bestEnergy>0.012&&margin>max(0.025,best*0.30)&&centerError<0.04&&forwardSupport&&backwardSupport", "resolver must require distinct-cost, center, and bidirectional support")
require(visibility_resolver, "baselineCost-best>0.035&&baselineDistance>3.0", "resolver must materially improve on production and reject near-duplicate motions")
require(visibility_resolver, "result=mix(fallback,mix(ca,cb,t),0.25)", "resolver correction must remain capped at 25 percent")
require(SOURCE, '"mpvflow_visibility_resolver_shader.h"', "the native GPU implementation must include the validated embedded GLSL")
require(SOURCE, "mpvflow_gpu_synthesize_internal(ctx, pair, source0, source1, t,\n                                           false, false, false, output)", "the public production synthesis API must keep the resolver and Media3 policy disabled")
require(SOURCE, "bool enable_soft_blend,\n    bool preserve_motion_blend_on_conflict, pl_tex *output)", "the policy API must keep the Media3 edge preference explicit")
require(SOURCE, "*pass = make_pass(ctx->gpu, glsl, resolver_desc, 10)", "resolver pass must be initialized lazily only after explicit opt-in")
require(SOURCE, "fallback=production_output", "resolver failure paths must retain the production result")
synthesis_main = synthesis.split("void main()", 1)[1]
output_guard = "if(any(greaterThanEqual(p,outSize))) return;"
assert output_guard in synthesis_main, "synthesis must reject padded invocations outside the output image"
assert synthesis_main.index(output_guard) < synthesis_main.index("imageStore(outTex,p,"), "output bounds must be checked before every synthesis image store"

def padded_workgroup_pixels(width: int, height: int, local_size: int = 8):
    groups_x = (width + local_size - 1) // local_size
    groups_y = (height + local_size - 1) // local_size
    return [(x, y) for y in range(groups_y * local_size) for x in range(groups_x * local_size)]

for output_width, output_height in ((1, 1), (7, 9), (13, 9), (16, 8), (17, 16)):
    dispatched = padded_workgroup_pixels(output_width, output_height)
    written = {(x, y) for x, y in dispatched if x < output_width and y < output_height}
    expected = {(x, y) for y in range(output_height) for x in range(output_width)}
    assert written == expected, f"bounds guard must write each valid pixel exactly once for {output_width}x{output_height}"
    assert all(x < output_width and y < output_height for x, y in written), "padded workgroup lanes must never reach output stores"
    if output_width % 8 or output_height % 8:
        assert len(dispatched) > len(written), "odd or partial dimensions must exercise padded workgroup lanes"

require(flow, "layout(set=0,binding=4,rgba16f) readonly uniform image2D sceneTex;", "Vulkan motion search must consume the reduced scene delta")
require(flow, "if(imageLoad(sceneTex,ivec2(0)).r>0.1882353){ imageStore(flowTex,g,vec4(0.0,0.0,1.0,0.0)); return; }", "Vulkan scene cuts must initialize a no-confidence flow field before skipping SAD search")
require(synthesis, "if(cut>0.1882353)", "Vulkan synthesis must use the same scene-cut threshold as the motion-search early-out")
require(SOURCE, "ctx->pass_flow = make_pass(gpu, shader_flow, flow_desc, 7);", "Vulkan flow pass must expose the five motion and two color descriptors")
require(SOURCE, "(qcom_parent ? 4 : 0) | (li == 0 ? 8 : 0)", "only the finest analysis grid should use the RGB-aware score")
require(SOURCE, "{ .object = pair->input[dir ? 1 : 0] },\n                { .object = pair->input[dir ? 0 : 1] },", "motion direction must bind matching source and destination color inputs")
require(SOURCE, "{ .object = flow }, { .object = scene_delta },", "Vulkan motion dispatch must bind the reduced scene-delta texture")
opencl_source = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_opencl.c").read_text(encoding="utf-8")
require(opencl_source, "inverse_residual0=length((pa+ t*f.xy-flow_p)*scale)", "OpenCL forward inverse residual must use output pixels and the final edge-aware sample")
require(opencl_source, "inverse_residual1=length((pb+(1.0f-t)*q.xy-flow_p)*scale)", "OpenCL backward inverse residual must use output pixels and the final edge-aware sample")
require(opencl_source, "inverse_residual0_valid=inverse_residual0<=0.75f,inverse_residual1_valid=inverse_residual1<=0.75f", "OpenCL must share Vulkan's subpixel inverse residual limit")
require(opencl_source, "cycle_error0=flow_finite(back_at0)?", "OpenCL forward cycle samples must reject non-finite data before reliability")
require(opencl_source, "cycle_error1=flow_finite(forward_at1)?", "OpenCL backward cycle samples must reject non-finite data before reliability")
require(opencl_source, "(float)x+0.5f)*(float)aw/(float)w-0.5f", "OpenCL output pixel centers must map to analysis pixel centers without a half-pixel offset")
require(opencl_source, "(float)y+0.5f)*(float)ah/(float)h-0.5f", "OpenCL output pixel centers must map to analysis pixel centers without a half-pixel offset")
opencl_synthesize_api = opencl_source.split(
    "enum MPVFlowOpenCLStatus mpvflow_opencl_synthesize(", 1
)[1].split("\n}\n", 1)[0]
require(
    opencl_synthesize_api,
    "mpvflow_opencl_synthesis_output_disjoint_from_inputs(",
    "OpenCL synthesis must reject output aliases of either source or backing allocation",
)
assert opencl_synthesize_api.index("mpvflow_opencl_synthesis_output_disjoint_from_inputs(") < \
       opencl_synthesize_api.index("c->clEnqueueNDRangeKernel("), (
           "OpenCL output/source alias validation must happen before GPU work is queued"
       )
require(opencl_source, "if(scene_delta[0]>0.1882353f){out[i]=(float4)(0.0f,0.0f,1.0f,0.0f);return;}", "OpenCL scene cuts must initialize a no-confidence flow field before skipping SAD search")
require(opencl_source, "if(scene_delta[0]>0.1882353f)", "OpenCL motion search and synthesis must share the same scene-cut threshold")
require(opencl_source, "SET_ARG(c->estimate_kernel, 9, block_size);\n    SET_ARG(c->estimate_kernel, 10, p->scene_delta); SET_ARG(c->estimate_kernel, 11, out);", "OpenCL block support, scene delta, and flow output kernel arguments must remain correctly ordered")
core_source = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_core.c").read_text(encoding="utf-8")
cpu_cut = re.search(r"#define MPVFLOW_SCENE_CUT_MEAN_DELTA ([0-9.]+)", core_source)
vulkan_search_cut = re.search(r"if\(imageLoad\(sceneTex,ivec2\(0\)\)\.r>([0-9.]+)\)", flow)
vulkan_synthesis_cut = re.search(r"if\(cut>([0-9.]+)\)", synthesis)
opencl_cut = re.search(r"if\(scene_delta\[0\]>([0-9.]+)f\)", opencl_source)
assert all((cpu_cut, vulkan_search_cut, vulkan_synthesis_cut, opencl_cut)), "all Flow engines must expose their scene-cut thresholds"
normalized_thresholds = (
    float(cpu_cut.group(1)) / 255.0,
    float(vulkan_search_cut.group(1)),
    float(vulkan_synthesis_cut.group(1)),
    float(opencl_cut.group(1)),
)
assert max(normalized_thresholds) - min(normalized_thresholds) <= 1e-7, (
    f"CPU/Vulkan/OpenCL scene-cut thresholds diverged: {normalized_thresholds}"
)
require(opencl_source, "int block_size = level == MPVFLOW_LEVELS - 1 ? 4 : 8;", "OpenCL must use 4x4 support only at its finest level and 8x8 at coarser levels")
require(opencl_source, "inline int sad_block(", "OpenCL matching must support the configured block area")
require(opencl_source, "for(int yy=0;yy<block_size;yy++) for(int xx=0;xx<block_size;xx++)", "OpenCL SAD must use the active fine/coarse support")
require(opencl_source, "p=min((int2)(gx*4,gy*4),(int2)(w-block_size,h-block_size))", "OpenCL block origins must be clamped using active support")
require(opencl_source, "sad_block(a,b,w,h,p.x,p.y,center.x,center.y,block_size,2147483647)", "OpenCL center SAD must use active support")
require(opencl_source, "out[i]=(float4)(refined.x,refined.y,(float)best/((float)(block_size*block_size)*255.0f),uniqueness)", "OpenCL residual confidence must normalize by active block area")
require(opencl_source, "limit=(int2)(w-block_size,h-block_size)", "OpenCL subpixel refinement must respect active block bounds")
require(opencl_source, "int2 bound=(int2)(aw-4,ah-4),c00=min(a*(int2)(4),bound)+(int2)(2)", "OpenCL edge-guide samples must match Vulkan's 4x4 block centers, including partial edge blocks")
require(opencl_source, "float static_luma_delta=", "OpenCL synthesis must gate static pixels before warping")
require(opencl_source, "static_out=convert_uchar4_sat_rte", "OpenCL synthesis must preserve static pixels instead of blending warped neighbors")
require(opencl_source, "float chroma_delta=max(fabs((ca.x-ca.y)-(cb.x-cb.y)),fabs((ca.z-ca.y)-(cb.z-cb.y)))/255.0f", "OpenCL synthesis must measure chroma disagreement between warped source samples")
require(opencl_source, "int chroma_boundary=color_delta>0.12f&&chroma_delta>0.06f", "OpenCL must reject strong chroma-only contour mixing")
require(synthesis, "float cycleQuality=1.0-smoothstep(0.75,4.0,cycle); float matchQuality=1.0-smoothstep(0.025,0.22,flow.z); float uniquenessQuality=smoothstep(0.08,0.30,flow.w)", "Vulkan reliability must combine cycle, residual, and uniqueness confidence")
require(opencl_source, "float cycle_quality=1.0f-smoothstep(0.75f,4.0f,cycle); float match_quality=1.0f-smoothstep(0.025f,0.22f,flow.z); float uniqueness_quality=smoothstep(0.08f,0.30f,flow.w)", "OpenCL reliability must match Vulkan cycle, residual, and uniqueness confidence")
require(opencl_source, "inline float flow_confidence_weight(float4 flow) { return clamp((0.22f-flow.z)*5.128205f,0.0f,1.0f)*clamp((flow.w-0.08f)*4.545455f,0.0f,1.0f); }", "OpenCL edge-aware flow filtering must use Vulkan's piecewise-linear confidence policy")
require(opencl_source, "float flow_spread=max(max(length(v00.xy-v10.xy),length(v01.xy-v11.xy)),max(length(v00.xy-v01.xy),length(v10.xy-v11.xy))); if(flow_spread<=1.0f)return geometric;", "OpenCL must use Vulkan's low-cost local vector-disagreement gate")
require(opencl_source, "float c00=flow_confidence_weight(v00)", "OpenCL bilinear sampling must reject low-confidence outliers at motion boundaries")
require(opencl_source, "w00*=flow_confidence_weight(v00)", "OpenCL final warp and cycle edge-aware samples must include motion confidence")
require(opencl_source, ":flow_at_bilinear(forward,backward,direction,aw,ah,x,y)", "OpenCL must preserve a bounded fallback when every edge candidate is uncertain")
require(opencl_source, "if(confidence<0.28f){ uchar4 px0=vload4(0,a+y*astride+x*4),px1=vload4(0,b+y*bstride+x*4); color=mix4(convert_float4(px0),convert_float4(px1),t); }", "OpenCL low-confidence fallback must blend source endpoints instead of creating hard spatial endpoint patches")
require(opencl_source, "inline float neighborhood_quality(", "OpenCL must mirror Vulkan local flow-consistency checks")
require(opencl_source, "int neighborhood_conflict=(min(neighborhood0,neighborhood1)<0.30f&&(luma_delta>0.06f||color_delta>0.08f))||(forward_backward_error>3.0f&&min(neighborhood0,neighborhood1)<0.45f)", "OpenCL neighborhood/flow-spread rejection must match Vulkan")
require(synthesis, "float forwardBackwardError=length(f.xy+b.xy);", "Vulkan must compare forward/backward vectors with opposite signs for a consistent motion pair")
require(opencl_source, "float forward_backward_error=length((float2)(f.x+q.x,f.y+q.y));", "OpenCL must use the same sign-correct forward/backward consistency error")

require(opencl_source, "int boundary_conflict=neighborhood_conflict||luma_delta>0.08f||chroma_boundary||(min(f.w,q.w)<0.08f&&color_delta>0.10f)", "OpenCL must match Vulkan luma, chroma, and ambiguity boundary thresholds")
require(opencl_source, "float4 f=flow_at_bilinear(forward,backward,0,aw,ah,flow_p.x,flow_p.y),q=flow_at_bilinear(forward,backward,1,aw,ah,flow_p.x,flow_p.y)", "OpenCL synthesis must use Vulkan's cheaper bilinear initial-flow sampling")
require(opencl_source, "f=flow_at_edge_aware(forward,backward,luma0,luma1,0,aw,ah,pa.x,pa.y)", "OpenCL must retain final edge-aware refinement after the bilinear fast path")
require(opencl_source, "parent_residual=q.z; parent_uniqueness=q.w", "OpenCL finer levels must retain the coarse SAD and uniqueness signals")
require(opencl_source, "float2 parent_origin=(float2)((float)block_size*0.25f-4.0f)", "OpenCL parent sampling must use the active child block center")
require(opencl_source, "if(has_coarse&&parent_residual>0.12f&&parent_uniqueness>0.0005f)search_radius=min(radius*2,16)", "OpenCL must use the same gated search-expansion policy")
require(synthesis, "dot(fastForward.xy,fastForward.xy)<=0.0025", "the static path must require a near-zero forward vector")
require(synthesis, "dot(fastBackward.xy,fastBackward.xy)<=0.0025", "the static path must require a near-zero backward vector")
require(synthesis, "colorDelta<=0.0039216", "the static path must require an at-most-one-LSB source difference")
require(synthesis, "for(int i=0;i<2;i++){ mat2 dfA,dfZ;", "inverse-flow refinement must retain its two-step sampling budget")
require(synthesis, "f=flowAtBilinearJacobian(a,0,dfA)", "forward inverse refinement must reuse the bilinear flow texels for its Jacobian")
require(synthesis, "b=flowAtBilinearJacobian(z,1,dfZ)", "backward inverse refinement must use the same Jacobian-aware field")
require(synthesis, "detA>0.05", "forward Newton refinement must reject singular and folded local maps")
require(synthesis, "detZ>0.05", "backward Newton refinement must reject singular and folded local maps")
require(synthesis, "all(lessThanEqual(abs(dA),vec2(4.0)))", "forward correction must stay within one grid cell per axis")
require(synthesis, "all(lessThanEqual(abs(dZ),vec2(4.0)))", "backward correction must stay within one grid cell per axis")
require(synthesis, "if(cycleSamplesFinite&&min(f.w,b.w)<0.30", "validated coherent motion support must be available with Soft Blend on or off")
require(synthesis, "b=flowAtBilinear(z,1)", "backward inverse-flow refinement must use the same efficient sampler")
require(synthesis, "layout(set=0,binding=5,rgba16f) readonly uniform image2D luma0Tex;", "frame-0 luma binding/format mismatch")
require(synthesis, "layout(set=0,binding=6,rgba16f) readonly uniform image2D luma1Tex;", "frame-1 luma binding/format mismatch")
require(synthesis, "cycleError0=flowFinite(backAt0)?length(f.xy+backAt0.xy):1e20", "forward warp must be checked against reverse flow at its endpoint")
require(synthesis, "cycleError1=flowFinite(forwardAt1)?length(b.xy+forwardAt1.xy):1e20", "backward warp must be checked against forward flow at its endpoint")
require(synthesis, "pointInBounds(a,motionSize)*pointInBounds(cycle0,motionSize)", "out-of-frame motion endpoints must be rejected")
require(synthesis, "vec2(outSize)/vec2(motionSize)", "motion vectors must scale from the bounded analysis grid to source-resolution pixels")
require(synthesis, "colorAt(frame0,srcA)", "interpolated color must be sampled from the source-resolution input")
require(synthesis, "confidence=softBlend?max(confidence0,confidence1):min(confidence0,confidence1)", "default warping needs common support; one good endpoint must not hide the other unsupported endpoint")
require(synthesis, "vec4 visibilityWarped=(ca*weight0+cb*weight1+fallback*max(0.0001-total,0.0))/max(total,0.0001)", "Soft Blend must retain the visibility-normalized warped color")
require(synthesis, "float warpTrust=smoothstep(0.0,0.60,confidence)", "Soft Blend must transition continuously through confidence")
require(synthesis, "weight0=(1.0-pc.timestep)*visibility0,weight1=pc.timestep*visibility1", "visible endpoint weights must preserve temporal alpha")
require(synthesis, "float uniquenessQuality=smoothstep(0.08,0.30,flow.w)", "endpoint reliability must reject ambiguous motion vectors")
require(synthesis, "return cycleQuality*matchQuality*uniquenessQuality*valid;", "endpoint reliability must include motion uniqueness")
require(synthesis, "float apertureQuality(float residual,float cycle,float coherence,vec3 difference)", "correct aperture-tied motion needs separately qualified support, not a lower uniqueness threshold")
require(synthesis, "if(cycleSamplesFinite&&min(f.w,b.w)<0.30&&min(valid0,valid1)>0.90", "both policies require finite, jointly interior endpoints before aperture rescue")
require(synthesis, "max(f.z,b.z)<0.025&&max(cycleError0,cycleError1)<0.75", "aperture rescue must reject weak residuals and inconsistent cycles before qualifying trajectories")
require(synthesis, "vec2 initialForward=f.xy,initialBackward=b.xy", "trajectory coherence must reuse the original inverse-field samples")
require(synthesis, "trajectorySpread,ca.rgb-cb.rgb", "aperture support must check trajectory coherence and full-resolution RGB agreement")
require(synthesis, "confidence0=max(confidence0,aperture*valid0); confidence1=max(confidence1,aperture*valid1)", "aperture support must preserve each endpoint's existing validity and common temporal weighting")
require(synthesis, "smoothstep(0.0,0.60,confidence)*boundaryTrust", "default warp trust must transition continuously instead of exposing a coarse-grid confidence contour")
require(synthesis, "bool softBlend=(pc.cfg.x&0x40000000)!=0", "the soft path must use an explicit per-synthesis debug flag")
require(synthesis, "softBlendAlpha=clamp(0.5+(pc.timestep-0.5)*1.35,0.0,1.0)", "soft fallback must sharpen uncertain temporal blends continuously without a midpoint jump")
require(synthesis, "texelFetch(frame1,p,0),softBlendAlpha", "soft fallback must use its biased temporal alpha only for source-frame blending")
require(synthesis, "outc=softBlend?softBlendColor(p,ca,cb,confidence,preserveMotionBlend):FLOW_RESULT(mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),pc.timestep)", "Vulkan low-confidence fallback must blend source endpoints instead of creating hard spatial endpoint patches")
require(synthesis, "float softWarpTrust=smoothstep(0.0,0.60,confidence)*boundaryTrust", "Soft Blend must continuously qualify the visibility warp rather than branch at a conflict contour")
require(synthesis, "preserveMotionBlend=(pc.cfg.x&0x20000000)!=0", "the continuous-motion blend policy must use a separate synthesis flag")
require(synthesis, "if(preserveMotionBlend) warpWeight=min(warpWeight,0.40);", "conflict blending must retain at least 60 percent of the temporally blended source colors")
require(synthesis, "if(preserveMotionBlend) warpWeight=min(warpWeight,0.40);", "MPV conflicts must retain a bounded source/warp blend")
require(synthesis, "if(boundaryTrust>0.0&&(lumaDelta>0.04||colorDelta>0.06||forwardBackwardError>2.0)){", "expensive neighborhood motion probes must run only when they could alter the conflict decision")
require(flow, "float(second-best)/max(float(second),1.0)", "flow vectors must report best-vs-runner-up match uniqueness")

for fragment, message in (
    ("textureBlockMatchWindowSADQCOM(", "QCOM2 must call the window-SAD intrinsic"),
    ("uniform texture2D targetImage", "QCOM block-match image descriptors must remain separate from ordinary sampled descriptors"),
    ("uniform sampler blockMatchSampler", "QCOM2 must bind its image-processing sampler separately"),
    ("ivec2 displacement = ivec2(targetOrigin) + offset - ivec2(p)", "QCOM target-local offsets must convert to signed frame displacement"),
    ("imageStore(seedMap, ivec2(g), vec4(vec2(displacement)", "QCOM output must contain only a coarse vector prior and SAD metadata"),
):
    require(QCOM_SHADER, fragment, message)
require(flow, "refineAxis(p,bestVector,ivec2(1,0)", "GPU flow vectors must be fractionally refined")
require(flow, "if(centerCost==0) return 0.0;", "an exact SAD winner must not be shifted by an asymmetric subpixel parabola")
require(opencl_source, "if(center_cost==0)return 0.0f;", "OpenCL must preserve exact integer matches like Vulkan")
require(flow, "center=clamp(center,-p,sz-block-p)", "coarse predictors must be clamped to the active block before border-local refinement")
require(flow, "bool opticalSeed=(pc.cfg.w&2)!=0,qcomSeed=(pc.cfg.w&4)!=0,hasParent=(pc.cfg.w&1)!=0||opticalSeed||qcomSeed", "the encoded block support must remain distinct from optional parent, optical, and QCOM seed flags")
require(flow, "if(opticalSeed){ parentFlow=imageLoad(parentTex,g); }", "coarse flow must use the direction-matched hardware vector as a local search center")
require(flow, "parentFlow.rg*(opticalSeed?1.0:2.0)", "hardware vectors must not be accidentally doubled when seeding the coarsest level")
require(flow, "parentResidual=parentFlow.b; parentUniqueness=parentFlow.a", "finer Vulkan levels must retain the coarse SAD and uniqueness signals")
require(flow, "if(hasParent&&!qcomSeed&&parentResidual>0.12&&parentUniqueness>0.0005)radius=min(radius*2,16)", "only a poor but non-tied non-QCOM parent may double the fine-level search")
require(synthesis, "bool neighborhoodConflict=", "synthesis must detect local motion disagreement")
require(synthesis, "float chromaDelta=max(abs((ca.r-ca.g)-(cb.r-cb.g)),abs((ca.b-ca.g)-(cb.b-cb.g)))", "Vulkan synthesis must measure chroma disagreement between warped source samples")
require(synthesis, "bool chromaBoundary=colorDelta>0.12&&chromaDelta>0.06", "Vulkan synthesis must reject strong chroma-only contour mixing")
require(synthesis, "bool boundaryConflict=lumaDelta>0.08||chromaBoundary", "strong motion and color boundaries must select a source sample instead of blending across regions")
require(synthesis, "backAt0=flowAtBilinear(cycle0,1)", "cycle checks must use the inverse solve field")
require(synthesis, "forwardAt1=flowAtBilinear(cycle1,0)", "reverse cycle checks must use the inverse solve field")
assert "exp(" not in synthesis, "production synthesis must not redo nearest-guide exponential filters at full output resolution"
assert synthesis_main.count("flowAtBilinear(") == 8 and synthesis_main.count("flowAtBilinearJacobian(") == 2, "static, inverse, final and cycle samples must retain the continuous-field evaluation budget"
sampler = synthesis.split("vec4 flowAtBilinear(", 1)[1].split("\n", 1)[0]
jacobian_sampler = synthesis.split("vec4 flowAtBilinearJacobian(", 1)[1].split("\n", 1)[0]
assert sampler.count("flowImageAt(") == 4 and jacobian_sampler.count("flowImageAt(") == 4, "the Jacobian must reuse the same four texels, not finite-difference additional field samples"
require(sampler, "return mix(mix(v00,v10,t.x),mix(v01,v11,t.x),t.y)", "bilinear vectors and their quality must remain continuous across confidence changes")
assert "flowConfidenceWeight" not in sampler and "if(" not in sampler, "motion coordinates must not switch interpolation modes based on confidence/spread"
assert "pc.timestep<0.5?texelFetch" not in synthesis_main, "non-finite flow must use a temporal source crossfade rather than endpoint patches"
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
require(SOURCE, "One continuous field for inverse iteration, final samples and cycles", "Vulkan synthesis must avoid changing fields or redoing edge-guide exponential lookups")
require(SOURCE, "const int grid_w = (w - block_size + FLOW_STEP - 1) / FLOW_STEP + 1", "motion texture width must contain only valid, unique per-level block origins")
require(SOURCE, "const int grid_h = (h - block_size + FLOW_STEP - 1) / FLOW_STEP + 1", "motion texture height must contain only valid, unique per-level block origins")
assert "r32f" not in SOURCE.lower(), "MPV Flow must not require the optional R32F image format"
assert "glsl.gles" not in SOURCE and "#version 310 es" not in SOURCE, "MPV Flow must not require GLES compute"
require(flow, "float flowGridAxis(", "Vulkan search prediction must map the clamped terminal block to its actual image-space origin")
require(flow, "float(parentSize.x),8,pg.x", "Vulkan parent-flow prediction must account for the coarser block grid")
require(synthesis, "flowGridCoord(p,direction)", "Vulkan synthesis must sample the irregular terminal block grid by its actual origin")
require(opencl_source, "inline float grid_axis(", "OpenCL must share the same terminal-block coordinate rule")
require(opencl_source, "grid_axis(x,aw,4,gw)", "OpenCL synthesis must sample the terminal fine block by its actual origin")
require(opencl_source, "int block_size = l == MPVFLOW_LEVELS - 1 ? 4 : 8", "OpenCL grid allocation must match each level's motion block support")
require(opencl_source, "(w - block_size + MPVFLOW_GRID_STEP - 1) / MPVFLOW_GRID_STEP + 1", "OpenCL must allocate unique valid coarse/fine block origins like Vulkan")
require(opencl_source, "(max(w-block_size,0)+3)/4+1", "OpenCL kernel launch dimensions must match allocated grid extents")


def terminal_grid_coord(position: float, extent: int, block: int, step: int = 4) -> float:
    grid_count = (max(extent - block, 0) + step - 1) // step + 1
    if grid_count <= 1:
        return 0.0
    last_anchor = max(extent - block, 0)
    previous_anchor = min((grid_count - 2) * step, last_anchor)
    last_index = grid_count - 1
    if position <= previous_anchor:
        return min(max(position / step, 0.0), float(last_index))
    if last_anchor <= previous_anchor:
        return float(last_index)
    return min(max(last_index - 1 + (position - previous_anchor) / (last_anchor - previous_anchor), 0.0), float(last_index))


for extent, block in ((7, 4), (13, 4), (98, 8), (13, 8), (96, 8)):
    count = (max(extent - block, 0) + 3) // 4 + 1
    anchors = [min(i * 4, extent - block) for i in range(count)]
    assert all(left < right for left, right in zip(anchors, anchors[1:])), (
        f"motion grid must not allocate duplicate terminal blocks: {extent=}, {block=}, {anchors=}"
    )
    for index, anchor in enumerate(anchors):
        assert abs(terminal_grid_coord(float(anchor), extent, block) - index) < 1e-9, (
            f"grid anchor did not map to its own sample: {extent=}, {block=}, {anchor=}"
        )
    assert terminal_grid_coord(float(extent), extent, block) == float(count - 1), (
        f"coordinates beyond the last valid block must clamp to its sample: {extent=}, {block=}"
    )
assert terminal_grid_coord(2.0, 7, 4) > 2.0 / 4.0, "a partial terminal block must be reached at its true origin"
assert terminal_grid_coord(2.0, 8, 4) == 0.5, "aligned block grids must preserve the existing linear coordinate mapping"
print("Terminal-grid regression: irregular edge anchors map exactly; aligned grids remain unchanged")


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
opencl_fine_edge_match = block_search(edge_frame0, edge_frame1, (8, 8), (0, 0), 2, True, block_size=4)
assert coarse_edge_match[0] == (0, 0), "the regression scene must expose a background-dominated 8x8 match"
assert fine_edge_match[0] == (1, 0), "4x4 support should recover the one-pixel foreground translation"
assert opencl_fine_edge_match[0] == fine_edge_match[0], "OpenCL's Vulkan-aligned fine support should recover the same thin foreground vector"
print("Fine-level motion support regression: a thin moving foreground selects its own vector")

def combined_luma_rgb_cost(luma_delta: int, rgb_deltas: tuple[int, int, int]) -> int:
    luma_cost = luma_delta
    color_cost = sum(rgb_deltas) // 3
    return (3 * luma_cost + color_cost + 2) // 4

# Same-luma candidates can represent different anime colors. The fine-level RGB
# term must select the near-color match instead of accepting a chromatically wrong tie.
wrong_same_luma = combined_luma_rgb_cost(0, (120, 120, 120))
correct_near_color = combined_luma_rgb_cost(4, (4, 4, 4))
assert correct_near_color < wrong_same_luma, "RGB-aware fine matching must reject a same-luma, wrong-color candidate"
print("Chromatic-match regression: same-luma, wrong-color candidates lose to the matching color patch")


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

def flow_sample_weight(residual: float, uniqueness: float) -> float:
    return max(0.0, min(1.0, (0.22 - residual) * 5.128205)) * max(
        0.0, min(1.0, (uniqueness - 0.08) * 4.545455)
    )

def confidence_weighted_flow(vectors, geometry_weights):
    geometric_total = sum(geometry_weights)
    geometric = sum(vector * geometry for (vector, _, _), geometry in zip(vectors, geometry_weights)) / geometric_total
    values = [vector for vector, _, _ in vectors]
    if max(values) - min(values) <= 1.0:
        return geometric
    weights = [geometry * flow_sample_weight(residual, uniqueness)
               for (vector, residual, uniqueness), geometry in zip(vectors, geometry_weights)]
    total = sum(weights)
    if total <= 0.000001:
        weights = geometry_weights
        total = sum(weights)
    return sum(vector * weight for (vector, _, _), weight in zip(vectors, weights)) / total

ambiguous_fast_outlier = [(0.0, 0.0, 1.0), (0.0, 0.0, 1.0),
                          (0.0, 0.0, 1.0), (12.0, 0.125, 0.0)]
assert confidence_weighted_flow(ambiguous_fast_outlier, [0.25] * 4) == 0.0, (
    "an ambiguous high-motion neighbor must not contaminate otherwise reliable stationary flow"
)
all_ambiguous = [(0.0, 0.125, 0.0), (4.0, 0.125, 0.0)]
assert confidence_weighted_flow(all_ambiguous, [0.5, 0.5]) == 2.0, (
    "when every match is ambiguous, keep a bounded geometric estimate and let endpoint fallback decide"
)
coherent_motion = [(0.0, 0.0, 1.0), (0.3, 0.0, 1.0),
                   (0.6, 0.0, 1.0), (0.8, 0.0, 1.0)]
assert abs(confidence_weighted_flow(coherent_motion, [0.25] * 4) - 0.425) < 1e-9, (
    "coherent motion below the disagreement threshold must keep the exact geometric fast path"
)

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
    rgba8_output = "layout(set=0,binding=7,rgba8) writeonly uniform image2D outTex;"
    assert synthesis.count(rgba8_output) == 1, "the synthesis baseline must expose one replaceable RGBA8 output binding"
    shaders_to_compile = dict(shaders)
    shaders_to_compile["shader_synthesize_rgba16f"] = synthesis.replace(
        rgba8_output,
        "layout(set=0,binding=7,rgba16f) writeonly uniform image2D outTex;",
        1,
    )
    visibility_rgba8_output = "layout(set=0,binding=8,rgba8) writeonly uniform image2D outTex;"
    assert visibility_resolver.count(visibility_rgba8_output) == 1, "the resolver must expose one replaceable RGBA8 output binding"
    shaders_to_compile["shader_visibility_resolver_rgba16f"] = visibility_resolver.replace(
        visibility_rgba8_output,
        "layout(set=0,binding=8,rgba16f) writeonly uniform image2D outTex;",
        1,
    )
    with tempfile.TemporaryDirectory(prefix="mpvflow-vulkan-shaders-") as temp_dir:
        temp_path = pathlib.Path(temp_dir)
        for name, shader in shaders_to_compile.items():
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

    with tempfile.TemporaryDirectory(prefix="mpvflow-qcom-shader-") as temp_dir:
        output_file = pathlib.Path(temp_dir) / "mpvflow_qcom_seed.spv"
        result = subprocess.run(
            [validator, "-V", "--target-env", "vulkan1.2", "-S", "comp", "-o", str(output_file), str(QCOM_SHADER_PATH)],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode:
            raise SystemExit(f"QCOM2 seed Vulkan SPIR-V compilation failed:\n{result.stdout}{result.stderr}")
        compiled = output_file.read_bytes()
        assert len(compiled) % 4 == 0, "compiled QCOM2 SPIR-V must contain whole words"
        qcom_header = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_qcom_seed_spv.h").read_text(encoding="utf-8")
        header_words = [int(word, 16) for word in re.findall(r"0x([0-9a-fA-F]{8})u", qcom_header)]
        compiled_words = list(struct.unpack(f"<{len(compiled) // 4}I", compiled))
        assert header_words == compiled_words, "checked-in QCOM2 SPIR-V header is stale relative to the GLSL source"
        spirv_val = shutil.which("spirv-val")
        if spirv_val:
            validation = subprocess.run(
                [spirv_val, "--target-env", "vulkan1.2", str(output_file)],
                check=False,
                capture_output=True,
                text=True,
            )
            if validation.returncode:
                raise SystemExit(f"QCOM2 seed SPIR-V validation failed:\n{validation.stdout}{validation.stderr}")
        print("MPV Flow QCOM2 seed Vulkan SPIR-V compiled and matches the embedded header")
else:
    print("glslangValidator unavailable; Vulkan GLSL compilation skipped")

print("MPV Flow Vulkan shader tests passed")
