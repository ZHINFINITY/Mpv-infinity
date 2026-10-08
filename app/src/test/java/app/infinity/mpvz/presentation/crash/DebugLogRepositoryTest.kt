/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.presentation.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogRepositoryTest {
  @Test
  fun retainsRifeDiagnosticsAlongsideTheMostRecentLogs() {
    val rifeEntry =
      DebugLogEntry(
        id = "rife-config",
        timeMillis = 1L,
        timestamp = "00:00:00.001",
        level = DebugLogLevel.Info,
        tag = "Mpv∞",
        message = "RIFE_DIAGNOSTIC event=config enabled=true target_fps=30",
      )
    val recentEntries =
      (0 until DEBUG_LOG_ENTRY_LIMIT).map { index ->
        DebugLogEntry(
          id = "recent-$index",
          timeMillis = index + 2L,
          timestamp = "00:00:00.002",
          level = DebugLogLevel.Info,
          tag = "Playback",
          message = "routine playback log $index",
        )
      }

    val retained = retainDebugLogEntries(listOf(rifeEntry) + recentEntries)

    assertEquals(DEBUG_LOG_ENTRY_LIMIT, retained.size)
    assertTrue(retained.any { it.id == rifeEntry.id })
    assertTrue(retained.any { it.id == "recent-${DEBUG_LOG_ENTRY_LIMIT - 1}" })
  }

  @Test
  fun classifiesRifeSetupFormatTimingAndFallbackEventsForTheRifeFilter() {
    val messages =
      listOf(
        "RIFE_DIAGNOSTIC event=config enabled=true target_fps=60",
        "RIFE_DIAGNOSTIC event=input format=yuv420p10le component_bits=10",
        "RIFE_DIAGNOSTIC event=frame_timing average_inference_ms=12.5",
        "RIFE_DIAGNOSTIC event=passthrough reason=hdr_pq",
        "RIFE_DIAGNOSTIC event=auto_fallback reason=slow_inference",
        "ordinary decoder log without interpolation details",
      )
    val entries =
      messages.mapIndexed { index, message ->
        DebugLogEntry(
          id = "entry-$index",
          timeMillis = index.toLong(),
          timestamp = "00:00:00.000",
          level = DebugLogLevel.Info,
          tag = if (index == 2) "vf_rife" else "Mpv∞",
          message = message,
        )
      }

    assertEquals(5, entries.count(DebugLogEntry::isRifeDiagnostic))
    assertEquals(5, retainDebugLogEntries(entries).count(DebugLogEntry::isRifeDiagnostic))
  }

  @Test
  fun samplesHighVolumeRifeFrameEventsAcrossTheLogWindow() {
    val rifeEntries =
      (0 until 520).map { index ->
        DebugLogEntry(
          id = "rife-$index",
          timeMillis = index.toLong(),
          timestamp = "00:00:00.000",
          level = DebugLogLevel.Info,
          tag = "Mpv∞",
          message = "RIFE_DIAGNOSTIC event=frame_timing frame=$index",
        )
      }
    val recentEntries =
      (0 until DEBUG_LOG_ENTRY_LIMIT).map { index ->
        DebugLogEntry(
          id = "recent-$index",
          timeMillis = 1_000L + index,
          timestamp = "00:00:00.001",
          level = DebugLogLevel.Info,
          tag = "Playback",
          message = "routine playback log $index",
        )
      }

    val retained = retainDebugLogEntries(rifeEntries + recentEntries)

    assertEquals(DEBUG_LOG_ENTRY_LIMIT, retained.size)
    assertEquals(500, retained.count(DebugLogEntry::isRifeDiagnostic))
    assertTrue(retained.any { it.id == "rife-0" })
    assertTrue(retained.any { it.id == "rife-519" })
    assertTrue(retained.any { it.id == "recent-${DEBUG_LOG_ENTRY_LIMIT - 1}" })
  }

  @Test
  fun preservesSetupWaitAndFailureEventsWhenImportsFloodTheLog() {
    fun rifeEntry(id: String, timeMillis: Long, message: String) =
      DebugLogEntry(
        id = id,
        timeMillis = timeMillis,
        timestamp = "00:00:00.000",
        level = DebugLogLevel.Info,
        tag = "Mpv∞",
        message = message,
      )

    val setup =
      rifeEntry(
        "setup",
        0L,
        "RIFE_DIAGNOSTIC event=config resident_state=awaiting_gpu_resident_frame active_filter_path=resident_vulkan_ncnn target_fps=60 max_dimension=0 resolution_mode=auto display_refresh_hz=120 renderer=gpu-next gpu_api=opengl decoder_mode=mediacodec,no resident_option_rc=0 model_option_rc=0 resident_timing_options_owned=false filter_set_result=skipped",
      )
    val imports =
      (0 until 600).map { index ->
        rifeEntry(
          "import-$index",
          index + 1L,
          "RIFE_DIAGNOSTIC event=ahb_input_imported pts=$index pts_delta_ms=0.000",
        )
      }
    val wait =
      rifeEntry(
        "wait",
        700L,
        "RIFE_DIAGNOSTIC event=resident_wait reason=paired_ahb_input_unavailable repeat=1 pts=18.492 mix_frames=2 queue_depth=3 vo_frames=1 display_synced=1 mpv_interpolation=1 can_interpolate=1 paused=0 still=0 pts_offset=0.004 vsync_duration=0.016667 pair_before_pts=18.475333 pair_after_pts=18.517 before_input_ready=1 after_input_ready=0 timestep=0.40 target_fps=60 cadence_origin_pts=18.475333",
      )
    val lookahead =
      rifeEntry(
        "lookahead",
        699L,
        "RIFE_DIAGNOSTIC event=config resident_lookahead_required=true video_latency_hacks_owned=false video_latency_hacks_rc=0",
      )
    val failure =
      rifeEntry(
        "failure",
        701L,
        "RIFE_DIAGNOSTIC event=resident_error reason=vulkan_inference_failed",
      )
    val timing =
      rifeEntry(
        "timing",
        702L,
        "RIFE_DIAGNOSTIC event=resident_timing_sample window_ms=2000 attempts=120 generated=34 inference_avg_us=28000",
      )
    val sync =
      rifeEntry(
        "sync",
        703L,
        "RIFE_DIAGNOSTIC event=mpv_sync_sample sample=4 avsync_ms=-3.000 drop_frames=0",
      )
    val recentEntries =
      (0 until DEBUG_LOG_ENTRY_LIMIT).map { index ->
        DebugLogEntry(
          id = "recent-$index",
          timeMillis = 1_000L + index,
          timestamp = "00:00:01.000",
          level = DebugLogLevel.Info,
          tag = "Playback",
          message = "routine playback log $index",
        )
      }

    val retained =
      retainDebugLogEntries(listOf(setup) + imports + listOf(lookahead, wait, failure, timing, sync) + recentEntries)
    val summary = buildRifeDiagnosticSummary(retained)

    assertEquals(500, retained.count(DebugLogEntry::isRifeDiagnostic))
    assertTrue(retained.any { it.id == "setup" })
    assertTrue(retained.any { it.id == "wait" })
    assertTrue(retained.any { it.id == "failure" })
    assertTrue(retained.any { it.id == "timing" })
    assertTrue(retained.any { it.id == "sync" })
    assertTrue(summary.any { it.contains("RUNTIME FAILURE reported: vulkan_inference_failed") })
    assertTrue(summary.any { it.contains("active_filter_path=resident_vulkan_ncnn") })
    assertTrue(summary.any { it.contains("display_refresh_hz=120") })
    assertTrue(summary.any { it.contains("max_dimension=0") && it.contains("resolution_mode=auto") })
    assertTrue(summary.any { it.contains("resident_option_rc=0 model_option_rc=0") })
    assertTrue(summary.any { it.contains("resident_timing_options_owned=false") })
    assertTrue(summary.any { it.contains("resident_lookahead_required=true") && it.contains("video_latency_hacks_rc=0") })
    assertTrue(summary.any { it.contains("target_fps=60") && it.contains("mix_frames=2") && it.contains("queue_depth=3") })
    assertTrue(summary.any { it.contains("vo_frames=1") && it.contains("display_synced=1") && it.contains("can_interpolate=1") })
    assertTrue(summary.any { it.contains("pair_before_pts=18.475333") && it.contains("after_input_ready=0") })
  }

  @Test
  fun reportsImportedFramesWithoutAnySubmittedResidentOutputAsNotWorking() {
    val imports =
      (0 until 500).map { index ->
        DebugLogEntry(
          id = "import-only-$index",
          timeMillis = index.toLong(),
          timestamp = "00:00:00.000",
          level = DebugLogLevel.Info,
          tag = "Mpv∞",
          message = "RIFE_DIAGNOSTIC event=ahb_input_imported pts=$index pts_delta_ms=0.000",
        )
      }

    val summary = buildRifeDiagnosticSummary(imports)

    assertTrue(summary.first().contains("INPUTS ONLY"))
    assertTrue(summary.first().contains("Interpolation is not confirmed working"))
    assertTrue(summary.any { it.contains("imported=500") && it.contains("submitted=0") })
    assertTrue(summary.any { it.contains("no intermediate wait/error marker") })
  }

  @Test
  fun summarizesPipelineTimingsAndMpvClockSamplesWithoutTreatingRecordsAsRates() {
    val entries =
      listOf(
        "RIFE_DIAGNOSTIC event=resident_timing_sample window_ms=2000 attempts=120 generated=34 waits=86 " +
          "queue_depth=3 mix_frames=2 source_before_pts=4.000000 source_after_pts=4.040000 output_pts=4.016667 " +
          "timestep=0.416667 import_avg_us=1200 import_max_us=2100 input_ready_avg_us=200 input_ready_max_us=400 " +
          "slot_sync_avg_us=500 slot_sync_max_us=1400 inference_avg_us=28000 inference_max_us=36000 " +
          "output_complete_avg_us=7000 output_complete_max_us=9000 " +
          "render_avg_us=2400 render_max_us=3300 present_submit_avg_us=800 present_submit_max_us=1000 " +
          "source_dimensions=1920x1080 process_dimensions=480x270 pts_offset=0.004 target_fps=60",
        "RIFE_DIAGNOSTIC event=mpv_sync_sample sample=3 phase=ready paused=false time_pos=4.020000 " +
          "audio_pts=4.018000 video_pts=4.020000 avsync_ms=-2.000 drop_frames=1 decoder_drop_frames=2 " +
          "mistimed_frames=3 source_dimensions=1920x1080 source_fps=24.000000",
        "RIFE_DIAGNOSTIC event=gpu_resident_frame pts=4.016667",
      ).mapIndexed { index, message ->
        DebugLogEntry(
          id = "diagnostic-$index",
          timeMillis = index.toLong(),
          timestamp = "00:00:00.000",
          level = DebugLogLevel.Info,
          tag = "Mpv∞",
          message = message,
        )
      }

    val summary = buildRifeDiagnosticSummary(entries)

    assertTrue(summary.any { it.contains("not FPS or drop totals") })
    assertTrue(summary.any { it.contains("inference_avg_us=28000") && it.contains("slot_sync_avg_us=500") })
    assertTrue(summary.any { it.contains("process_dimensions=480x270") })
    assertTrue(summary.any { it.contains("avsync_ms=-2.000") && it.contains("drop_frames=1") })
    assertTrue(summary.any { it.contains("audio_pts=4.018000") && it.contains("video_pts=4.020000") })
  }
}
