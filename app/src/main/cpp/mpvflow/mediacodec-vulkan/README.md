# Android MediaCodec → Vulkan AImageReader mapper

This directory contains an Android `AImageReader` hardware-buffer mapper for MPV's Vulkan renderer. It imports MediaCodec output as an Android `AHardwareBuffer`, synchronizes decoder/GPU access with acquire/release fences, and uses direct libplacebo sampling when supported, with a GPU conversion fallback for buffers that cannot be sampled directly.

## Source and license

The Vulkan mapper and its shader sources were adapted from the Android AImageReader implementation in [FongMi/mpv at `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`](https://github.com/FongMi/mpv/tree/cca559b41ceb0bb7731cf6ef2e1f33276cd30c42/video/out/hwdec). The source is adapted to this repository's pinned upstream MPV revision (`c1529642089bfebfc928a1c1664638a7a5d219ba`) and FFmpeg 9.0.2 API; fork-only MediaCodec helpers and mapper fields are not required. `LICENSE.LGPL` contains the LGPL 2.1 text applicable to the MPV-derived code.

The `.comp`, `.frag`, and `.vert` files are the editable shader sources; their generated C headers are compiled into MPV. `scripts/prepare-mpvflow.py` copies these files into the pinned MPV checkout and applies `mpvflow-aimagereader-vulkan.patch` after the MPVFlow renderer patch.

## Runtime policy

The custom MPVFlow arm64 runtime enables this mapper and prefers direct MediaCodec/Vulkan import with a conversion fallback. Other standard builds keep the existing copy-decoding fallback unless they package this custom runtime. A device without the required Vulkan AHardwareBuffer/sync-fd capabilities does not advertise the direct path.
