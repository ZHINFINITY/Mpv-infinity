# MPVFlow diagnostics and presentation checks

This opt-in feature is for **debug/testing builds**. It captures a short, user-started sample from the selected playback engine; it is not a general playback recorder or a guarantee of display cadence.

## Capture a marked moment from each engine

Run the same SDR scene twice—once with **MPV** and once with **Media3**—so the two engines can be compared independently.

1. Install the diagnostics debug build. Open the video, choose the engine under test, seek to a few seconds before the fast scene, and open the player’s **More** sheet.
2. Press **Start marked capture**, then replay the scene. Capture starts on the next eligible synthesized output. The app does not capture unless you explicitly start it.
3. Let the bounded sample finish, or press **Stop capture** to end it early. A session is limited to **4 adjacent source-frame pairs**, **4 synthesized output samples per pair**, **64 MiB**, and a 12-second watchdog. The final pair auto-stops when its fourth output sample is saved. A byte/readback limit can produce a partial capture.
4. The app saves the ZIP locally to `Downloads/Mpv∞/Flow Diagnostics/` as `MpvFlow_<engine>_<session-id>.zip`; the More sheet shows the saved location. No export or upload is performed automatically.
5. Repeat with the other engine on the same scene and settings where possible. Keep the ZIP private unless you choose to share it; captured images may contain video frames.

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
