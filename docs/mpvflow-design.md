# MPVFlow: on-device motion-interpolation experiment

## Goal and relationship to RIFE

MPVFlow is a separate, local motion-estimation/motion-compensation (MEMC) path. It does not call the existing RIFE filter, its model/runtime, or an off-device service. The settings switches are mutually exclusive so the two interpolation paths are not accidentally stacked.

This is an independently written prototype informed by public descriptions of frame-rate conversion. It is not SVPFlow source code, does not reproduce proprietary defaults, and is not claimed to be pixel-identical to SVPlayer.

## Active GPU implementation

### MPV `gpu-next` route

When MPVFlow is eligible on the MPV engine, the app selects the `gpu-next` renderer with Vulkan. The patched MPV video output supplies source-frame textures and display-timed output ticks to the shared Flow implementation. The renderer API and interpolation compute API are separate choices: Vulkan remains the MPV renderer even when Flow's motion and synthesis kernels use OpenCL. In the Flow path, the timestamped source mix, pair analysis and offscreen synthesis are prepared and flushed before `pl_swapchain_start_frame`; this allows submitted GPU work to overlap a blocking acquire call, but does not make WSI acquisition nonblocking or prove the frame was scanned out.

### OpenCL compute backend

The app now has a real OpenCL path, not a settings-only toggle. It dynamically loads the optional Android `libOpenCL.so`, compiles the Flow kernels at runtime, and imports shared Android `HardwareBuffer` BLOB allocations through Qualcomm's `cl_qcom_android_ahardwarebuffer_host_ptr` extension. Vulkan copies RGBA8 images to/from those shared buffers; OpenCL performs multi-scale luma/motion analysis, scene-cut detection, confidence/consistency checks, and full-resolution synthesis. The generated image returns to the Vulkan renderer. Pixel data is not copied through the CPU and there is no CPU pixel readback path.

The current OpenCL interop implementation is **Qualcomm/Adreno-specific**. OpenCL enumeration alone is not enough: the runtime extension, Vulkan external-memory/foreign-queue support, compatible RGBA8 transfer usage, buffer import probe, and kernel build must all succeed. `Auto` uses Vulkan/SPIR-V on the renderer's native queue to avoid cross-API ownership transfers and per-frame queue finishes. OpenCL remains an explicit experimental choice and does not silently switch to Vulkan: initialization or runtime failures are diagnosed and affected frames pass through. Other vendors' OpenCL import extensions are not implemented yet.

For conservative cross-API correctness, this first bridge serializes Vulkan buffer copies and OpenCL work with Vulkan fences and `clFinish`. This avoids CPU pixel copies but can add substantial blocking overhead. It is an initial functional backend, not evidence that it improves cadence on every device; it needs measurement on the target phone before being recommended as faster.

### Vulkan compute backend

The Vulkan/SPIR-V backend remains available explicitly and as `Auto` fallback. It uses libplacebo passes on the renderer's Vulkan device. Motion analysis is bounded to a reduced working size while synthesis remains at the original source dimensions. Unsupported input or a deadline miss uses original source frames rather than stalling playback.

Both Vulkan and OpenCL finer pyramid levels now retain the parent block's normalized SAD and best-versus-runner-up uniqueness. If the parent has high residual (`>0.12`) but is not an exact tie (`uniqueness >0.0005`), that level doubles its local search radius up to 16; reliable or fully tied parents keep the original radius. A deterministic synthetic translated-texture test recovers 9–12-pixel misses beyond the base radius, at the cost of extra SAD comparisons. Motion-grid allocation now avoids duplicate terminal blocks, and interpolation maps the final partial interval to the actual clamped block origin for Vulkan and OpenCL, including coarse-to-fine search prediction. The exact Vulkan shader pixel oracle includes a small irregular-grid case that rejects the former uniform-coordinate result; this is a deterministic CI regression, not device quality evidence. These are bounded source-level improvements, not a Neural ODE/SciFlow implementation or evidence of on-device speed/quality gains. Forward–backward cycle consistency and source-frame fallback remain the separate inference-time safeguards for poor matches and occlusions.

### Native Media3 route

