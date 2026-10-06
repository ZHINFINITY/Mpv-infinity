/* SPDX-License-Identifier: AGPL-3.0-or-later */

#ifndef MPV_INFINITY_RIFE_VFI_H
#define MPV_INFINITY_RIFE_VFI_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct RifeVfiEngine RifeVfiEngine;

RifeVfiEngine *rife_vfi_create(const char *model_dir, char *error,
                               size_t error_size);
void rife_vfi_destroy(RifeVfiEngine *engine);
int rife_vfi_interpolate_rgb24(RifeVfiEngine *engine,
                               const uint8_t *frame0,
                               const uint8_t *frame1,
                               int width, int height, float timestep,
                               uint8_t *output);

#ifdef __cplusplus
}
#endif

#endif // MPV_INFINITY_RIFE_VFI_H
