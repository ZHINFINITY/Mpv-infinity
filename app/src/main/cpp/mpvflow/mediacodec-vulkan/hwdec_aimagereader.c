/*
 * Copyright (c) 2021 sfan5 <sfan5@live.de>
 *
 * This file is part of mpv.
 *
 * mpv is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * mpv is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with mpv.  If not, see <http://www.gnu.org/licenses/>.
 */

#include "config.h"

#include <dlfcn.h>
#include <errno.h>
#include <inttypes.h>
#include <math.h>
#include <poll.h>
#include <unistd.h>
#include <EGL/egl.h>
#include <android/data_space.h>
#include <media/NdkImageReader.h>
#include <android/native_window_jni.h>
#include <libavcodec/mediacodec.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_mediacodec.h>

#include "misc/jni.h"
#include "options/options.h"
#include "osdep/threads.h"
#include "osdep/timer.h"
#include "video/out/gpu/hwdec.h"
#include "video/out/opengl/ra_gl.h"

#if HAVE_VULKAN
#include "hwdec_aimagereader_vk.h"
#endif

#define IMAGE_TIMESTAMP_TOLERANCE_NS 100000.0

typedef void *GLeglImageOES;
typedef void *EGLImageKHR;
#define EGL_NATIVE_BUFFER_ANDROID 0x3140

struct priv_owner {
    struct mp_hwdec_ctx hwctx;
    AImageReader *reader;
    jobject java_surface;
    jmethodID surface_release_id;
    void *lib_handle;
    bool has_gl_yuv_target;

    // AImageReader callbacks are queued and can outlive individual mappers.
    mp_mutex image_lock;
    mp_cond image_cond;
    bool image_available;

#if HAVE_VULKAN
    mp_mutex vk_lock;
    struct aimagereader_vk *vk;
    enum android_vulkan_aimagereader_backend vk_backend;
#endif

    media_status_t (*AImageReader_newWithUsage)(
        int32_t, int32_t, int32_t, uint64_t, int32_t, AImageReader **);
    media_status_t (*AImageReader_getWindow)(
        AImageReader *, ANativeWindow **);
    media_status_t (*AImageReader_setImageListener)(
        AImageReader *, AImageReader_ImageListener *);
    media_status_t (*AImageReader_setBufferRemovedListener)(
        AImageReader *, AImageReader_BufferRemovedListener *);
    media_status_t (*AImageReader_acquireLatestImage)(
        AImageReader *, AImage **);
    media_status_t (*AImageReader_acquireLatestImageAsync)(
        AImageReader *, AImage **, int *);
    void (*AImageReader_delete)(AImageReader *);
    media_status_t (*AImage_getHardwareBuffer)(
        const AImage *, AHardwareBuffer **);
    media_status_t (*AImage_getCropRect)(
        const AImage *, AImageCropRect *);
    media_status_t (*AImage_getDataSpace)(
        const AImage *, int32_t *);
    media_status_t (*AImage_getTimestamp)(
        const AImage *, int64_t *);
    void (*AImage_delete)(AImage *);
    void (*AImage_deleteAsync)(AImage *, int);
    void (*AHardwareBuffer_describe)(
        const AHardwareBuffer *, AHardwareBuffer_Desc *);
    jobject (*ANativeWindow_toSurface)(JNIEnv *, ANativeWindow *);
};

enum mapper_backend {
    MAPPER_BACKEND_GL,
    MAPPER_BACKEND_VULKAN,
};

enum aimage_color_standard {
    AIMAGE_COLOR_STANDARD_UNKNOWN,
    AIMAGE_COLOR_STANDARD_BT601,
    AIMAGE_COLOR_STANDARD_BT709,
    AIMAGE_COLOR_STANDARD_BT2020,
    AIMAGE_COLOR_STANDARD_BT2020_CL,
    AIMAGE_COLOR_STANDARD_ICTCP,
};

struct aimage_stats {
    uint64_t render_requests;
    uint64_t unavailable_outputs;
    uint64_t pending_replacements;
    uint64_t acquire_timeouts;
    uint64_t stale_images;
    uint64_t newer_images;
    uint64_t acquire_errors;
    uint64_t acquired_images;
    uint64_t mapped_images;
};

struct priv {
    struct mp_log *log;
    enum mapper_backend backend;

    GLuint gl_texture;
    AImage *image;
    EGLImageKHR egl_image;
    EGLDisplay egl_image_display;
    struct mp_image *gl_source_frame;
    struct mp_image **gl_source_aliases;
    int num_gl_source_aliases;
    bool checked_data_space;
    int32_t last_checked_data_space;
    // MediaCodec release and AImageReader availability are asynchronous.
    struct mp_image *pending_frame;
    int64_t pending_timestamp_ns;
    bool pending_check_timestamp;
    struct aimage_stats stats;