The separate Native Media3 route uses a Vulkan renderer/context and the same Flow compute API preference. Its decoded surface is captured on GLES, with Vulkan handling Flow buffers and the final display handoff; interpolation itself uses either the selected supported OpenCL route or Vulkan/SPIR-V. Source-pair analysis happens during input capture and synthesis is submitted before the EGL window swap, but SurfaceTexture capture and presentation currently share one GL handler, so a long `eglSwapBuffers` can delay later input callbacks. Although a device may expose a dedicated compute family, Media3 intentionally keeps `async_compute` and `async_transfer` disabled: its imported AHardwareBuffer images are created with exclusive sharing, while the current libplacebo wrapper contract requires multi-family images to be concurrently shared. Diagnostics now report available dedicated-compute families without selecting one. A fully decoupled route would need separately synchronized capture/presentation contexts plus a bounded, multi-slot output queue; it must not overwrite an output still being sampled. MPV's `gpu-next` setting does not select the Native Media3 route's renderer.

Both Vulkan contexts opportunistically request `VK_NV_optical_flow`, its feature bit and an optical-flow queue family where available. When the device also supports compatible image formats and timeline synchronization, the shared core creates two NV sessions (one for each direction), dispatches full-resolution `R8_UNORM` frame luma without CPU readback, and samples the device's S10.5 motion-vector maps as centers for the existing coarsest-level SAD search. The SAD match, ambiguity/confidence checks, forward/backward consistency, occlusion handling, and source-frame fallback remain authoritative; hardware vectors are never treated as verified final motion. Image/session/queue setup failure or a busy nonblocking command ring falls back to the ordinary bidirectional block matcher. The runtime logs distinguish capability, successful session setup, actual optical dispatch, and fallback. This optional path is an implementation change, not evidence that either engine sustains its requested frame rate or is artifact-free on a device.

The shared core also queries optional `VK_QCOM_image_processing` / `VK_QCOM_image_processing2` support, feature bits, block-match region/window limits, and whether `R8_UNORM` supports the block-match format feature and image usage. A `base_r8_capability_present_shader_unintegrated` diagnostic means only those capability queries passed; it is not a runnable-path claim. The required QCOM block-match descriptor type, image-processing sampler, format/usage/layout setup, device extension/feature enablement, and shader operation are not integrated into the libplacebo Flow pass. Enabling this accelerator therefore remains a separate hardware-gated project requiring device validation. A device profile containing only legacy `VkFormatProperties` does not establish the newer QCOM `VkFormatFeatureFlags2` result; the runtime diagnostic is needed before an implementation can rely on it.

Debug builds with the Vulkan backend expose a manual **Run NV Optical Flow self-test** action in Advanced Settings. It creates a temporary Media3 Vulkan context, builds a 256×256 textured luma pair with a square shifted 16 pixels horizontally, checks the driver-advertised RGBA16F Optical Flow output and exact transfer/sample usage combinations, dispatches one NV session, then synchronizes and reads the output back. It logs each 8×8-grid cell's four raw half-float values and decoded channels; if a supported transfer-destination combination exists, the output is cleared first so unwritten channels are distinguishable. Channel meaning, direction, and scale remain for manual validation—RGBA16F is **not** enabled in the production decoder, and this probe is never invoked during playback. Completion verifies dispatch/readback, not motion correctness, sustained frame rate, or artifact-free interpolation.

The current support policy keeps HDR, Dolby Vision, DRM, rotated, and non-square-pixel content on Media3's standard renderer. Unsupported formats or tight deadlines fall back to source frames.

## Renderer and compute settings

The Decoder screen has distinct controls for **Vulkan renderer API** and **MPVFlow compute API**. While MPVFlow is active and eligible on the MPV route, Flow requires `gpu-next` with Vulkan rendering; the screen shows those effective renderer choices and locks the renderer controls until Flow is turned off. The compute selector offers `Auto`, `OpenCL`, and `Vulkan` without changing that renderer choice.

On the MPV route, `mpvflow-compute-api` is the corresponding mpv option; an explicit `mpv.conf` value remains authoritative. Native Media3 receives the app preference directly. The target-rate choices remain **48, 60, 72, 90, 96, 120, and 144 fps**; actual output remains device- and display-dependent.

