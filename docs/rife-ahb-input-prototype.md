# RIFE GPU-resident playback prototype

## Status

The `experimental-features` branch now wires an **opt-in, end-to-end resident path** through the app, mpv's `gpu-next` frame queue, NCNN Vulkan input conversion and RIFE inference, and a Vulkan-written AHardwareBuffer output ring. It has **not yet been accepted on the target phone**. A successful remote build proves compilation and packaging, not correct playback or synchronization on the device; `gpu_resident_playback_ready` therefore remains false.

RIFE inference and both GPU conversion passes use **NCNN/Vulkan**. The `gpu-api=opengl` eligibility check selects only mpv's existing GLES compositor as the consumer of the Vulkan-produced RGBA AHardwareBuffer; it does not move RIFE work to OpenGL. No resident-path pixel readback to CPU is intended.

The app selects this route only when RIFE is enabled, the model is installed, hardware decoding is enabled, `gpu-next` is selected with its GLES/OpenGL renderer, and the user has not taken ownership of the resident path's `interpolation`/`video-sync` timing options. The app does not force a renderer change. Ineligible setups retain the existing CPU RGB24 `vf_rife` path. If the resident path encounters a deterministic capability, import, inference, or output-handoff error, it disables itself for the playback session and leaves ordinary mpv rendering/mixing in place; it does not dynamically copy decoder frames to CPU and switch to `vf_rife` midstream.

## Implemented path and conservative limits

- mpv's Android `AImageReader` mapper exposes its borrowed AHardwareBuffer and timestamp only while that mapper is mapped. Import is synchronous, before the image lease is released.
- Each candidate source is rejected unless its AImage timestamp is within **5 ms** of mpv's PTS and its AHardwareBuffer dimensions exactly match the visible source dimensions. Padded decoder surfaces are deliberately rejected because a crop-rectangle import path is not implemented.
- NCNN imports the decoder AHardwareBuffer with its Vulkan importer and performs GPU-only planar-FP32-to-packed-RGB8 conversion for the active int8-storage RIFE preprocessor. The importer allocator, image wrapper, and specialization pipeline are cached by AHardwareBuffer pointer; the cache is LRU-bounded to **three entries**, matching mpv's `AImageReader` `maxImages=3`. Cache entries retain and release an AHardwareBuffer reference explicitly. This avoids recreating NCNN's import pipeline for every frame.
- The VO selects PTS-bracketing source tensors from the presentation queue and uses a stream-anchored target-FPS cadence grid to avoid re-anchoring each source pair.
- A three-slot RGBA8 AHardwareBuffer ring is allocated only when Vulkan format/usage queries succeed. NCNN writes the RIFE result through a Vulkan storage image; the existing GLES renderer samples that buffer through EGLImage. The current correctness-first handoff waits for Vulkan queue completion and calls GLES `glFinish()` before reusing a sampled slot. This is intentionally conservative and may affect performance.
- Queue-owned GPU tensors are released on unmap/discard, and session failures close the resident path rather than repeatedly retrying a known-bad boundary.

These are source-level implementation facts; **timestamp semantics, decoder-buffer reuse, cross-API synchronization, color/crop correctness, frame pacing, and sustained performance still require device evidence**.

## Last-known target

The prior test target was a Xiaomi/POCO **25053PC47I** (`onyx`), Android 16 / API 36. Treat that as last-known, not a fresh device inventory; record the exact model, build fingerprint, GPU, and driver from the device used for acceptance.

## Build and host checks

The workflow builds the RIFE/native MPV ARM64 runtime and the app APK remotely. Before native MPV compilation it runs a real `mpv_set_option_string` test against host libmpv built from the pinned, patched source, the shader and RIFE host-policy tests, and the Android unit-test gate. The MPV builder validates the complete resident contract against the exact source tree it will compile. After packaging, a binary-level guard rejects a runtime APK missing the root options, async/no-buffer reader, AHB-import event, or submitted-frame event. Do not build an APK locally. A green Actions run still does not complete device acceptance.

### Latest remote build

