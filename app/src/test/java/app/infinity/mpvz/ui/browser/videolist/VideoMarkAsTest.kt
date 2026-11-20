package app.infinity.mpvz.ui.browser.videolist

import app.infinity.mpvz.database.entities.PlaybackStateEntity
import app.infinity.mpvz.ui.player.isPlaybackStateWatched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMarkAsTest {
  @Test
  fun newResetsProgressAndExplicitWatchedState() {
    val result = playbackStateForMarkAs(state(), VideoMarkAs.New, durationSeconds = 600)
    assertEquals(0, result.lastPosition)
    assertEquals(600, result.timeRemaining)
    assertFalse(result.hasBeenWatched)
  }

  @Test
  fun lastPlayedResetsStaleFinishedState() {
    val result = playbackStateForMarkAs(state(lastPosition = 600, remaining = 0, watched = true), VideoMarkAs.LastPlayed, 600)
    assertEquals(0, result.lastPosition)
    assertEquals(600, result.timeRemaining)
    assertFalse(result.hasBeenWatched)
  }

  @Test
  fun finishedUsesNoResumePositionAndWatched() {
    val result = playbackStateForMarkAs(state(lastPosition = 120, remaining = 480), VideoMarkAs.Finished, 600)
    assertEquals(0, result.lastPosition)
    assertEquals(0, result.timeRemaining)
    assertTrue(result.hasBeenWatched)
  }

  @Test
  fun noneClearsResumeAndWatchedStateWithoutChangingSettings() {
    val before = state(lastPosition = 240, remaining = 360, watched = true)
    val result = playbackStateForMarkAs(before, VideoMarkAs.None, 600)
    assertEquals(0, result.lastPosition)
    assertEquals(600, result.timeRemaining)
    assertFalse(result.hasBeenWatched)
    assertEquals(before.playbackSpeed, result.playbackSpeed, 0.0)
    assertEquals(before.sid, result.sid)
    assertEquals(before.videoZoom, result.videoZoom, 0.0f)
    assertEquals(before.videoAspect, result.videoAspect)
  }

  @Test
  fun thresholdCrossingWinsOverNewOverride() {
    val state = state(lastPosition = 570, remaining = 30)
    assertTrue(isPlaybackStateWatched(state, durationSeconds = 600, watchedThreshold = 95))
  }

  @Test
  fun zeroThresholdDoesNotAutoMarkWatched() {
    val state = state(lastPosition = 599, remaining = 1)
    assertFalse(isPlaybackStateWatched(state, durationSeconds = 600, watchedThreshold = 0))
    assertTrue(isPlaybackStateWatched(state.copy(hasBeenWatched = true), 600, 0))
  }

  private fun state(
    lastPosition: Int = 300,
    remaining: Int = 300,
    watched: Boolean = false,
  ) = PlaybackStateEntity(
    mediaTitle = "local:test-video",
    lastPosition = lastPosition,
    playbackSpeed = 1.25,
    videoZoom = 0.2f,
    sid = 3,
    secondarySid = 4,
    subDelay = 5,
    subSpeed = 0.9,
    aid = 2,
    audioDelay = 6,
    timeRemaining = remaining,
    externalSubtitles = "sub.srt",
    hasBeenWatched = watched,
    videoAspect = "Fill",
    customAspectRatio = 1.5f,
    audioSelection = "eng",
    subtitleSelection = "spa",
    secondarySubtitleSelection = "fra",
  )
}