## Cadence and device constraints

The effective Flow target is capped to Android's reported display refresh; this cap is disclosed and is not presented as proof that a selected target was achieved. The app's performance/quality profile maps the longest-side motion-analysis budget monotonically across 480–640 pixels through 60 fps, 384–480 at 72–90, 320–432 at 96, 320–448 at 120, and 288–320 at 144. At 120 fps, the balanced profile is 384 pixels to match the observed Media3 configuration; lower and higher quality settings remain adjustable. The native renderer applies only the target-rate safety ceiling, so the quality profile is not expanded and clamped a second time. These are processing-size limits, not promises of encoded output resolution or sustained performance.

For the MPV Vulkan renderer, Flow requests Vulkan `mailbox` presentation before creating `gpu-next`, so stale queued images can be replaced without lowering the selected target rate. An explicit `vulkan-swap-mode` in `mpv.conf` remains authoritative. libplacebo falls back to FIFO when the Android surface does not support mailbox mode; the request is not a guarantee of achieved cadence, and the current device logs do not identify the selected mode. Runtime diagnostics separate image-acquisition and blocking `swap_buffers` waits from nonblocking submit duration. These CPU timings do not measure SurfaceFlinger scanout or prove every generated frame was displayed.

Interpolation is intended for compatible SDR progressive video. The active Android path can use MediaCodec direct mode or `mediacodec-copy` for decoder input, depending on APK capabilities; decoder-input copying is distinct from GPU-side synthesis and does not imply CPU readback of generated frames. Actual smoothness, artifacts, displayed cadence, A/V sync, detail, power, and thermal behavior must be measured on target phones. No claim of sustained 144 fps or SVPlayer parity is made before device testing.

## Diagnostics, tests, and build

- `MPVFLOW_DIAGNOSTIC` logs identify the requested and effective compute backend, OpenCL extension/build/import failures, Auto fallback, Flow stages, selected/effective cadence, input pass-through reasons, queue state, blocking swapchain waits, and NV optical session/dispatch/fallback state.
- Capability and execution are distinct: `event=optical_flow_capability` reports support, `event=optical_flow_backend state=ready` reports session/resource setup, and `event=optical_flow_dispatch state=submitted` confirms a real hardware submission; none of these proves a 120-fps display cadence or visual quality.
- `scripts/tests/mpvflow_gpu_shader_test.py` checks the Vulkan shader passes and artifact protections.
- `scripts/tests/mpvflow_native_build_test.py` checks Android native source registration, optional optical queue/session dispatch and nonblocking fallback, the manually gated NV format self-test and its production isolation, OpenCL/Vulkan interop extension requests, selector persistence, option ownership, renderer policy, and related build contracts.
- `scripts/test-mpvflow-core.sh` runs host-only synthetic tests for the legacy CPU motion core. Those tests do not build an APK or validate device OpenCL support, on-device cadence, image quality, or A/V synchronization.
- `scripts/prepare-mpvflow.py` installs the Flow, OpenCL, NV optical-flow, and Vulkan AHardwareBuffer bridge sources in the pinned Android MPV build; it also registers libplacebo's sampleable representation of NV's S10.5 vector format and requests optional Android interop extensions. It does not compile or register the legacy CPU filter.
- `.github/workflows/mpvflow-debug-arm64.yml` builds the experimental arm64 MPVFlow debug APK remotely. APK generation is performed through that workflow, not by host-only tests.

## Public background

- [SVPflow manual](https://www.svp-team.com/wiki/Manual:SVPflow)
- [SVP frame-rate conversion manual](https://www.svp-team.com/wiki/Manual:FRC)
- [Qualcomm OpenCL programming guide](https://docs.qualcomm.com/bundle/publicresource/80-NB295-11_REV_C_Qualcomm_Snapdragon_Mobile_Platform_Opencl_General_Programming_and_Optimization.pdf)
- [Khronos Vulkan/OpenCL interop sample](https://docs.vulkan.org/samples/latest/samples/extensions/open_cl_interop_arm/README.html)
