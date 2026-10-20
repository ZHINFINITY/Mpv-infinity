/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
#ifndef MPVFLOW_VULKAN_QCOM_H
#define MPVFLOW_VULKAN_QCOM_H
#include "mpvflow_visibility.h"

#include <libplacebo/vulkan.h>
#include <stdbool.h>
#include <stddef.h>

typedef struct MPVFlowVulkanQcom MPVFlowVulkanQcom;

#ifdef __cplusplus
extern "C" {
#endif

/* Optional accelerator; unavailable or unverified hardware returns NULL/false. */
MPVFLOW_INTERNAL MPVFlowVulkanQcom *mpvflow_vulkan_qcom_create(pl_gpu gpu,
                                             char *reason,
                                             size_t reason_size);
MPVFLOW_INTERNAL bool mpvflow_vulkan_qcom_ensure(MPVFlowVulkanQcom *qcom,
                                int width, int height,
                                int grid_width, int grid_height,
                                int block_size);
MPVFLOW_INTERNAL pl_tex mpvflow_vulkan_qcom_luma(MPVFlowVulkanQcom *qcom, int index);
MPVFLOW_INTERNAL pl_tex mpvflow_vulkan_qcom_seed(MPVFlowVulkanQcom *qcom, int index);
MPVFLOW_INTERNAL bool mpvflow_vulkan_qcom_dispatch(MPVFlowVulkanQcom *qcom);
MPVFLOW_INTERNAL void mpvflow_vulkan_qcom_destroy(MPVFlowVulkanQcom **qcom);

#ifdef __cplusplus
}
#endif

#endif
