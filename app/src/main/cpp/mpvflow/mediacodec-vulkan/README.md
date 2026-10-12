# Android MediaCodec → Vulkan AImageReader mapper

This mapper sends MediaCodec output to an Android `AImageReader`/`AHardwareBuffer`, imports each buffer into Vulkan, and converts its YCbCr samples into a normal RGB texture with a Vulkan compute or fragment pass. MPVFlow consumes that texture on the GPU; video pixels are never copied back to the CPU. Acquire/release fences protect decoder/GPU ownership.

## Why conversion is used

The native build pins upstream libplacebo revision `0d043c7f6f79cd3687c023454bdacbe615e4d96f`. That API cannot wrap an Android external-format YCbCr image as a directly sampleable libplacebo texture; the direct wrapper in the FongMi fork relies on a fork-only API. The converter is the supported path for this pinned build and mirrors Media3's GPU-side decoder-output conversion before Flow processing. It adds a GPU color-conversion pass but does not add CPU readback.

## Source and license

The Android AImageReader adapter, Vulkan conversion backend, and shader sources are adapted from [FongMi/mpv at `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`](https://github.com/FongMi/mpv/tree/cca559b41ceb0bb7731cf6ef2e1f33276cd30c42/video/out/hwdec). They are adapted to this repository's pinned upstream MPV revision (`c1529642089bfebfc928a1c1664638a7a5d219ba`) and FFmpeg 9.0.2 API; fork-only MediaCodec helpers and mapper fields are not required. `LICENSE.LGPL` contains the LGPL 2.1 text applicable to the MPV-derived code.

The `.comp`, `.frag`, and `.vert` files are the editable shader sources; their generated C headers are compiled into MPV. `scripts/prepare-mpvflow.py` copies these files into the pinned MPV checkout and applies `mpvflow-aimagereader-vulkan.patch` after the MPVFlow renderer patch.

## Runtime policy

The custom MPVFlow arm64 runtime enables this Vulkan AImageReader mapper and prefers the direct MediaCodec decoder surface path followed by GPU conversion. Builds without this mapper, or devices without the required Vulkan AHardwareBuffer/synchronization capabilities, retain the existing `mediacodec-copy` fallback.
