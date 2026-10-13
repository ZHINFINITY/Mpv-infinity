/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
#ifndef MPVFLOW_VULKAN_OPTICAL_H
#define MPVFLOW_VULKAN_OPTICAL_H

#include <libplacebo/vulkan.h>
#include <stddef.h>
#include <stdint.h>

typedef struct MPVFlowVulkanOptical MPVFlowVulkanOptical;

#ifdef __cplusplus
extern "C" {
#endif

/* Optional acceleration only: unavailable capabilities return NULL or false. */
MPVFlowVulkanOptical *mpvflow_vulkan_optical_create(pl_gpu gpu,
                                                    char *reason,
                                                    size_t reason_size);
bool mpvflow_vulkan_optical_ensure(MPVFlowVulkanOptical *optical,
                                   int width, int height);
pl_tex mpvflow_vulkan_optical_input(MPVFlowVulkanOptical *optical, int index);
pl_tex mpvflow_vulkan_optical_vectors(MPVFlowVulkanOptical *optical, int index);
bool mpvflow_vulkan_optical_dispatch(MPVFlowVulkanOptical *optical);
void mpvflow_vulkan_optical_destroy(MPVFlowVulkanOptical **optical);

/* Manual debug-only probe; never called by the playback adapter. */
bool mpvflow_vulkan_optical_debug_self_test(pl_gpu gpu);

#ifdef __cplusplus
}
#endif

#endif
