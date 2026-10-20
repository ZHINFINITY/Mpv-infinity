/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import app.infinity.mpvz.database.entities.PlaybackStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatePersistenceTest {
  @Test
  fun unavailablePositionKeepsLastKnownHistoryAfterTeardown() {
    val saved = entity(lastPosition = 321, externalSubtitles = "content://subs/movie.srt")
    val snapshot = snapshot(currentPosition = 0, duration = 0, positionIsKnown = false)

    val persisted = PlaybackStatePersistence.buildEntity(saved, snapshot, true, 80)

    assertEquals(321, persisted.lastPosition)
    assertEquals("content://subs/movie.srt", persisted.externalSubtitles)
  }

  @Test
  fun explicitSeekToZeroIsSavedWhenPositionIsKnown() {
    val saved = entity(lastPosition = 321)
    val snapshot = snapshot(currentPosition = 0, duration = 600, positionIsKnown = true)

    val persisted = PlaybackStatePersistence.buildEntity(saved, snapshot, true, 80)

    assertEquals(0, persisted.lastPosition)
  }

  @Test
  fun absentHistoryStartsAtZeroAndCanBeReopenedFromSavedPosition() {
    val first = PlaybackStatePersistence.buildEntity(
      oldState = null,
      snapshot = snapshot(currentPosition = 42, duration = 600),
      savePositionOnQuit = true,
      watchedThreshold = 80,
    )
    val reopened = PlaybackStatePersistence.buildEntity(
      oldState = first,
      snapshot = snapshot(currentPosition = 0, duration = 0, positionIsKnown = false),
      savePositionOnQuit = true,
      watchedThreshold = 80,
    )

    assertEquals(42, first.lastPosition)
    assertEquals(42, reopened.lastPosition)
    assertTrue(reopened.externalSubtitles.isEmpty())
  }

  private fun snapshot(
    currentPosition: Int,
    duration: Int,
    positionIsKnown: Boolean = true,
  ) = PlaybackStateSnapshot(
    mediaIdentifier = "media:v2:test",
    mediaTitle = "movie.mkv",
    currentPosition = currentPosition,
    duration = duration,
    playbackSpeed = 1.0,
    videoZoom = 0f,
    videoAspect = "Fit",
    customAspectRatio = -1f,
    sid = -1,
    secondarySid = -1,
    subDelayMs = 0,
    subSpeed = 1.0,
    aid = -1,
    audioDelayMs = 0,
    externalSubtitles = "",
    positionIsKnown = positionIsKnown,
  )

  private fun entity(lastPosition: Int, externalSubtitles: String = "") =
    PlaybackStateEntity(
      mediaTitle = "media:v2:test",
      lastPosition = lastPosition,
      playbackSpeed = 1.0,
      sid = -1,
      subDelay = 0,
      subSpeed = 1.0,
      aid = -1,
      audioDelay = 0,
      externalSubtitles = externalSubtitles,
    )
}
