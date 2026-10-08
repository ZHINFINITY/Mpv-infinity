# MPVFlow: on-device motion-interpolation experiment

## Goal and relationship to RIFE

MPVFlow is a separate, local motion-estimation/motion-compensation (MEMC) path for Android MPV. It does **not** call the existing RIFE filter, its model/runtime, or an off-device service. The existing RIFE implementation remains a separate option; the settings switches are mutually exclusive so the two filters are not accidentally stacked.

This is an independently written prototype informed by SVP's public descriptions of its two-stage flow: estimate motion between decoded pictures, then use the vectors to synthesize intermediate pictures. It is not SVPFlow source code, does not reproduce proprietary defaults, and is not claimed to be pixel-identical to SVPlayer.

## Implemented pipeline

1. The Android mpv user filter holds adjacent decoded pictures and places output timestamps on the selected target-FPS grid.
2. Each source pair is converted to a reduced RGB24 image. A luma pyramid is built at up to three scales.
3. The native C core performs the same exhaustive coarse-to-fine block matching in both directions. Independent block rows are analyzed concurrently on up to four online CPU workers when a pyramid level is large enough; small levels stay serial. This changes scheduling, not the search candidates or vector decisions.
4. The resulting pair of vector fields is cached. Analysis runs once per decoded pair; the cached vectors are reused for every synthesized timestamp in that interval.
5. For each output timestamp, vectors warp samples from both source pictures. Forward/backward consistency and photometric agreement provide a confidence estimate; uncertain/occluded pixels fall back toward the nearer source picture. A large frame-wide change is treated as a scene cut and does not get motion-warped.
6. The reduced output is converted back to the input dimensions and pixel format, with its presentation timestamp set to the target grid.

## Media3 GPU path

The opt-in Media3 sink keeps decoded SDR source textures and synthesized output at input dimensions, while luma and block-motion analysis use a reduced grid capped at 480 pixels on the longest side. The full-resolution synthesis shader maps the reduced motion vectors through normalized sampling and directly blends pixels whose adjacent-frame colors are effectively unchanged, avoiding needless warping of static UI and other stationary detail. This preserves spatial detail better than resizing the generated frame itself to the motion-analysis cap; it does not guarantee GPU completion time, displayed cadence, or visual quality until measured on-device.

## Rates and device constraints

The UI offers the choices shown in the supplied SVPlayer image: **48, 60, 72, 90, 96, 120, and 144 fps**. It adds a `(!)` marker from 90 fps onward. The effective target is capped to the display refresh reported by Android. If the display rate is unavailable, the requested preset is used.

To bound CPU cost, the prototype automatically limits the longest processing-image side to 480 pixels through 60 fps, 360 at 72–90, 320 at 96–120, and 240 at 144. This is a longest-side pixel cap, not a promise of a particular encoded resolution. The target is a pacing request: the filter does not guarantee the device can sustain that rate.

The implementation is CPU-side and requires software-readable **SDR progressive** pictures. On Android, enabling it requests `mediacodec-copy,no`: MediaCodec performs hardware decoding where supported, then copies frames into CPU-visible memory for the filter; unsupported codecs can fall back to software decoding. Direct/no-copy hardware surfaces cannot be consumed by this filter. HDR PQ/HLG, interlaced, hardware-only, invalid-timestamp, and incompatible-format cases pass through or disable the filter safely. Sources already faster than the selected target bypass motion analysis and keep their original PTS for mpv's output-frame dropping. The player selects mpv's audio-master `video-sync=audio`; MPVFlow does not rewrite source-frame PTS, and generated frames use the target cadence. The automatic working-size cap adapts between source pairs: it steps down under sustained budget pressure and recovers slowly only after repeated low-cost pairs; explicit size settings remain fixed. If the 85% reserve is reached while more outputs remain in the current pair, MPVFlow emits the current result, queues the next source frame at its original PTS, skips only the unfinished part of that pair, and resumes at the adapted size. A pair that has already completed is not disabled just for using the reserve. This avoids repeated full-resolution overshoot while preserving audio-master timing and preventing an interpolation backlog. The longest-side caps soften synthesized detail relative to source frames. This is not an SVPFlow-equivalent GPU-resident pipeline and may be slower or less artifact-resistant than SVP's optimized CPU/GPU path, especially at high resolution or on a low-power device. Actual smoothness, displayed frame cadence, A/V sync, detail, power, and thermal behavior must be measured on target phones. No claim of sustained 144 fps or SVPlayer parity is made before device testing.

## Build and tests

- `scripts/test-mpvflow-core.sh` runs host-only synthetic tests for translation recovery, identical-frame handling, scene-cut handling, and deterministic parallel analysis/synthesis. It does not make an Android APK or validate A/V synchronization.
- `scripts/prepare-mpvflow.py` registers `vf_mpvflow` and its core files in the pinned Android mpv source tree.
- `.github/workflows/mpvflow-debug-arm64.yml` builds the arm64 MPV runtime and debug APK remotely, without building or linking the RIFE runtime into this test APK. The GitHub Actions artifact is retained for 14 days.
- In the testing APK, open **Settings → Decoder → On-device motion interpolation (MPVFlow, experimental)**, choose a target, then restart playback. The player log includes `MPVFLOW_DIAGNOSTIC` lines for selected/effective rate, per-pair vector analysis, scene cuts, and any pass-through/disable reason.

## Public background

- [SVPflow manual](https://www.svp-team.com/wiki/Manual:SVPflow)
- [SVP frame-rate conversion manual](https://www.svp-team.com/wiki/Manual:FRC)
