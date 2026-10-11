# MPVFlow: on-device motion-interpolation experiment

## Goal and relationship to RIFE

MPVFlow is a separate, local motion-estimation/motion-compensation (MEMC) path. It does not call the existing RIFE filter, its model/runtime, or an off-device service. The settings switches are mutually exclusive so the two interpolation paths are not accidentally stacked.

This is an independently written prototype informed by SVP's public descriptions of frame-rate conversion. It is not SVPFlow source code, does not reproduce proprietary defaults, and is not claimed to be pixel-identical to SVPlayer.

## Active GPU implementation

### MPV `gpu-next` route

When MPVFlow is eligible on the MPV engine, the app selects the `gpu-next` renderer with Vulkan. The patched MPV video output supplies source-frame textures and display-timed output ticks to `mpvflow_gpu.c`. Preprocessing, luma/motion analysis, and synthesis run as libplacebo Vulkan compute passes compiled to SPIR-V, using the renderer's existing `pl_gpu`. Synthesized output remains GPU-side; the active Android build does not register or compile the legacy CPU `vf_mpvflow` filter and does not read synthesized pixels back to the CPU.

The compute path works on reduced motion-analysis images while synthesizing at the source output dimensions. Unsupported or incompatible input is passed through rather than forcing interpolation. The feature is a motion-compensated prototype; image artifacts and achieved cadence still require device testing.

### Native Media3 route

The separate Native Media3 route uses a Vulkan context with the same libplacebo-based Flow compute implementation and shared Android `HardwareBuffer` images. GLES is limited to decoder-surface capture and display handoff; it does not perform interpolation compute. MPV's `gpu-next` renderer selection does not choose the Native Media3 route's compute backend.

The current support policy keeps HDR, Dolby Vision, DRM, rotated, and non-square-pixel content on Media3's standard renderer. Unsupported formats or tight deadlines fall back to source frames.

## Renderer and compute API settings

The Decoder screen distinguishes **Vulkan renderer API** from **MPVFlow compute API**. The renderer control is a real MPV rendering setting during ordinary playback. While MPVFlow is active and eligible on the MPV route, Flow requires `gpu-next` plus Vulkan; the screen shows those effective renderer choices as enabled and locks the controls until Flow is turned off.

The current Flow compute implementation is Vulkan/SPIR-V only. On the MPV route it uses the same Vulkan `pl_gpu` as `gpu-next`; OpenCL is not implemented. The compute entry is therefore a read-only status, not an Auto/OpenCL selector that would imply nonexistent functionality. A genuinely independent compute API selection requires another working backend first. The separate Native Media3 Flow route also currently uses Vulkan compute.

## Cadence and device constraints

The UI offers **48, 60, 72, 90, 96, 120, and 144 fps**, with a `(!)` marker from 90 fps onward. The effective target is capped to the display refresh reported by Android. The automatic longest-side motion-analysis cap is 640 pixels through 60 fps, 480 at 72–90, 400 at 96–120, and 320 at 144. These are processing-size limits, not promises of encoded output resolution or sustained performance.

For the MPV Vulkan route, Flow requests Vulkan `mailbox` presentation before creating `gpu-next`, so stale queued images can be replaced without lowering the selected target rate. An explicit `vulkan-swap-mode` in `mpv.conf` remains authoritative. libplacebo falls back to FIFO when the Android surface does not support mailbox mode; the request is not a guarantee of achieved cadence. The runtime reports swapchain image-acquisition and blocking `swap_buffers` waits separately from nonblocking submit duration. These CPU timings do not measure SurfaceFlinger scanout or prove the display presented every generated frame.

Interpolation is intended for compatible SDR progressive video. The active Android path can use MediaCodec direct mode or `mediacodec-copy` for decoder input, depending on APK capabilities; decoder-input copying is distinct from GPU-side synthesis and does not imply a CPU readback of generated frames. Actual smoothness, artifacts, displayed cadence, A/V sync, detail, power, and thermal behavior must be measured on target phones. No claim of sustained 144 fps or SVPlayer parity is made before device testing.

## Diagnostics, tests, and build

- `MPVFLOW_DIAGNOSTIC` logs identify the initialized `vulkan-spirv` renderer, GPU timing stages, selected/effective cadence, input pass-through reasons, queue state, and blocking swapchain waits.
- `scripts/tests/mpvflow_gpu_shader_test.py` compiles and checks the active Vulkan shader passes, including preprocessing, luma, downsample/reduction, motion, and synthesis.
- `scripts/tests/mpvflow_native_build_test.py` checks the Android native integration, option ownership, renderer policy, and related build contracts.
- `scripts/test-mpvflow-core.sh` runs host-only synthetic tests for the legacy CPU motion core. Those tests do not build an APK or validate the active Vulkan renderer, on-device cadence, or A/V synchronization.
- `scripts/prepare-mpvflow.py` installs `mpvflow_gpu.c` and the `gpu-next` integration patch into the pinned Android MPV source. It explicitly does not compile or register the legacy CPU filter.
- `.github/workflows/mpvflow-debug-arm64.yml` builds the experimental arm64 MPVFlow debug APK remotely. APK generation is performed through that workflow, not by the host-only tests.

## Public background

- [SVPflow manual](https://www.svp-team.com/wiki/Manual:SVPflow)
- [SVP frame-rate conversion manual](https://www.svp-team.com/wiki/Manual:FRC)
