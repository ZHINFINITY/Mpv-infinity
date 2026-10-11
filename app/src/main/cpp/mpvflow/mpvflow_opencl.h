/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Optional OpenCL/QCOM AHardwareBuffer motion-compensated interpolation backend.
 * This module is deliberately independent of libplacebo/Vulkan: callers keep
 * Vulkan for rendering and texture copies, and explicitly bridge eligible frame
 * storage as Android HardwareBuffers. No pixels are mapped or read on the CPU.
 */
#ifndef MPV_INFINITY_MPVFLOW_OPENCL_H
#define MPV_INFINITY_MPVFLOW_OPENCL_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct AHardwareBuffer;
typedef struct MPVFlowOpenCLContext MPVFlowOpenCLContext;
typedef struct MPVFlowOpenCLBuffer MPVFlowOpenCLBuffer;
typedef struct MPVFlowOpenCLPair MPVFlowOpenCLPair;

enum MPVFlowOpenCLStatus {
    MPVFLOW_OPENCL_OK = 0,
    MPVFLOW_OPENCL_UNAVAILABLE = -1,
    MPVFLOW_OPENCL_UNSUPPORTED_EXTENSION = -2,
    MPVFLOW_OPENCL_NO_GPU = -3,
    MPVFLOW_OPENCL_ERROR = -4,
    MPVFLOW_OPENCL_INVALID = -5,
    MPVFLOW_OPENCL_BUILD_ERROR = -6,
    MPVFLOW_OPENCL_UNSUPPORTED_DIMENSION = -7,
    MPVFLOW_OPENCL_UNSUPPORTED_FORMAT_OR_USAGE = -8,
    MPVFLOW_OPENCL_UNSUPPORTED_LAYOUT = -9,
};

/* Raw clCreateBuffer status sentinel when import validation/allocation fails
 * before the vendor OpenCL API is called. */
#define MPVFLOW_OPENCL_CL_ERROR_NOT_RETURNED INT32_MIN

enum MPVFlowOpenCLAccess {
    MPVFLOW_OPENCL_READ_ONLY = 0,
    MPVFLOW_OPENCL_WRITE_ONLY = 1,
    MPVFLOW_OPENCL_READ_WRITE = 2,
};

struct MPVFlowOpenCLConfig {
    /* Per-level integer-pixel search radius; clamped to [1, 8], default 4. */
    int search_radius;
    /* Maximum pair-analysis working dimension, default 2048, hard maximum 4096. */
    int max_dimension;
};

/* Values mirror AHardwareBuffer_describe(): BLOB format is 0x21 and usage bit
 * 24 is AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER. The AHB's own width is its byte
 * allocation size; logical frame dimensions/row pitch are separate fields. */
#define MPVFLOW_AHB_FORMAT_BLOB 0x21u
#define MPVFLOW_AHB_USAGE_GPU_DATA_BUFFER (1ULL << 24)
enum MPVFlowOpenCLLayout {
    /* BLOB AHB contains row-major packed RGBA8 rows (including row padding). */
    MPVFLOW_OPENCL_LAYOUT_BLOB_RGBA8_ROWS = 1,
};
struct MPVFlowOpenCLAHBDesc {
    uint32_t ahb_width;
    uint32_t ahb_height;
    uint32_t ahb_stride;
    uint32_t layers;
    uint32_t format;
    uint64_t usage;
    size_t allocation_bytes;
    uint32_t frame_width;
    uint32_t frame_height;
    uint32_t row_stride_bytes;
    enum MPVFlowOpenCLLayout layout;
};

/*
 * Dynamically loads libOpenCL.so (no OpenCL link dependency), chooses an OpenCL
 * GPU advertising cl_qcom_android_ahardwarebuffer_host_ptr, creates a context,
 * queue and all required kernels. Returns NULL with an explicit status if the
 * runtime, extension, GPU or required entry points are unavailable. The optional
 * error string, when non-NULL, points to a process-static build log valid until
 * the next create call. No alternate CPU/Vulkan backend is selected here.
 */
MPVFlowOpenCLContext *mpvflow_opencl_create(
    const struct MPVFlowOpenCLConfig *config,
    enum MPVFlowOpenCLStatus *status,
    const char **build_log);
/* Complete all queued OpenCL work; callers can use this before marking shared
 * AHardwareBuffer storage Vulkan-safe after an operation that reported an error. */
enum MPVFlowOpenCLStatus mpvflow_opencl_finish(MPVFlowOpenCLContext *context);
/* Release every buffer and pair created from this context before destroying it. */
void mpvflow_opencl_destroy(MPVFlowOpenCLContext *context);

