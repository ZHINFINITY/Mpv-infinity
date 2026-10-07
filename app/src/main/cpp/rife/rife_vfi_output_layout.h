/* SPDX-License-Identifier: AGPL-3.0-or-later */
#ifndef MPV_INFINITY_RIFE_VFI_OUTPUT_LAYOUT_H
#define MPV_INFINITY_RIFE_VFI_OUTPUT_LAYOUT_H
#include <cstddef>

enum class RifeVfiOutputLayout {
    unsupported = 0,
    packed_rgb8 = 1,
    planar_rgb32f = 2,
};

// Pinned RIFE-v4.6 writes either interleaved RGB8 when NCNN int8 storage is
// enabled, or three planar FP32 channels otherwise. Its postprocess shader
// chooses this representation from use_int8_storage, independent of FP16.
static inline RifeVfiOutputLayout rife_vfi_output_layout_classify(
    int dims, int depth, int channels, int elempack, std::size_t elemsize)
{
    if (elempack != 1)
        return RifeVfiOutputLayout::unsupported;
    if (dims == 2 && depth == 1 && channels == 1 && elemsize == 3u)
        return RifeVfiOutputLayout::packed_rgb8;
    if (dims == 3 && depth == 1 && channels == 3 && elemsize == 4u)
        return RifeVfiOutputLayout::planar_rgb32f;
    return RifeVfiOutputLayout::unsupported;
}
#endif // MPV_INFINITY_RIFE_VFI_OUTPUT_LAYOUT_H