    EGLImageKHR (EGLAPIENTRY *CreateImageKHR)(
        EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint *);
    EGLBoolean (EGLAPIENTRY *DestroyImageKHR)(EGLDisplay, EGLImageKHR);
    EGLClientBuffer (EGLAPIENTRY *GetNativeClientBufferANDROID)(
        const struct AHardwareBuffer *);
    void (EGLAPIENTRY *EGLImageTargetTexture2DOES)(GLenum, GLeglImageOES);

#if HAVE_VULKAN
    struct aimagereader_vk *vk;
#endif
};

static enum aimage_color_standard source_color_standard(
    const struct mp_image_params *params)
{
    switch (params->repr.sys) {
    case PL_COLOR_SYSTEM_BT_601:
        return AIMAGE_COLOR_STANDARD_BT601;
    case PL_COLOR_SYSTEM_BT_709:
        return AIMAGE_COLOR_STANDARD_BT709;
    case PL_COLOR_SYSTEM_BT_2020_NC:
        return AIMAGE_COLOR_STANDARD_BT2020;
    case PL_COLOR_SYSTEM_BT_2020_C:
        return AIMAGE_COLOR_STANDARD_BT2020_CL;
    case PL_COLOR_SYSTEM_BT_2100_PQ:
    case PL_COLOR_SYSTEM_BT_2100_HLG:
        return AIMAGE_COLOR_STANDARD_ICTCP;
    default:
        return AIMAGE_COLOR_STANDARD_UNKNOWN;
    }
}

static enum aimage_color_standard data_space_color_standard(int32_t data_space)
{
    switch (data_space & ADATASPACE_STANDARD_MASK) {
    case ADATASPACE_STANDARD_BT601_625:
    case ADATASPACE_STANDARD_BT601_625_UNADJUSTED:
    case ADATASPACE_STANDARD_BT601_525:
    case ADATASPACE_STANDARD_BT601_525_UNADJUSTED:
        return AIMAGE_COLOR_STANDARD_BT601;
    case ADATASPACE_STANDARD_BT709:
        return AIMAGE_COLOR_STANDARD_BT709;
    case ADATASPACE_STANDARD_BT2020:
        return AIMAGE_COLOR_STANDARD_BT2020;
    case ADATASPACE_STANDARD_BT2020_CONSTANT_LUMINANCE:
        return AIMAGE_COLOR_STANDARD_BT2020_CL;
    default:
        return AIMAGE_COLOR_STANDARD_UNKNOWN;
    }
}

static enum pl_color_primaries data_space_primaries(int32_t data_space)
{
    switch (data_space & ADATASPACE_STANDARD_MASK) {
    case ADATASPACE_STANDARD_BT709:
        return PL_COLOR_PRIM_BT_709;
    case ADATASPACE_STANDARD_BT2020:
    case ADATASPACE_STANDARD_BT2020_CONSTANT_LUMINANCE:
        return PL_COLOR_PRIM_BT_2020;
    case ADATASPACE_STANDARD_DCI_P3:
        return PL_COLOR_PRIM_DCI_P3;
    default:
        return PL_COLOR_PRIM_UNKNOWN;
    }
}

static enum pl_color_levels data_space_levels(int32_t data_space)
{
    switch (data_space & ADATASPACE_RANGE_MASK) {
    case ADATASPACE_RANGE_FULL:
        return PL_COLOR_LEVELS_FULL;
    case ADATASPACE_RANGE_LIMITED:
        return PL_COLOR_LEVELS_LIMITED;
    default:
        return PL_COLOR_LEVELS_UNKNOWN;
    }
}

static enum pl_color_transfer data_space_transfer(int32_t data_space)
{
    switch (data_space & ADATASPACE_TRANSFER_MASK) {
    case ADATASPACE_TRANSFER_LINEAR:
        return PL_COLOR_TRC_LINEAR;
    case ADATASPACE_TRANSFER_SRGB:
        return PL_COLOR_TRC_SRGB;
    case ADATASPACE_TRANSFER_SMPTE_170M:
        return PL_COLOR_TRC_BT_1886;
    case ADATASPACE_TRANSFER_GAMMA2_2:
        return PL_COLOR_TRC_GAMMA22;
    case ADATASPACE_TRANSFER_GAMMA2_6:
        return PL_COLOR_TRC_GAMMA26;
    case ADATASPACE_TRANSFER_GAMMA2_8:
        return PL_COLOR_TRC_GAMMA28;
    case ADATASPACE_TRANSFER_ST2084:
        return PL_COLOR_TRC_PQ;
    case ADATASPACE_TRANSFER_HLG:
        return PL_COLOR_TRC_HLG;
    default:
        return PL_COLOR_TRC_UNKNOWN;
    }
}

