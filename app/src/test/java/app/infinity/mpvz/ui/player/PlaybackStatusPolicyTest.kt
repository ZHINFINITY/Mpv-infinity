/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import app.infinity.mpvz.database.entities.PlaybackStateEntity
import app.infinity.mpvz.ui.browser.videolist.mostRecentUnwatchedFilePath
import app.infinity.mpvz.ui.browser.videolist.shouldShowNewBadge
import app.infinity.mpvz.ui.browser.videolist.videoProgressPercentage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatusPolicyTest {
  @Test
  fun oldVideoWithoutPlaybackStateIsNotTreatedAsWatched() {
    assertFalse(isPlaybackStateWatched(null, durationSeconds = 600L, watchedThreshold = 95))
  }

  @Test
  fun savedPositionAtThresholdCountsAsWatchedEvenIfNewOverrideRemains() {
    val state = playbackState(lastPosition = 570, timeRemaining = 30, hasBeenWatched = false)

    assertTrue(isPlaybackStateWatched(state, durationSeconds = 600L, watchedThreshold = 95))
    assertNull(videoProgressPercentage(state, durationSeconds = 600L, isWatched = true))
  }

  @Test
  fun recentVideoLosesNewBadgeOnceSavedPlaybackCrossesWatchedThreshold() {
    val state = playbackState(lastPosition = 570, timeRemaining = 30, hasBeenWatched = false)
    val watched = isPlaybackStateWatched(state, durationSeconds = 600L, watchedThreshold = 95)

    assertTrue(watched)
    assertFalse(shouldShowNewBadge(isWatched = watched, isWithinNewLabelWindow = true))
    assertTrue(shouldShowNewBadge(isWatched = false, isWithinNewLabelWindow = true))
  }

  @Test
  fun belowThresholdRemainsUnwatchedAndShowsItsProgress() {
    val state = playbackState(lastPosition = 564, timeRemaining = 36, hasBeenWatched = false)

    assertFalse(isPlaybackStateWatched(state, durationSeconds = 600L, watchedThreshold = 95))
    assertEquals(
      0.94f,
      videoProgressPercentage(state, durationSeconds = 600L, isWatched = false)!!,
      0.0001f,
    )
  }

  @Test
  fun disabledAutoThresholdOnlyHonorsPersistedWatchedFlag() {
    val inProgress = playbackState(lastPosition = 599, timeRemaining = 1, hasBeenWatched = false)
    val finished = inProgress.copy(hasBeenWatched = true)

    assertFalse(isPlaybackStateWatched(inProgress, durationSeconds = 600L, watchedThreshold = 0))
    assertTrue(isPlaybackStateWatched(finished, durationSeconds = 600L, watchedThreshold = 0))
  }

  @Test
  fun lastPlayedMarkerMovesToTheMostRecentUnfinishedVideo() {
    val finished = "/videos/finished.mp4"
    val previous = "/videos/previous.mp4"

    assertEquals(
      previous,
      mostRecentUnwatchedFilePath(
        recentlyPlayedFilePaths = listOf(finished, previous),
        folderPath = "/videos",
        watchedFilePaths = setOf(finished),
      ),
    )
  }

  @Test
  fun lastPlayedMarkerIsEmptyWhenAllRecentVideosAreFinished() {
    val finished = "/videos/finished.mp4"

    assertNull(
      mostRecentUnwatchedFilePath(
        recentlyPlayedFilePaths = listOf(finished),
        folderPath = "/videos",
        watchedFilePaths = setOf(finished),
      ),
    )
  }

  private fun playbackState(
    lastPosition: Int,
    timeRemaining: Int,
    hasBeenWatched: Boolean,
  ) = PlaybackStateEntity(
    mediaTitle = "local:test-video",
    lastPosition = lastPosition,
    playbackSpeed = 1.0,
    sid = -1,
    secondarySid = -1,
    subDelay = 0,
    subSpeed = 1.0,
    aid = -1,
    audioDelay = 0,
    timeRemaining = timeRemaining,
    hasBeenWatched = hasBeenWatched,
  )
}
