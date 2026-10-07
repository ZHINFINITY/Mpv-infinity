/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * GPU-resident, clean-room block-motion interpolation prototype. All pixel
 * processing is expressed as libplacebo compute passes; no CPU texture
 * transfers, pixel reads, or CPU-side synthesis occur here.
 */
#include "mpvflow_gpu.h"

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define FLOW_LEVELS 3
#define FLOW_BLOCK 8
#define FLOW_STEP 4
#define FLOW_REDUCTIONS 8
#define FLOW_DEFAULT_MAX_DIM 480
#define FLOW_DEFAULT_RADIUS 8

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
    pl_fmt fmt_r32f;
    pl_fmt fmt_rgba16f;
    pl_fmt fmt_rgba8;
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

/* Pass uniforms are ordinary GLSL uniforms. This avoids Vulkan-only push
 * constant syntax and works with GLES 3.1 as well as desktop OpenGL. */
static const struct pl_var flow_vars[] = {
    { .name = "cfg", .type = PL_VAR_SINT, .dim_v = 4, .dim_m = 1, .dim_a = 1 },
    { .name = "timestep", .type = PL_VAR_FLOAT, .dim_v = 1, .dim_m = 1, .dim_a = 1 },
};

static const char shader_luma[] =
    "#version 310 es\n"
    "precision highp float; precision highp int;\n"
    "uniform highp sampler2D inTex;\n"
    "layout(r32f) writeonly uniform highp image2D outTex;\n"
    "uniform ivec4 cfg; uniform float timestep;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy);"
    " if(any(greaterThanEqual(p,cfg.xy))) return;"
    " vec3 c=texelFetch(inTex,p,0).rgb;"
    " float y=dot(c,vec3(0.299,0.587,0.114));"
    " imageStore(outTex,p,vec4(y,0.0,0.0,1.0)); }\n";

static const char shader_downsample[] =
    "#version 310 es\n"
    "precision highp float; precision highp int;\n"
    "layout(r32f) readonly uniform highp image2D inTex;\n"
    "layout(r32f) writeonly uniform highp image2D outTex;\n"
    "uniform ivec4 cfg; uniform float timestep;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy);"
    " if(any(greaterThanEqual(p,cfg.xy))) return; ivec2 sz=imageSize(inTex);"
    " float s=0.0; float n=0.0; for(int y=0;y<2;y++) for(int x=0;x<2;x++){"
    " ivec2 q=p*2+ivec2(x,y); if(all(lessThan(q,sz))){s+=imageLoad(inTex,q).r;n+=1.0;}}"
    " imageStore(outTex,p,vec4(s/max(n,1.0),0.0,0.0,1.0)); }\n";

static const char shader_reduce_first[] =
    "#version 310 es\n"
    "precision highp float; precision highp int;\n"
    "layout(r32f) readonly uniform highp image2D aTex;\n"
    "layout(r32f) readonly uniform highp image2D bTex;\n"
    "layout(r32f) writeonly uniform highp image2D outTex;\n"
    "uniform ivec4 cfg; uniform float timestep;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy);"
    " if(any(greaterThanEqual(p,cfg.xy))) return; ivec2 sz=imageSize(aTex);"
    " float s=0.0; float n=0.0; for(int y=0;y<16;y++) for(int x=0;x<16;x++){"
    " ivec2 q=p*16+ivec2(x,y); if(all(lessThan(q,sz))){"
    " s+=abs(imageLoad(aTex,q).r-imageLoad(bTex,q).r); n+=1.0; }}"
    " imageStore(outTex,p,vec4(s/max(n,1.0),0.0,0.0,1.0)); }\n";

static const char shader_reduce[] =
    "#version 310 es\n"
    "precision highp float; precision highp int;\n"
    "layout(r32f) readonly uniform highp image2D inTex;\n"
    "layout(r32f) writeonly uniform highp image2D outTex;\n"
    "uniform ivec4 cfg; uniform float timestep;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy);"
    " if(any(greaterThanEqual(p,cfg.xy))) return; ivec2 sz=imageSize(inTex);"
    " float s=0.0; float n=0.0; for(int y=0;y<16;y++) for(int x=0;x<16;x++){"
    " ivec2 q=p*16+ivec2(x,y); if(all(lessThan(q,sz))){s+=imageLoad(inTex,q).r;n+=1.0;}}"
    " imageStore(outTex,p,vec4(s/max(n,1.0),0.0,0.0,1.0)); }\n";