static void log_data_space_mismatch(struct ra_hwdec_mapper *mapper,
                                    int32_t data_space)
{
    struct priv *p = mapper->priv;
    if (p->checked_data_space &&
        p->last_checked_data_space == data_space)
        return;
    p->checked_data_space = true;
    p->last_checked_data_space = data_space;

    if (data_space == ADATASPACE_UNKNOWN)
        return;

    const struct mp_image_params *params = &mapper->src_params;
    enum aimage_color_standard source_standard =
        source_color_standard(params);
    enum aimage_color_standard image_standard =
        data_space_color_standard(data_space);
    enum pl_color_primaries image_primaries =
        data_space_primaries(data_space);
    enum pl_color_levels image_levels = data_space_levels(data_space);
    enum pl_color_transfer image_transfer =
        data_space_transfer(data_space);

    bool standard_conflict =
        source_standard != AIMAGE_COLOR_STANDARD_UNKNOWN &&
        image_standard != AIMAGE_COLOR_STANDARD_UNKNOWN &&
        source_standard != image_standard;
    bool primaries_conflict =
        params->color.primaries != PL_COLOR_PRIM_UNKNOWN &&
        image_primaries != PL_COLOR_PRIM_UNKNOWN &&
        params->color.primaries != image_primaries;
    bool levels_conflict =
        source_standard != AIMAGE_COLOR_STANDARD_UNKNOWN &&
        params->repr.levels != PL_COLOR_LEVELS_UNKNOWN &&
        image_levels != PL_COLOR_LEVELS_UNKNOWN &&
        params->repr.levels != image_levels;
    bool transfer_conflict =
        params->color.transfer != PL_COLOR_TRC_UNKNOWN &&
        image_transfer != PL_COLOR_TRC_UNKNOWN &&
        params->color.transfer != image_transfer;

    if (!standard_conflict && !primaries_conflict &&
        !levels_conflict && !transfer_conflict)
        return;

    // AImage describes the buffer MediaCodec actually produced. A decoder or
    // external sampler may validly convert the coded representation, so this
    // is diagnostic information and never a reason to discard the frame.
    MP_VERBOSE(mapper, "AImage dataspace 0x%x differs from MediaCodec frame "
                       "metadata (standard %d/%d, primaries %s/%s, "
                       "levels %d/%d, transfer %s/%s)\n",
               data_space, source_standard, image_standard,
               pl_color_primaries_name(params->color.primaries),
               pl_color_primaries_name(image_primaries),
               params->repr.levels, image_levels,
               pl_color_transfer_name(params->color.transfer),
               pl_color_transfer_name(image_transfer));
}

#define LIB_FUNCTION(name) {#name, offsetof(struct priv_owner, name)}
static const struct {
    const char *symbol;
    int offset;
} lib_functions[] = {
    LIB_FUNCTION(AImageReader_newWithUsage),
    LIB_FUNCTION(AImageReader_getWindow),
    LIB_FUNCTION(AImageReader_setImageListener),
    LIB_FUNCTION(AImageReader_setBufferRemovedListener),
    LIB_FUNCTION(AImageReader_acquireLatestImage),
    LIB_FUNCTION(AImageReader_acquireLatestImageAsync),
    LIB_FUNCTION(AImageReader_delete),
    LIB_FUNCTION(AImage_getHardwareBuffer),
    LIB_FUNCTION(AImage_getCropRect),
    LIB_FUNCTION(AImage_getTimestamp),
    LIB_FUNCTION(AImage_delete),
    LIB_FUNCTION(AImage_deleteAsync),
    LIB_FUNCTION(AHardwareBuffer_describe),
    LIB_FUNCTION(ANativeWindow_toSurface),
    { NULL, 0 },
};
#undef LIB_FUNCTION

static void *resolve_lib_symbol(struct priv_owner *p, const char *symbol)
{
    void *function = dlsym(p->lib_handle, symbol);
    return function ? function : dlsym(RTLD_DEFAULT, symbol);
}

static AVBufferRef *create_mediacodec_device_ref(jobject surface)
{
    AVBufferRef *device_ref = av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_MEDIACODEC);
    if (!device_ref)
        return NULL;

    AVHWDeviceContext *ctx = (void *)device_ref->data;
    AVMediaCodecDeviceContext *hwctx = ctx->hwctx;
    hwctx->surface = surface;

    if (av_hwdevice_ctx_init(device_ref) < 0)
        av_buffer_unref(&device_ref);

    return device_ref;
}

static bool load_lib_functions(struct priv_owner *p, struct mp_log *log)
{
    p->lib_handle = dlopen("libmediandk.so", RTLD_NOW | RTLD_GLOBAL);
    if (!p->lib_handle)
        return false;
    for (int i = 0; lib_functions[i].symbol; i++) {
        const char *sym = lib_functions[i].symbol;
        void *fun = resolve_lib_symbol(p, sym);
        if (!fun) {
            mp_warn(log, "Could not resolve symbol %s\n", sym);
            return false;
        }

        *(void **) ((uint8_t*)p + lib_functions[i].offset) = fun;
    }

    *(void **)&p->AImage_getDataSpace =
        resolve_lib_symbol(p, "AImage_getDataSpace");

    return true;
}

#if HAVE_VULKAN
static void buffer_removed_callback(void *context, AImageReader *reader,
                                    AHardwareBuffer *buffer)
{
    struct priv_owner *p = context;

    mp_mutex_lock(&p->vk_lock);
    if (p->vk)
        aimagereader_vk_buffer_removed(p->vk, buffer);
    mp_mutex_unlock(&p->vk_lock);
}
#endif

