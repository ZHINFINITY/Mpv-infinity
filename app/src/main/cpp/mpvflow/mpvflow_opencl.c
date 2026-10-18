/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 *
 * Optional OpenCL/QCOM AHardwareBuffer backend for Mpv∞ Flow. OpenCL symbols
 * are resolved at runtime; no OpenCL vendor library is a build-time dependency.
 */
#include "mpvflow_opencl.h"
#include "mpvflow_opencl_validation.h"

#include <dlfcn.h>
#include <link.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Minimal OpenCL ABI declarations keep this translation unit buildable without
 * vendor OpenCL headers. Values follow the OpenCL 1.2 and QCOM extension ABI. */
typedef int32_t cl_int;
typedef uint32_t cl_uint;
typedef uint64_t cl_ulong;
typedef intptr_t cl_context_properties;
typedef uint64_t cl_device_type;
typedef uint64_t cl_mem_flags;
typedef uint32_t cl_bool;
typedef uint32_t cl_program_build_info;
typedef uint32_t cl_device_info;
typedef uint32_t cl_platform_info;
typedef struct _cl_platform_id *cl_platform_id;
typedef struct _cl_device_id *cl_device_id;
typedef struct _cl_context *cl_context;
typedef struct _cl_command_queue *cl_command_queue;
typedef struct _cl_program *cl_program;
typedef struct _cl_kernel *cl_kernel;
typedef struct _cl_mem *cl_mem;
typedef struct _cl_event *cl_event;

#define CL_SUCCESS 0
#define CL_DEVICE_NOT_FOUND -1
#define CL_DEVICE_TYPE_GPU (1ULL << 2)
#define CL_DEVICE_EXTENSIONS 0x1030u
#define CL_PLATFORM_VERSION 0x0901u
#define CL_PLATFORM_NAME 0x0902u
#define CL_PLATFORM_VENDOR 0x0903u
#define CL_DEVICE_NAME 0x102Bu
#define CL_DEVICE_VENDOR 0x102Cu
#define CL_DRIVER_VERSION 0x102Du
#define CL_DEVICE_VERSION 0x102Fu
#define CL_DEVICE_OPENCL_C_VERSION 0x103Du
#define CL_PROGRAM_BUILD_LOG 0x1183u
#define CL_MEM_READ_WRITE (1ULL << 0)
#define CL_MEM_WRITE_ONLY (1ULL << 1)
#define CL_MEM_READ_ONLY (1ULL << 2)
#define CL_DEVICE_EXT_MEM_PADDING_IN_BYTES_QCOM 0x40A0u
#define CL_DEVICE_PAGE_SIZE_QCOM 0x40A1u
#define CL_CONTEXT_PLATFORM 0x1084
#define CL_PROGRAM_BUILD_LOG_CAP 4096
#define MPVFLOW_LEVELS 3
#define MPVFLOW_BLOCK 8
#define MPVFLOW_GRID_STEP 4
#define MPVFLOW_SCENE_REDUCTION_SIZE 256
#define MPVFLOW_MAX_DIM 4096
#define MPVFLOW_AHB_USAGE_PROTECTED_CONTENT (1ULL << 14)
#define MPVFLOW_BASE_EXTENSION "cl_qcom_ext_host_ptr"
#define MPVFLOW_EXTENSION "cl_qcom_android_ahardwarebuffer_host_ptr"

struct cl_mem_ext_host_ptr_qcom {
    cl_uint allocation_type;
    cl_uint host_cache_policy;
};
struct cl_mem_ahardwarebuffer_host_ptr_qcom {
    struct cl_mem_ext_host_ptr_qcom ext_host_ptr;
    struct AHardwareBuffer *ahb_ptr;
};

typedef cl_int (*PFN_clGetPlatformIDs)(cl_uint, cl_platform_id *, cl_uint *);
typedef cl_int (*PFN_clGetDeviceIDs)(cl_platform_id, cl_device_type, cl_uint, cl_device_id *, cl_uint *);
typedef cl_int (*PFN_clGetPlatformInfo)(cl_platform_id, cl_platform_info, size_t, void *, size_t *);
typedef cl_int (*PFN_clGetDeviceInfo)(cl_device_id, cl_device_info, size_t, void *, size_t *);
typedef cl_context (*PFN_clCreateContext)(const cl_context_properties *, cl_uint, const cl_device_id *, void (*)(const char *, const void *, size_t, void *), void *, cl_int *);
typedef cl_int (*PFN_clReleaseContext)(cl_context);
typedef cl_command_queue (*PFN_clCreateCommandQueue)(cl_context, cl_device_id, cl_ulong, cl_int *);
typedef cl_int (*PFN_clReleaseCommandQueue)(cl_command_queue);
typedef cl_program (*PFN_clCreateProgramWithSource)(cl_context, cl_uint, const char **, const size_t *, cl_int *);
typedef cl_int (*PFN_clBuildProgram)(cl_program, cl_uint, const cl_device_id *, const char *, void (*)(cl_program, void *), void *);
typedef cl_int (*PFN_clGetProgramBuildInfo)(cl_program, cl_device_id, cl_program_build_info, size_t, void *, size_t *);
typedef cl_int (*PFN_clReleaseProgram)(cl_program);
typedef cl_kernel (*PFN_clCreateKernel)(cl_program, const char *, cl_int *);
typedef cl_int (*PFN_clReleaseKernel)(cl_kernel);
typedef cl_mem (*PFN_clCreateBuffer)(cl_context, cl_mem_flags, size_t, void *, cl_int *);
typedef cl_int (*PFN_clReleaseMemObject)(cl_mem);
typedef cl_int (*PFN_clRetainMemObject)(cl_mem);
typedef cl_int (*PFN_clSetKernelArg)(cl_kernel, cl_uint, size_t, const void *);
typedef cl_int (*PFN_clEnqueueNDRangeKernel)(cl_command_queue, cl_kernel, cl_uint, const size_t *, const size_t *, const size_t *, cl_uint, const cl_event *, cl_event *);
typedef cl_int (*PFN_clFinish)(cl_command_queue);
typedef void *(*PFN_clGetExtensionFunctionAddressForPlatform)(
    cl_platform_id, const char *);

static char g_build_log[CL_PROGRAM_BUILD_LOG_CAP];

/* OpenCL C kernels mirror the Vulkan Flow motion grid, coarse-to-fine search,
 * subpixel refinement and edge/occlusion-aware synthesis. */
static const char g_kernel_source_1[] =
"inline int clampi(int v, int lo, int hi) { return min(max(v, lo), hi); }\n"
"inline float clampf(float v, float lo, float hi) { return fmin(fmax(v, lo), hi); }\n"
"inline float4 mix4(float4 a, float4 b, float t) { return a + (b - a) * t; }\n"
"inline float lum(uchar4 p) { return (0.299f*(float)p.x + 0.587f*(float)p.y + 0.114f*(float)p.z) * (1.0f/255.0f); }\n"
"__kernel void make_luma(__global const uchar *src, int stride, int w, int h, int scale, __global float *dst) {\n"
" int i=(int)get_global_id(0), ow=(w+scale-1)/scale, oh=(h+scale-1)/scale; if(i>=ow*oh)return; int x=i%ow,y=i/ow; float sum=0.0f; int n=0;\n"
" for(int yy=0;yy<scale;yy++) for(int xx=0;xx<scale;xx++){ int sx=x*scale+xx, sy=y*scale+yy; if(sx<w&&sy<h){ uchar4 p=vload4(0,(__global const uchar*)(src+sy*stride+sx*4)); sum+=lum(p); n++; }} dst[i]=sum/(float)max(n,1); }\n";

