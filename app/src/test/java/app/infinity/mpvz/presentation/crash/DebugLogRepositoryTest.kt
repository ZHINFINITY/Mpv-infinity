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
}
