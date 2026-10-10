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
  fun reservesTheLatestFiveHundredRifeDiagnosticsWhenTheLogBufferOverflows() {
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
    assertTrue(retained.none { it.id == "rife-0" })
    assertTrue(retained.any { it.id == "rife-519" })
    assertTrue(retained.any { it.id == "recent-${DEBUG_LOG_ENTRY_LIMIT - 1}" })
  }

  @Test
  fun reservesTheLatestFiveHundredMpvFlowAndRifeDiagnostics() {
    fun diagnostic(id: String, timeMillis: Long, message: String) =
      DebugLogEntry(
        id = id,
        timeMillis = timeMillis,
        timestamp = "00:00:00.000",
        level = DebugLogLevel.Info,
        tag = "Mpv∞",
        message = message,
      )

    val rifeEntries =
      (0 until 520).map { index ->
        diagnostic("rife-$index", index.toLong(), "RIFE_DIAGNOSTIC event=frame_timing frame=$index")
      }
    val mpvFlowEntries =
      (0 until 520).map { index ->
        diagnostic("mpvflow-$index", 520L + index, "MPVFLOW_DIAGNOSTIC event=source_pair_synthesis pair=$index")
      }
    val recentEntries =
      (0 until DEBUG_LOG_ENTRY_LIMIT).map { index ->
        diagnostic("recent-$index", 2_000L + index, "routine playback log $index")
      }

    val retained = retainDebugLogEntries(rifeEntries + mpvFlowEntries + recentEntries)

    assertEquals(DEBUG_LOG_ENTRY_LIMIT, retained.size)
    assertEquals(500, retained.count(DebugLogEntry::isRifeDiagnostic))
    assertEquals(500, retained.count(DebugLogEntry::isMpvFlowDiagnostic))
    assertTrue(retained.none { it.id == "rife-0" || it.id == "mpvflow-0" })
    assertTrue(retained.any { it.id == "rife-519" })
    assertTrue(retained.any { it.id == "mpvflow-519" })
  }

  @Test
  fun classifiesMpvFlowWatchdogGovernorAndSourceRateEvents() {
    val entries =
      listOf(
        "MPVFLOW_DIAGNOSTIC event=source_pair_synthesis frames=2 processing_ms=38.90 pair_budget_ms=42.00 budget_ratio=0.926 target_fps=60 process_width=480 process_height=270",
        "MPVFLOW_DIAGNOSTIC event=deadline_yield processing_ms=36.10 pair_budget_ms=42.00",
        "MPVFLOW_DIAGNOSTIC event=resolution_governor reason=budget_pressure from_dimension=480 to_dimension=416",
        "MPVFLOW_DIAGNOSTIC event=source_rate_guard action=passthrough_original_pts",
        "ordinary playback log without interpolation details",
      ).mapIndexed { index, message ->
        DebugLogEntry(
          id = "event-$index",
          timeMillis = index.toLong(),
          timestamp = "00:00:00.000",
          level = DebugLogLevel.Info,
          tag = if (index == 1) "vf_mpvflow" else "Mpv∞",
          message = message,
        )
      }

    assertEquals(4, entries.count(DebugLogEntry::isMpvFlowDiagnostic))
  }

  @Test
  fun classifiesAndPreservesMpvGpuFlowRuntimeDiagnostics() {
    val rendererEntries =
      listOf(
        "MPVFLOW_GPU_DIAGNOSTIC event=init state=initialized backend=vulkan-spirv target_fps=120 reason=ready",
        "MPVFLOW_GPU_DIAGNOSTIC event=active state=synthesized backend=vulkan-spirv target_fps=120 reason=motion_pair",
        "MPVFLOW_DIAGNOSTIC event=config requested_enabled=true runtime_state=not_verified",
      ).mapIndexed { index, message ->
        DebugLogEntry(
          id = "renderer-$index",
          timeMillis = index.toLong(),
          timestamp = "00:00:00.000",
          level = DebugLogLevel.Info,
          tag = "mpv",
          message = message,
        )
      }
    val recentEntries =
      (0 until DEBUG_LOG_ENTRY_LIMIT).map { index ->
        DebugLogEntry(
          id = "recent-$index",
          timeMillis = 10L + index,
          timestamp = "00:00:00.000",
          level = DebugLogLevel.Info,
          tag = "Playback",
          message = "routine playback log $index",
        )
      }

    val retained = retainDebugLogEntries(rendererEntries + recentEntries)

    assertEquals(3, rendererEntries.count(DebugLogEntry::isMpvFlowDiagnostic))
    assertEquals(3, retained.count(DebugLogEntry::isMpvFlowDiagnostic))
    assertTrue(retained.any { it.id == "renderer-0" })
    assertTrue(retained.any { it.id == "renderer-1" })
    assertTrue(retained.any { it.id == "renderer-2" })
  }

  @Test
  fun classifiesAndPreservesNativeMedia3FlowRouteAndSummary() {
    val flowEntries =
      listOf(
        "media3_flow_route enabled=true state=ready reason=selected",
        "flow_summary backend=vulkan-spirv state=passthrough bypass=hdr target_fps=120",
      ).mapIndexed { index, message ->
        DebugLogEntry(
          id = "media3-flow-$index",
          timeMillis = index.toLong(),
          timestamp = "00:00:00.000",
          level = DebugLogLevel.Info,
          tag = "Mpv∞-Media3",
          message = message,
        )
      }
    val recentEntries =
      (0 until DEBUG_LOG_ENTRY_LIMIT).map { index ->
        DebugLogEntry(
          id = "media3-recent-$index",
          timeMillis = 10L + index,
          timestamp = "00:00:00.000",
          level = DebugLogLevel.Info,
          tag = "Playback",
          message = "routine playback log $index",
        )
      }

    val retained = retainDebugLogEntries(flowEntries + recentEntries)

    assertEquals(2, flowEntries.count(DebugLogEntry::isNativeMedia3FlowDiagnostic))
    assertEquals(2, retained.count(DebugLogEntry::isNativeMedia3FlowDiagnostic))
    assertTrue(retained.any { it.id == "media3-flow-0" })
    assertTrue(retained.any { it.id == "media3-flow-1" })
  }

  @Test
  fun pinsInitialRendererStateAndLatestFailuresAfterLargeUnrelatedLogcatFlood() {
    fun entry(id: String, time: Long, tag: String, message: String) =
      DebugLogEntry(
        id = id,
        timeMillis = time,
        timestamp = "00:00:00.000",
        level = if (message.contains("state=failed") || message.contains("state=unavailable")) {
          DebugLogLevel.Error
        } else {
          DebugLogLevel.Info
        },
        tag = tag,
        message = message,
      )

    val startup =
      listOf(
        entry("mpv-config", 1L, "mpv", "MPVFLOW_DIAGNOSTIC event=config requested_enabled=true renderer=gpu-next gpu_api=vulkan"),
        entry("mpv-renderer-config", 2L, "mpv", "MPVFLOW_DIAGNOSTIC event=renderer_config state=applied renderer=gpu-next gpu_api=vulkan gpu_context=androidvk option_order=gpu-api>gpu-context>vo gpu_api_rc=0 gpu_context_rc=0 vo_rc=0"),
        entry("mpv-renderer", 3L, "mpv", "MPVFLOW_DIAGNOSTIC event=renderer_init state=unavailable backend=vulkan target_fps=120 reason=gpu_compute_initialization_failed"),
        entry("native-vulkan", 4L, "MpvInfinityFlowVk", "MPVFLOW_DIAGNOSTIC component=native-media3 event=vulkan_context state=requested api=vulkan"),
        entry("native-renderer", 5L, "MpvInfinityFlowVk", "MPVFLOW_DIAGNOSTIC component=native-media3 event=renderer_init state=ready backend=vulkan-spirv"),
      )
    val unrelatedFlood =
      (0 until DEBUG_LOG_ENTRY_LIMIT + 2_000).map { index ->
        entry("codec-$index", 10L + index, "MediaCodec", "routine codec buffer trace $index")
      }
    val repetitiveFlowNoise =
      (0 until 900).map { index ->
        entry("flow-noise-$index", 20_000L + index, "mpv", "MPVFLOW_DIAGNOSTIC event=timing_sample sample=$index")
      }
    val latestFailures =
      listOf(
        entry("mpv-latest-failure", 30_001L, "mpv", "MPVFLOW_DIAGNOSTIC event=output state=passthrough reason=gpu_context_unavailable source_pts=8.000000"),
        entry("native-latest-failure", 30_002L, "MpvInfinityFlowVk", "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed stage=vkBindImageMemory vk_result=-100"),
      )

    val retained = retainDebugLogEntries(startup + unrelatedFlood + repetitiveFlowNoise + latestFailures)

    assertTrue(retained.size <= DEBUG_LOG_ENTRY_LIMIT)
    assertTrue(retained.any { it.id == "mpv-config" })
    assertTrue(retained.any { it.id == "mpv-renderer-config" })
    assertTrue(retained.any { it.id == "mpv-renderer" })
    assertTrue(retained.any { it.id == "native-vulkan" })
    assertTrue(retained.any { it.id == "native-renderer" })
    assertTrue(retained.any { it.id == "mpv-latest-failure" })
    assertTrue(retained.any { it.id == "native-latest-failure" })
    assertTrue(retained.any { it.id == "codec-${DEBUG_LOG_ENTRY_LIMIT + 1_999}" })
  }

  @Test
  fun pinsLatestRendererConfigOutsideTheFirstStartupWindow() {
    fun entry(id: String, time: Long, message: String) =
      DebugLogEntry(
        id = id,
        timeMillis = time,
        timestamp = "00:00:00.000",
        level = DebugLogLevel.Info,
        tag = "mpv",
        message = message,
      )

    val earlierStartup =
      (0 until 20).map { index ->
        entry(
          "old-startup-$index",
          index.toLong(),
          "MPVFLOW_DIAGNOSTIC event=renderer_init state=initialized session=$index",
        )
      }
    val latestRendererConfig =
      entry(
        "latest-renderer-config",
        21L,
        "MPVFLOW_DIAGNOSTIC event=renderer_config state=applied requested_renderer=gpu-next " +
          "requested_gpu_api=vulkan requested_gpu_context=androidvk " +
          "option_order=gpu-api>gpu-context>vo gpu_api_rc=0 gpu_context_rc=0 vo_rc=0",
      )
    val flowNoise =
      (0 until DEBUG_LOG_ENTRY_LIMIT + 2_000).map { index ->
        entry("flow-noise-$index", 100L + index, "MPVFLOW_DIAGNOSTIC event=timing_sample sample=$index")
      }

    val retained = retainDebugLogEntries(earlierStartup + latestRendererConfig + flowNoise)

    assertTrue(retained.size <= DEBUG_LOG_ENTRY_LIMIT)
    assertTrue(retained.any { it.id == latestRendererConfig.id })
    assertTrue(retained.any { it.id == "flow-noise-${DEBUG_LOG_ENTRY_LIMIT + 1_999}" })
  }

  @Test
  fun parsesAndExportsNativeVulkanDiagnosticsAlongsideTheUnfilteredLogcat() {
    val rawLogcat =
      listOf(
        "10-11 01:02:03.000 123 456 I mpv: MPVFLOW_DIAGNOSTIC event=config requested_enabled=true gpu_api=vulkan",
        "10-11 01:02:03.001 123 456 I mpv: MPVFLOW_DIAGNOSTIC event=renderer_config state=applied renderer=gpu-next gpu_api=vulkan gpu_context=androidvk option_order=gpu-api>gpu-context>vo gpu_api_rc=0 gpu_context_rc=0 vo_rc=0",
        "10-11 01:02:03.002 123 456 I MpvInfinityFlowVk: MPVFLOW_DIAGNOSTIC component=native-media3 event=compute_capabilities glsl_compute=1 glsl_vulkan=1",
        "10-11 01:02:03.003 123 456 E MpvInfinityFlowVk: MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed stage=vkGetAndroidHardwareBufferPropertiesANDROID vk_result=-100",
        "10-11 01:02:03.004 123 456 I Playback: unrelated decoder detail remains in full Logcat",
      )
    val parsed = DebugLogReader.parseForTesting(rawLogcat, expectedPid = 123, allowRawFallback = false)
    val retained = retainDebugLogEntries(parsed)
    val selected = selectMpvFlowDiagnosticLines(rawLogcat.joinToString("\n"))

    assertEquals(4, retained.count(DebugLogEntry::isMpvFlowDiagnostic))
    assertEquals(2, retained.count(DebugLogEntry::isNativeMedia3FlowDiagnostic))
    assertTrue(selected.contains("event=config"))
    assertTrue(selected.contains("event=renderer_config"))
    assertTrue(selected.contains("option_order=gpu-api>gpu-context>vo"))
    assertTrue(selected.contains("event=compute_capabilities"))
    assertTrue(selected.contains("stage=vkGetAndroidHardwareBufferPropertiesANDROID"))
    assertTrue(!selected.contains("unrelated decoder detail"))
    assertTrue(rawLogcat.last().contains("unrelated decoder detail"))
  }

  @Test
  fun formatsMeasuredPairCostWithoutCallingItDisplayedFrameRate() {
    val entry =
      DebugLogEntry(
        id = "mpvflow-pair",
        timeMillis = 1L,
        timestamp = "00:00:00.001",
        level = DebugLogLevel.Info,
        tag = "vf_mpvflow",
        message = "MPVFLOW_DIAGNOSTIC event=source_pair_synthesis frames=2 processing_ms=38.90 pair_budget_ms=42.00 budget_ratio=0.926 target_fps=60 process_width=480 process_height=270",
      )

    val summary = formatMpvFlowTimingSummary(listOf(entry))

    assertTrue(summary.contains("38.9/42.0 ms (93%)"))
    assertTrue(summary.contains("480×270"))
    assertTrue(summary.contains("target 60 fps"))
    assertTrue(!summary.contains("displayed"))
  }
}
