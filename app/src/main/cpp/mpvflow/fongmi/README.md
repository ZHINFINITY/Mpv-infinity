# FongMi GPU motion interpolation

This directory contains the GPU motion estimation and frame-synthesis implementation from [FongMi/mpv](https://github.com/FongMi/mpv), pinned to commit [`f0ebca2a2c546444d9f9a62662b2f6e27614f7de`](https://github.com/FongMi/mpv/tree/f0ebca2a2c546444d9f9a62662b2f6e27614f7de/video/out/gpu_next). The upstream progress notes are available in [`DOCS/memc-progress.md`](https://github.com/FongMi/mpv/blob/04e9d60470cfac3b407bc8bafcd54e2955e8f761/DOCS/memc-progress.md).

`motion.c`, `motion.h`, `motion_clock.h`, and the accompanying tests retain their upstream `SPDX-License-Identifier: LGPL-2.1-or-later` notices. Local changes add a pre-analysis entry point and a caller-owned output-texture path so Media3 can reuse its existing Vulkan/AHardwareBuffer presentation buffers without an extra output allocation. The MPV runtime port is applied by `scripts/prepare-mpvflow.py`.

Keep this attribution and the source-file notices with the code. The experimental branch remains unmerged and unreleased; no release decision is made here.