static void image_callback(void *context, AImageReader *reader)
{
    struct priv_owner *p = context;

    mp_mutex_lock(&p->image_lock);
    p->image_available = true;
    mp_cond_signal(&p->image_cond);
    mp_mutex_unlock(&p->image_lock);
}

static int init(struct ra_hwdec *hw)
{
    struct priv_owner *p = hw->priv;
    bool use_gl = ra_is_gl(hw->ra_ctx->ra);
    bool use_vulkan = false;

    mp_mutex_init(&p->image_lock);
    mp_cond_init(&p->image_cond);

#if HAVE_VULKAN
    mp_mutex_init(&p->vk_lock);
    p->vk_backend = ANDROID_VULKAN_AIMAGEREADER_BACKEND_AUTO;
    use_vulkan = aimagereader_vk_available(hw->ra_ctx, hw->log,
                                           p->vk_backend);
#endif

    if (!use_gl && !use_vulkan)
        return -1;

    if (use_gl) {
        if (!eglGetCurrentContext())
            return -1;
        const char *exts =
            eglQueryString(eglGetCurrentDisplay(), EGL_EXTENSIONS);
        if (!gl_check_extension(exts, "EGL_ANDROID_image_native_buffer"))
            return -1;
    }

    JNIEnv *env = MP_JNI_GET_ENV(hw);
    if (!env)
        return -1;

    if (!load_lib_functions(p, hw->log))
        return -1;

    if (use_gl) {
        static const char *es2_exts[] = {"GL_OES_EGL_image_external", 0};
        static const char *es3_exts[] = {
            "GL_OES_EGL_image_external_essl3", 0
        };
        GL *gl = ra_gl_get(hw->ra_ctx->ra);
        bool external_essl3 =
            gl_check_extension(gl->extensions, es3_exts[0]);
#if PL_API_VER >= 375
        p->has_gl_yuv_target = gl->es >= 300 &&
            gl_check_extension(gl->extensions, es2_exts[0]) &&
            gl_check_extension(gl->extensions, "GL_EXT_YUV_target");
#endif
        if (external_essl3)
            hw->glsl_extensions = es3_exts;
        else
            hw->glsl_extensions = es2_exts;
    }

    // dummy dimensions, AImageReader only transports hardware buffers
    media_status_t ret = p->AImageReader_newWithUsage(16, 16,
        AIMAGE_FORMAT_PRIVATE, AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
        5, &p->reader);
    if (ret != AMEDIA_OK) {
        MP_ERR(hw, "newWithUsage failed: %d\n", ret);
        return -1;
    }
    mp_assert(p->reader);

    AImageReader_ImageListener image_listener = {
        .context = p,
        .onImageAvailable = image_callback,
    };
    ret = p->AImageReader_setImageListener(p->reader, &image_listener);
    if (ret != AMEDIA_OK) {
        MP_ERR(hw, "setImageListener failed: %d\n", ret);
        return -1;
    }

#if HAVE_VULKAN
    if (use_vulkan) {
        AImageReader_BufferRemovedListener buffer_listener = {
            .context = p,
            .onBufferRemoved = buffer_removed_callback,
        };
        ret = p->AImageReader_setBufferRemovedListener(
            p->reader, &buffer_listener);
        if (ret != AMEDIA_OK) {
            MP_ERR(hw, "setBufferRemovedListener failed: %d\n", ret);
            return -1;
        }
    }
#endif

    ANativeWindow *window;
    ret = p->AImageReader_getWindow(p->reader, &window);
    if (ret != AMEDIA_OK) {
        MP_ERR(hw, "getWindow failed: %d\n", ret);
        return -1;
    }
    mp_assert(window);

    // The Java wrapper owns a local native Surface reference. Release it
    // explicitly instead of deferring native cleanup to Java GC.
    jobject java_surface = p->ANativeWindow_toSurface(env, window);
    if (MP_JNI_EXCEPTION_LOG(hw) < 0 || !java_surface) {
        MP_JNI_LOCAL_FREEP(&java_surface);
        MP_ERR(hw, "Failed to create Java surface\n");
        return -1;
    }

    jclass surface_class = (*env)->GetObjectClass(env, java_surface);
    if (MP_JNI_EXCEPTION_LOG(hw) < 0 || !surface_class) {
        MP_JNI_LOCAL_FREEP(&surface_class);
        MP_JNI_LOCAL_FREEP(&java_surface);
        MP_ERR(hw, "Failed to resolve Java Surface class\n");
        return -1;
    }

    jmethodID surface_release_id =
        (*env)->GetMethodID(env, surface_class, "release", "()V");
    MP_JNI_LOCAL_FREEP(&surface_class);
    if (MP_JNI_EXCEPTION_LOG(hw) < 0 || !surface_release_id) {
        MP_JNI_LOCAL_FREEP(&java_surface);
        MP_ERR(hw, "Failed to resolve Java Surface.release\n");
        return -1;
    }

    p->java_surface = (*env)->NewGlobalRef(env, java_surface);
    MP_JNI_LOCAL_FREEP(&java_surface);
    if (MP_JNI_EXCEPTION_LOG(hw) < 0 || !p->java_surface) {
        MP_ERR(hw, "Failed to retain Java surface\n");
        return -1;
    }
    p->surface_release_id = surface_release_id;

    p->hwctx = (struct mp_hwdec_ctx) {
        .driver_name = hw->driver->name,
        .av_device_ref = create_mediacodec_device_ref(p->java_surface),
        .hw_imgfmt = IMGFMT_MEDIACODEC,
    };

    if (!p->hwctx.av_device_ref) {
        MP_VERBOSE(hw, "Failed to create hwdevice_ctx\n");
        return -1;
    }

    hwdec_devices_add(hw->devs, &p->hwctx);

    return 0;
}

