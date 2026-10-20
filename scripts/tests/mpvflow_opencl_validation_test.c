#include <assert.h>
#include <stdio.h>

#include "mpvflow_opencl_validation.h"

int main(void)
{
    int source0_mem, source1_mem, output_mem;
    int source0_storage, source1_storage, output_storage;

    assert(mpvflow_opencl_synthesis_output_disjoint_from_inputs(
        &source0_mem, &source1_mem, &output_mem,
        &source0_storage, &source1_storage, &output_storage));

    /* Read-only source aliasing is valid for repeated/stationary frames. */
    assert(mpvflow_opencl_synthesis_output_disjoint_from_inputs(
        &source0_mem, &source0_mem, &output_mem,
        &source0_storage, &source0_storage, &output_storage));

    /* Catch identity aliases even when wrappers expose different handles. */
    assert(!mpvflow_opencl_synthesis_output_disjoint_from_inputs(
        &source0_mem, &source1_mem, &source0_mem,
        &source0_storage, &source1_storage, &output_storage));
    assert(!mpvflow_opencl_synthesis_output_disjoint_from_inputs(
        &source0_mem, &source1_mem, &output_mem,
        &source0_storage, &source1_storage, &source0_storage));
    assert(!mpvflow_opencl_synthesis_output_disjoint_from_inputs(
        &source0_mem, &source1_mem, &output_mem,
        &source0_storage, &source1_storage, &source1_storage));
    assert(!mpvflow_opencl_synthesis_output_disjoint_from_inputs(
        NULL, &source1_mem, &output_mem,
        &source0_storage, &source1_storage, &output_storage));

    puts("MPV Flow OpenCL synthesis alias validation tests passed");
    return 0;
}
