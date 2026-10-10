/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * GPU-resident, clean-room block-motion interpolation prototype. All pixel
 * processing is expressed as libplacebo compute passes; no CPU texture
 * transfers, pixel reads, or CPU-side synthesis occur here.
 */
#include "mpvflow_gpu.h"

#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define FLOW_LEVELS 3
#define FLOW_BLOCK 8
#define FLOW_STEP 4
#define FLOW_REDUCTIONS 8
#define FLOW_DEFAULT_MAX_DIM 640
#define FLOW_DEFAULT_RADIUS 8

enum {
    FLOW_DIAG_TEXTURE_ALLOC = 1u << 0,
    FLOW_DIAG_DISPATCH = 1u << 1,
    FLOW_DIAG_PREPARE_INPUT = 1u << 2,
    FLOW_DIAG_ANALYZE_PAIR = 1u << 3,
    FLOW_DIAG_SYNTHESIZE = 1u << 4,
};

struct FlowLevel {
    int w, h;
    pl_tex luma[2];
    pl_tex forward;
    pl_tex backward;
};

struct MPVFlowGPUContext {
    pl_gpu gpu;                 /* borrowed */
    int max_dimension;
    int search_radius;
    uint32_t diagnostic_log_mask;
    pl_fmt fmt_rgba16f;
    pl_fmt fmt_rgba8;
    pl_pass pass_preprocess;
    pl_pass pass_luma;
    pl_pass pass_downsample;
    pl_pass pass_reduce_first;
    pl_pass pass_reduce;
    pl_pass pass_flow;
    pl_pass pass_synthesize;
};

struct MPVFlowGPUPair {
    MPVFlowGPUContext *owner;
    pl_tex input[2];            /* borrowed from caller */
    pl_tex output;              /* owned, reused for each timestep */
    int w, h;
    int levels;
    struct FlowLevel level[FLOW_LEVELS];
    int reductions;
    pl_tex scene[FLOW_REDUCTIONS];
};

