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
  fun unavailableRendererDoesNotResetSharedSpeedOrZoom() {
    val saved = entity(lastPosition = 600).copy(playbackSpeed = 1.5, videoZoom = 0.4f)
    val unavailable = snapshot(currentPosition = 0, duration = 0, positionIsKnown = false)
      .copy(playbackSettingsAreKnown = false)
    val persisted = PlaybackStatePersistence.buildEntity(saved, unavailable, true, 80)
    assertEquals(600, persisted.lastPosition)
    assertEquals(1.5, persisted.playbackSpeed, 0.0)
    assertEquals(0.4f, persisted.videoZoom, 0f)
  }

  @Test
  fun unavailablePositionKeepsLastKnownHistoryAfterTeardown() {
    val saved = entity(lastPosition = 321, externalSubtitles = "content://subs/movie.srt")
    val snapshot = snapshot(
      currentPosition = 0,
      duration = 0,
      positionIsKnown = false,
      externalSubtitlesAreKnown = false,
    )

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
  fun unavailableSubtitleListKeepsOldListEvenWhenPositionIsKnown() {
    val saved = entity(lastPosition = 321, externalSubtitles = "content://subs/movie.srt")
    val persisted = PlaybackStatePersistence.buildEntity(
      saved,
      snapshot(currentPosition = 12, duration = 600, positionIsKnown = true, externalSubtitlesAreKnown = false),
      true,
      80,
    )
    assertEquals("content://subs/movie.srt", persisted.externalSubtitles)
  }

  @Test
  fun knownEmptySubtitleListClearsOldList() {
    val saved = entity(lastPosition = 321, externalSubtitles = "content://subs/movie.srt")
    val persisted = PlaybackStatePersistence.buildEntity(
      saved,
      snapshot(currentPosition = 12, duration = 600, externalSubtitlesAreKnown = true),
      true,
      80,
    )
    assertTrue(persisted.externalSubtitles.isEmpty())
  }

  @Test
  fun unknownNonBlankSubtitleListIsPreserved() {
    val persisted = PlaybackStatePersistence.buildEntity(
      entity(lastPosition = 321, externalSubtitles = "old.srt"),
      snapshot(currentPosition = 12, duration = 600, externalSubtitles = "new.srt", externalSubtitlesAreKnown = false),
      true,
      80,
    )
    assertEquals("new.srt", persisted.externalSubtitles)
  }

  @Test
  fun explicitSeekToZeroIsSavedWhenDurationIsUnknown() {
    val persisted = PlaybackStatePersistence.buildEntity(
      entity(lastPosition = 321),
      snapshot(currentPosition = 0, duration = 0, positionIsKnown = true),
      true,
      80,
    )
    assertEquals(0, persisted.lastPosition)
  }

  @Test
  fun unknownPositionWithoutHistoryFallsBackToNonNegativePosition() {
    val persisted = PlaybackStatePersistence.buildEntity(
      null,
      snapshot(currentPosition = -4, duration = 0, positionIsKnown = false),
      true,
      80,
    )
    assertTrue(persisted.lastPosition >= 0)
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

  @Test
  fun nativeSnapshotAfterMpvThenTeardownKeepsLatestSupportedAndMpvOnlySettings() {
    val mpv = snapshot(
      currentPosition = 600,
      duration = 1200,
      playbackSpeed = 1.25,
      videoZoom = 0.2f,
      videoAspect = "Custom",
      customAspectRatio = 1.78f,
      sid = 4,
      secondarySid = 5,
      subDelayMs = 120,
      subSpeed = 0.9,
      aid = 3,
      audioDelayMs = 80,
    )
    val savedFromMpv = PlaybackStatePersistence.buildEntity(null, mpv, true, 80)
    val native = snapshot(
      currentPosition = 240,
      duration = 1200,
      playbackSpeed = 1.5,
      videoZoom = 0.35f,
      videoAspect = "Fill",
      customAspectRatio = 1.33f,
      mpvSettingsAreKnown = false,
      sid = -1,
      secondarySid = -1,
      subDelayMs = 0,
      subSpeed = 1.0,
      aid = -1,
      audioDelayMs = 0,
    )
    val savedFromNative = PlaybackStatePersistence.buildEntity(savedFromMpv, native, true, 80)
    val reopened = PlaybackStatePersistence.buildEntity(
      savedFromNative,
      snapshot(
        currentPosition = 0,
        duration = 0,
        positionIsKnown = false,
        mpvSettingsAreKnown = false,
        videoPresentationIsKnown = false,
      ),
      true,
      80,
    )

    assertEquals(240, savedFromNative.lastPosition)
    assertEquals(1.5, reopened.playbackSpeed, 0.0)
    assertEquals(0.35f, reopened.videoZoom)
    assertEquals("Fill", reopened.videoAspect)
    assertEquals(1.33f, reopened.customAspectRatio)
    assertEquals(4, reopened.sid)
    assertEquals(5, reopened.secondarySid)
    assertEquals(120, reopened.subDelay)
    assertEquals(0.9, reopened.subSpeed, 0.0)
    assertEquals(3, reopened.aid)
    assertEquals(80, reopened.audioDelay)
  }

  @Test
  fun unknownMpvAndVideoSettingsUseSnapshotWhenThereIsNoOldState() {
    val persisted = PlaybackStatePersistence.buildEntity(
      oldState = null,
      snapshot = snapshot(
        currentPosition = 10,
        duration = 600,
        playbackSpeed = 1.25,
        videoZoom = 0.2f,
        videoAspect = "Fill",
        customAspectRatio = 1.5f,
        sid = 2,
        secondarySid = 3,
        subDelayMs = 40,
        subSpeed = 0.8,
        aid = 1,
        audioDelayMs = 25,
        mpvSettingsAreKnown = false,
        videoPresentationIsKnown = false,
      ),
      savePositionOnQuit = true,
      watchedThreshold = 80,
    )

    assertEquals(2, persisted.sid)
    assertEquals(3, persisted.secondarySid)
    assertEquals(40, persisted.subDelay)
    assertEquals(0.8, persisted.subSpeed, 0.0)
    assertEquals(1, persisted.aid)
    assertEquals(25, persisted.audioDelay)
    assertEquals("Fill", persisted.videoAspect)
    assertEquals(1.5f, persisted.customAspectRatio)
  }

  @Test
  fun positionSavePreferenceOffStillUpdatesKnownSettings() {
    val persisted = PlaybackStatePersistence.buildEntity(
      oldState = entity(lastPosition = 600),
      snapshot = snapshot(
        currentPosition = 240,
        duration = 1200,
        playbackSpeed = 1.4,
        videoZoom = 0.3f,
        videoAspect = "Fill",
      ),
      savePositionOnQuit = false,
      watchedThreshold = 80,
    )

    assertEquals(600, persisted.lastPosition)
    assertEquals(1.4, persisted.playbackSpeed, 0.0)
    assertEquals(0.3f, persisted.videoZoom)
    assertEquals("Fill", persisted.videoAspect)
  }

  @Test
  fun snapshotHelperSupportsCopyForUpdatedState() {
    val updated = snapshot(currentPosition = 600, duration = 1200)
      .copy(playbackSpeed = 1.5, videoZoom = 0.4f, videoAspect = "Fill")

    assertEquals(1.5, updated.playbackSpeed, 0.0)
    assertEquals(0.4f, updated.videoZoom)
    assertEquals("Fill", updated.videoAspect)
  }

  private fun snapshot(
    currentPosition: Int,
    duration: Int,
    playbackSpeed: Double = 1.0,
    videoZoom: Float = 0f,
    videoAspect: String = "Fit",
    customAspectRatio: Float = -1f,
    sid: Int = -1,
    secondarySid: Int = -1,
    subDelayMs: Int = 0,
    subSpeed: Double = 1.0,
    aid: Int = -1,
    audioDelayMs: Int = 0,
    positionIsKnown: Boolean = true,
    externalSubtitles: String = "",
    externalSubtitlesAreKnown: Boolean = true,
    mpvSettingsAreKnown: Boolean = true,
    videoPresentationIsKnown: Boolean = true,
  ) = PlaybackStateSnapshot(
    mediaIdentifier = "media:v2:test",
    mediaTitle = "movie.mkv",
    currentPosition = currentPosition,
    duration = duration,
    playbackSpeed = playbackSpeed,
    videoZoom = videoZoom,
    videoAspect = videoAspect,
    customAspectRatio = customAspectRatio,
    sid = sid,
    secondarySid = secondarySid,
    subDelayMs = subDelayMs,
    subSpeed = subSpeed,
    aid = aid,
    audioDelayMs = audioDelayMs,
    externalSubtitles = externalSubtitles,
    positionIsKnown = positionIsKnown,
    externalSubtitlesAreKnown = externalSubtitlesAreKnown,
    mpvSettingsAreKnown = mpvSettingsAreKnown,
    videoPresentationIsKnown = videoPresentationIsKnown,
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
