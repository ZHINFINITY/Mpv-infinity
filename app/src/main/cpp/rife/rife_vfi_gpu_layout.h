/* SPDX-License-Identifier: AGPL-3.0-or-later */

#ifndef MPV_INFINITY_RIFE_VFI_GPU_LAYOUT_H
#define MPV_INFINITY_RIFE_VFI_GPU_LAYOUT_H

#include <cstddef>

// Keep this in sync with the pinned rife_preproc.comp shader. With
// NCNN_int8_storage enabled, RIFE expects interleaved RGB8 packed into a 2-D
// VkMat (one element per pixel, three bytes per element). Otherwise it expects
// a planar, three-channel VkMat using the selected floating-point storage.
static inline bool rife_vfi_gpu_input_layout_is_compatible(
    bool use_fp16_storage, bool use_int8_storage,
    int dims, int channels, int elempack, std::size_t elemsize)
{
    if (elempack != 1)
        return false;
    if (use_int8_storage)
        return dims == 2 && channels == 1 && elemsize == 3;
    return dims == 3 && channels == 3 &&
           elemsize == (use_fp16_storage ? 2u : 4u);
}

#endif // MPV_INFINITY_RIFE_VFI_GPU_LAYOUT_H