static void uninit(struct ra_hwdec *hw)
{
    struct priv_owner *p = hw->priv;

    hwdec_devices_remove(hw->devs, &p->hwctx);
    av_buffer_unref(&p->hwctx.av_device_ref);

    if (p->java_surface) {
        JNIEnv *env = MP_JNI_GET_ENV(hw);
        mp_assert(env);
        mp_assert(p->surface_release_id);
        MP_JNI_CALL_VOID(p->java_surface, p->surface_release_id);
        MP_JNI_EXCEPTION_LOG(hw);
        MP_JNI_GLOBAL_FREEP(&p->java_surface);
        p->surface_release_id = NULL;
    }

    if (p->reader) {
        p->AImageReader_delete(p->reader);
        p->reader = NULL;
    }

    mp_mutex_destroy(&p->image_lock);
    mp_cond_destroy(&p->image_cond);

#if HAVE_VULKAN
    mp_assert(!p->vk);
    mp_mutex_destroy(&p->vk_lock);
#endif

    if (p->lib_handle) {
        dlclose(p->lib_handle);
        p->lib_handle = NULL;
    }
}

static int mapper_init(struct ra_hwdec_mapper *mapper)
{
    struct priv *p = mapper->priv;
    struct priv_owner *o = mapper->owner->priv;
    bool raw_dovi = mapper->src_params.repr.dovi != NULL;

    p->log = mapper->log;

    mapper->dst_params = mapper->src_params;
    mapper->dst_params.imgfmt = IMGFMT_RGB0;
    mapper->dst_params.hw_subfmt = 0;

#if HAVE_VULKAN
    if (!ra_is_gl(mapper->ra)) {
        const struct aimagereader_vk_api api = {
            .AImage_delete = o->AImage_delete,
            .AImage_deleteAsync = o->AImage_deleteAsync,
            .AHardwareBuffer_describe = o->AHardwareBuffer_describe,
        };
        p->backend = MAPPER_BACKEND_VULKAN;
        p->vk = aimagereader_vk_create(mapper, &api, o->vk_backend);
        if (!p->vk)
            return -1;
        mp_mutex_lock(&o->vk_lock);
        mp_assert(!o->vk);
        o->vk = p->vk;
        mp_mutex_unlock(&o->vk_lock);
        return 0;
    }
#endif

    p->backend = MAPPER_BACKEND_GL;
    if (raw_dovi && !o->has_gl_yuv_target)
        return -1;

    GL *gl = ra_gl_get(mapper->ra);
    p->CreateImageKHR = (void *)eglGetProcAddress("eglCreateImageKHR");
    p->DestroyImageKHR = (void *)eglGetProcAddress("eglDestroyImageKHR");
    p->GetNativeClientBufferANDROID =
        (void *)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    p->EGLImageTargetTexture2DOES =
        (void *)eglGetProcAddress("glEGLImageTargetTexture2DOES");

    if (!p->CreateImageKHR || !p->DestroyImageKHR ||
        !p->GetNativeClientBufferANDROID || !p->EGLImageTargetTexture2DOES)
        return -1;

    // texture creation
    gl->GenTextures(1, &p->gl_texture);
    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, p->gl_texture);
    gl->TexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    gl->TexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    gl->TexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    gl->TexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    struct ra_tex_params params = {
        .dimensions = 2,
        .w = mapper->src_params.w,
        .h = mapper->src_params.h,
        .d = 1,
        .format = ra_find_unorm_format(mapper->ra, 1, 4),
        .render_src = true,
        .src_linear = true,
        .external_oes = true,
    };

    if (params.format->ctype != RA_CTYPE_UNORM)
        return -1;

    mapper->tex[0] = ra_create_wrapped_tex(mapper->ra, &params, p->gl_texture);
    if (!mapper->tex[0])
        return -1;

    if (raw_dovi) {
        // Profile 5 exposes normalized 10-bit YUV samples through the external
        // texture, without P010 storage padding.
        mapper->dst_params.repr.bits = (struct pl_bit_encoding) {
            .sample_depth = 10,
            .color_depth = 10,
        };
        mapper->dst_params_preserve_repr = true;
        mapper->dst_gl_external_yuv = true;
        mapper->dst_num_components = 3;
        const int mapping[4] = {
            PL_CHANNEL_Y,
            PL_CHANNEL_CB,
            PL_CHANNEL_CR,
            PL_CHANNEL_NONE,
        };
        memcpy(mapper->dst_component_mapping, mapping, sizeof(mapping));
        MP_INFO(mapper, "Using OpenGL raw YUV AHardwareBuffer sampling for "
                        "Dolby Vision\n");
    }

    return 0;
}