static const char g_kernel_source_1b[] =
"inline int sad_block(__global const float *a, __global const float *b, int w, int h, int x, int y, int dx, int dy, int block_size, int cutoff) {\n"
" int cost=0; for(int yy=0;yy<block_size;yy++) for(int xx=0;xx<block_size;xx++){ int ax=x+xx, ay=y+yy, bx=ax+dx, by=ay+dy; if(ax<w&&ay<h&&bx>=0&&by>=0&&bx<w&&by<h){ cost+=(int)(fabs(a[ay*w+ax]-b[by*w+bx])*255.0f+0.5f); if(cost>cutoff)return cost; }} return cost; }\n"
"inline float subpixel_offset(float minus_cost, float center_cost, float plus_cost) { float curvature=minus_cost-2.0f*center_cost+plus_cost; if(center_cost>minus_cost||center_cost>plus_cost||curvature<=0.0001f)return 0.0f; return clampf(0.5f*(minus_cost-plus_cost)/curvature,-0.5f,0.5f); }\n"
"inline float refine_axis(__global const float *a, __global const float *b, int w, int h, int2 p, int2 v, int2 axis, int center_cost, int block_size) { if(center_cost==0)return 0.0f; int2 m=v-axis,q=v+axis,limit=(int2)(w-block_size,h-block_size); if(any((p+m)<(int2)(0))||any((p+q)<(int2)(0))||any((p+m)>limit)||any((p+q)>limit))return 0.0f; int cm=sad_block(a,b,w,h,p.x,p.y,m.x,m.y,block_size,2147483647),cp=sad_block(a,b,w,h,p.x,p.y,q.x,q.y,block_size,2147483647); return subpixel_offset((float)cm,(float)center_cost,(float)cp); }\n"
"inline float grid_axis(float p,int extent,int block_size,int grid_count) { if(grid_count<=1)return 0.0f; float last=fmax((float)(extent-block_size),0.0f),previous=fmin((float)(grid_count-2)*4.0f,last),last_index=(float)(grid_count-1); if(p<=previous)return clampf(p*0.25f,0.0f,last_index); if(last<=previous)return last_index; return clampf(last_index-1.0f+(p-previous)/(last-previous),0.0f,last_index); }\n"
"inline float4 sample_coarse(__global const float4 *f, int gw, int gh, int aw, int ah, float x, float y) { float2 p=(float2)(grid_axis(x,aw,8,gw),grid_axis(y,ah,8,gh)); int2 a=convert_int2_rtn(p),b=min(a+(int2)(1),(int2)(gw-1,gh-1)); float2 t=p-convert_float2(a); return mix4(mix4(f[a.y*gw+a.x],f[a.y*gw+b.x],t.x),mix4(f[b.y*gw+a.x],f[b.y*gw+b.x],t.x),t.y); }\n"
"__kernel void estimate(__global const float *a, __global const float *b, int w, int h, __global const float4 *coarse, int cgw, int cgh, int has_coarse, int radius, int block_size, __global const float *scene_delta, __global float4 *out) {\n"
" int i=(int)get_global_id(0), gw=(max(w-block_size,0)+3)/4+1, gh=(max(h-block_size,0)+3)/4+1; if(i>=gw*gh)return; if(scene_delta[0]>0.1882353f){out[i]=(float4)(0.0f,0.0f,1.0f,0.0f);return;} if(w<block_size||h<block_size){out[i]=(float4)(0.0f,0.0f,1.0f,0.0f);return;} int gx=i%gw,gy=i/gw; int2 p=min((int2)(gx*4,gy*4),(int2)(w-block_size,h-block_size)); int2 center=(int2)(0); float parent_residual=0.0f,parent_uniqueness=1.0f;\n"
" if(has_coarse){ float2 parent_origin=(float2)((float)block_size*0.25f-4.0f); float2 pc=convert_float2(p)*0.5f+parent_origin; float4 q=sample_coarse(coarse,cgw,cgh,(w+1)/2,(h+1)/2,pc.x,pc.y); center=convert_int2_rte((float2)(q.x,q.y)*2.0f); parent_residual=q.z; parent_uniqueness=q.w; } center=clamp(center,-p,(int2)(w-block_size,h-block_size)-p); int search_radius=radius; if(has_coarse&&parent_residual>0.12f&&parent_uniqueness>0.0005f)search_radius=min(radius*2,16); int2 best_vector=center; int best=sad_block(a,b,w,h,p.x,p.y,center.x,center.y,block_size,2147483647),second=2147483647; int stop=0;\n"
" for(int ring=1;ring<=search_radius&&!stop;ring++) for(int dy=-ring;dy<=ring&&!stop;dy++) for(int dx=-ring;dx<=ring&&!stop;dx++){ if(max(abs(dx),abs(dy))!=ring)continue; int2 d=center+(int2)(dx,dy),lo=-p,hi=(int2)(w-block_size,h-block_size)-p; if(any(d<lo)||any(d>hi)||all(d==best_vector))continue; if(best==0&&second==0){ if(all(best_vector==center)){stop=1;continue;} if(d.y>best_vector.y||(d.y==best_vector.y&&d.x>=best_vector.x))continue; } int c=sad_block(a,b,w,h,p.x,p.y,d.x,d.y,block_size,best==0?0:second); if(best==0){if(c==0){second=0;if(!all(best_vector==center)&&(d.y<best_vector.y||(d.y==best_vector.y&&d.x<best_vector.x)))best_vector=d;}else if(second!=0)second=1;}else if(c<best){second=best;best=c;best_vector=d;}else if(c==best){if(second>c)second=c;if(!all(best_vector==center)&&(d.y<best_vector.y||(d.y==best_vector.y&&d.x<best_vector.x)))best_vector=d;}else if(c<second)second=c; }\n"
" float2 refined=convert_float2(best_vector); refined.x+=refine_axis(a,b,w,h,p,best_vector,(int2)(1,0),best,block_size); refined.y+=refine_axis(a,b,w,h,p,best_vector,(int2)(0,1),best,block_size); float uniqueness=second<2147483647?clampf((float)(second-best)/fmax((float)second,1.0f),0.0f,1.0f):0.0f; out[i]=(float4)(refined.x,refined.y,(float)best/((float)(block_size*block_size)*255.0f),uniqueness); }\n";


static const char g_kernel_source_2[] =
"__kernel void scene_delta_reduce(__global const float *a, __global const float *b, int count, __local float *scratch, __global float *partial) {\n"
" int lid=(int)get_local_id(0), i=(int)get_global_id(0); scratch[lid]=i<count?fabs(a[i]-b[i]):0.0f; barrier(CLK_LOCAL_MEM_FENCE);\n"
" for(int stride=(int)get_local_size(0)/2;stride>0;stride>>=1){ if(lid<stride)scratch[lid]+=scratch[lid+stride]; barrier(CLK_LOCAL_MEM_FENCE); }\n"
" if(lid==0)partial[get_group_id(0)]=scratch[0]; }\n"
"__kernel void scene_delta_finish(__global const float *partial, int groups, int count, __local float *scratch, __global float *delta) {\n"
" int lid=(int)get_local_id(0); float sum=0.0f; for(int i=lid;i<groups;i+=(int)get_local_size(0))sum+=partial[i]; scratch[lid]=sum; barrier(CLK_LOCAL_MEM_FENCE);\n"
" for(int stride=(int)get_local_size(0)/2;stride>0;stride>>=1){ if(lid<stride)scratch[lid]+=scratch[lid+stride]; barrier(CLK_LOCAL_MEM_FENCE); }\n"
" if(lid==0)delta[0]=scratch[0]/(float)count; }\n";

