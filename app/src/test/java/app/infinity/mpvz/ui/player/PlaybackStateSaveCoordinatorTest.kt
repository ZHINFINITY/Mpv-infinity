/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PlaybackStateSaveCoordinatorTest {
  @Test
  fun concurrentWriterWaitsForSuspendedDatabaseWrite() = runBlocking {
    val media = "concurrent-${PlaybackStateSaveCoordinator.nextSequence()}"
    val first = PlaybackStateSaveCoordinator.nextSequence()
    val second = PlaybackStateSaveCoordinator.nextSequence()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val writes = mutableListOf<String>()
    val firstJob = launch {
      PlaybackStateSaveCoordinator.persistIfNewest(media, first) {
        writes += "first-start"
        entered.complete(Unit)
        release.await()
        writes += "first-end"
      }
    }
    entered.await()
    val secondJob = launch {
      PlaybackStateSaveCoordinator.persistIfNewest(media, second) { writes += "second" }
    }
    release.complete(Unit)
    firstJob.join()
    secondJob.join()
    assertEquals(listOf("first-start", "first-end", "second"), writes)
  }
  @Test
  fun lateOldSaveIsRejectedAfterNewSaveStartsFirst() = runBlocking {
    val media = "late-old-${PlaybackStateSaveCoordinator.nextSequence()}"
    val old = PlaybackStateSaveCoordinator.nextSequence()
    val newest = PlaybackStateSaveCoordinator.nextSequence()
    val writes = CopyOnWriteArrayList<Long>()

    assertTrue(
      PlaybackStateSaveCoordinator.persistIfNewest(media, newest) { writes += newest },
    )
    assertFalse(
      PlaybackStateSaveCoordinator.persistIfNewest(media, old) { writes += old },
    )
    assertEquals(listOf(newest), writes.toList())
  }

  @Test
  fun orderedWritersRunInSequenceOrderAndEachWriteIsSerialized() = runBlocking {
    val media = "ordered-${PlaybackStateSaveCoordinator.nextSequence()}"
    val first = PlaybackStateSaveCoordinator.nextSequence()
    val second = PlaybackStateSaveCoordinator.nextSequence()
    val writes = mutableListOf<Long>()

    assertTrue(PlaybackStateSaveCoordinator.persistIfNewest(media, first) { writes += first })
    assertTrue(PlaybackStateSaveCoordinator.persistIfNewest(media, second) { writes += second })
    assertEquals(listOf(first, second), writes)
  }

  @Test
  fun sequencesAreTrackedSeparatelyPerMedia() = runBlocking {
    val firstMedia = "first-${PlaybackStateSaveCoordinator.nextSequence()}"
    val secondMedia = "second-${PlaybackStateSaveCoordinator.nextSequence()}"
    val newer = PlaybackStateSaveCoordinator.nextSequence()
    val older = newer - 1

    assertTrue(PlaybackStateSaveCoordinator.persistIfNewest(firstMedia, newer) {})
    assertTrue(PlaybackStateSaveCoordinator.persistIfNewest(secondMedia, older) {})
  }
}