Run [37608380375](https://github.com/ZHINFINITY/Mpv-infinity/actions/runs/37608380375) for commit `ab435f2b0f907c67daa69e661cc92cc0e5b0d30e` failed while linking `librife_vfi.so`: the API-26 `libvulkan.so` stub lacks the directly referenced Vulkan 1.1 core symbol `vkGetPhysicalDeviceImageFormatProperties2`, so that run produced no APK. The fix uses pinned NCNN's KHR dispatch pointer loaded through `vkGetInstanceProcAddr`, checks extension and pointer availability, and explicitly links NCNN statically for those dispatch globals.

The corrected [run 37612375997](https://github.com/ZHINFINITY/Mpv-infinity/actions/runs/37612375997) for commit `b3456cec74731d1d221ccbc7851cb4b2c155b96d` passed the full workflow and uploaded an APK, but a later device report came from [run 37621585320](https://github.com/ZHINFINITY/Mpv-infinity/actions/runs/37621585320) for `0ea77c5fbb2a182393e72c641b96c1b2029bbcc6`. The latter APK did **not** contain the resident VO patch or root option names: its `libmpv.so` lacked `rife-resident`, `rife-model-dir`, `AImageReader_acquireLatestImageAsync`, and both resident-frame event strings. That matches the device's `resident_option_rc=-5`, `model_option_rc=-5`, and CPU-filter fallback; the visible lack of change was expected because the resident path was not in the packaged library. The old synchronous reader also reported `AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE` (`-30001`).

Cause: CI checked the resident patch in a separate MPV checkout, then exported pristine `git archive HEAD` into the native builder. The preparation step could silently return based on one VO marker and never validated the exact source tree being compiled. The fix now copies the tested patched working tree, runs the complete contract against the builder's source, tests the bare root options through a real pre-initialization `mpv_set_option_string` call, handles transient no-buffer results without poisoning the session, and rejects any Android runtime APK missing the required native markers. `active_filter_path` is also reported only after the selected setter succeeds.

The first gated workflow attempt, [run 37629016307](https://github.com/ZHINFINITY/Mpv-infinity/actions/runs/37629016307), stopped at the host option test before any ARM64 compilation: CI's Meson 1.6.1 runs in a pipx-isolated Python environment, so the system Jinja2 package was invisible to libplacebo's shader generator. The same Meson/test setup now injects pinned Jinja2 into that venv; a local reproduction with Meson 1.6.1 and Jinja2 3.1.6 passes all four options and the unknown-option control. That run produced no APK.

The resident path is still **not device-verified**. Keep `gpu_resident_playback_ready` false and do not treat another APK as a candidate until the repaired build passes and the phone logs both `event=ahb_input_imported` and a submitted `event=gpu_resident_frame` on the intended Vulkan-NCNN/GLES-presentation path.

## Acceptance checklist for the full candidate build

1. **Identify the device/runtime:** record phone model, Android build fingerprint/API, Vulkan device/driver, mpv renderer (`gpu-next`/GLES), and selected RIFE mode.
2. **Prove the intended path is active:** app diagnostics must show `active_filter_path=vo_gpu_next_vulkan_ncnn`; mpv must log both `RIFE_DIAGNOSTIC event=ahb_input_imported` and `RIFE_DIAGNOSTIC event=gpu_resident_frame`. Confirm the latter reports `inference=vulkan-ncnn` and `renderer=gpu-next-gles-presentation`.
3. **Inspect each source boundary:** on successful input-import logs, verify AHB format/usage, buffer/process dimensions, and `pts_delta_ms` (absolute value no greater than 5 ms). Any padded-size or timestamp rejection must disable resident mode and preserve ordinary playback.
4. **Check image correctness:** compare moving/color-bar content against non-interpolated playback for RGB order, range, crop/rotation, scale, alpha and visible output dimensions. Test SDR first; HDR, interlaced, padded/cropped, or otherwise unsupported inputs must bypass safely.
5. **Check motion and timing:** test 24, 30 and 60 fps sources on 60/90/120 Hz displays; verify smooth cadence, no repeated/dropped synthesized ticks, no audio drift, and sustained playback without thermal/performance collapse.
6. **Exercise lifetime transitions:** seek/scrub, pause/resume, redraw, EOF, stream resolution/format changes, decoder restart and repeated playback. Check for stalls, stale frames, AHB-pool starvation, crashes, leaks, and cleanup warnings.
7. **Exercise fail-closed behavior:** force an unsupported renderer/usage or inspect runtime rejection; verify resident RIFE turns off and ordinary mpv playback continues. When the user owns timing options or chooses another backend, confirm the app uses the CPU filter where eligible rather than silently overriding settings.

Only after the target-device checklist passes should `gpu_resident_playback_ready` be changed to true or the mode be described as device-verified.