static void release_gl_mapping(struct ra_hwdec_mapper *mapper)
{
    struct priv *p = mapper->priv;
    struct priv_owner *o = mapper->owner->priv;

    if (p->egl_image) {
        if (!p->DestroyImageKHR(p->egl_image_display, p->egl_image))
            MP_WARN(mapper, "Failed to destroy EGLImage: 0x%x\n",
                    eglGetError());
        p->egl_image = 0;
        p->egl_image_display = EGL_NO_DISPLAY;
    }
    if (p->image) {
        o->AImage_delete(p->image);
        p->image = NULL;
    }
    mp_image_unrefp(&p->gl_source_frame);
    for (int n = 0; n < p->num_gl_source_aliases; n++)
        mp_image_unrefp(&p->gl_source_aliases[n]);
    p->num_gl_source_aliases = 0;
}

static void mapper_uninit(struct ra_hwdec_mapper *mapper)
{
    struct priv *p = mapper->priv;

    MP_VERBOSE(mapper, "AImageReader stats: render requests %" PRIu64
                       ", unavailable outputs %" PRIu64
                       ", pending replacements %" PRIu64 "\n",
               p->stats.render_requests, p->stats.unavailable_outputs,
               p->stats.pending_replacements);
    MP_VERBOSE(mapper, "AImageReader stats: acquired %" PRIu64
                       ", mapped %" PRIu64 ", timeouts %" PRIu64
                       ", stale %" PRIu64 ", newer %" PRIu64
                       ", errors %" PRIu64 "\n",
               p->stats.acquired_images, p->stats.mapped_images,
               p->stats.acquire_timeouts, p->stats.stale_images,
               p->stats.newer_images, p->stats.acquire_errors);
    mp_image_unrefp(&p->pending_frame);
#if HAVE_VULKAN
    struct priv_owner *o = mapper->owner->priv;
    if (p->backend == MAPPER_BACKEND_VULKAN) {
        mp_mutex_lock(&o->vk_lock);
        mp_assert(o->vk == p->vk);
        o->vk = NULL;
        mp_mutex_unlock(&o->vk_lock);
        aimagereader_vk_destroy(&p->vk);
    } else
#endif
    {
        release_gl_mapping(mapper);
        GL *gl = ra_gl_get(mapper->ra);
        if (p->gl_texture)
            gl->DeleteTextures(1, &p->gl_texture);
        p->gl_texture = 0;

        ra_tex_free(mapper->ra, &mapper->tex[0]);
    }
}

static void mapper_unmap(struct ra_hwdec_mapper *mapper)
{
    // Keep the last successfully imported image alive until its replacement is
    // ready. MediaCodec can drop an output during a flush, and AImageReader
    // then legitimately has no matching image for that output buffer.
}

static void delete_image(struct priv_owner *o, AImage *image, int fence_fd)
{
    if (fence_fd >= 0)
        o->AImage_deleteAsync(image, fence_fd);
    else
        o->AImage_delete(image);
}