static const char g_kernel_source_2b[] =
"inline float4 flow_value(__global const float4 *forward, __global const float4 *backward, int direction, int index) { return direction==0?forward[index]:backward[index]; }\n"
"inline float luma_at(__global const float *luma0, __global const float *luma1, int direction, int w, int h, float x, float y) { int2 q=clamp(convert_int2_rte((float2)(x,y)),(int2)(0),(int2)(w-1,h-1)); __global const float *luma=direction==0?luma0:luma1; return luma[q.y*w+q.x]; }\n"
"inline float flow_confidence_weight(float4 flow) { return clamp((0.22f-flow.z)*5.128205f,0.0f,1.0f)*clamp((flow.w-0.08f)*4.545455f,0.0f,1.0f); }\n"
"inline float4 flow_at_bilinear(__global const float4 *forward, __global const float4 *backward, int direction, int aw, int ah, float x, float y) { int gw=(max(aw-4,0)+3)/4+1,gh=(max(ah-4,0)+3)/4+1; float2 g=(float2)(grid_axis(x,aw,4,gw),grid_axis(y,ah,4,gh)); int2 a=convert_int2_rtn(g),b=min(a+(int2)(1),(int2)(gw-1,gh-1)); float2 t=g-convert_float2(a); float4 v00=flow_value(forward,backward,direction,a.y*gw+a.x),v10=flow_value(forward,backward,direction,a.y*gw+b.x),v01=flow_value(forward,backward,direction,b.y*gw+a.x),v11=flow_value(forward,backward,direction,b.y*gw+b.x); float4 geometric=mix4(mix4(v00,v10,t.x),mix4(v01,v11,t.x),t.y); float flow_spread=max(max(length(v00.xy-v10.xy),length(v01.xy-v11.xy)),max(length(v00.xy-v01.xy),length(v10.xy-v11.xy))); if(flow_spread<=1.0f)return geometric; float c00=flow_confidence_weight(v00),c10=flow_confidence_weight(v10),c01=flow_confidence_weight(v01),c11=flow_confidence_weight(v11); float w00=(1.0f-t.x)*(1.0f-t.y)*c00,w10=t.x*(1.0f-t.y)*c10,w01=(1.0f-t.x)*t.y*c01,w11=t.x*t.y*c11,total=w00+w10+w01+w11; return total>0.000001f?(v00*w00+v10*w10+v01*w01+v11*w11)/total:geometric; }\n"
"inline float4 flow_at_edge_aware(__global const float4 *forward, __global const float4 *backward, __global const float *luma0, __global const float *luma1, int direction, int aw, int ah, float x, float y) { int gw=(max(aw-4,0)+3)/4+1,gh=(max(ah-4,0)+3)/4+1; float2 g=(float2)(grid_axis(x,aw,4,gw),grid_axis(y,ah,4,gh)); int2 a=convert_int2_rtn(g),b=min(a+(int2)(1),(int2)(gw-1,gh-1)); float2 t=g-convert_float2(a); float s00=(1.0f-t.x)*(1.0f-t.y),s10=t.x*(1.0f-t.y),s01=(1.0f-t.x)*t.y,s11=t.x*t.y; int2 bound=(int2)(aw-4,ah-4),c00=min(a*(int2)(4),bound)+(int2)(2),c10=min((int2)(b.x,a.y)*(int2)(4),bound)+(int2)(2),c01=min((int2)(a.x,b.y)*(int2)(4),bound)+(int2)(2),c11=min(b*(int2)(4),bound)+(int2)(2); float guide=luma_at(luma0,luma1,direction,aw,ah,x,y),d00=luma_at(luma0,luma1,direction,aw,ah,(float)c00.x,(float)c00.y)-guide,d10=luma_at(luma0,luma1,direction,aw,ah,(float)c10.x,(float)c10.y)-guide,d01=luma_at(luma0,luma1,direction,aw,ah,(float)c01.x,(float)c01.y)-guide,d11=luma_at(luma0,luma1,direction,aw,ah,(float)c11.x,(float)c11.y)-guide; float variance=s00*d00*d00+s10*d10*d10+s01*d01*d01+s11*d11*d11,safe=max(variance,0.000001f); float w00=s00*exp(-(d00*d00)/safe),w10=s10*exp(-(d10*d10)/safe),w01=s01*exp(-(d01*d01)/safe),w11=s11*exp(-(d11*d11)/safe); float4 v00=flow_value(forward,backward,direction,a.y*gw+a.x),v10=flow_value(forward,backward,direction,a.y*gw+b.x),v01=flow_value(forward,backward,direction,b.y*gw+a.x),v11=flow_value(forward,backward,direction,b.y*gw+b.x); w00*=flow_confidence_weight(v00);w10*=flow_confidence_weight(v10);w01*=flow_confidence_weight(v01);w11*=flow_confidence_weight(v11); float total=w00+w10+w01+w11; return total>0.000001f?(v00*w00+v10*w10+v01*w01+v11*w11)/total:flow_at_bilinear(forward,backward,direction,aw,ah,x,y); }\n"
"inline float4 sample_rgba(__global const uchar *src, int stride, int w, int h, float x, float y) {\n"
" x=clampf(x,0.0f,(float)(w-1)); y=clampf(y,0.0f,(float)(h-1)); int x0=(int)floor(x), y0=(int)floor(y), x1=min(x0+1,w-1), y1=min(y0+1,h-1); float fx=x-(float)x0, fy=y-(float)y0;\n"
" uchar4 a=vload4(0,(__global const uchar*)(src+y0*stride+x0*4)), b=vload4(0,(__global const uchar*)(src+y0*stride+x1*4)), c=vload4(0,(__global const uchar*)(src+y1*stride+x0*4)), d=vload4(0,(__global const uchar*)(src+y1*stride+x1*4));\n"
" return mix4(mix4(convert_float4(a),convert_float4(b),fx),mix4(convert_float4(c),convert_float4(d),fx),fy); }\n"
"inline float point_in_bounds(float x,float y,int w,int h) { return (x>=0.0f&&x<=(float)(w-1)&&y>=0.0f&&y<=(float)(h-1))?1.0f:0.0f; }\n"
"inline float reliability(float4 flow,float cycle,float valid) { float cycle_quality=1.0f-smoothstep(0.75f,4.0f,cycle); float match_quality=1.0f-smoothstep(0.025f,0.22f,flow.z); float uniqueness_quality=smoothstep(0.08f,0.30f,flow.w); return cycle_quality*match_quality*uniqueness_quality*valid; }\n"
"inline int flow_finite(float4 v) { return all(isfinite(v)); }\n"
"inline float neighborhood_quality(__global const float4 *forward,__global const float4 *backward,int direction,int aw,int ah,float x,float y,float2 center) { float2 d=(float2)(2.0f); float2 n0=flow_at_bilinear(forward,backward,direction,aw,ah,x-d.x,y-d.y).xy,n1=flow_at_bilinear(forward,backward,direction,aw,ah,x+d.x,y-d.y).xy,n2=flow_at_bilinear(forward,backward,direction,aw,ah,x-d.x,y+d.y).xy,n3=flow_at_bilinear(forward,backward,direction,aw,ah,x+d.x,y+d.y).xy; float spread=(length(n0-center)+length(n1-center)+length(n2-center)+length(n3-center))*0.25f; return 1.0f-smoothstep(0.65f,2.8f,spread); }\n";

static const char g_kernel_source_2c[] =
"__kernel void synth(__global const uchar *a,int astride,__global const uchar *b,int bstride,int w,int h,int aw,int ah,__global const float4 *forward,__global const float4 *backward,__global const float *luma0,__global const float *luma1,float t,__global const float *scene_delta,__global uchar *dst,int dstride) {\n"
" int i=(int)get_global_id(0); if(i>=w*h)return; int x=i%w,y=i/w; if(scene_delta[0]>0.1882353f){ __global const uchar *src=t<=0.5f?a:b; int stride=t<=0.5f?astride:bstride; uchar4 px=vload4(0,src+y*stride+x*4); vstore4(px,0,dst+y*dstride+x*4); return; } float2 flow_p=clamp((float2)(((float)x+0.5f)*(float)aw/(float)w-0.5f,((float)y+0.5f)*(float)ah/(float)h-0.5f),(float2)(0.0f),(float2)((float)aw-1.0f,(float)ah-1.0f)); float static_luma_delta=fabs(luma_at(luma0,luma1,0,aw,ah,flow_p.x,flow_p.y)-luma_at(luma0,luma1,1,aw,ah,flow_p.x,flow_p.y)); if(static_luma_delta<=0.0039216f){ float4 static_f=flow_at_bilinear(forward,backward,0,aw,ah,flow_p.x,flow_p.y),static_b=flow_at_bilinear(forward,backward,1,aw,ah,flow_p.x,flow_p.y); if(dot((float2)(static_f.x,static_f.y),(float2)(static_f.x,static_f.y))<=0.0025f&&dot((float2)(static_b.x,static_b.y),(float2)(static_b.x,static_b.y))<=0.0025f){ uchar4 static_a=vload4(0,a+y*astride+x*4),static_bpx=vload4(0,b+y*bstride+x*4); if(max(max(fabs((float)static_a.x-(float)static_bpx.x),fabs((float)static_a.y-(float)static_bpx.y)),fabs((float)static_a.z-(float)static_bpx.z))<=1.0f){ uchar4 static_out=convert_uchar4_sat_rte(mix4(convert_float4(static_a),convert_float4(static_bpx),t)); static_out.w=255; vstore4(static_out,0,dst+y*dstride+x*4); return; } } } float4 f=flow_at_bilinear(forward,backward,0,aw,ah,flow_p.x,flow_p.y),q=flow_at_bilinear(forward,backward,1,aw,ah,flow_p.x,flow_p.y); float2 pa=flow_p-t*(float2)(f.x,f.y),pb=flow_p-(1.0f-t)*(float2)(q.x,q.y);\n"
" for(int k=0;k<2;k++){ f=flow_at_bilinear(forward,backward,0,aw,ah,pa.x,pa.y); q=flow_at_bilinear(forward,backward,1,aw,ah,pb.x,pb.y); if(!flow_finite(f)||!flow_finite(q)){ uchar4 px=t<0.5f?vload4(0,a+y*astride+x*4):vload4(0,b+y*bstride+x*4); vstore4(px,0,dst+y*dstride+x*4); return; } pa=flow_p-t*(float2)(f.x,f.y); pb=flow_p-(1.0f-t)*(float2)(q.x,q.y); } f=flow_at_edge_aware(forward,backward,luma0,luma1,0,aw,ah,pa.x,pa.y); q=flow_at_edge_aware(forward,backward,luma0,luma1,1,aw,ah,pb.x,pb.y); if(!flow_finite(f)||!flow_finite(q)||!all(isfinite(pa))||!all(isfinite(pb))){ uchar4 px=t<0.5f?vload4(0,a+y*astride+x*4):vload4(0,b+y*bstride+x*4); vstore4(px,0,dst+y*dstride+x*4); return; } float2 cycle0=pa+(float2)(f.x,f.y),cycle1=pb+(float2)(q.x,q.y); float4 back_at0=flow_at_edge_aware(forward,backward,luma0,luma1,1,aw,ah,cycle0.x,cycle0.y),forward_at1=flow_at_edge_aware(forward,backward,luma0,luma1,0,aw,ah,cycle1.x,cycle1.y); float cycle_error0=flow_finite(back_at0)?length((float2)(f.x+back_at0.x,f.y+back_at0.y)):1e20f,cycle_error1=flow_finite(forward_at1)?length((float2)(q.x+forward_at1.x,q.y+forward_at1.y)):1e20f; float2 scale=(float2)((float)w/(float)aw,(float)h/(float)ah); float inverse_residual0=length((pa+ t*f.xy-flow_p)*scale),inverse_residual1=length((pb+(1.0f-t)*q.xy-flow_p)*scale); int cycle_samples_finite=flow_finite(back_at0)&&flow_finite(forward_at1); int inverse_residual0_valid=inverse_residual0<=0.75f,inverse_residual1_valid=inverse_residual1<=0.75f; float2 src_a=(float2)((float)x,(float)y)+(pa-flow_p)*scale,src_b=(float2)((float)x,(float)y)+(pb-flow_p)*scale; float4 ca=sample_rgba(a,astride,w,h,src_a.x,src_a.y),cb=sample_rgba(b,bstride,w,h,src_b.x,src_b.y); float valid0=point_in_bounds(pa.x,pa.y,aw,ah)*point_in_bounds(cycle0.x,cycle0.y,aw,ah)*(cycle_samples_finite&&inverse_residual0_valid),valid1=point_in_bounds(pb.x,pb.y,aw,ah)*point_in_bounds(cycle1.x,cycle1.y,aw,ah)*(cycle_samples_finite&&inverse_residual1_valid); float confidence0=reliability(f,cycle_error0,valid0),confidence1=reliability(q,cycle_error1,valid1),confidence=max(confidence0,confidence1); float4 color;\n"
" if(confidence<0.28f){ uchar4 px0=vload4(0,a+y*astride+x*4),px1=vload4(0,b+y*bstride+x*4); color=mix4(convert_float4(px0),convert_float4(px1),t); } else { float visibility0=smoothstep(0.15f,0.45f,confidence0),visibility1=smoothstep(0.15f,0.45f,confidence1),weight0=(1.0f-t)*visibility0,weight1=t*visibility1,total=weight0+weight1; float luma_delta=fabs((ca.x*0.299f+ca.y*0.587f+ca.z*0.114f-cb.x*0.299f-cb.y*0.587f-cb.z*0.114f)/255.0f),color_delta=max(max(fabs(ca.x-cb.x),fabs(ca.y-cb.y)),fabs(ca.z-cb.z))/255.0f; float chroma_delta=max(fabs((ca.x-ca.y)-(cb.x-cb.y)),fabs((ca.z-ca.y)-(cb.z-cb.y)))/255.0f; int chroma_boundary=color_delta>0.12f&&chroma_delta>0.06f; float neighborhood0=neighborhood_quality(forward,backward,0,aw,ah,pa.x,pa.y,(float2)(f.x,f.y)),neighborhood1=neighborhood_quality(forward,backward,1,aw,ah,pb.x,pb.y,(float2)(q.x,q.y)); float forward_backward_error=length((float2)(f.x+q.x,f.y+q.y)); int neighborhood_conflict=(min(neighborhood0,neighborhood1)<0.30f&&(luma_delta>0.06f||color_delta>0.08f))||(forward_backward_error>3.0f&&min(neighborhood0,neighborhood1)<0.45f); int boundary_conflict=neighborhood_conflict||luma_delta>0.08f||chroma_boundary||(min(f.w,q.w)<0.08f&&color_delta>0.10f); float edge_confidence0=confidence0*(0.5f+0.5f*smoothstep(0.02f,0.35f,f.w)),edge_confidence1=confidence1*(0.5f+0.5f*smoothstep(0.02f,0.35f,q.w)); if(boundary_conflict){ uchar4 px0=vload4(0,a+y*astride+x*4),px1=vload4(0,b+y*bstride+x*4); color=mix4(convert_float4(px0),convert_float4(px1),t); } else color=total>0.0001f?mix4(ca,cb,weight1/total):(t<0.5f?convert_float4(vload4(0,a+y*astride+x*4)):convert_float4(vload4(0,b+y*bstride+x*4))); } color.w=255.0f; uchar4 out=convert_uchar4_sat_rte(color); vstore4(out,0,dst+y*dstride+x*4); }\n";

