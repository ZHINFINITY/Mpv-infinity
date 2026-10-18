# MPVFlow diagnostics and presentation checks

The **Start marked capture** and **Start paired Soft Blend comparison** controls have been removed from the player's More sheet. Normal playback no longer exposes this capture panel. The capture implementation is retained for diagnostic tooling and existing captures; the legacy capture procedures below describe older debug builds, not controls available in the current player. For current on-device investigation, export **Debug Logs** for each engine playing the same scene. The separate Perfetto procedure below remains applicable.

## Legacy debug builds: capture a marked moment from each engine

Run the same SDR scene twice—once with **MPV** and once with **Media3**—so the two engines can be compared independently.

1. Install the diagnostics debug build. Open the video, choose the engine under test, seek to a few seconds before the fast scene, and open the player’s **More** sheet.
2. Press **Start marked capture**, then replay the scene. Capture starts on the next eligible synthesized output. The app does not capture unless you explicitly start it.
3. Let the bounded sample finish, or press **Stop capture** to end it early. A session is limited to **4 adjacent source-frame pairs**, **4 synthesized output samples per pair**, **64 MiB**, and a 12-second watchdog. The final pair auto-stops when its fourth output sample is saved. A byte/readback limit can produce a partial capture.
4. The app saves the ZIP locally to `Downloads/Mpv∞/Flow Diagnostics/` as `MpvFlow_<engine>_<session-id>.zip`; the More sheet shows the saved location. No export or upload is performed automatically.
5. Repeat with the other engine on the same scene and settings where possible. Keep the ZIP private unless you choose to share it; captured images may contain video frames.

## Legacy debug builds: paired Soft Blend comparison

Use **Start paired Soft Blend comparison** in the debug-only More sheet when an ordinary marked capture does not isolate the Soft Blend effect. It begins on the next eligible output and captures **Soft Blend off, then on**, from the same live source textures, cached motion field, source PTS pair, output PTS, and interpolation alpha. The two manifest records include the per-variant flag and must match on all other captured settings and pair identity; MPV's `preserve_motion_blend_on_conflict` value is snapshotted and held equal for both variants. The common comparison settings omit the varying `soft_blend` value.

The visibility resolver is disabled for both comparison outputs because the current synthesis path makes it mutually exclusive with Soft Blend. After the two diagnostic renders, the engine reruns and returns the configured playback output; the comparison does not retain frames, alter queue ownership, or change production defaults. Expect extra GPU work and a brief capture-only readback stall while the explicit diagnostic is active.

A paired session records **one source pair**, exactly **two synthesized outputs**, and native-resolution RGBA8 images without resampling, subject to **1920 px**, **2,073,600 pixels**, and **64 MiB** limits; the existing 12-second watchdog and manual Stop remain available. It is local-only and never uploads automatically. Both engines refuse the comparison for HDR, Dolby Vision, or unknown color metadata. Run it separately for MPV and Media3; a pair never mixes engines.

### Interpreting fallback decisions

The decision mask distinguishes an exact source endpoint from a temporal source blend. IDs **11** (low confidence) and **12** (boundary conflict) mean `(1-t)*source0[p] + t*source1[p]`, not a motion-warped sample or an exact copy of either endpoint. IDs 3/4, 7/8, and 9/10 retain their source-exact meanings for older captures and hard fallback paths. `scripts/analyze-mpvflow-decisions.py` accepts the original and extended palettes and reports `source_blend_equality` separately from `source_equality`. A source blend can still produce double edges during motion; it is a safety fallback, not proof of correct interpolation.

HDR, Dolby Vision, unknown color metadata, unsupported compute backends, or unavailable readback are refused or recorded as missing rather than bypassing the existing fail-closed HDR path. Review `manifest.json` for engine/settings, exact source/output PTS, status, and missing-channel reasons. Per-pair images can include `source0`, `source1`, `motion_forward`, `motion_backward`, `motion`, `residual`, `uniqueness`, `confidence`, `cycle_consistency`, `cycle_error`, and `visibility_support`; each synthesized sample includes `synthesized`. Confidence and visibility/occlusion information are diagnostic proxies, not ground-truth segmentation.

`app_timing.jsonl` records bounded app-side snapshots for each engine. It distinguishes generated/synthesized counts from swap-submit-return cadence and includes available CPU/GPU timing, queue, and failure counters. **Neither engine can claim actual displayed/scanout FPS from these app-side counters.** MPV’s successful swap submits, Media3’s `eglSwapBuffers` return cadence, decoder counts, generated FPS, and a Surface frame-rate hint are different measurements; none proves what SurfaceFlinger displayed.

For Native Media3, `output_submission_failure_reasons` separates producer-side causes such as no writable presentation slot, EGL/pbuffer setup, staging/FBO/GL work, and producer-fence creation. A no-writable-slot event also snapshots both bounded slot states atomically, so two slots in `PRESENTING` can be distinguished from a quarantined slot. `presentation_path_failure_reasons` records later presentation-thread failures—including window `eglMakeCurrent`, GLES draw, presentation-time, `eglSwapBuffers`, and fence/exception paths—separately; these occur after producer staging and are not causes of its `presentation_stage_failed` result. The aggregate `output_submission_failures` remains for compatibility, and none of these counters represents panel scanout.

## Companion check: Android presentation evidence with Perfetto

Use this separate procedure when you need a system trace. It requires a desktop with Android Platform Tools (`adb`) on `PATH`, a USB-connected device with Developer options and USB debugging enabled, and **Android 12 (S) or newer**.

1. Install and launch the same debug build. Select the engine and prepare the same repeatable scene.
2. On the desktop, use Perfetto’s official [`record_android_trace` helper](https://perfetto.dev/docs/getting-started/system-tracing). For example:

   ```sh
   curl -LO https://raw.githubusercontent.com/google/perfetto/main/tools/record_android_trace
   python3 record_android_trace -o mpvflow.perfetto-trace -t 15s -b 32mb -a '*' sched freq view sf
   ```

   This records a bounded 15-second trace. Adjust `-t` to a shorter interval if appropriate and consult the helper’s `--help` if your device setup differs.
3. Start the trace, then immediately replay the same scene while it records. When recording ends, open `mpvflow.perfetto-trace` in [Perfetto UI](https://ui.perfetto.dev/) using **Open trace file** if the helper did not open it automatically.
4. Inspect the app and SurfaceFlinger tracks, and correlate actual/expected FrameTimeline slices, present type, and jank only when the relevant tracks and tested surface are present and attributable.

**SurfaceView limitation:** Perfetto’s [FrameTimeline documentation](https://perfetto.dev/docs/data-sources/frametimeline) notes that app FrameTimeline currently does not support `SurfaceView`. This player uses a `SurfaceView` for both MPV and Media3. A trace may therefore fail to attribute the video surface’s frames; absent tracks are not evidence that no frames were displayed. Report actual presentation rate only when the trace provides reliable, attributable evidence for the tested layer; otherwise leave it unavailable. Never relabel render or swap submissions as displayed FPS.

Perfetto UI analyzes the trace locally in your browser. The trace is not uploaded by default; it is shared only if you explicitly choose **Share**. See the official [system tracing guide](https://perfetto.dev/docs/getting-started/system-tracing) for current recording and UI instructions.
