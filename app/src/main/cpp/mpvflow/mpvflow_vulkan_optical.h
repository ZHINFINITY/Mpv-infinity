/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
#ifndef MPVFLOW_VULKAN_OPTICAL_H
#define MPVFLOW_VULKAN_OPTICAL_H
#include "mpvflow_visibility.h"

#include <libplacebo/vulkan.h>
#include <stddef.h>
#include <stdint.h>

typedef struct MPVFlowVulkanOptical MPVFlowVulkanOptical;

#ifdef __cplusplus
extern "C" {
#endif

/* Optional acceleration only: unavailable capabilities return NULL or false. */
MPVFLOW_INTERNAL MPVFlowVulkanOptical *mpvflow_vulkan_optical_create(pl_gpu gpu,
                                                    char *reason,
                                                    size_t reason_size);
MPVFLOW_INTERNAL bool mpvflow_vulkan_optical_ensure(MPVFlowVulkanOptical *optical,
                                   int width, int height);
MPVFLOW_INTERNAL pl_tex mpvflow_vulkan_optical_input(MPVFlowVulkanOptical *optical, int index);
MPVFLOW_INTERNAL pl_tex mpvflow_vulkan_optical_vectors(MPVFlowVulkanOptical *optical, int index);
MPVFLOW_INTERNAL bool mpvflow_vulkan_optical_dispatch(MPVFlowVulkanOptical *optical);
MPVFLOW_INTERNAL void mpvflow_vulkan_optical_destroy(MPVFlowVulkanOptical **optical);

#ifdef __cplusplus
}
#endif

#endif