struct MPVFlowOpenCLContext {
    void *library;
    cl_platform_id platform;
    cl_device_id device;
    cl_context context;
    cl_command_queue queue;
    cl_program program;
    cl_kernel luma_kernel;
    cl_kernel estimate_kernel;
    cl_kernel scene_delta_reduce_kernel;
    cl_kernel scene_delta_finish_kernel;
    cl_kernel synth_kernel;
    int search_radius;
    int max_dimension;
    char platform_name[128];
    char platform_vendor[128];
    char platform_version[128];
    char device_name[128];
    char device_vendor[128];
    char device_version[128];
    char driver_version[128];
    char opencl_c_version[128];
    size_t qcom_ext_mem_padding_bytes;
    size_t qcom_page_size_bytes;
    int qcom_page_size_query_ok;
    char device_extensions[2048];
    size_t device_extensions_length;
    int device_extensions_truncated;
    char library_path[256];
    int direct_import_symbol_present;
    int direct_import_image_symbol_present;
    int import_memory_arm_symbol_present;
    char runtime_info[3200];
    char extension_presence_info[960];
    PFN_clGetExtensionFunctionAddressForPlatform clGetExtensionFunctionAddressForPlatform;
#define MPVFLOW_CL_FN(name) PFN_##name name
    MPVFLOW_CL_FN(clGetPlatformIDs);
    MPVFLOW_CL_FN(clGetDeviceIDs);
    MPVFLOW_CL_FN(clGetPlatformInfo);
    MPVFLOW_CL_FN(clGetDeviceInfo);
    MPVFLOW_CL_FN(clCreateContext);
    MPVFLOW_CL_FN(clReleaseContext);
    MPVFLOW_CL_FN(clCreateCommandQueue);
    MPVFLOW_CL_FN(clReleaseCommandQueue);
    MPVFLOW_CL_FN(clCreateProgramWithSource);
    MPVFLOW_CL_FN(clBuildProgram);
    MPVFLOW_CL_FN(clGetProgramBuildInfo);
    MPVFLOW_CL_FN(clReleaseProgram);
    MPVFLOW_CL_FN(clCreateKernel);
    MPVFLOW_CL_FN(clReleaseKernel);
    MPVFLOW_CL_FN(clCreateBuffer);
    MPVFLOW_CL_FN(clReleaseMemObject);
    MPVFLOW_CL_FN(clRetainMemObject);
    MPVFLOW_CL_FN(clSetKernelArg);
    MPVFLOW_CL_FN(clEnqueueNDRangeKernel);
    MPVFLOW_CL_FN(clFinish);
#undef MPVFLOW_CL_FN
};

struct MPVFlowOpenCLBuffer {
    MPVFlowOpenCLContext *owner;
    cl_mem mem;
    const void *storage_identity; /* Non-owning AHardwareBuffer identity. */
    int width, height, stride;
    enum MPVFlowOpenCLAccess access;
};

struct MPVFlowOpenCLPair {
    MPVFlowOpenCLContext *owner;
    cl_mem frames[2];
    int width, height;
    int stride[2];
    int dims[MPVFLOW_LEVELS][2];
    int grids[MPVFLOW_LEVELS][2];
    cl_mem luma[2][MPVFLOW_LEVELS];
    cl_mem flow[2][MPVFLOW_LEVELS];
    cl_mem scene_delta;
    cl_mem scene_delta_partial;
    int scene_delta_groups;
};

static int has_extension(const char *list, const char *name)
{
    size_t n = strlen(name);
    const char *p = list;
    while ((p = strstr(p, name)) != NULL) {
        if ((p == list || p[-1] == ' ') && (p[n] == '\0' || p[n] == ' '))
            return 1;
        p += n;
    }
    return 0;
}

static void copy_info_string(char *dst, size_t capacity, const char *src, size_t bytes)
{
    if (!dst || !capacity)
        return;
    dst[0] = '\0';
    if (!src)
        return;
    size_t length = 0;
    while (length < bytes && src[length] != '\0')
        length++;
    if (length >= capacity)
        length = capacity - 1;
    memcpy(dst, src, length);
    dst[length] = '\0';
}

static void query_platform_string(MPVFlowOpenCLContext *c, cl_platform_info key,
                                  char *dst, size_t capacity)
{
    size_t bytes = 0;
    char *value;
    if (!c->clGetPlatformInfo || !c->platform || !capacity ||
        c->clGetPlatformInfo(c->platform, key, 0, NULL, &bytes) != CL_SUCCESS ||
        !bytes || bytes > 4096)
        return;
    value = calloc(bytes + 1, 1);
    if (!value)
        return;
    if (c->clGetPlatformInfo(c->platform, key, bytes, value, NULL) == CL_SUCCESS)
        copy_info_string(dst, capacity, value, bytes);
    free(value);
}

static void query_device_string(MPVFlowOpenCLContext *c, cl_device_info key,
                                char *dst, size_t capacity)
{
    size_t bytes = 0;
    char *value;
    if (!c->clGetDeviceInfo || !c->device || !capacity ||
        c->clGetDeviceInfo(c->device, key, 0, NULL, &bytes) != CL_SUCCESS ||
        !bytes || bytes > 4096)
        return;
    value = calloc(bytes + 1, 1);
    if (!value)
        return;
    if (c->clGetDeviceInfo(c->device, key, bytes, value, NULL) == CL_SUCCESS)
        copy_info_string(dst, capacity, value, bytes);
    free(value);
}

static void context_cleanup(MPVFlowOpenCLContext *c)
{
    if (!c) return;
    if (c->luma_kernel) c->clReleaseKernel(c->luma_kernel);
    if (c->estimate_kernel) c->clReleaseKernel(c->estimate_kernel);
    if (c->scene_delta_reduce_kernel) c->clReleaseKernel(c->scene_delta_reduce_kernel);
    if (c->scene_delta_finish_kernel) c->clReleaseKernel(c->scene_delta_finish_kernel);
    if (c->synth_kernel) c->clReleaseKernel(c->synth_kernel);
    if (c->program) c->clReleaseProgram(c->program);
    if (c->queue) c->clReleaseCommandQueue(c->queue);
    if (c->context) c->clReleaseContext(c->context);
    if (c->library) dlclose(c->library);
    free(c);
}

