# MPVFlow: on-device motion-interpolation experiment

## Goal and relationship to RIFE

MPVFlow is a separate, local motion-estimation/motion-compensation (MEMC) path. It does not call the existing RIFE filter, its model/runtime, or an off-device service. The settings switches are mutually exclusive so the two interpolation paths are not accidentally stacked.

This is an independently written prototype informed by public descriptions of frame-rate conversion. It is not SVPFlow source code, does not reproduce proprietary defaults, and is not claimed to be pixel-identical to SVPlayer.

## Active GPU implementation

### MPV `gpu-next` route

When MPVFlow is eligible on the MPV engine, the app selects the `gpu-next` renderer with Vulkan. The patched MPV video output supplies source-frame textures and display-timed output ticks to the shared Flow implementation. The renderer API and interpolation compute API are separate choices: Vulkan remains the MPV renderer even when Flow's motion and synthesis kernels use OpenCL.

### OpenCL compute backend

The app now has a real OpenCL path, not a settings-only toggle. It dynamically loads the optional Android `libOpenCL.so`, compiles the Flow kernels at runtime, and imports shared Android `HardwareBuffer` BLOB allocations through Qualcomm's `cl_qcom_android_ahardwarebuffer_host_ptr` extension. Vulkan copies RGBA8 images to/from those shared buffers; OpenCL performs multi-scale luma/motion analysis, scene-cut detection, confidence/consistency checks, and full-resolution synthesis. The generated image returns to the Vulkan renderer. Pixel data is not copied through the CPU and there is no CPU pixel readback path.

The current OpenCL interop implementation is **Qualcomm/Adreno-specific**. OpenCL enumeration alone is not enough: the runtime extension, Vulkan external-memory/foreign-queue support, compatible RGBA8 transfer usage, buffer import probe, and kernel build must all succeed. `Auto` selects OpenCL only after those checks; otherwise it uses the Vulkan/SPIR-V backend. Explicit OpenCL does not silently switch to Vulkan: initialization or runtime failures are diagnosed and affected frames pass through. Other vendors' OpenCL import extensions are not implemented yet.

For conservative cross-API correctness, this first bridge serializes Vulkan buffer copies and OpenCL work with Vulkan fences and `clFinish`. This avoids CPU pixel copies but can add substantial blocking overhead. It is an initial functional backend, not evidence that it improves cadence on every device; it needs measurement on the target phone before being recommended as faster.

### Vulkan compute backend

The Vulkan/SPIR-V backend remains available explicitly and as `Auto` fallback. It uses libplacebo passes on the renderer's Vulkan device. Motion analysis is bounded to a reduced working size while synthesis remains at the original source dimensions. Unsupported input or a deadline miss uses original source frames rather than stalling playback.

### Native Media3 route

The separate Native Media3 route uses a Vulkan renderer/context and the same Flow compute API preference. Its decoded surface is captured on GLES, with Vulkan handling Flow buffers and the final display handoff; interpolation itself uses either the selected supported OpenCL route or Vulkan/SPIR-V. MPV's `gpu-next` setting does not select the Native Media3 route's renderer.

The current support policy keeps HDR, Dolby Vision, DRM, rotated, and non-square-pixel content on Media3's standard renderer. Unsupported formats or tight deadlines fall back to source frames.

## Renderer and compute settings

The Decoder screen has distinct controls for **Vulkan renderer API** and **MPVFlow compute API**. While MPVFlow is active and eligible on the MPV route, Flow requires `gpu-next` with Vulkan rendering; the screen shows those effective renderer choices and locks the renderer controls until Flow is turned off. The compute selector offers `Auto`, `OpenCL`, and `Vulkan` without changing that renderer choice.

On the MPV route, `mpvflow-compute-api` is the corresponding mpv option; an explicit `mpv.conf` value remains authoritative. Native Media3 receives the app preference directly. The target-rate choices remain **48, 60, 72, 90, 96, 120, and 144 fps**; actual output remains device- and display-dependent.

## Cadence and device constraints

The effective Flow target is capped to Android's reported display refresh; this cap is disclosed and is not presented as proof that a selected target was achieved. The automatic longest-side motion-analysis cap is 640 pixels through 60 fps, 480 at 72–90, 400 at 96–120, and 320 at 144. These are processing-size limits, not promises of encoded output resolution or sustained performance.

For the MPV Vulkan renderer, Flow requests Vulkan `mailbox` presentation before creating `gpu-next`, so stale queued images can be replaced without lowering the selected target rate. An explicit `vulkan-swap-mode` in `mpv.conf` remains authoritative. libplacebo falls back to FIFO when the Android surface does not support mailbox mode; the request is not a guarantee of achieved cadence. Runtime diagnostics separate image-acquisition and blocking `swap_buffers` waits from nonblocking submit duration. These CPU timings do not measure SurfaceFlinger scanout or prove every generated frame was displayed.

Interpolation is intended for compatible SDR progressive video. The active Android path can use MediaCodec direct mode or `mediacodec-copy` for decoder input, depending on APK capabilities; decoder-input copying is distinct from GPU-side synthesis and does not imply CPU readback of generated frames. Actual smoothness, artifacts, displayed cadence, A/V sync, detail, power, and thermal behavior must be measured on target phones. No claim of sustained 144 fps or SVPlayer parity is made before device testing.

## Diagnostics, tests, and build

- `MPVFLOW_DIAGNOSTIC` logs identify the requested and effective compute backend, OpenCL extension/build/import failures, Auto fallback, Flow stages, selected/effective cadence, input pass-through reasons, queue state, and blocking swapchain waits.
- `scripts/tests/mpvflow_gpu_shader_test.py` checks the Vulkan shader passes and artifact protections.
- `scripts/tests/mpvflow_native_build_test.py` checks Android native source registration, OpenCL/Vulkan interop extension requests, selector persistence, option ownership, renderer policy, and related build contracts.
- `scripts/test-mpvflow-core.sh` runs host-only synthetic tests for the legacy CPU motion core. Those tests do not build an APK or validate device OpenCL support, on-device cadence, image quality, or A/V synchronization.
- `scripts/prepare-mpvflow.py` installs the Flow, OpenCL, and Vulkan AHardwareBuffer bridge sources in the pinned Android MPV build and requests optional Android interop extensions. It does not compile or register the legacy CPU filter.
- `.github/workflows/mpvflow-debug-arm64.yml` builds the experimental arm64 MPVFlow debug APK remotely. APK generation is performed through that workflow, not by host-only tests.

## Public background

- [SVPflow manual](https://www.svp-team.com/wiki/Manual:SVPflow)
- [SVP frame-rate conversion manual](https://www.svp-team.com/wiki/Manual:FRC)
- [Qualcomm OpenCL programming guide](https://docs.qualcomm.com/bundle/publicresource/80-NB295-11_REV_C_Qualcomm_Snapdragon_Mobile_Platform_Opencl_General_Programming_and_Optimization.pdf)
- [Khronos Vulkan/OpenCL interop sample](https://docs.vulkan.org/samples/latest/samples/extensions/open_cl_interop_arm/README.html)
