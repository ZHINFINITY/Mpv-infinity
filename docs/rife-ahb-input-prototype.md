# RIFE AHardwareBuffer input prototype

## Status

This branch contains an **opt-in native diagnostic probe**, not GPU-resident playback. The regular `vf_rife` path is unchanged and remains the safe CPU-readable RGB24 path with NCNN Vulkan inference. The probe is not called from the filter or renderer, and `gpu_resident_playback_ready` is hard-gated false.

The native probe accepts a caller-owned Android `AHardwareBuffer`, validates API 26+, Vulkan AHB-import and foreign-queue support plus `GPU_SAMPLED_IMAGE` usage, imports it through NCNN's `VkAndroidHardwareBufferImageAllocator` and `ImportAndroidHardwareBufferPipeline`, and returns an RGB float32 `VkMat`. A diagnostic-only synchronized readback API is available to compare channels/range against a CPU reference. The import pipeline is created per probe call; it is not suitable for frame-rate playback until cached per allocation.

The imported RGB float32 tensor is **not** the active RIFE model's int8-storage input layout. There is no GPU-only tensor conversion yet. There is also no retained decoder-frame lease, PTS-pair matching, writable output AHardwareBuffer, renderer texture import, or queue/fence lifecycle integration. Therefore the probe must not be used as interpolation input, and it does not enable or claim GPU-resident playback.

## Known target

Current manual test target, based on the prior device record: Xiaomi/POCO **25053PC47I** (`onyx`), **Android 16 / API 36**. Treat this as the target only; confirm the device identity/build fingerprint from current logs if it changes.

## Required device acceptance before enabling playback

1. **Runtime capability and decoder buffer**: log Android API/build fingerprint, Vulkan device/driver, AHB external-memory and foreign-queue extensions, and the actual decoder AHB descriptor. Verify `GPU_SAMPLED_IMAGE` usage and fail closed if absent.
2. **Input import correctness**: with known-color MediaCodec frames, import through NCNN Vulkan; compare RGB order, limited/full range, crop/rotation, stride, dimensions, and timestamp association against a CPU reference. Test repeated AHB allocations and import-cache reuse.
3. **RIFE tensor adapter**: add a GPU-only conversion from imported RGB to the exact active RIFE tensor; prove layout, normalization, padding, and timestep behavior. Do not pass the current FP32 probe tensor directly to the int8-storage RIFE path.
4. **Writable output bridge**: query and import the chosen RGBA AHB usage on device, write a deterministic Vulkan pattern, import/sample it in the mpv renderer, and validate producer/consumer fences and safe ring-slot reuse.
5. **PTS queue/lifetime integration**: correlate source PTS and AImage timestamp uniquely; exercise pair retention, seek/reset, format/size change, pause, EOF, redraw, queue discard, backpressure, and exactly-once release.
6. **Playback acceptance**: compare synthesized cadence/audio sync and sustained performance with the CPU fallback. Confirm logs explicitly distinguish active Vulkan-resident synthesis, CPU RGB24 filtering, and fail-closed bypass.

Until all six groups pass on the target phone, keep the prototype disabled and preserve the existing filter fallback. The ARM64 Actions build validates compilation/package integration only; it is not device acceptance.