static int load_opencl(MPVFlowOpenCLContext *c)
{
    c->library = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!c->library) c->library = dlopen("libOpenCL.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!c->library) return 0;
#define LOAD_CL(name) do { *(void **)(&c->name) = dlsym(c->library, #name); if (!c->name) return 0; } while (0)
    LOAD_CL(clGetPlatformIDs); LOAD_CL(clGetDeviceIDs); LOAD_CL(clGetPlatformInfo); LOAD_CL(clGetDeviceInfo);
    LOAD_CL(clCreateContext); LOAD_CL(clReleaseContext); LOAD_CL(clCreateCommandQueue);
    LOAD_CL(clReleaseCommandQueue); LOAD_CL(clCreateProgramWithSource); LOAD_CL(clBuildProgram);
    LOAD_CL(clGetProgramBuildInfo); LOAD_CL(clReleaseProgram); LOAD_CL(clCreateKernel);
    LOAD_CL(clReleaseKernel); LOAD_CL(clCreateBuffer); LOAD_CL(clReleaseMemObject);
    LOAD_CL(clRetainMemObject); LOAD_CL(clSetKernelArg); LOAD_CL(clEnqueueNDRangeKernel);
    LOAD_CL(clFinish);
    *(void **)(&c->clGetExtensionFunctionAddressForPlatform) =
        dlsym(c->library, "clGetExtensionFunctionAddressForPlatform");
#undef LOAD_CL
    if (c->clGetExtensionFunctionAddressForPlatform) {
        Dl_info info;
        memset(&info, 0, sizeof(info));
        if (dladdr((void *)c->clGetPlatformIDs, &info) && info.dli_fname)
            copy_info_string(c->library_path, sizeof(c->library_path),
                             info.dli_fname, strlen(info.dli_fname) + 1);
    }
    return 1;
}

static void probe_extension_symbols(MPVFlowOpenCLContext *c)
{
    if (!c || !c->clGetExtensionFunctionAddressForPlatform || !c->platform)
        return;
    c->import_memory_arm_symbol_present =
        c->clGetExtensionFunctionAddressForPlatform(c->platform, "clImportMemoryARM") != NULL;
    c->direct_import_symbol_present =
        c->clGetExtensionFunctionAddressForPlatform(
            c->platform, "clCreateBufferFromAHardwareBufferQCOM") != NULL;
    c->direct_import_image_symbol_present =
        c->clGetExtensionFunctionAddressForPlatform(
            c->platform, "clCreateImageFromAHardwareBufferQCOM") != NULL;
}

MPVFlowOpenCLContext *mpvflow_opencl_create(const struct MPVFlowOpenCLConfig *config,
                                             enum MPVFlowOpenCLStatus *status,
                                             const char **build_log)
{
    MPVFlowOpenCLContext *c = calloc(1, sizeof(*c));
    cl_int err = CL_SUCCESS;
    cl_uint nplatforms = 0;
    if (status) *status = MPVFLOW_OPENCL_ERROR;
    if (build_log) *build_log = NULL;
    g_build_log[0] = '\0';
    if (!c) return NULL;
    c->search_radius = config && config->search_radius > 0 ? config->search_radius : 4;
    if (c->search_radius > 8) c->search_radius = 8;
    c->max_dimension = config && config->max_dimension > 0 ? config->max_dimension : 2048;
    if (c->max_dimension > MPVFLOW_MAX_DIM) c->max_dimension = MPVFLOW_MAX_DIM;
    if (!load_opencl(c)) {
        if (status) *status = MPVFLOW_OPENCL_UNAVAILABLE;
        context_cleanup(c);
        return NULL;
    }
    if (c->clGetPlatformIDs(0, NULL, &nplatforms) != CL_SUCCESS || !nplatforms) {
        if (status) *status = MPVFLOW_OPENCL_UNAVAILABLE;
        context_cleanup(c);
        return NULL;
    }
    cl_platform_id *platforms = calloc(nplatforms, sizeof(*platforms));
    if (!platforms) { context_cleanup(c); return NULL; }
    if (c->clGetPlatformIDs(nplatforms, platforms, NULL) != CL_SUCCESS) {
        free(platforms); if (status) *status = MPVFLOW_OPENCL_UNAVAILABLE; context_cleanup(c); return NULL;
    }
    int saw_gpu = 0;
    for (cl_uint i = 0; i < nplatforms && !c->device; i++) {
        cl_uint ndev = 0;
        if (c->clGetDeviceIDs(platforms[i], CL_DEVICE_TYPE_GPU, 0, NULL, &ndev) != CL_SUCCESS || !ndev)
            continue;
        cl_device_id *devices = calloc(ndev, sizeof(*devices));
        if (!devices) continue;
        if (c->clGetDeviceIDs(platforms[i], CL_DEVICE_TYPE_GPU, ndev, devices, NULL) == CL_SUCCESS) {
            saw_gpu = 1;
            for (cl_uint j = 0; j < ndev; j++) {
                size_t bytes = 0;
                if (c->clGetDeviceInfo(devices[j], CL_DEVICE_EXTENSIONS, 0, NULL, &bytes) != CL_SUCCESS || !bytes)
                    continue;
                char *exts = malloc(bytes + 1);
                if (!exts) continue;
                if (c->clGetDeviceInfo(devices[j], CL_DEVICE_EXTENSIONS, bytes, exts, NULL) == CL_SUCCESS) {
                    exts[bytes] = '\0';
                    if (has_extension(exts, MPVFLOW_BASE_EXTENSION) &&
                        has_extension(exts, MPVFLOW_EXTENSION)) {
                        c->platform = platforms[i]; c->device = devices[j];
                        size_t extension_length = bytes;
                        if (extension_length && exts[extension_length - 1] == '\0')
                            extension_length--;
                        c->device_extensions_length = extension_length;
                        size_t extension_copy = extension_length;
                        if (extension_copy >= sizeof(c->device_extensions)) {
                            extension_copy = sizeof(c->device_extensions) - 1;
                            c->device_extensions_truncated = 1;
                        }
                        memcpy(c->device_extensions, exts, extension_copy);
                        c->device_extensions[extension_copy] = '\0';
                        free(exts); break;
                    }
                }
                free(exts);
            }
        }
        free(devices);
    }
    free(platforms);
    if (!c->device) {
        if (status) *status = saw_gpu ? MPVFLOW_OPENCL_UNSUPPORTED_EXTENSION : MPVFLOW_OPENCL_NO_GPU;
        context_cleanup(c);
        return NULL;
    }
    query_platform_string(c, CL_PLATFORM_NAME, c->platform_name, sizeof(c->platform_name));
    query_platform_string(c, CL_PLATFORM_VENDOR, c->platform_vendor, sizeof(c->platform_vendor));
    query_platform_string(c, CL_PLATFORM_VERSION, c->platform_version, sizeof(c->platform_version));
    query_device_string(c, CL_DEVICE_NAME, c->device_name, sizeof(c->device_name));
    query_device_string(c, CL_DEVICE_VENDOR, c->device_vendor, sizeof(c->device_vendor));
    query_device_string(c, CL_DEVICE_VERSION, c->device_version, sizeof(c->device_version));
    query_device_string(c, CL_DRIVER_VERSION, c->driver_version, sizeof(c->driver_version));
    query_device_string(c, CL_DEVICE_OPENCL_C_VERSION, c->opencl_c_version,
                        sizeof(c->opencl_c_version));
    err = c->clGetDeviceInfo(c->device, CL_DEVICE_EXT_MEM_PADDING_IN_BYTES_QCOM,
                             sizeof(c->qcom_ext_mem_padding_bytes),
                             &c->qcom_ext_mem_padding_bytes, NULL);
    if (err != CL_SUCCESS) {
        (void)snprintf(g_build_log, sizeof(g_build_log),
                       "clGetDeviceInfo(CL_DEVICE_EXT_MEM_PADDING_IN_BYTES_QCOM) failed: %d",
                       (int)err);
        if (build_log) *build_log = g_build_log;
        if (status) *status = MPVFLOW_OPENCL_UNSUPPORTED_EXTENSION;
        context_cleanup(c);
        return NULL;
    }
    err = c->clGetDeviceInfo(c->device, CL_DEVICE_PAGE_SIZE_QCOM,
                             sizeof(c->qcom_page_size_bytes),
                             &c->qcom_page_size_bytes, NULL);
    c->qcom_page_size_query_ok = err == CL_SUCCESS;
    if (!c->qcom_page_size_query_ok)
        c->qcom_page_size_bytes = 0;
    probe_extension_symbols(c);
    const cl_context_properties props[] = { CL_CONTEXT_PLATFORM, (cl_context_properties)c->platform, 0 };
    c->context = c->clCreateContext(props, 1, &c->device, NULL, NULL, &err);
    if (!c->context || err != CL_SUCCESS) { context_cleanup(c); return NULL; }
    c->queue = c->clCreateCommandQueue(c->context, c->device, 0, &err);
    if (!c->queue || err != CL_SUCCESS) { context_cleanup(c); return NULL; }
    const char *src[] = {
        g_kernel_source_1, g_kernel_source_1b, g_kernel_source_2,
        g_kernel_source_2b, g_kernel_source_2c,
    };
    c->program = c->clCreateProgramWithSource(
        c->context, (cl_uint)(sizeof(src) / sizeof(src[0])), src, NULL, &err);
    if (!c->program || err != CL_SUCCESS) { context_cleanup(c); return NULL; }
    err = c->clBuildProgram(c->program, 1, &c->device, "-cl-std=CL1.2", NULL, NULL);
    if (err != CL_SUCCESS) {
        (void)c->clGetProgramBuildInfo(c->program, c->device, CL_PROGRAM_BUILD_LOG,
                                      sizeof(g_build_log) - 1, g_build_log, NULL);
        g_build_log[sizeof(g_build_log) - 1] = '\0';
        if (build_log) *build_log = g_build_log;
        if (status) *status = MPVFLOW_OPENCL_BUILD_ERROR;
        context_cleanup(c);
        return NULL;
    }
    c->luma_kernel = c->clCreateKernel(c->program, "make_luma", &err);
    if (c->luma_kernel && err == CL_SUCCESS) c->estimate_kernel = c->clCreateKernel(c->program, "estimate", &err);
    if (c->estimate_kernel && err == CL_SUCCESS) c->scene_delta_reduce_kernel = c->clCreateKernel(c->program, "scene_delta_reduce", &err);
    if (c->scene_delta_reduce_kernel && err == CL_SUCCESS) c->scene_delta_finish_kernel = c->clCreateKernel(c->program, "scene_delta_finish", &err);
    if (c->scene_delta_finish_kernel && err == CL_SUCCESS) c->synth_kernel = c->clCreateKernel(c->program, "synth", &err);
    if (!c->luma_kernel || !c->estimate_kernel || !c->scene_delta_reduce_kernel ||
        !c->scene_delta_finish_kernel || !c->synth_kernel || err != CL_SUCCESS) {
        if (status) *status = MPVFLOW_OPENCL_BUILD_ERROR;
        context_cleanup(c);
        return NULL;
    }
    if (status) *status = MPVFLOW_OPENCL_OK;
    return c;
}