static media_status_t acquire_latest_image(struct ra_hwdec_mapper *mapper,
                                           AImage **image,
                                           int *acquire_fence,
                                           int64_t expected_timestamp_ns,
                                           bool check_timestamp)
{
    struct priv *p = mapper->priv;
    struct priv_owner *o = mapper->owner->priv;
    int64_t deadline = mp_time_ns() + MP_TIME_MS_TO_NS(100);

    while (true) {
        *image = NULL;
        *acquire_fence = -1;

        media_status_t ret;
#if HAVE_VULKAN
        if (p->backend == MAPPER_BACKEND_VULKAN) {
            ret = o->AImageReader_acquireLatestImageAsync(
                o->reader, image, acquire_fence);
        } else
#endif
        {
            ret = o->AImageReader_acquireLatestImage(o->reader, image);
        }
        if (ret == AMEDIA_OK && check_timestamp) {
            int64_t image_timestamp_ns;
            ret = o->AImage_getTimestamp(*image, &image_timestamp_ns);
            if (ret != AMEDIA_OK) {
                MP_ERR(mapper, "getTimestamp failed: %d\n", ret);
                delete_image(o, *image, *acquire_fence);
                *image = NULL;
                *acquire_fence = -1;
                return ret;
            }

            double delta_ns =
                (double)image_timestamp_ns - expected_timestamp_ns;
            // MediaCodec timestamps are in microseconds before Android
            // converts them to nanoseconds. Allow only rescaling noise.
            if (fabs(delta_ns) <= IMAGE_TIMESTAMP_TOLERANCE_NS)
                return AMEDIA_OK;

            if (delta_ns < 0) {
                p->stats.stale_images++;
                MP_VERBOSE(mapper, "Discarding stale AImage timestamp "
                                   "(expected %" PRId64 ", got %" PRId64
                                   ", delta %.0f ns)\n",
                           expected_timestamp_ns, image_timestamp_ns,
                           delta_ns);
                delete_image(o, *image, *acquire_fence);
                *image = NULL;
                *acquire_fence = -1;
                continue;
            }

            p->stats.newer_images++;
            MP_ERR(mapper, "AImage timestamp is newer than the MediaCodec "
                           "frame (expected %" PRId64 ", got %" PRId64
                           ", delta %.0f ns)\n",
                   expected_timestamp_ns, image_timestamp_ns, delta_ns);
            delete_image(o, *image, *acquire_fence);
            *image = NULL;
            *acquire_fence = -1;
            return AMEDIA_ERROR_UNKNOWN;
        }
        if (ret != AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE)
            return ret;

        int64_t remaining = deadline - mp_time_ns();
        if (remaining <= 0) {
            p->stats.acquire_timeouts++;
            return ret;
        }

        // acquireLatestImage() is allowed to consume all images covered by
        // already queued callbacks. Treat the listener as a wakeup hint and
        // retry until the shared deadline rather than failing on a stale one.
        mp_mutex_lock(&o->image_lock);
        if (!o->image_available)
            mp_cond_timedwait(&o->image_cond, &o->image_lock, remaining);
        o->image_available = false;
        mp_mutex_unlock(&o->image_lock);
    }
}

static bool retain_previous_frame(struct ra_hwdec_mapper *mapper)
{
    struct priv *p = mapper->priv;
#if HAVE_VULKAN
    if (p->backend == MAPPER_BACKEND_VULKAN)
        return aimagereader_vk_retain_last(p->vk, mapper->src);
#endif

    if (!p->egl_image)
        return false;
    struct mp_image *frame_ref = mp_image_new_ref(mapper->src);
    if (!frame_ref)
        return false;
    MP_TARRAY_APPEND(p, p->gl_source_aliases,
                     p->num_gl_source_aliases, frame_ref);
    return true;
}

