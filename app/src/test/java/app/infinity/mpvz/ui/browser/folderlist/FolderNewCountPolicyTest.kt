package app.infinity.mpvz.ui.browser.folderlist

import app.infinity.mpvz.database.entities.PlaybackStateEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderNewCountPolicyTest {
  @Test
  fun resetRowsForOldFilesDoNotBecomeNewWithoutAnExplicitOverride() {
    val modifiedSeconds = 1_600_000_000L
    val nowMillis = modifiedSeconds * 1000L + 30L * 24L * 60L * 60L * 1000L

    assertFalse(
      isVideoCountedAsNew(
        playbackState = state(lastPosition = 0, timeRemaining = 600),
        durationSeconds = 600,
        videoDateModifiedSeconds = modifiedSeconds,
        currentTimeMillis = nowMillis,
        newLabelDays = 14,
        watchedThreshold = 95,
        folderWatchedOverride = null,
        explicitlyMarkedUnwatched = false,
        explicitlyMarkedWatched = false,
      ),
    )
  }

  @Test
  fun explicitNewOverrideMarksAnOldUnwatchedFileNew() {
    val modifiedSeconds = 1_600_000_000L
    val nowMillis = modifiedSeconds * 1000L + 30L * 24L * 60L * 60L * 1000L

    assertTrue(
      isVideoCountedAsNew(
        playbackState = state(lastPosition = 0, timeRemaining = 600),
        durationSeconds = 600,
        videoDateModifiedSeconds = modifiedSeconds,
        currentTimeMillis = nowMillis,
        newLabelDays = 14,
        watchedThreshold = 95,
        folderWatchedOverride = null,
        explicitlyMarkedUnwatched = true,
        explicitlyMarkedWatched = false,
      ),
    )
  }

  @Test
  fun watchedThresholdOverridesAnExplicitNewMark() {
    assertFalse(
      isVideoCountedAsNew(
        playbackState = state(lastPosition = 570, timeRemaining = 30),
        durationSeconds = 600,
        videoDateModifiedSeconds = 1_600_000_000L,
        currentTimeMillis = 1_700_000_000_000L,
        newLabelDays = 0,
        watchedThreshold = 95,
        folderWatchedOverride = null,
        explicitlyMarkedUnwatched = true,
        explicitlyMarkedWatched = false,
      ),
    )
  }

  @Test
  fun zeroAgeWindowKeepsUnwatchedFilesNewAndFolderUnwatchedOverrideApplies() {
    val oldDate = 1_600_000_000L
    val now = oldDate * 1000L + 30L * 24L * 60L * 60L * 1000L
    val playback = state(lastPosition = 0, timeRemaining = 600)

    assertTrue(
      isVideoCountedAsNew(playback, 600, oldDate, now, 0, 95, null, false, false),
    )
    assertTrue(
      isVideoCountedAsNew(playback, 600, oldDate, now, 14, 95, false, false, false),
    )
  }

  private fun state(
    lastPosition: Int,
    timeRemaining: Int,
  ) = PlaybackStateEntity(
    mediaTitle = "local:test-video",
    lastPosition = lastPosition,
    playbackSpeed = 1.0,
    sid = -1,
    subDelay = 0,
    subSpeed = 1.0,
    aid = -1,
    audioDelay = 0,
    timeRemaining = timeRemaining,
    hasBeenWatched = false,
  )
}