const char *mpvflow_opencl_get_runtime_info(MPVFlowOpenCLContext *context)
{
    if (!context)
        return NULL;
    (void)snprintf(context->runtime_info, sizeof(context->runtime_info),
                   "platform_name=\"%s\" platform_vendor=\"%s\" platform_version=\"%s\" "
                   "device_name=\"%s\" device_vendor=\"%s\" device_version=\"%s\" "
                   "driver_version=\"%s\" opencl_c_version=\"%s\" "
                   "opencl_library=\"%s\" import_memory_arm_symbol=%d "
                   "direct_import_buffer_symbol=%d direct_import_image_symbol=%d "
                   "qcom_ext_mem_padding_bytes=%zu qcom_page_size_bytes=%zu qcom_page_size_query_ok=%d "
                   "extensions_bytes=%zu extensions_truncated=%d",
                   context->platform_name[0] ? context->platform_name : "unavailable",
                   context->platform_vendor[0] ? context->platform_vendor : "unavailable",
                   context->platform_version[0] ? context->platform_version : "unavailable",
                   context->device_name[0] ? context->device_name : "unavailable",
                   context->device_vendor[0] ? context->device_vendor : "unavailable",
                   context->device_version[0] ? context->device_version : "unavailable",
                   context->driver_version[0] ? context->driver_version : "unavailable",
                   context->opencl_c_version[0] ? context->opencl_c_version : "unavailable",
                   context->library_path[0] ? context->library_path : "unavailable",
                   context->import_memory_arm_symbol_present,
                   context->direct_import_symbol_present,
                   context->direct_import_image_symbol_present,
                   context->qcom_ext_mem_padding_bytes, context->qcom_page_size_bytes,
                   context->qcom_page_size_query_ok,
                   context->device_extensions_length, context->device_extensions_truncated);
    return context->runtime_info;
}

const char *mpvflow_opencl_get_extension_presence_info(MPVFlowOpenCLContext *context)
{
    if (!context)
        return NULL;
    (void)snprintf(context->extension_presence_info,
                   sizeof(context->extension_presence_info),
                   "cl_qcom_ext_host_ptr=%d "
                   "cl_qcom_ext_host_ptr_iocoherent=%d "
                   "cl_qcom_android_ahardwarebuffer_host_ptr=%d "
                   "cl_qcom_android_native_buffer_host_ptr=%d "
                   "cl_qcom_dmabuf_host_ptr=%d "
                   "cl_qcom_ahardwarebuffer_direct_import=%d "
                   "cl_arm_import_memory_android_hardware_buffer=%d "
                   "cl_khr_external_memory_android_hardware_buffer=%d "
                   "cl_khr_external_memory=%d "
                   "cl_khr_external_memory_dma_buf=%d "
                   "cl_khr_external_memory_opaque_fd=%d "
                   "cl_khr_external_semaphore=%d "
                   "cl_khr_external_semaphore_sync_fd=%d "
                   "cl_khr_egl_event=%d cl_khr_egl_image=%d cl_khr_gl_sharing=%d "
                   "cl_ext_image_from_buffer=%d cl_ext_image_requirements_info=%d "
                   "cl_khr_byte_addressable_store=%d",
                   has_extension(context->device_extensions, "cl_qcom_ext_host_ptr"),
                   has_extension(context->device_extensions, "cl_qcom_ext_host_ptr_iocoherent"),
                   has_extension(context->device_extensions,
                                 "cl_qcom_android_ahardwarebuffer_host_ptr"),
                   has_extension(context->device_extensions,
                                 "cl_qcom_android_native_buffer_host_ptr"),
                   has_extension(context->device_extensions, "cl_qcom_dmabuf_host_ptr"),
                   has_extension(context->device_extensions,
                                 "cl_qcom_ahardwarebuffer_direct_import"),
                   has_extension(context->device_extensions,
                                 "cl_arm_import_memory_android_hardware_buffer"),
                   has_extension(context->device_extensions,
                                 "cl_khr_external_memory_android_hardware_buffer"),
                   has_extension(context->device_extensions, "cl_khr_external_memory"),
                   has_extension(context->device_extensions,
                                 "cl_khr_external_memory_dma_buf"),
                   has_extension(context->device_extensions,
                                 "cl_khr_external_memory_opaque_fd"),
                   has_extension(context->device_extensions,
                                 "cl_khr_external_semaphore"),
                   has_extension(context->device_extensions,
                                 "cl_khr_external_semaphore_sync_fd"),
                   has_extension(context->device_extensions, "cl_khr_egl_event"),
                   has_extension(context->device_extensions, "cl_khr_egl_image"),
                   has_extension(context->device_extensions, "cl_khr_gl_sharing"),
                   has_extension(context->device_extensions,
                                 "cl_ext_image_from_buffer"),
                   has_extension(context->device_extensions,
                                 "cl_ext_image_requirements_info"),
                   has_extension(context->device_extensions,
                                 "cl_khr_byte_addressable_store"));
    return context->extension_presence_info;
}

size_t mpvflow_opencl_get_qcom_ext_mem_padding_bytes(MPVFlowOpenCLContext *context)
{
    return context ? context->qcom_ext_mem_padding_bytes : 0;
}

void mpvflow_opencl_destroy(MPVFlowOpenCLContext *context)
{
    context_cleanup(context);
}

enum MPVFlowOpenCLStatus mpvflow_opencl_finish(MPVFlowOpenCLContext *context)
{
    if (!context || !context->queue) return MPVFLOW_OPENCL_INVALID;
    return context->clFinish(context->queue) == CL_SUCCESS ? MPVFLOW_OPENCL_OK : MPVFLOW_OPENCL_ERROR;
}

static enum MPVFlowOpenCLStatus make_buffer(MPVFlowOpenCLContext *c,
                                             struct AHardwareBuffer *ahb,
                                             const struct MPVFlowOpenCLAHBDesc *desc_in,
                                             enum MPVFlowOpenCLAccess access,
                                             MPVFlowOpenCLBuffer **out,
                                             int32_t *cl_create_error_out)
{
    struct cl_mem_ahardwarebuffer_host_ptr_qcom desc;
    cl_mem_flags flags;
    cl_int err = CL_SUCCESS;
    MPVFlowOpenCLBuffer *b;
    if (cl_create_error_out)
        *cl_create_error_out = MPVFLOW_OPENCL_CL_ERROR_NOT_RETURNED;
    if (out) *out = NULL;
    if (!c || !ahb || !desc_in || !out)
        return MPVFLOW_OPENCL_INVALID;
    if (desc_in->layout != MPVFLOW_OPENCL_LAYOUT_BLOB_RGBA8_ROWS)
        return MPVFLOW_OPENCL_UNSUPPORTED_LAYOUT;
    if (desc_in->format != MPVFLOW_AHB_FORMAT_BLOB || desc_in->layers != 1 ||
        !(desc_in->usage & MPVFLOW_AHB_USAGE_GPU_DATA_BUFFER) ||
        (desc_in->usage & MPVFLOW_AHB_USAGE_PROTECTED_CONTENT))
        return MPVFLOW_OPENCL_UNSUPPORTED_FORMAT_OR_USAGE;
    if (!desc_in->ahb_width || desc_in->ahb_height != 1 || desc_in->ahb_stride != desc_in->ahb_width ||
        desc_in->allocation_bytes != (size_t)desc_in->ahb_width ||
        !desc_in->frame_width || !desc_in->frame_height ||
        desc_in->frame_width > MPVFLOW_MAX_DIM ||
        desc_in->frame_height > MPVFLOW_MAX_DIM ||
        desc_in->row_stride_bytes < desc_in->frame_width * 4u ||
        desc_in->row_stride_bytes > (uint32_t)INT32_MAX)
        return MPVFLOW_OPENCL_UNSUPPORTED_DIMENSION;
    size_t stride = (size_t)desc_in->row_stride_bytes;
    if (desc_in->frame_height > SIZE_MAX / stride ||
        desc_in->allocation_bytes < stride * (size_t)desc_in->frame_height)
        return MPVFLOW_OPENCL_INVALID;
    *out = NULL;
    b = calloc(1, sizeof(*b));
    if (!b) return MPVFLOW_OPENCL_ERROR;
    memset(&desc, 0, sizeof(desc));
    desc.ext_host_ptr.allocation_type = MPVFLOW_OPENCL_QCOM_AHB_ALLOCATION_TYPE;
    desc.ext_host_ptr.host_cache_policy = MPVFLOW_OPENCL_QCOM_HOST_WRITEBACK_POLICY;
    desc.ahb_ptr = ahb;
    if (access != MPVFLOW_OPENCL_READ_ONLY &&
        access != MPVFLOW_OPENCL_WRITE_ONLY &&
        access != MPVFLOW_OPENCL_READ_WRITE) {
        free(b);
        return MPVFLOW_OPENCL_INVALID;
    }
    /* Match the QCOM AHardwareBuffer host_ptr call shape used by MNN's Adreno
     * path: the external-allocation descriptor carries the AHB, size is zero,
     * and no explicit access qualifier is added to the two required host flags.
     * Keep allocation_bytes for descriptor validation and failure diagnostics. */
    flags = MPVFLOW_OPENCL_QCOM_IMPORT_FLAGS;
    /* The private Qualcomm AHardwareBuffer extension uses size=0: the
     * allocation descriptor, not the ordinary buffer-size argument, carries
     * the external allocation. This is the ABI used by Qualcomm's examples
     * and by established Adreno clients such as MNN. */
    b->mem = c->clCreateBuffer(c->context, flags,
                               MPVFLOW_OPENCL_QCOM_IMPORT_SIZE_ARG,
                               &desc, &err);
    /* A few vendor revisions reportedly require the allocation size instead;
     * retain a narrowly-scoped compatibility retry for that variant. */
    if ((!b->mem || err != CL_SUCCESS) &&
        err == MPVFLOW_OPENCL_CL_INVALID_OPERATION) {
        b->mem = c->clCreateBuffer(c->context, flags,
                                   desc_in->allocation_bytes, &desc, &err);
    }
    if (cl_create_error_out)
        *cl_create_error_out = err;
    if (!b->mem || err != CL_SUCCESS) { free(b); return MPVFLOW_OPENCL_ERROR; }
    b->owner = c;
    b->storage_identity = ahb;
    b->width = (int)desc_in->frame_width; b->height = (int)desc_in->frame_height;
    b->stride = (int)stride; b->access = access;
    *out = b;
    return MPVFLOW_OPENCL_OK;
}

