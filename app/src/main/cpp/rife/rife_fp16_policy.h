/* SPDX-License-Identifier: AGPL-3.0-or-later */

#ifndef MPV_INFINITY_RIFE_FP16_POLICY_H
#define MPV_INFINITY_RIFE_FP16_POLICY_H

#include <stdbool.h>

// Use the faster arithmetic mode only when Vulkan advertises support and ncnn
// does not identify the device as affected by its implicit-FP16 driver bug.
static inline bool rife_fp16_arithmetic_is_usable(bool vulkan_available,
                                                   bool supported,
                                                   bool known_driver_bug)
{
    return vulkan_available && supported && !known_driver_bug;
}

#endif // MPV_INFINITY_RIFE_FP16_POLICY_H
