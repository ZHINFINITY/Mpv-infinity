/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.presentation.crash

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogSnapshotStoreTest {
  @Test
  fun roundTripsRifeAndMemoryDiagnosticsFromPrivateStorage() {
    val directory = Files.createTempDirectory("crash-log-snapshot").toFile()
    try {
      val snapshot =
        "RIFE_DIAGNOSTIC event=resident_error reason=vulkan_inference_failed\n" +
          "Crash-time memory snapshot: system_low_memory=true"

      assertTrue(CrashLogSnapshotStore.save(directory, snapshot))
      assertEquals(snapshot, CrashLogSnapshotStore.read(directory))
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun retainsNewestTailWhenSnapshotExceedsBound() {
    val directory = Files.createTempDirectory("crash-log-snapshot-bound").toFile()
    try {
      val snapshot =
        "old-log-line\n" +
          "x".repeat(CrashLogSnapshotStore.MAX_SNAPSHOT_CHARS) +
          "\nLATEST_RIFE_FAILURE"

      assertTrue(CrashLogSnapshotStore.save(directory, snapshot))
      val restored = CrashLogSnapshotStore.read(directory)

      assertFalse(restored.orEmpty().contains("old-log-line"))
      assertTrue(restored.orEmpty().endsWith("LATEST_RIFE_FAILURE"))
      assertTrue(restored.orEmpty().length <= CrashLogSnapshotStore.MAX_SNAPSHOT_CHARS)
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun returnsNullWhenNoCrashSnapshotExists() {
    val directory = Files.createTempDirectory("crash-log-snapshot-empty").toFile()
    try {
      assertEquals(null, CrashLogSnapshotStore.read(directory))
    } finally {
      directory.deleteRecursively()
    }
  }
}