/* One output vector per half-block-grid location. Candidate block SAD is
 * searched locally at the coarsest level, then around the doubled parent-level
 * estimate. cfg=(width,height,radius,has_parent). */
static const char shader_flow[] =
    "#version 310 es\n"
    "precision highp float; precision highp int;\n"
    "layout(r32f) readonly uniform highp image2D srcTex;\n"
    "layout(r32f) readonly uniform highp image2D dstTex;\n"
    "layout(rgba16f) readonly uniform highp image2D parentTex;\n"
    "layout(rgba16f) writeonly uniform highp image2D flowTex;\n"
    "uniform ivec4 cfg; uniform float timestep;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "int sadAt(ivec2 p, ivec2 d, ivec2 sz, int best){ int cost=0;"
    " for(int y=0;y<8;y++) for(int x=0;x<8;x++){"
    " float a=imageLoad(srcTex,p+ivec2(x,y)).r;"
    " float b=imageLoad(dstTex,p+ivec2(x,y)+d).r;"
    " cost+=int(abs(a-b)*255.0+0.5); if(cost>best) return cost; } return cost; }\n"
    "void main(){ ivec2 g=ivec2(gl_GlobalInvocationID.xy); ivec2 grid=imageSize(flowTex);"
    " if(any(greaterThanEqual(g,grid))) return; ivec2 sz=cfg.xy;"
    " ivec2 p=min(g*4,sz-ivec2(8)); ivec2 center=ivec2(0);"
    " if(cfg.w!=0){ ivec2 pg=imageSize(parentTex);"
    " vec2 v=imageLoad(parentTex,clamp((p/2)/4,ivec2(0),pg-1)).rg;"
    " center=ivec2(round(v*2.0)); }"
    " int radius=cfg.z; ivec2 lo=max(-p,center-ivec2(radius));"
    " ivec2 hi=min(sz-ivec2(8)-p,center+ivec2(radius));"
    " int best=2147483647; ivec2 bv=clamp(center,lo,hi);"
    " best=sadAt(p,bv,sz,best);"
    " for(int dy=-8;dy<=8;dy++) for(int dx=-8;dx<=8;dx++){"
    " if(abs(dx)>radius||abs(dy)>radius) continue; ivec2 d=center+ivec2(dx,dy);"
    " if(any(lessThan(d,lo))||any(greaterThan(d,hi))||all(equal(d,bv))) continue;"
    " int c=sadAt(p,d,sz,best); if(c<best||(c==best&&abs(d.x)+abs(d.y)<abs(bv.x)+abs(bv.y))){best=c;bv=d;} }"
    " imageStore(flowTex,g,vec4(vec2(bv),0.0,1.0)); }\n";

static const char shader_synthesize[] =
    "#version 310 es\n"
    "precision highp float; precision highp int;\n"
    "uniform highp sampler2D frame0; uniform highp sampler2D frame1;\n"
    "layout(rgba16f) readonly uniform highp image2D fwdTex;\n"
    "layout(rgba16f) readonly uniform highp image2D backTex;\n"
    "layout(r32f) readonly uniform highp image2D sceneTex;\n"
    "layout(rgba8) writeonly uniform highp image2D outTex;\n"
    "uniform ivec4 cfg; uniform float timestep;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "vec2 flowAtForward(vec2 p){ ivec2 sz=imageSize(fwdTex); vec2 g=clamp(p/4.0,vec2(0.0),vec2(sz-1));"
    " ivec2 a=ivec2(floor(g)); ivec2 b=min(a+1,sz-1); vec2 t=fract(g);"
    " vec2 x=mix(imageLoad(fwdTex,a).rg,imageLoad(fwdTex,ivec2(b.x,a.y)).rg,t.x);"
    " vec2 y=mix(imageLoad(fwdTex,ivec2(a.x,b.y)).rg,imageLoad(fwdTex,b).rg,t.x); return mix(x,y,t.y); }\n"
    "vec2 flowAtBackward(vec2 p){ ivec2 sz=imageSize(backTex); vec2 g=clamp(p/4.0,vec2(0.0),vec2(sz-1));"
    " ivec2 a=ivec2(floor(g)); ivec2 b=min(a+1,sz-1); vec2 t=fract(g);"
    " vec2 x=mix(imageLoad(backTex,a).rg,imageLoad(backTex,ivec2(b.x,a.y)).rg,t.x);"
    " vec2 y=mix(imageLoad(backTex,ivec2(a.x,b.y)).rg,imageLoad(backTex,b).rg,t.x); return mix(x,y,t.y); }\n"
    "vec4 colorAt(highp sampler2D s,vec2 p){ ivec2 sz=textureSize(s,0); p=clamp(p,vec2(0.0),vec2(sz-1));"
    " ivec2 a=ivec2(floor(p)); ivec2 b=min(a+1,sz-1); vec2 t=fract(p);"
    " return mix(mix(texelFetch(s,a,0),texelFetch(s,ivec2(b.x,a.y),0),t.x),"
    " mix(texelFetch(s,ivec2(a.x,b.y),0),texelFetch(s,b,0),t.x),t.y); }\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,cfg.xy))) return;"
    " float cut=imageLoad(sceneTex,ivec2(0)).r;"
    " if(cut>0.1882353){ vec4 c; if(timestep<=0.5) c=texelFetch(frame0,p,0); else c=texelFetch(frame1,p,0); imageStore(outTex,p,c); return; }"
    " vec2 fp=vec2(p); vec2 f=flowAtForward(fp); vec2 b=flowAtBackward(fp);"
    " vec2 a=fp-timestep*f; vec2 z=fp-(1.0-timestep)*b;"
    " for(int i=0;i<2;i++){ f=flowAtForward(a); a=fp-timestep*f;"
    " b=flowAtBackward(z); z=fp-(1.0-timestep)*b; }"
    " float consistency=length(f+b); vec4 ca=colorAt(frame0,a); vec4 cb=colorAt(frame1,z);"
    " float photo=(abs(ca.r-cb.r)+abs(ca.g-cb.g)+abs(ca.b-cb.b))/3.0;"
    " float confidence=clamp(1.0-consistency/4.0,0.0,1.0)*clamp(1.0-photo/0.3764706,0.0,1.0);"
    " float wa=(1.0-timestep)*confidence+(timestep<=0.5?1.0-confidence:0.0);"
    " float wb=timestep*confidence+(timestep>0.5?1.0-confidence:0.0);"
    " vec4 outc=(ca*wa+cb*wb)/max(wa+wb,0.00001); outc.a=1.0; imageStore(outTex,p,outc); }\n";

static pl_pass make_pass(pl_gpu gpu, const char *glsl,
                         struct pl_desc *descs, int ndesc)
{
    return pl_pass_create(gpu, pl_pass_params(
        .type = PL_PASS_COMPUTE,
        .variables = (struct pl_var *) flow_vars,
        .num_variables = (int) (sizeof(flow_vars) / sizeof(flow_vars[0])),
        .descriptors = descs,
        .num_descriptors = ndesc,
        .glsl_shader = glsl));
}

static pl_tex create_texture(MPVFlowGPUContext *ctx, int w, int h, pl_fmt fmt,
                             bool sampleable)
{
    return pl_tex_create(ctx->gpu, pl_tex_params(
        .w = w, .h = h, .d = 0, .format = fmt,
        .sampleable = sampleable, .storable = true));
}

static void destroy_tex(MPVFlowGPUContext *ctx, pl_tex *tex)
{
    if (tex && *tex)
        pl_tex_destroy(ctx->gpu, tex);
}

static bool run_pass(MPVFlowGPUContext *ctx, pl_pass pass,
                     struct pl_desc_binding *bindings, int nbindings,
                     const int cfg[4], float t, int w, int h)
{
    if (!pass || w <= 0 || h <= 0)
        return false;
    struct pl_var_update updates[2] = {
        { .index = 0, .data = cfg },
        { .index = 1, .data = &t },
    };
    struct pl_pass_run_params run = {
        .pass = pass,
        .var_updates = updates,
        .num_var_updates = 2,
        .desc_bindings = bindings,
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
    pair->output = create_texture(ctx, w, h, ctx->fmt_rgba8, true);
    if (!pair->output)
        return false;
    for (int i = 0; i < FLOW_LEVELS && w >= FLOW_BLOCK && h >= FLOW_BLOCK; i++) {
        struct FlowLevel *lv = &pair->level[i];
        lv->w = w; lv->h = h;
        lv->luma[0] = create_texture(ctx, w, h, ctx->fmt_r32f, false);
        lv->luma[1] = create_texture(ctx, w, h, ctx->fmt_r32f, false);
        lv->forward = create_texture(ctx, (w + FLOW_STEP - 1) / FLOW_STEP,
                                     (h + FLOW_STEP - 1) / FLOW_STEP,
                                     ctx->fmt_rgba16f, false);
        lv->backward = create_texture(ctx, (w + FLOW_STEP - 1) / FLOW_STEP,
                                      (h + FLOW_STEP - 1) / FLOW_STEP,
                                      ctx->fmt_rgba16f, false);
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
        pair->scene[i] = create_texture(ctx, w, h, ctx->fmt_r32f, false);
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
                                      enum MPVFlowGPUResult *status)
{
    if (status)
        *status = MPVFLOW_GPU_INVALID;
    if (!gpu)
        return NULL;
    if (!gpu->glsl.compute || !gpu->glsl.gles || gpu->glsl.version < 310 ||
        gpu->glsl.vulkan) {
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED;
        return NULL;
    }
    MPVFlowGPUContext *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) {
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
        free(ctx);
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED;
        return NULL;
    }

    ctx->fmt_r32f = pl_find_named_fmt(gpu, "r32f");
    ctx->fmt_rgba16f = pl_find_named_fmt(gpu, "rgba16f");
    ctx->fmt_rgba8 = pl_find_named_fmt(gpu, "rgba8");
    if (!ctx->fmt_r32f || !ctx->fmt_rgba16f || !ctx->fmt_rgba8 ||
        !(ctx->fmt_r32f->caps & PL_FMT_CAP_STORABLE) ||
        !(ctx->fmt_rgba16f->caps & PL_FMT_CAP_STORABLE) ||
        !(ctx->fmt_rgba8->caps & PL_FMT_CAP_STORABLE) ||
        !(ctx->fmt_rgba8->caps & PL_FMT_CAP_SAMPLEABLE) ||
        !ctx->fmt_r32f->glsl_format || !ctx->fmt_rgba16f->glsl_format ||
        !ctx->fmt_rgba8->glsl_format ||
        strcmp(ctx->fmt_r32f->glsl_format, "r32f") ||
        strcmp(ctx->fmt_rgba16f->glsl_format, "rgba16f") ||
        strcmp(ctx->fmt_rgba8->glsl_format, "rgba8")) {
        free(ctx);
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED;
        return NULL;
    }

    struct pl_desc luma_desc[] = {
        { .name = "inTex", .type = PL_DESC_SAMPLED_TEX, .binding = 0 },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
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
        { .name = "fwdTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "backTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "sceneTex", .type = PL_DESC_STORAGE_IMG, .binding = 2,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 3,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    ctx->pass_luma = make_pass(gpu, shader_luma, luma_desc, 2);
    ctx->pass_downsample = make_pass(gpu, shader_downsample, down_desc, 2);
    ctx->pass_reduce_first = make_pass(gpu, shader_reduce_first, first_desc, 3);
    ctx->pass_reduce = make_pass(gpu, shader_reduce, reduce_desc, 2);
    ctx->pass_flow = make_pass(gpu, shader_flow, flow_desc, 4);
    ctx->pass_synthesize = make_pass(gpu, shader_synthesize, synth_desc, 6);
    if (!ctx->pass_luma || !ctx->pass_downsample || !ctx->pass_reduce_first ||
        !ctx->pass_reduce || !ctx->pass_flow || !ctx->pass_synthesize) {
        mpvflow_gpu_destroy(ctx);
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED;
        return NULL;
    }
    if (status)
        *status = MPVFLOW_GPU_OK;
    return ctx;
}

void mpvflow_gpu_destroy(MPVFlowGPUContext *ctx)
{
    if (!ctx)
        return;
    if (ctx->pass_luma) pl_pass_destroy(ctx->gpu, &ctx->pass_luma);
    if (ctx->pass_downsample) pl_pass_destroy(ctx->gpu, &ctx->pass_downsample);
    if (ctx->pass_reduce_first) pl_pass_destroy(ctx->gpu, &ctx->pass_reduce_first);
    if (ctx->pass_reduce) pl_pass_destroy(ctx->gpu, &ctx->pass_reduce);
    if (ctx->pass_flow) pl_pass_destroy(ctx->gpu, &ctx->pass_flow);
    if (ctx->pass_synthesize) pl_pass_destroy(ctx->gpu, &ctx->pass_synthesize);
    free(ctx);
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
        !(p1->format->caps & PL_FMT_CAP_SAMPLEABLE))
        return MPVFLOW_GPU_UNSUPPORTED;

    MPVFlowGPUPair *pair = calloc(1, sizeof(*pair));
    if (!pair)
        return MPVFLOW_GPU_ERROR;
    pair->owner = ctx;
    pair->input[0] = frame0;
    pair->input[1] = frame1;
    pair->w = p0->w;
    pair->h = p0->h;
    if (!allocate_pair_textures(ctx, pair)) {
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
    if (!run_pass(ctx, ctx->pass_luma, lb, 2, cfg, 0.0f, pair->w, pair->h))
        goto failure;
    lb[0].object = frame1;
    lb[1].object = pair->level[0].luma[1];
    if (!run_pass(ctx, ctx->pass_luma, lb, 2, cfg, 0.0f, pair->w, pair->h))
        goto failure;

    for (int i = 1; i < pair->levels; i++) {
        struct FlowLevel *prev = &pair->level[i - 1];
        struct FlowLevel *lv = &pair->level[i];
        int dc[4] = { lv->w, lv->h, 0, 0 };
        struct pl_desc_binding db[2] = { { .object = prev->luma[0] },
                                         { .object = lv->luma[0] } };
        if (!run_pass(ctx, ctx->pass_downsample, db, 2, dc, 0.0f, lv->w, lv->h))
            goto failure;
        db[0].object = prev->luma[1];
        db[1].object = lv->luma[1];
        if (!run_pass(ctx, ctx->pass_downsample, db, 2, dc, 0.0f, lv->w, lv->h))
            goto failure;
    }

    int rw = (pair->w + 15) / 16, rh = (pair->h + 15) / 16;
    int rc[4] = { rw, rh, 0, 0 };
    struct pl_desc_binding rb[3] = {
        { .object = pair->level[0].luma[0] },
        { .object = pair->level[0].luma[1] },
        { .object = pair->scene[0] },
    };
    if (!run_pass(ctx, ctx->pass_reduce_first, rb, 3, rc, 0.0f, rw, rh))
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
        if (!run_pass(ctx, ctx->pass_reduce, r2, 2, rc, 0.0f, rw, rh))
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
                          flow->params.w, flow->params.h))
                goto failure;
        }
    }
    *pair_out = pair;
    return MPVFLOW_GPU_OK;

failure:
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
        !isfinite(t) || t <= 0.0f || t >= 1.0f)
        return MPVFLOW_GPU_INVALID;
    pl_tex out = pair->output;
    if (!out)
        return MPVFLOW_GPU_UNSUPPORTED;
    struct FlowLevel *full = (struct FlowLevel *) &pair->level[0];
    int cfg[4] = { pair->w, pair->h, 0, 0 };
    struct pl_desc_binding sb[6] = {
        { .object = pair->input[0], .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_NEAREST },
        { .object = pair->input[1], .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_NEAREST },
        { .object = full->forward },
        { .object = full->backward },
        { .object = pair->scene[pair->reductions - 1] },
        { .object = out },
    };
    if (!run_pass(ctx, ctx->pass_synthesize, sb, 6, cfg, t, pair->w, pair->h))
        return MPVFLOW_GPU_ERROR;
    *output = out;
    return MPVFLOW_GPU_OK;
}
