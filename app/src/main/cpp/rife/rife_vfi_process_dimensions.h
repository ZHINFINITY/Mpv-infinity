#ifndef RIFE_VFI_PROCESS_DIMENSIONS_H
#define RIFE_VFI_PROCESS_DIMENSIONS_H

#include <stdbool.h>

// Resolve the processing size used by both RIFE input imports and output slots.
// max_dimension == 0 selects the target-FPS Auto cap; -1 disables scaling;
// positive values are explicit maximum dimensions.
static inline bool rife_vfi_process_dimensions(int source_width, int source_height,
                                               int max_dimension, int target_fps,
                                               int *process_width, int *process_height)
{
    if (source_width <= 0 || source_height <= 0 || target_fps <= 0 ||
        max_dimension < -1 || !process_width || !process_height)
        return false;

    int limit = max_dimension;
    if (limit == 0)
        limit = target_fps >= 60 ? 480 : target_fps >= 48 ? 720 : 1080;

    int width = source_width;
    int height = source_height;
    int source_max = source_width > source_height ? source_width : source_height;
    if (limit > 0 && source_max > limit) {
        double scale = (double) limit / source_max;
        width = (int) (source_width * scale) & ~1;
        height = (int) (source_height * scale) & ~1;
        if (width < 2)
            width = 2;
        if (height < 2)
            height = 2;
    }

    *process_width = width;
    *process_height = height;
    return true;
}

#endif
