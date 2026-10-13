#ifndef MPV_INFINITY_MPVFLOW_OPENCL_VALIDATION_H
#define MPV_INFINITY_MPVFLOW_OPENCL_VALIDATION_H

#include <stdbool.h>

/* Inputs may refer to the same read-only frame, but synthesis output must not
 * alias either input handle or its backing allocation. */
static inline bool mpvflow_opencl_synthesis_output_disjoint_from_inputs(
    const void *source0_mem,
    const void *source1_mem,
    const void *output_mem,
    const void *source0_storage,
    const void *source1_storage,
    const void *output_storage)
{
    if (!source0_mem || !source1_mem || !output_mem ||
        !source0_storage || !source1_storage || !output_storage)
        return false;

    return source0_mem != output_mem && source1_mem != output_mem &&
           source0_storage != output_storage && source1_storage != output_storage;
}

#endif
