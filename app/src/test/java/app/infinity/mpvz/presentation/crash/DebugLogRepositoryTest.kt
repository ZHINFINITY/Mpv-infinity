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