static void flow_log(pl_gpu gpu, enum pl_log_level level, const char *format, ...)
{
    if (!gpu || !gpu->log || !gpu->log->params.log_cb)
        return;
    char message[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    gpu->log->params.log_cb(gpu->log->params.log_priv, level, message);
}

static void flow_log_once(MPVFlowGPUContext *ctx, uint32_t bit,
                          enum pl_log_level level, const char *format, ...)
{
    if (!ctx || (ctx->diagnostic_log_mask & bit))
        return;
    ctx->diagnostic_log_mask |= bit;
    char message[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    flow_log(ctx->gpu, level, "%s", message);
}

/* All compute passes target libplacebo's Vulkan/SPIR-V backend. Constants use
 * one std430-compatible push-constant block; descriptor bindings are explicit
 * and unique within each pass. */
struct FlowPushConstants {
    int32_t cfg[4];
    float timestep;
};
_Static_assert(sizeof(struct FlowPushConstants) == 20, "Flow push-constant ABI");
static const char shader_preprocess[] =
    "#version 450\n"
    "layout(set=0,binding=0) uniform sampler2D inTex;\n"
    "layout(set=0,binding=1,rgba8) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,pc.cfg.xy))) return; vec2 uv=(vec2(p)+vec2(0.5))/vec2(pc.cfg.xy); imageStore(outTex,p,texture(inTex,uv)); }\n"
;
static const char shader_luma[] =
    "#version 450\n"
    "layout(set=0,binding=0) uniform sampler2D inTex;\n"
    "layout(set=0,binding=1,rgba16f) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,pc.cfg.xy))) return; vec3 c=texelFetch(inTex,p,0).rgb; float y=dot(c,vec3(0.299,0.587,0.114)); imageStore(outTex,p,vec4(y,0.0,0.0,1.0)); }\n"
;

static const char shader_downsample[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D inTex;\n"
    "layout(set=0,binding=1,rgba16f) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,pc.cfg.xy))) return; ivec2 sz=imageSize(inTex); float s=0.0; float n=0.0; for(int y=0;y<2;y++) for(int x=0;x<2;x++){ ivec2 q=p*2+ivec2(x,y); if(all(lessThan(q,sz))){s+=imageLoad(inTex,q).r;n+=1.0;}} imageStore(outTex,p,vec4(s/max(n,1.0),0.0,0.0,1.0)); }\n"
;

static const char shader_reduce_first[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D aTex;\n"
    "layout(set=0,binding=1,rgba16f) readonly uniform image2D bTex;\n"
    "layout(set=0,binding=2,rgba16f) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,pc.cfg.xy))) return; ivec2 sz=imageSize(aTex); float s=0.0; float n=0.0; for(int y=0;y<16;y++) for(int x=0;x<16;x++){ ivec2 q=p*16+ivec2(x,y); if(all(lessThan(q,sz))){ s+=abs(imageLoad(aTex,q).r-imageLoad(bTex,q).r); n+=1.0; }} imageStore(outTex,p,vec4(s/max(n,1.0),0.0,0.0,1.0)); }\n"
;

static const char shader_reduce[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D inTex;\n"
    "layout(set=0,binding=1,rgba16f) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,pc.cfg.xy))) return; ivec2 sz=imageSize(inTex); float s=0.0; float n=0.0; for(int y=0;y<16;y++) for(int x=0;x<16;x++){ ivec2 q=p*16+ivec2(x,y); if(all(lessThan(q,sz))){s+=imageLoad(inTex,q).r;n+=1.0;}} imageStore(outTex,p,vec4(s/max(n,1.0),0.0,0.0,1.0)); }\n"
;

static const char shader_flow[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D srcTex;\n"
    "layout(set=0,binding=1,rgba16f) readonly uniform image2D dstTex;\n"
    "layout(set=0,binding=2,rgba16f) readonly uniform image2D parentTex;\n"
    "layout(set=0,binding=3,rgba16f) writeonly uniform image2D flowTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "int sadAt(ivec2 p,ivec2 d,ivec2 sz,int cutoff){ int cost=0; for(int y=0;y<8;y++) for(int x=0;x<8;x++){ float a=imageLoad(srcTex,p+ivec2(x,y)).r; float b=imageLoad(dstTex,p+ivec2(x,y)+d).r; cost+=int(abs(a-b)*255.0+0.5); if(cost>cutoff) return cost; } return cost; }\n"
    "float subpixelOffset(float minusCost,float centerCost,float plusCost){ float curvature=minusCost-2.0*centerCost+plusCost; if(centerCost>minusCost||centerCost>plusCost||curvature<=0.0001) return 0.0; return clamp(0.5*(minusCost-plusCost)/curvature,-0.5,0.5); }\n"
    "float refineAxis(ivec2 p,ivec2 v,ivec2 axis,ivec2 sz,int centerCost){ ivec2 m=v-axis,q=v+axis; if(any(lessThan(p+m,ivec2(0)))||any(lessThan(p+q,ivec2(0)))|| any(greaterThan(p+m,sz-ivec2(8)))||any(greaterThan(p+q,sz-ivec2(8)))) return 0.0; int cm=sadAt(p,m,sz,2147483647),cp=sadAt(p,q,sz,2147483647); return subpixelOffset(float(cm),float(centerCost),float(cp)); }\n"
    "void main(){ ivec2 g=ivec2(gl_GlobalInvocationID.xy); ivec2 grid=imageSize(flowTex); if(any(greaterThanEqual(g,grid))) return; ivec2 sz=pc.cfg.xy; ivec2 p=min(g*4,sz-ivec2(8)); ivec2 center=ivec2(0); if(pc.cfg.w!=0){ ivec2 pg=imageSize(parentTex); vec2 pc=vec2(p)*0.5/4.0; ivec2 a=clamp(ivec2(floor(pc)),ivec2(0),pg-1); ivec2 b=min(a+1,pg-1); vec2 f=fract(pc); vec2 top=mix(imageLoad(parentTex,a).rg,imageLoad(parentTex,ivec2(b.x,a.y)).rg,f.x); vec2 bot=mix(imageLoad(parentTex,ivec2(a.x,b.y)).rg,imageLoad(parentTex,b).rg,f.x); center=ivec2(round(mix(top,bot,f.y)*2.0)); } center=clamp(center,-p,sz-ivec2(8)-p); int radius=pc.cfg.z; ivec2 lo=max(-p,center-ivec2(radius)); ivec2 hi=min(sz-ivec2(8)-p,center+ivec2(radius)); ivec2 bestVector=center; int best=sadAt(p,bestVector,sz,2147483647),second=2147483647; for(int dy=-8;dy<=8;dy++) for(int dx=-8;dx<=8;dx++){ if(abs(dx)>radius||abs(dy)>radius) continue; ivec2 d=center+ivec2(dx,dy); if(any(lessThan(d,lo))||any(greaterThan(d,hi))||all(equal(d,bestVector))) continue; int c=sadAt(p,d,sz,second); if(c<best){second=best;best=c;bestVector=d;} else if(c<second) second=c; } vec2 refined=vec2(bestVector); refined.x+=refineAxis(p,bestVector,ivec2(1,0),sz,best); refined.y+=refineAxis(p,bestVector,ivec2(0,1),sz,best); float uniqueness=second<2147483647?clamp(float(second-best)/max(float(second),1.0),0.0,1.0):0.0; imageStore(flowTex,g,vec4(refined,float(best)/(64.0*255.0),uniqueness)); }\n"
;

static const char shader_synthesize[] =
    "#version 450\n"
    "layout(set=0,binding=0) uniform sampler2D frame0; layout(set=0,binding=1) uniform sampler2D frame1;\n"
    "layout(set=0,binding=2,rgba16f) readonly uniform image2D fwdTex;\n"
    "layout(set=0,binding=3,rgba16f) readonly uniform image2D backTex;\n"
    "layout(set=0,binding=4,rgba16f) readonly uniform image2D sceneTex;\n"
    "layout(set=0,binding=5,rgba16f) readonly uniform image2D luma0Tex;\n"
    "layout(set=0,binding=6,rgba16f) readonly uniform image2D luma1Tex;\n"
    "layout(set=0,binding=7,rgba8) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "vec4 flowImageAt(ivec2 p,int direction){ return direction==0?imageLoad(fwdTex,p):imageLoad(backTex,p); }\n"
    "float lumaAt(vec2 p,int direction){ ivec2 sz=direction==0?imageSize(luma0Tex):imageSize(luma1Tex); ivec2 q=clamp(ivec2(round(p)),ivec2(0),sz-1); return direction==0?imageLoad(luma0Tex,q).r:imageLoad(luma1Tex,q).r; }\n"
    "vec4 flowAtEdgeAware(vec2 p,int direction){ ivec2 sz=imageSize(fwdTex); vec2 g=clamp(p/4.0,vec2(0.0),vec2(sz-1)); ivec2 a=ivec2(floor(g)); ivec2 b=min(a+1,sz-1); vec2 t=fract(g); vec4 v00=flowImageAt(a,direction),v10=flowImageAt(ivec2(b.x,a.y),direction); vec4 v01=flowImageAt(ivec2(a.x,b.y),direction),v11=flowImageAt(b,direction); float s00=(1.0-t.x)*(1.0-t.y),s10=t.x*(1.0-t.y),s01=(1.0-t.x)*t.y,s11=t.x*t.y; ivec2 lumaSize=direction==0?imageSize(luma0Tex):imageSize(luma1Tex); ivec2 c00=min(a*4,lumaSize-8)+4,c10=min(ivec2(b.x,a.y)*4,lumaSize-8)+4; ivec2 c01=min(ivec2(a.x,b.y)*4,lumaSize-8)+4,c11=min(b*4,lumaSize-8)+4; float guide=lumaAt(p,direction),d00=lumaAt(vec2(c00),direction)-guide; float d10=lumaAt(vec2(c10),direction)-guide,d01=lumaAt(vec2(c01),direction)-guide; float d11=lumaAt(vec2(c11),direction)-guide; float variance=s00*d00*d00+s10*d10*d10+s01*d01*d01+s11*d11*d11; float safeVariance=max(variance,0.000001); float w00=s00*exp(-(d00*d00)/safeVariance),w10=s10*exp(-(d10*d10)/safeVariance); float w01=s01*exp(-(d01*d01)/safeVariance),w11=s11*exp(-(d11*d11)/safeVariance); float total=w00+w10+w01+w11; return total>0.000001?(v00*w00+v10*w10+v01*w01+v11*w11)/total:flowImageAt(a,direction); }\n"
    "vec4 colorAt(sampler2D s,vec2 p){ ivec2 sz=textureSize(s,0); p=clamp(p,vec2(0.0),vec2(sz-1)); ivec2 a=ivec2(floor(p)); ivec2 b=min(a+1,sz-1); vec2 t=fract(p); return mix(mix(texelFetch(s,a,0),texelFetch(s,ivec2(b.x,a.y),0),t.x), mix(texelFetch(s,ivec2(a.x,b.y),0),texelFetch(s,b,0),t.x),t.y); }\n"
    "float pointInBounds(vec2 p,ivec2 sz){ return step(0.0,p.x)*step(p.x,float(sz.x-1))*step(0.0,p.y)*step(p.y,float(sz.y-1)); }\n"
    "float reliability(vec4 flow,float cycle,float valid){ float cycleQuality=1.0-smoothstep(1.0,6.0,cycle); float matchQuality=1.0-smoothstep(0.04,0.35,flow.z); float uniqueQuality=0.5+0.5*smoothstep(0.02,0.35,flow.w); return cycleQuality*matchQuality*uniqueQuality*valid; }\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,pc.cfg.xy))) return; float cut=imageLoad(sceneTex,ivec2(0)).r; if(cut>0.1882353){ vec4 c; if(pc.timestep<=0.5) c=texelFetch(frame0,p,0); else c=texelFetch(frame1,p,0); imageStore(outTex,p,c); return; } vec2 fp=vec2(p); vec4 f=flowAtEdgeAware(fp,0),b=flowAtEdgeAware(fp,1); vec2 a=fp-pc.timestep*f.xy,z=fp-(1.0-pc.timestep)*b.xy; for(int i=0;i<2;i++){ f=flowAtEdgeAware(a,0); a=fp-pc.timestep*f.xy; b=flowAtEdgeAware(z,1); z=fp-(1.0-pc.timestep)*b.xy; } f=flowAtEdgeAware(a,0); b=flowAtEdgeAware(z,1); vec2 cycle0=a+f.xy,cycle1=z+b.xy; vec4 backAt0=flowAtEdgeAware(cycle0,1); vec4 forwardAt1=flowAtEdgeAware(cycle1,0); float cycleError0=length(f.xy+backAt0.xy); float cycleError1=length(b.xy+forwardAt1.xy); vec4 ca=colorAt(frame0,a); vec4 cb=colorAt(frame1,z); float valid0=pointInBounds(a,pc.cfg.xy)*pointInBounds(cycle0,pc.cfg.xy); float valid1=pointInBounds(z,pc.cfg.xy)*pointInBounds(cycle1,pc.cfg.xy); float confidence0=reliability(f,cycleError0,valid0); float confidence1=reliability(b,cycleError1,valid1); float confidence=max(confidence0,confidence1); vec4 outc; if(confidence<0.15){ outc=pc.timestep<0.5?texelFetch(frame0,p,0):texelFetch(frame1,p,0); } else { float visibility0=smoothstep(0.15,0.45,confidence0),visibility1=smoothstep(0.15,0.45,confidence1); float weight0=(1.0-pc.timestep)*visibility0,weight1=pc.timestep*visibility1,total=weight0+weight1; float lumaDelta=abs(dot(ca.rgb,vec3(0.299,0.587,0.114))-dot(cb.rgb,vec3(0.299,0.587,0.114))); bool boundaryConflict=lumaDelta>0.12; if(boundaryConflict&&confidence0>confidence1+0.10) outc=ca; else if(boundaryConflict&&confidence1>confidence0+0.10) outc=cb; else if(boundaryConflict) outc=pc.timestep<0.5?texelFetch(frame0,p,0):texelFetch(frame1,p,0); else outc=total>0.0001?mix(ca,cb,weight1/total):(pc.timestep<0.5?texelFetch(frame0,p,0):texelFetch(frame1,p,0)); } outc.a=1.0; imageStore(outTex,p,outc); }\n"
;

static pl_pass make_pass(pl_gpu gpu, const char *glsl,
                         struct pl_desc *descs, int ndesc)
{
    return pl_pass_create(gpu, pl_pass_params(
        .type = PL_PASS_COMPUTE,
        .push_constants_size = sizeof(struct FlowPushConstants),
        .descriptors = descs,
        .num_descriptors = ndesc,
        .glsl_shader = glsl));
}

static pl_tex create_texture(MPVFlowGPUContext *ctx, int w, int h, pl_fmt fmt,
                             bool sampleable, const char *stage)
{
    pl_tex texture = pl_tex_create(ctx->gpu, pl_tex_params(
        .w = w, .h = h, .d = 0, .format = fmt,
        .sampleable = sampleable, .storable = true));
    if (!texture)
        flow_log_once(ctx, FLOW_DIAG_TEXTURE_ALLOC, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=compute_runtime state=failed stage=%s "
                      "reason=texture_allocation width=%d height=%d format=%s sampleable=%d storable=1",
                      stage, w, h, fmt ? fmt->name : "missing", sampleable);
    return texture;
}

static void destroy_tex(MPVFlowGPUContext *ctx, pl_tex *tex)
{
    if (tex && *tex)
        pl_tex_destroy(ctx->gpu, tex);
}

static bool run_pass(MPVFlowGPUContext *ctx, pl_pass pass,
                     struct pl_desc_binding *bindings, int nbindings,
                     const int cfg[4], float t, int w, int h,
                     const char *stage)
{
    if (!pass || w <= 0 || h <= 0) {
        flow_log_once(ctx, FLOW_DIAG_DISPATCH, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=compute_dispatch state=failed stage=%s "
                      "pass_available=%d width=%d height=%d reason=%s",
                      stage, !!pass, w, h, !pass ? "pass_missing" : "invalid_dimensions");
        return false;
    }
    struct FlowPushConstants push = {
        .cfg = { cfg[0], cfg[1], cfg[2], cfg[3] },
        .timestep = t,
    };
    struct pl_pass_run_params run = {
        .pass = pass,
        .desc_bindings = bindings,
        .push_constants = &push,
        .compute_groups = { (w + 7) / 8, (h + 7) / 8, 1 },
    };
    (void) nbindings; /* descriptor array length is fixed by the pass */
    pl_pass_run(ctx->gpu, &run);
    return true;
}

static bool allocate_pair_textures(MPVFlowGPUContext *ctx,
                                   MPVFlowGPUPair *pair)
{
    int w = pair->w, h = pair->h;
    pair->output = create_texture(ctx, w, h, ctx->fmt_rgba8, true, "pair_output");
    if (!pair->output)
        return false;
    for (int i = 0; i < FLOW_LEVELS && w >= FLOW_BLOCK && h >= FLOW_BLOCK; i++) {
        struct FlowLevel *lv = &pair->level[i];
        lv->w = w; lv->h = h;
        lv->luma[0] = create_texture(ctx, w, h, ctx->fmt_rgba16f, false, "pyramid_luma_frame0");
        lv->luma[1] = create_texture(ctx, w, h, ctx->fmt_rgba16f, false, "pyramid_luma_frame1");
        lv->forward = create_texture(ctx, (w + FLOW_STEP - 1) / FLOW_STEP,
                                     (h + FLOW_STEP - 1) / FLOW_STEP,
                                     ctx->fmt_rgba16f, false, "motion_forward_field");
        lv->backward = create_texture(ctx, (w + FLOW_STEP - 1) / FLOW_STEP,
                                      (h + FLOW_STEP - 1) / FLOW_STEP,
                                      ctx->fmt_rgba16f, false, "motion_backward_field");
        if (!lv->luma[0] || !lv->luma[1] || !lv->forward || !lv->backward)
            return false;
        pair->levels++;
        w = (w + 1) / 2;
        h = (h + 1) / 2;
    }
    if (!pair->levels)
        return false;

    /* First reduction computes the frame-wide average absolute luma delta in
     * 16x16 tiles. Further passes reduce those tile means to one scalar. */
    w = (pair->w + 15) / 16;
    h = (pair->h + 15) / 16;
    for (int i = 0; i < FLOW_REDUCTIONS; i++) {
        pair->scene[i] = create_texture(ctx, w, h, ctx->fmt_rgba16f, false,
                                        "scene_cut_reduction_texture");
        if (!pair->scene[i])
            return false;
        pair->reductions++;
        if (w == 1 && h == 1)
            break;
        w = (w + 15) / 16;
        h = (h + 15) / 16;
    }
    return w == 1 && h == 1;
}

static void destroy_pair_textures(MPVFlowGPUContext *ctx,
                                  MPVFlowGPUPair *pair)
{
    if (!pair)
        return;
    destroy_tex(ctx, &pair->output);
    for (int i = 0; i < FLOW_LEVELS; i++) {
        destroy_tex(ctx, &pair->level[i].luma[0]);
        destroy_tex(ctx, &pair->level[i].luma[1]);
        destroy_tex(ctx, &pair->level[i].forward);
        destroy_tex(ctx, &pair->level[i].backward);
    }
    for (int i = 0; i < FLOW_REDUCTIONS; i++)
        destroy_tex(ctx, &pair->scene[i]);
}

MPVFlowGPUContext *mpvflow_gpu_create(pl_gpu gpu,
                                      const struct MPVFlowGPUConfig *config,
                                      enum MPVFlowGPUResult *status,
                                      struct MPVFlowGPUCreateDiagnostics *diagnostics)
{
    if (diagnostics)
        *diagnostics = (struct MPVFlowGPUCreateDiagnostics) { .stage = "not_started" };
    if (status)
        *status = MPVFLOW_GPU_INVALID;
    if (!gpu) {
        if (diagnostics) diagnostics->stage = "gpu_missing";
        return NULL;
    }
    if (diagnostics) {
        diagnostics->max_pushc_size = gpu->limits.max_pushc_size;
        diagnostics->required_pushc_size = sizeof(struct FlowPushConstants);
    }
    flow_log(gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=compute_capabilities backend=%s glsl_compute=%d "
             "glsl_vulkan=%d glsl_version=%d max_pushc_size=%zu required_pushc_size=%zu "
             "max_tex_2d_dim=%u max_group_threads=%u max_group_size=%ux%ux%u compute_queues=%u",
             gpu->glsl.vulkan ? "vulkan" : "non_vulkan", gpu->glsl.compute,
             gpu->glsl.vulkan, gpu->glsl.version, gpu->limits.max_pushc_size,
             sizeof(struct FlowPushConstants), gpu->limits.max_tex_2d_dim,
             gpu->glsl.max_group_threads, gpu->glsl.max_group_size[0],
             gpu->glsl.max_group_size[1], gpu->glsl.max_group_size[2],
             gpu->limits.compute_queues);
    if (!gpu->glsl.compute || !gpu->glsl.vulkan || gpu->glsl.version < 450) {
        if (diagnostics) diagnostics->stage = "compute_api_capability";
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_setup state=failed stage=compute_api_capability "
                 "failed_compute=%d failed_vulkan=%d failed_glsl_version=%d",
                 !gpu->glsl.compute, !gpu->glsl.vulkan, gpu->glsl.version < 450);
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED_API;
        return NULL;
    }
    if (gpu->limits.max_pushc_size < sizeof(struct FlowPushConstants)) {
        if (diagnostics) diagnostics->stage = "push_constant_limit";
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_setup state=failed stage=push_constant_limit "
                 "max_pushc_size=%zu required_pushc_size=%zu",
                 gpu->limits.max_pushc_size, sizeof(struct FlowPushConstants));
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED_API;
        return NULL;
    }
    MPVFlowGPUContext *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) {
        if (diagnostics) diagnostics->stage = "context_allocation";
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_setup state=failed stage=context_allocation reason=out_of_memory");
        if (status) *status = MPVFLOW_GPU_ERROR;
        return NULL;
    }
    ctx->gpu = gpu;
    ctx->max_dimension = config && config->max_dimension > 0
        ? config->max_dimension : FLOW_DEFAULT_MAX_DIM;
    if (ctx->max_dimension > FLOW_DEFAULT_MAX_DIM)
        ctx->max_dimension = FLOW_DEFAULT_MAX_DIM;
    ctx->search_radius = config && config->search_radius > 0
        ? config->search_radius : FLOW_DEFAULT_RADIUS;
    if (ctx->search_radius > FLOW_DEFAULT_RADIUS)
        ctx->search_radius = FLOW_DEFAULT_RADIUS;
    if (ctx->search_radius < 1)
        ctx->search_radius = 1;
    if (ctx->max_dimension < FLOW_BLOCK ||
        ctx->max_dimension > (int) gpu->limits.max_tex_2d_dim) {
        if (diagnostics) diagnostics->stage = "texture_dimension_limit";
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_setup state=failed stage=texture_dimension_limit "
                 "requested_dimension=%d max_tex_2d_dim=%u min_required=%d",
                 ctx->max_dimension, gpu->limits.max_tex_2d_dim, FLOW_BLOCK);
        free(ctx);
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED_DIMENSION;
        return NULL;
    }

    ctx->fmt_rgba16f = pl_find_named_fmt(gpu, "rgba16f");
    ctx->fmt_rgba8 = pl_find_named_fmt(gpu, "rgba8");
    if (diagnostics) {
        diagnostics->rgba16f_found = ctx->fmt_rgba16f != NULL;
        diagnostics->rgba8_found = ctx->fmt_rgba8 != NULL;
        diagnostics->rgba16f_caps = ctx->fmt_rgba16f ? (uint32_t) ctx->fmt_rgba16f->caps : 0;
        diagnostics->rgba8_caps = ctx->fmt_rgba8 ? (uint32_t) ctx->fmt_rgba8->caps : 0;
    }
    if (!ctx->fmt_rgba16f || !ctx->fmt_rgba8 ||
        !(ctx->fmt_rgba16f->caps & PL_FMT_CAP_STORABLE) ||
        !(ctx->fmt_rgba8->caps & PL_FMT_CAP_STORABLE) ||
        !(ctx->fmt_rgba8->caps & PL_FMT_CAP_SAMPLEABLE) ||
        !ctx->fmt_rgba16f->glsl_format ||
        !ctx->fmt_rgba8->glsl_format ||
        strcmp(ctx->fmt_rgba16f->glsl_format, "rgba16f") ||
        strcmp(ctx->fmt_rgba8->glsl_format, "rgba8")) {
        if (diagnostics) diagnostics->stage = "required_storage_formats";
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_setup state=failed stage=required_storage_formats "
                 "rgba16f_found=%d rgba16f_caps=0x%x rgba16f_glsl=%s rgba8_found=%d "
                 "rgba8_caps=0x%x rgba8_glsl=%s required_rgba16f_storable=1 "
                 "required_rgba8_storable=1 required_rgba8_sampleable=1",
                 ctx->fmt_rgba16f != NULL,
                 diagnostics ? diagnostics->rgba16f_caps : (ctx->fmt_rgba16f ? (uint32_t) ctx->fmt_rgba16f->caps : 0),
                 ctx->fmt_rgba16f && ctx->fmt_rgba16f->glsl_format ? ctx->fmt_rgba16f->glsl_format : "missing",
                 ctx->fmt_rgba8 != NULL,
                 diagnostics ? diagnostics->rgba8_caps : (ctx->fmt_rgba8 ? (uint32_t) ctx->fmt_rgba8->caps : 0),
                 ctx->fmt_rgba8 && ctx->fmt_rgba8->glsl_format ? ctx->fmt_rgba8->glsl_format : "missing");
        free(ctx);
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED_FORMAT;
        return NULL;
    }

    struct pl_desc preprocess_desc[] = {
        { .name = "inTex", .type = PL_DESC_SAMPLED_TEX, .binding = 0 },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc luma_desc[] = {
        { .name = "inTex", .type = PL_DESC_SAMPLED_TEX, .binding = 0 },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc down_desc[] = {
        { .name = "inTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc first_desc[] = {
        { .name = "aTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "bTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 2,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc reduce_desc[] = {
        { .name = "inTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc flow_desc[] = {
        { .name = "srcTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "dstTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "parentTex", .type = PL_DESC_STORAGE_IMG, .binding = 2,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "flowTex", .type = PL_DESC_STORAGE_IMG, .binding = 3,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc synth_desc[] = {
        { .name = "frame0", .type = PL_DESC_SAMPLED_TEX, .binding = 0 },
        { .name = "frame1", .type = PL_DESC_SAMPLED_TEX, .binding = 1 },
        { .name = "fwdTex", .type = PL_DESC_STORAGE_IMG, .binding = 2,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "backTex", .type = PL_DESC_STORAGE_IMG, .binding = 3,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "sceneTex", .type = PL_DESC_STORAGE_IMG, .binding = 4,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "luma0Tex", .type = PL_DESC_STORAGE_IMG, .binding = 5,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "luma1Tex", .type = PL_DESC_STORAGE_IMG, .binding = 6,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 7,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    ctx->pass_preprocess = make_pass(gpu, shader_preprocess, preprocess_desc, 2);
    ctx->pass_luma = make_pass(gpu, shader_luma, luma_desc, 2);
    ctx->pass_downsample = make_pass(gpu, shader_downsample, down_desc, 2);
    ctx->pass_reduce_first = make_pass(gpu, shader_reduce_first, first_desc, 3);
    ctx->pass_reduce = make_pass(gpu, shader_reduce, reduce_desc, 2);
    ctx->pass_flow = make_pass(gpu, shader_flow, flow_desc, 4);
    ctx->pass_synthesize = make_pass(gpu, shader_synthesize, synth_desc, 8);
    uint32_t failed_pass_mask = 0;
    if (!ctx->pass_preprocess) failed_pass_mask |= MPVFLOW_GPU_PASS_PREPROCESS;
    if (!ctx->pass_luma) failed_pass_mask |= MPVFLOW_GPU_PASS_LUMA;
    if (!ctx->pass_downsample) failed_pass_mask |= MPVFLOW_GPU_PASS_DOWNSAMPLE;
    if (!ctx->pass_reduce_first) failed_pass_mask |= MPVFLOW_GPU_PASS_REDUCE_FIRST;
    if (!ctx->pass_reduce) failed_pass_mask |= MPVFLOW_GPU_PASS_REDUCE;
    if (!ctx->pass_flow) failed_pass_mask |= MPVFLOW_GPU_PASS_MOTION;
    if (!ctx->pass_synthesize) failed_pass_mask |= MPVFLOW_GPU_PASS_SYNTHESIZE;
    if (diagnostics) {
        diagnostics->stage = failed_pass_mask ? "compute_pass_creation" : "ready";
        diagnostics->failed_pass_mask = failed_pass_mask;
    }
    if (!ctx->pass_preprocess || !ctx->pass_luma || !ctx->pass_downsample || !ctx->pass_reduce_first ||
        !ctx->pass_reduce || !ctx->pass_flow || !ctx->pass_synthesize) {
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=shader_setup state=failed stage=compute_pass_creation "
                 "missing_pass_mask=0x%x preprocess=%d luma=%d downsample=%d reduce_first=%d "
                 "reduce=%d motion=%d synthesize=%d",
                 failed_pass_mask, !!ctx->pass_preprocess, !!ctx->pass_luma,
                 !!ctx->pass_downsample, !!ctx->pass_reduce_first, !!ctx->pass_reduce,
                 !!ctx->pass_flow, !!ctx->pass_synthesize);
        mpvflow_gpu_destroy(ctx);
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED_SHADER;
        return NULL;
    }
    if (status)
        *status = MPVFLOW_GPU_OK;
    flow_log(gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=shader_setup state=ready stage=compute_pass_creation "
             "pass_mask=0x7f max_dimension=%d search_radius=%d",
             ctx->max_dimension, ctx->search_radius);
    return ctx;
}

void mpvflow_gpu_destroy(MPVFlowGPUContext *ctx)
{
    if (!ctx)
        return;
    if (ctx->pass_preprocess) pl_pass_destroy(ctx->gpu, &ctx->pass_preprocess);
    if (ctx->pass_luma) pl_pass_destroy(ctx->gpu, &ctx->pass_luma);
    if (ctx->pass_downsample) pl_pass_destroy(ctx->gpu, &ctx->pass_downsample);
    if (ctx->pass_reduce_first) pl_pass_destroy(ctx->gpu, &ctx->pass_reduce_first);
    if (ctx->pass_reduce) pl_pass_destroy(ctx->gpu, &ctx->pass_reduce);
    if (ctx->pass_flow) pl_pass_destroy(ctx->gpu, &ctx->pass_flow);
    if (ctx->pass_synthesize) pl_pass_destroy(ctx->gpu, &ctx->pass_synthesize);
    free(ctx);
}

enum MPVFlowGPUResult mpvflow_gpu_prepare_input(MPVFlowGPUContext *ctx,
                                                pl_tex input,
                                                pl_tex *prepared_out)
{
    if (prepared_out)
        *prepared_out = NULL;
    if (!ctx || !input || !prepared_out)
        return MPVFLOW_GPU_INVALID;
    if (!input->params.sampleable || input->params.w < FLOW_BLOCK ||
        input->params.h < FLOW_BLOCK) {
        flow_log_once(ctx, FLOW_DIAG_PREPARE_INPUT, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=prepare_input state=failed stage=input_texture_validation "
                      "width=%u height=%u sampleable=%d format=%s format_caps=0x%x min_dimension=%d",
                      input->params.w, input->params.h, input->params.sampleable,
                      input->params.format ? input->params.format->name : "missing",
                      input->params.format ? (unsigned int) input->params.format->caps : 0,
                      FLOW_BLOCK);
        return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
    }

    int source_w = (int) input->params.w;
    int source_h = (int) input->params.h;
    double scale = fmin(1.0, (double) ctx->max_dimension /
                              fmax((double) source_w, (double) source_h));
    int w = (int) lround(source_w * scale);
    int h = (int) lround(source_h * scale);
    if (w < FLOW_BLOCK || h < FLOW_BLOCK) {
        flow_log_once(ctx, FLOW_DIAG_PREPARE_INPUT, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=prepare_input state=failed stage=scaled_input_dimensions "
                      "source=%dx%d scaled=%dx%d max_dimension=%d min_dimension=%d",
                      source_w, source_h, w, h, ctx->max_dimension, FLOW_BLOCK);
        return MPVFLOW_GPU_UNSUPPORTED_DIMENSION;
    }

    pl_tex prepared = create_texture(ctx, w, h, ctx->fmt_rgba8, true,
                                     "prepared_input_rgba8");
    if (!prepared) {
        flow_log_once(ctx, FLOW_DIAG_PREPARE_INPUT, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=prepare_input state=failed stage=prepared_texture_allocation "
                      "width=%d height=%d format=rgba8 sampleable=1 storable=1",
                      w, h);
        return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
    }
    struct pl_desc_binding bindings[] = {
        { .object = input, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_LINEAR },
        { .object = prepared },
    };
    int cfg[4] = { w, h, 0, 0 };
    if (!run_pass(ctx, ctx->pass_preprocess, bindings, 2, cfg, 0.0f, w, h,
                  "preprocess_input")) {
        destroy_tex(ctx, &prepared);
        flow_log_once(ctx, FLOW_DIAG_PREPARE_INPUT, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=prepare_input state=failed stage=preprocess_dispatch "
                      "width=%d height=%d status=%d",
                      w, h, MPVFLOW_GPU_ERROR);
        return MPVFLOW_GPU_ERROR;
    }
    *prepared_out = prepared;
    return MPVFLOW_GPU_OK;
}

enum MPVFlowGPUResult mpvflow_gpu_analyze_pair(MPVFlowGPUContext *ctx,
                                               pl_tex frame0, pl_tex frame1,
                                               MPVFlowGPUPair **pair_out)
{
    if (pair_out)
        *pair_out = NULL;
    if (!ctx || !frame0 || !frame1 || !pair_out)
        return MPVFLOW_GPU_INVALID;
    const struct pl_tex_params *p0 = &frame0->params;
    const struct pl_tex_params *p1 = &frame1->params;
    if (p0->d != 0 || p1->d != 0 || p0->w != p1->w || p0->h != p1->h ||
        p0->w < FLOW_BLOCK || p0->h < FLOW_BLOCK ||
        p0->w > ctx->max_dimension || p0->h > ctx->max_dimension ||
        frame0->sampler_type != PL_SAMPLER_NORMAL ||
        frame1->sampler_type != PL_SAMPLER_NORMAL ||
        !p0->sampleable || !p1->sampleable || !p0->format || !p1->format ||
        p0->format->num_planes != 0 || p1->format->num_planes != 0 ||
        p0->format->num_components < 3 || p1->format->num_components < 3 ||
        !(p0->format->caps & PL_FMT_CAP_SAMPLEABLE) ||
        !(p1->format->caps & PL_FMT_CAP_SAMPLEABLE)) {
        flow_log_once(ctx, FLOW_DIAG_ANALYZE_PAIR, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=analyze_pair state=failed stage=input_pair_validation "
                      "frame0=%ux%u depth=%u sampler=%d sampleable=%d format=%s caps=0x%x planes=%d components=%d "
                      "frame1=%ux%u depth=%u sampler=%d sampleable=%d format=%s caps=0x%x planes=%d components=%d "
                      "max_dimension=%d required_sampleable_rgb=1",
                      p0->w, p0->h, p0->d, frame0->sampler_type, p0->sampleable,
                      p0->format ? p0->format->name : "missing",
                      p0->format ? (unsigned int) p0->format->caps : 0,
                      p0->format ? p0->format->num_planes : -1,
                      p0->format ? p0->format->num_components : -1,
                      p1->w, p1->h, p1->d, frame1->sampler_type, p1->sampleable,
                      p1->format ? p1->format->name : "missing",
                      p1->format ? (unsigned int) p1->format->caps : 0,
                      p1->format ? p1->format->num_planes : -1,
                      p1->format ? p1->format->num_components : -1,
                      ctx->max_dimension);
        return MPVFLOW_GPU_UNSUPPORTED;
    }

    MPVFlowGPUPair *pair = calloc(1, sizeof(*pair));
    if (!pair) {
        flow_log_once(ctx, FLOW_DIAG_ANALYZE_PAIR, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=analyze_pair state=failed stage=pair_allocation reason=out_of_memory");
        return MPVFLOW_GPU_ERROR;
    }
    pair->owner = ctx;
    pair->input[0] = frame0;
    pair->input[1] = frame1;
    pair->w = p0->w;
    pair->h = p0->h;
    if (!allocate_pair_textures(ctx, pair)) {
        flow_log_once(ctx, FLOW_DIAG_ANALYZE_PAIR, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=analyze_pair state=failed stage=pair_scratch_texture_allocation "
                      "width=%d height=%d levels=%d reductions=%d",
                      pair->w, pair->h, pair->levels, pair->reductions);
        destroy_pair_textures(ctx, pair);
        free(pair);
        return MPVFLOW_GPU_ERROR;
    }

    int cfg[4] = { pair->w, pair->h, 0, 0 };
    struct pl_desc_binding lb[2] = {
        { .object = frame0, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_NEAREST },
        { .object = pair->level[0].luma[0] },
    };
    if (!run_pass(ctx, ctx->pass_luma, lb, 2, cfg, 0.0f, pair->w, pair->h,
                  "luma_frame0"))
        goto failure;
    lb[0].object = frame1;
    lb[1].object = pair->level[0].luma[1];
    if (!run_pass(ctx, ctx->pass_luma, lb, 2, cfg, 0.0f, pair->w, pair->h,
                  "luma_frame1"))
        goto failure;

    for (int i = 1; i < pair->levels; i++) {
        struct FlowLevel *prev = &pair->level[i - 1];
        struct FlowLevel *lv = &pair->level[i];
        int dc[4] = { lv->w, lv->h, 0, 0 };
        struct pl_desc_binding db[2] = { { .object = prev->luma[0] },
                                         { .object = lv->luma[0] } };
        if (!run_pass(ctx, ctx->pass_downsample, db, 2, dc, 0.0f, lv->w, lv->h,
                      "pyramid_downsample_frame0"))
            goto failure;
        db[0].object = prev->luma[1];
        db[1].object = lv->luma[1];
        if (!run_pass(ctx, ctx->pass_downsample, db, 2, dc, 0.0f, lv->w, lv->h,
                      "pyramid_downsample_frame1"))
            goto failure;
    }

    int rw = (pair->w + 15) / 16, rh = (pair->h + 15) / 16;
    int rc[4] = { rw, rh, 0, 0 };
    struct pl_desc_binding rb[3] = {
        { .object = pair->level[0].luma[0] },
        { .object = pair->level[0].luma[1] },
        { .object = pair->scene[0] },
    };
    if (!run_pass(ctx, ctx->pass_reduce_first, rb, 3, rc, 0.0f, rw, rh,
                  "scene_cut_reduce_first"))
        goto failure;
    for (int i = 1; i < pair->reductions; i++) {
        int prevw = (rw + 15) / 16, prevh = (rh + 15) / 16;
        if (rw == 1 && rh == 1)
            break;
        rw = prevw; rh = prevh;
        rc[0] = rw; rc[1] = rh;
        struct pl_desc_binding r2[2] = {
            { .object = pair->scene[i - 1] }, { .object = pair->scene[i] },
        };
        if (!run_pass(ctx, ctx->pass_reduce, r2, 2, rc, 0.0f, rw, rh,
                      "scene_cut_reduce"))
            goto failure;
    }

    for (int dir = 0; dir < 2; dir++) {
        for (int li = pair->levels - 1; li >= 0; li--) {
            struct FlowLevel *lv = &pair->level[li];
            struct FlowLevel *parent = li + 1 < pair->levels
                ? &pair->level[li + 1] : NULL;
            pl_tex src = lv->luma[dir ? 1 : 0];
            pl_tex dst = lv->luma[dir ? 0 : 1];
            pl_tex flow = dir ? lv->backward : lv->forward;
            pl_tex parent_flow = parent
                ? (dir ? parent->backward : parent->forward)
                : (dir ? lv->forward : lv->backward);
            int radius = parent ? 2 : ctx->search_radius;
            int fc[4] = { lv->w, lv->h, radius, parent ? 1 : 0 };
            struct pl_desc_binding fb[4] = {
                { .object = src }, { .object = dst }, { .object = parent_flow },
                { .object = flow },
            };
            if (!run_pass(ctx, ctx->pass_flow, fb, 4, fc, 0.0f,
                          flow->params.w, flow->params.h,
                          dir ? "motion_search_backward" : "motion_search_forward"))
                goto failure;
        }
    }
    *pair_out = pair;
    return MPVFLOW_GPU_OK;

failure:
    flow_log_once(ctx, FLOW_DIAG_ANALYZE_PAIR, PL_LOG_WARN,
                  "MPVFLOW_DIAGNOSTIC event=analyze_pair state=failed stage=motion_analysis_dispatch "
                  "width=%d height=%d levels=%d reductions=%d status=%d; see preceding compute_dispatch stage",
                  pair->w, pair->h, pair->levels, pair->reductions, MPVFLOW_GPU_ERROR);
    destroy_pair_textures(ctx, pair);
    free(pair);
    return MPVFLOW_GPU_ERROR;
}

void mpvflow_gpu_pair_release(MPVFlowGPUContext *ctx, MPVFlowGPUPair *pair)
{
    if (!pair)
        return;
    if (!ctx)
        ctx = pair->owner;
    if (ctx && ctx == pair->owner)
        destroy_pair_textures(ctx, pair);
    free(pair);
}

enum MPVFlowGPUResult mpvflow_gpu_synthesize(MPVFlowGPUContext *ctx,
                                             const MPVFlowGPUPair *pair,
                                             float t, pl_tex *output)
{
    if (output)
        *output = NULL;
    if (!ctx || !pair || !output || pair->owner != ctx ||
        !isfinite(t) || t <= 0.0f || t >= 1.0f) {
        flow_log_once(ctx, FLOW_DIAG_SYNTHESIZE, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=synthesize state=failed stage=input_validation "
                      "pair_present=%d output_pointer_present=%d timestep=%.9g reason=invalid_context_pair_or_timestep",
                      pair != NULL, output != NULL, t);
        return MPVFLOW_GPU_INVALID;
    }
    pl_tex out = pair->output;
    if (!out) {
        flow_log_once(ctx, FLOW_DIAG_SYNTHESIZE, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=synthesize state=failed stage=pair_output_texture reason=missing");
        return MPVFLOW_GPU_UNSUPPORTED;
    }
    struct FlowLevel *full = (struct FlowLevel *) &pair->level[0];
    int cfg[4] = { pair->w, pair->h, 0, 0 };
    struct pl_desc_binding sb[8] = {
        { .object = pair->input[0], .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_NEAREST },
        { .object = pair->input[1], .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_NEAREST },
        { .object = full->forward },
        { .object = full->backward },
        { .object = pair->scene[pair->reductions - 1] },
        { .object = full->luma[0] },
        { .object = full->luma[1] },
        { .object = out },
    };
    if (!run_pass(ctx, ctx->pass_synthesize, sb, 8, cfg, t, pair->w, pair->h,
                  "synthesize_output")) {
        flow_log_once(ctx, FLOW_DIAG_SYNTHESIZE, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=synthesize state=failed stage=synthesis_dispatch "
                      "width=%d height=%d timestep=%.9g status=%d; see preceding compute_dispatch stage",
                      pair->w, pair->h, t, MPVFLOW_GPU_ERROR);
        return MPVFLOW_GPU_ERROR;
    }
    *output = out;
    return MPVFLOW_GPU_OK;
}