enum MPVFlowOpenCLStatus mpvflow_opencl_import_ahardwarebuffer(
    MPVFlowOpenCLContext *context, struct AHardwareBuffer *ahb,
    const struct MPVFlowOpenCLAHBDesc *desc, enum MPVFlowOpenCLAccess access,
    MPVFlowOpenCLBuffer **buffer_out, int32_t *cl_create_error_out)
{
    return make_buffer(context, ahb, desc, access, buffer_out,
                       cl_create_error_out);
}

int32_t mpvflow_opencl_buffer_release(MPVFlowOpenCLContext *context,
                                      MPVFlowOpenCLBuffer *buffer)
{
    if (!buffer)
        return CL_SUCCESS;
    if (!context || context != buffer->owner)
        return MPVFLOW_OPENCL_CL_ERROR_NOT_RETURNED;
    cl_int err = CL_SUCCESS;
    if (buffer->mem)
        err = context->clReleaseMemObject(buffer->mem);
    free(buffer);
    return err;
}

static void pair_free(MPVFlowOpenCLContext *c, MPVFlowOpenCLPair *p)
{
    if (!p || !c || p->owner != c) return;
    for (int d = 0; d < 2; d++) {
        for (int l = 0; l < MPVFLOW_LEVELS; l++) {
            if (p->luma[d][l]) c->clReleaseMemObject(p->luma[d][l]);
            if (p->flow[d][l]) c->clReleaseMemObject(p->flow[d][l]);
        }
        if (p->frames[d]) c->clReleaseMemObject(p->frames[d]);
    }
    if (p->scene_delta) c->clReleaseMemObject(p->scene_delta);
    if (p->scene_delta_partial) c->clReleaseMemObject(p->scene_delta_partial);
    free(p);
}

static enum MPVFlowOpenCLStatus create_scratch(MPVFlowOpenCLContext *c,
                                                MPVFlowOpenCLPair *p)
{
    cl_int err;
    for (int l = 0; l < MPVFLOW_LEVELS; l++) {
        int scale = 1 << (MPVFLOW_LEVELS - 1 - l);
        int w = (p->width + scale - 1) / scale;
        int h = (p->height + scale - 1) / scale;
        int block_size = l == MPVFLOW_LEVELS - 1 ? 4 : 8;
        int gw = w >= block_size ? (w - block_size + MPVFLOW_GRID_STEP - 1) / MPVFLOW_GRID_STEP + 1 : 1;
        int gh = h >= block_size ? (h - block_size + MPVFLOW_GRID_STEP - 1) / MPVFLOW_GRID_STEP + 1 : 1;
        p->dims[l][0] = w; p->dims[l][1] = h;
        p->grids[l][0] = gw; p->grids[l][1] = gh;
        for (int d = 0; d < 2; d++) {
            p->luma[d][l] = c->clCreateBuffer(c->context, CL_MEM_READ_WRITE,
                                               (size_t)w * (size_t)h * sizeof(float), NULL, &err);
            if (!p->luma[d][l] || err != CL_SUCCESS) return MPVFLOW_OPENCL_ERROR;
            p->flow[d][l] = c->clCreateBuffer(c->context, CL_MEM_READ_WRITE,
                                               (size_t)gw * (size_t)gh * 4 * sizeof(float), NULL, &err);
            if (!p->flow[d][l] || err != CL_SUCCESS) return MPVFLOW_OPENCL_ERROR;
        }
    }
    size_t pixels = (size_t)p->width * (size_t)p->height;
    size_t group_size = (size_t)MPVFLOW_SCENE_REDUCTION_SIZE;
    size_t groups = (pixels + group_size - 1) / group_size;
    p->scene_delta_groups = (int)groups;
    p->scene_delta = c->clCreateBuffer(c->context, CL_MEM_READ_WRITE, sizeof(float), NULL, &err);
    if (!p->scene_delta || err != CL_SUCCESS) return MPVFLOW_OPENCL_ERROR;
    p->scene_delta_partial = c->clCreateBuffer(c->context, CL_MEM_READ_WRITE,
                                                groups * sizeof(float), NULL, &err);
    if (!p->scene_delta_partial || err != CL_SUCCESS) return MPVFLOW_OPENCL_ERROR;
    return MPVFLOW_OPENCL_OK;
}

#define SET_ARG(kernel, index, value) do { \
    err = c->clSetKernelArg((kernel), (index), sizeof(value), &(value)); \
    if (err != CL_SUCCESS) return MPVFLOW_OPENCL_ERROR; \
} while (0)

static enum MPVFlowOpenCLStatus enqueue_luma(MPVFlowOpenCLContext *c,
                                              MPVFlowOpenCLPair *p, int direction, int level)
{
    cl_int err;
    int scale = 1 << (MPVFLOW_LEVELS - 1 - level);
    int w = p->dims[level][0], h = p->dims[level][1];
    int ow = p->width, oh = p->height;
    cl_mem src = p->frames[direction], dst = p->luma[direction][level];
    int stride = p->stride[direction];
    SET_ARG(c->luma_kernel, 0, src); SET_ARG(c->luma_kernel, 1, stride);
    SET_ARG(c->luma_kernel, 2, ow); SET_ARG(c->luma_kernel, 3, oh);
    SET_ARG(c->luma_kernel, 4, scale); SET_ARG(c->luma_kernel, 5, dst);
    size_t global = (size_t)w * (size_t)h;
    if (c->clEnqueueNDRangeKernel(c->queue, c->luma_kernel, 1, NULL, &global, NULL, 0, NULL, NULL) != CL_SUCCESS)
        return MPVFLOW_OPENCL_ERROR;
    return MPVFLOW_OPENCL_OK;
}

static enum MPVFlowOpenCLStatus enqueue_estimate(MPVFlowOpenCLContext *c,
                                                  MPVFlowOpenCLPair *p,
                                                  int direction, int level)
{
    cl_int err;
    int w = p->dims[level][0], h = p->dims[level][1];
    int gw = p->grids[level][0], gh = p->grids[level][1];
    int cgw = 1, cgh = 1, has = level > 0;
    int radius = has ? 2 : c->search_radius;
    int block_size = level == MPVFLOW_LEVELS - 1 ? 4 : 8;
    cl_mem ref = p->luma[direction][level];
    cl_mem target = p->luma[1 - direction][level];
    cl_mem coarse = has ? p->flow[direction][level - 1] : p->flow[direction][level];
    cl_mem out = p->flow[direction][level];
    if (has) { cgw = p->grids[level - 1][0]; cgh = p->grids[level - 1][1]; }
    SET_ARG(c->estimate_kernel, 0, ref); SET_ARG(c->estimate_kernel, 1, target);
    SET_ARG(c->estimate_kernel, 2, w); SET_ARG(c->estimate_kernel, 3, h);
    SET_ARG(c->estimate_kernel, 4, coarse); SET_ARG(c->estimate_kernel, 5, cgw);
    SET_ARG(c->estimate_kernel, 6, cgh); SET_ARG(c->estimate_kernel, 7, has);
    SET_ARG(c->estimate_kernel, 8, radius); SET_ARG(c->estimate_kernel, 9, block_size);
    SET_ARG(c->estimate_kernel, 10, p->scene_delta); SET_ARG(c->estimate_kernel, 11, out);
    size_t global = (size_t)gw * (size_t)gh;
    if (c->clEnqueueNDRangeKernel(c->queue, c->estimate_kernel, 1, NULL, &global, NULL, 0, NULL, NULL) != CL_SUCCESS)
        return MPVFLOW_OPENCL_ERROR;
    return MPVFLOW_OPENCL_OK;
}