/*
 * Import a format-BLOB, single-layer, GPU_DATA_BUFFER AHardwareBuffer using the
 * QCOM host_ptr extension. `ahb_width` must equal the actual allocation size in
 * bytes; the AHB descriptor's height/layers must be 1 and its stride must match
 * the reported descriptor. `frame_width`, `frame_height`, and `row_stride_bytes`
 * describe packed RGBA8 content inside the BLOB, and `allocation_bytes` must be
 * the actual allocation size (not a guessed width*height); this nonzero byte
 * size is passed to the QCOM clCreateBuffer host-pointer import. The caller must
 * establish that this is the same linear VkBuffer-backed allocation used for
 * Vulkan buffer-to-image/image-to-buffer copies; optimal-tiled Vulkan image
 * AHardwareBuffers are not accepted as byte buffers. Logical frame dimensions
 * are limited to 4096 per axis; analyze_pair additionally applies the configured
 * analysis working-size limit. Vulkan↔AHardwareBuffer
 * creation, buffer/image copies, synchronization and layout transitions remain
 * the integration layer's responsibility; this module does not create that
 * bridge. The
 * caller retains ownership of the AHardwareBuffer and must keep it alive until
 * this wrapper (and all pair work using it) has been released. The OpenCL
 * import result is returned explicitly; the optional raw error output receives
 * clCreateBuffer's vendor error (or MPVFLOW_OPENCL_CL_ERROR_NOT_RETURNED if the
 * API was not reached). Unsupported descriptors and failed imports never fall
 * back to CPU or Vulkan processing.
 */
enum MPVFlowOpenCLStatus mpvflow_opencl_import_ahardwarebuffer(
    MPVFlowOpenCLContext *context,
    struct AHardwareBuffer *ahb,
    const struct MPVFlowOpenCLAHBDesc *desc,
    enum MPVFlowOpenCLAccess access,
    MPVFlowOpenCLBuffer **buffer_out,
    int32_t *cl_create_error_out);
void mpvflow_opencl_buffer_release(MPVFlowOpenCLContext *context,
                                   MPVFlowOpenCLBuffer *buffer);

/*
 * Analyze a pair of imported BLOB-backed RGBA8 frame buffers at their configured
 * working resolution with a three-level luma pyramid and bounded coarse-to-fine
 * 8x8 block matching in both directions. Pair owns its
 * retained cl_mem references and scratch buffers; imported wrappers may be
 * released after this call succeeds. Commands are completed with clFinish before
 * return so input lifetime and error reporting are deterministic. Frame buffers
 * must have equal dimensions and be unchanged for the duration of this call.
 */
enum MPVFlowOpenCLStatus mpvflow_opencl_analyze_pair(
    MPVFlowOpenCLContext *context,
    MPVFlowOpenCLBuffer *frame0,
    MPVFlowOpenCLBuffer *frame1,
    MPVFlowOpenCLPair **pair_out);
void mpvflow_opencl_pair_release(MPVFlowOpenCLContext *context,
                                 MPVFlowOpenCLPair *pair);

/*
 * Synthesize from two separate, full-resolution imported BLOB-backed RGBA8 source
 * buffers into a full-resolution output buffer using the pair's retained low-resolution
 * analysis flow. Source0, source1, and output dimensions must match and may be up to
 * the hard 4096-per-axis limit, independently of the analysis max_dimension. Sources
 * must be readable and output writable. Pixel centers map into analysis coordinates;
 * flow vectors are scaled back to source pixels for warping. Confidence and
 * forward/backward consistency checks, with nearest-frame fallback for occlusions/
 * ambiguous matches are retained; a global mean absolute analysis-luma delta above
 * 48/255 selects the nearest endpoint without warping. 0 < timestep < 1. A blocking
 * clFinish completes output writes before return. Wrappers remain caller-owned; Vulkan can then consume/
 * copy the same AHardwareBuffer through its existing interop path.
 */
enum MPVFlowOpenCLStatus mpvflow_opencl_synthesize(
    MPVFlowOpenCLContext *context,
    MPVFlowOpenCLPair *pair,
    MPVFlowOpenCLBuffer *source0,
    MPVFlowOpenCLBuffer *source1,
    MPVFlowOpenCLBuffer *output,
    float timestep);

#ifdef __cplusplus
}
#endif
#endif /* MPV_INFINITY_MPVFLOW_OPENCL_H */