static int mapper_map(struct ra_hwdec_mapper *mapper)
{
    struct priv *p = mapper->priv;
    struct priv_owner *o = mapper->owner->priv;

    if (mapper->src->imgfmt != IMGFMT_MEDIACODEC)
        return -1;
#if HAVE_VULKAN
    if (p->backend == MAPPER_BACKEND_VULKAN &&
        aimagereader_vk_reuse(p->vk, mapper->src))
        return 0;
#endif
    if (p->backend == MAPPER_BACKEND_GL) {
        if (p->gl_source_frame &&
            p->gl_source_frame->planes[3] == mapper->src->planes[3])
            return 0;
        for (int n = 0; n < p->num_gl_source_aliases; n++) {
            if (p->gl_source_aliases[n]->planes[3] == mapper->src->planes[3])
                return 0;
        }
    }
    AVMediaCodecBuffer *buffer = (AVMediaCodecBuffer *)mapper->src->planes[3];
    long double pts_ns = (long double)mapper->src->pts * 1000000000.0L;
    bool check_timestamp = mapper->src->pts != MP_NOPTS_VALUE &&
        isfinite(mapper->src->pts) && pts_ns >= (long double)INT64_MIN &&
        pts_ns <= (long double)INT64_MAX;
    int64_t expected_timestamp_ns = check_timestamp ? llroundl(pts_ns) : 0;

    AVMediaCodecBuffer *pending_buffer = p->pending_frame
        ? (AVMediaCodecBuffer *)p->pending_frame->planes[3]
        : NULL;
    if (pending_buffer && pending_buffer != buffer) {
        p->stats.pending_replacements++;
        MP_VERBOSE(mapper, "Dropping a pending MediaCodec/AImage match after "
                           "the output changed\n");
        mp_image_unrefp(&p->pending_frame);
    }
    if (p->pending_frame) {
        expected_timestamp_ns = p->pending_timestamp_ns;
        check_timestamp = p->pending_check_timestamp;
    } else {
        if (av_mediacodec_release_buffer(buffer, 1) < 0) {
            MP_ERR(mapper, "Failed rendering MediaCodec output buffer\n");
            return -1;
        }
        p->stats.render_requests++;

        mp_image_setrefp(&p->pending_frame, mapper->src);
        p->pending_timestamp_ns = expected_timestamp_ns;
        p->pending_check_timestamp = check_timestamp;
    }

    AImage *image = NULL;
    int acquire_fence = -1;
    media_status_t ret =
        acquire_latest_image(mapper, &image, &acquire_fence,
                             expected_timestamp_ns, check_timestamp);
    if (ret == AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) {
        MP_VERBOSE(mapper, "Waiting for frame timed out\n");
        return RA_HWDEC_MAP_RETRY;
    }
    mp_image_unrefp(&p->pending_frame);
    if (ret != AMEDIA_OK) {
        p->stats.acquire_errors++;
        MP_ERR(mapper, "acquireLatestImage failed: %d\n", ret);
        return -1;
    }
    mp_assert(image);
    p->stats.acquired_images++;

    bool vulkan_mapping = false;
#if HAVE_VULKAN
    vulkan_mapping = p->backend == MAPPER_BACKEND_VULKAN;
#endif
    if (acquire_fence >= 0 && !vulkan_mapping) {
        struct pollfd fence = {
            .fd = acquire_fence,
            .events = POLLIN,
        };
        int poll_result;
        do {
            poll_result = poll(&fence, 1, 100);
        } while (poll_result < 0 && (errno == EINTR || errno == EAGAIN));
        if (poll_result <= 0 || fence.revents & (POLLERR | POLLNVAL)) {
            MP_ERR(mapper, "Waiting for AImage acquire fence failed: "
                           "%d (revents 0x%x)\n",
                   poll_result, (unsigned)fence.revents);
            delete_image(o, image, acquire_fence);
            return -1;
        }
        close(acquire_fence);
        acquire_fence = -1;
    }

    AHardwareBuffer *hwbuf = NULL;
    ret = o->AImage_getHardwareBuffer(image, &hwbuf);
    if (ret != AMEDIA_OK) {
        MP_ERR(mapper, "getHardwareBuffer failed: %d\n", ret);
        delete_image(o, image, acquire_fence);
        return -1;
    }
    mp_assert(hwbuf);

    int32_t data_space = ADATASPACE_UNKNOWN;
    if (o->AImage_getDataSpace &&
        o->AImage_getDataSpace(image, &data_space) != AMEDIA_OK)
        data_space = ADATASPACE_UNKNOWN;
    log_data_space_mismatch(mapper, data_space);

#if HAVE_VULKAN
    if (p->backend == MAPPER_BACKEND_VULKAN) {
        AImageCropRect crop;
        ret = o->AImage_getCropRect(image, &crop);
        if (ret != AMEDIA_OK) {
            MP_ERR(mapper, "getCropRect failed: %d\n", ret);
            delete_image(o, image, acquire_fence);
            return -1;
        }

        int result = aimagereader_vk_map(
            p->vk, image, hwbuf, &crop, data_space, mapper->src,
            &acquire_fence);
        if (result < 0)
            delete_image(o, image, acquire_fence);
        else
            p->stats.mapped_images++;
        return result;
    }
#endif

    EGLClientBuffer buf = p->GetNativeClientBufferANDROID(hwbuf);
    if (!buf) {
        o->AImage_delete(image);
        return -1;
    }

    EGLDisplay egl_display = eglGetCurrentDisplay();
    if (egl_display == EGL_NO_DISPLAY) {
        MP_ERR(mapper, "No current EGLDisplay for AHardwareBuffer import\n");
        o->AImage_delete(image);
        return -1;
    }

    const int attribs[] = {EGL_NONE};
    EGLImageKHR egl_image = p->CreateImageKHR(egl_display,
        EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, buf, attribs);
    if (!egl_image) {
        o->AImage_delete(image);
        return -1;
    }
    struct mp_image *frame_ref = mp_image_new_ref(mapper->src);
    if (!frame_ref) {
        p->DestroyImageKHR(egl_display, egl_image);
        o->AImage_delete(image);
        return -1;
    }

    GL *gl = ra_gl_get(mapper->ra);
    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, p->gl_texture);
    p->EGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, egl_image);
    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    release_gl_mapping(mapper);
    p->egl_image = egl_image;
    p->egl_image_display = egl_display;
    p->image = image;
    p->gl_source_frame = frame_ref;
    p->stats.mapped_images++;

    // Update texture size since it may differ.
    AHardwareBuffer_Desc d;
    o->AHardwareBuffer_describe(hwbuf, &d);
    if (mapper->tex[0]->params.w != (int)d.width ||
        mapper->tex[0]->params.h != (int)d.height) {
        MP_VERBOSE(p, "Texture dimensions changed to %dx%d\n", d.width, d.height);
        mapper->tex[0]->params.w = d.width;
        mapper->tex[0]->params.h = d.height;
    }

    return 0;
}


const struct ra_hwdec_driver ra_hwdec_aimagereader = {
    .name = "aimagereader",
    .priv_size = sizeof(struct priv_owner),
    .imgfmts = {IMGFMT_MEDIACODEC, 0},
    .device_type = AV_HWDEVICE_TYPE_MEDIACODEC,
    .init = init,
    .uninit = uninit,
    .mapper = &(const struct ra_hwdec_mapper_driver){
        .priv_size = sizeof(struct priv),
        .init = mapper_init,
        .uninit = mapper_uninit,
        .map = mapper_map,
        .unmap = mapper_unmap,
    },
};