static enum MPVFlowOpenCLStatus enqueue_scene_delta(MPVFlowOpenCLContext *c,
                                                     MPVFlowOpenCLPair *p)
{
    cl_int err;
    int count = p->width * p->height;
    int groups = p->scene_delta_groups;
    cl_mem a = p->luma[0][MPVFLOW_LEVELS - 1];
    cl_mem b = p->luma[1][MPVFLOW_LEVELS - 1];
    cl_mem partial = p->scene_delta_partial;
    cl_mem delta = p->scene_delta;
    size_t local = (size_t)MPVFLOW_SCENE_REDUCTION_SIZE;
    size_t global = (size_t)groups * local;
    SET_ARG(c->scene_delta_reduce_kernel, 0, a);
    SET_ARG(c->scene_delta_reduce_kernel, 1, b);
    SET_ARG(c->scene_delta_reduce_kernel, 2, count);
    err = c->clSetKernelArg(c->scene_delta_reduce_kernel, 3, local * sizeof(float), NULL);
    if (err != CL_SUCCESS) return MPVFLOW_OPENCL_ERROR;
    SET_ARG(c->scene_delta_reduce_kernel, 4, partial);
    if (c->clEnqueueNDRangeKernel(c->queue, c->scene_delta_reduce_kernel, 1, NULL,
                                  &global, &local, 0, NULL, NULL) != CL_SUCCESS)
        return MPVFLOW_OPENCL_ERROR;

    SET_ARG(c->scene_delta_finish_kernel, 0, partial);
    SET_ARG(c->scene_delta_finish_kernel, 1, groups);
    SET_ARG(c->scene_delta_finish_kernel, 2, count);
    err = c->clSetKernelArg(c->scene_delta_finish_kernel, 3, local * sizeof(float), NULL);
    if (err != CL_SUCCESS) return MPVFLOW_OPENCL_ERROR;
    SET_ARG(c->scene_delta_finish_kernel, 4, delta);
    if (c->clEnqueueNDRangeKernel(c->queue, c->scene_delta_finish_kernel, 1, NULL,
                                  &local, &local, 0, NULL, NULL) != CL_SUCCESS)
        return MPVFLOW_OPENCL_ERROR;
    return MPVFLOW_OPENCL_OK;
}

enum MPVFlowOpenCLStatus mpvflow_opencl_analyze_pair(
    MPVFlowOpenCLContext *c, MPVFlowOpenCLBuffer *frame0, MPVFlowOpenCLBuffer *frame1,
    MPVFlowOpenCLPair **pair_out)
{
    if (!c || !frame0 || !frame1 || !pair_out || !frame0->mem || !frame1->mem ||
        frame0->owner != c || frame1->owner != c ||
        frame0->access == MPVFLOW_OPENCL_WRITE_ONLY || frame1->access == MPVFLOW_OPENCL_WRITE_ONLY ||
        frame0->width != frame1->width || frame0->height != frame1->height)
        return MPVFLOW_OPENCL_INVALID;
    *pair_out = NULL;
    if (frame0->width > c->max_dimension || frame0->height > c->max_dimension)
        return MPVFLOW_OPENCL_UNSUPPORTED_DIMENSION;
    MPVFlowOpenCLPair *p = calloc(1, sizeof(*p));
    if (!p) return MPVFLOW_OPENCL_ERROR;
    p->owner = c;
    p->width = frame0->width; p->height = frame0->height;
    p->stride[0] = frame0->stride; p->stride[1] = frame1->stride;
    if (c->clRetainMemObject(frame0->mem) != CL_SUCCESS) { free(p); return MPVFLOW_OPENCL_ERROR; }
    p->frames[0] = frame0->mem;
    if (c->clRetainMemObject(frame1->mem) != CL_SUCCESS) { pair_free(c, p); return MPVFLOW_OPENCL_ERROR; }
    p->frames[1] = frame1->mem;
    enum MPVFlowOpenCLStatus result = create_scratch(c, p);
    if (result != MPVFLOW_OPENCL_OK) { pair_free(c, p); return result; }
    for (int d = 0; d < 2; d++) {
        for (int l = 0; l < MPVFLOW_LEVELS; l++) {
            result = enqueue_luma(c, p, d, l);
            if (result != MPVFLOW_OPENCL_OK) { pair_free(c, p); return result; }
        }
    }
    result = enqueue_scene_delta(c, p);
    if (result != MPVFLOW_OPENCL_OK) { pair_free(c, p); return result; }
    for (int l = 0; l < MPVFLOW_LEVELS; l++) {
        for (int d = 0; d < 2; d++) {
            result = enqueue_estimate(c, p, d, l);
            if (result != MPVFLOW_OPENCL_OK) { pair_free(c, p); return result; }
        }
    }
    if (c->clFinish(c->queue) != CL_SUCCESS) { pair_free(c, p); return MPVFLOW_OPENCL_ERROR; }
    *pair_out = p;
    return MPVFLOW_OPENCL_OK;
}

void mpvflow_opencl_pair_release(MPVFlowOpenCLContext *context, MPVFlowOpenCLPair *pair)
{
    pair_free(context, pair);
}

enum MPVFlowOpenCLStatus mpvflow_opencl_synthesize(MPVFlowOpenCLContext *c,
                                                    MPVFlowOpenCLPair *p,
                                                    MPVFlowOpenCLBuffer *source0,
                                                    MPVFlowOpenCLBuffer *source1,
                                                    MPVFlowOpenCLBuffer *output,
                                                    float timestep)
{
    cl_int err;
    if (!c || !p || p->owner != c || !source0 || !source1 || !output ||
        source0->owner != c || source1->owner != c || output->owner != c ||
        !source0->mem || !source1->mem || !output->mem ||
        !mpvflow_opencl_synthesis_output_disjoint_from_inputs(
            source0->mem, source1->mem, output->mem,
            source0->storage_identity, source1->storage_identity,
            output->storage_identity) ||
        source0->access == MPVFLOW_OPENCL_WRITE_ONLY ||
        source1->access == MPVFLOW_OPENCL_WRITE_ONLY ||
        output->access == MPVFLOW_OPENCL_READ_ONLY ||
        source0->width != source1->width || source0->width != output->width ||
        source0->height != source1->height || source0->height != output->height ||
        source0->width <= 0 || source0->height <= 0 ||
        !isfinite(timestep) || timestep <= 0.0f || timestep >= 1.0f)
        return MPVFLOW_OPENCL_INVALID;
    if (source0->width > MPVFLOW_MAX_DIM || source0->height > MPVFLOW_MAX_DIM)
        return MPVFLOW_OPENCL_UNSUPPORTED_DIMENSION;
    cl_mem a = source0->mem, b = source1->mem, f = p->flow[0][MPVFLOW_LEVELS - 1];
    cl_mem q = p->flow[1][MPVFLOW_LEVELS - 1], dst = output->mem;
    cl_mem luma0 = p->luma[0][MPVFLOW_LEVELS - 1];
    cl_mem luma1 = p->luma[1][MPVFLOW_LEVELS - 1];
    int astride = source0->stride;
    int bstride = source1->stride;
    int w = source0->width, h = source0->height;
    int aw = p->width, ah = p->height, dstride = output->stride;
    SET_ARG(c->synth_kernel, 0, a); SET_ARG(c->synth_kernel, 1, astride);
    SET_ARG(c->synth_kernel, 2, b); SET_ARG(c->synth_kernel, 3, bstride);
    SET_ARG(c->synth_kernel, 4, w); SET_ARG(c->synth_kernel, 5, h);
    SET_ARG(c->synth_kernel, 6, aw); SET_ARG(c->synth_kernel, 7, ah);
    SET_ARG(c->synth_kernel, 8, f); SET_ARG(c->synth_kernel, 9, q);
    SET_ARG(c->synth_kernel, 10, luma0); SET_ARG(c->synth_kernel, 11, luma1);
    SET_ARG(c->synth_kernel, 12, timestep); SET_ARG(c->synth_kernel, 13, p->scene_delta);
    SET_ARG(c->synth_kernel, 14, dst); SET_ARG(c->synth_kernel, 15, dstride);
    size_t pixels = (size_t)w * (size_t)h;
    /* Stable 64-wide groups map well to Adreno wave execution; the kernel
     * already guards the rounded tail. */
    const size_t local = 64;
    size_t global = (pixels + local - 1u) / local * local;
    if (c->clEnqueueNDRangeKernel(c->queue, c->synth_kernel, 1, NULL,
                                  &global, &local, 0, NULL, NULL) != CL_SUCCESS)
        return MPVFLOW_OPENCL_ERROR;
    return c->clFinish(c->queue) == CL_SUCCESS ? MPVFLOW_OPENCL_OK : MPVFLOW_OPENCL_ERROR;
}

#undef SET_ARG
