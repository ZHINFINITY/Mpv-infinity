/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
#ifndef MPV_INFINITY_MPVFLOW_VISIBILITY_H
#define MPV_INFINITY_MPVFLOW_VISIBILITY_H

#if defined(__GNUC__) || defined(__clang__)
#define MPVFLOW_INTERNAL __attribute__((visibility("hidden")))
#else
#define MPVFLOW_INTERNAL
#endif

#endif /* MPV_INFINITY_MPVFLOW_VISIBILITY_H */
