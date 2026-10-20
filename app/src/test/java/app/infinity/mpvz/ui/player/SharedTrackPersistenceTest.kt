package app.infinity.mpvz.ui.player

import app.infinity.mpvz.database.entities.PlaybackStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTrackPersistenceTest {
  private fun saved() = PlaybackStateEntity(
    mediaTitle = "media:v2:movie", lastPosition = 600, playbackSpeed = 1.25,
    sid = 2, aid = 3, subDelay = 0, subSpeed = 1.0, audioDelay = 0,
    audioSelection = "saved-audio", subtitleSelection = "saved-subtitle",
  )

  private fun snapshot(audio: String? = null, subtitle: String? = null) = PlaybackStateSnapshot(
    mediaIdentifier = "media:v2:movie", mediaTitle = "movie.mkv", currentPosition = 240, duration = 3600,
    playbackSpeed = 1.5, videoZoom = 0f, videoAspect = "Fit", customAspectRatio = -1f,
    sid = -1, secondarySid = -1, subDelayMs = 0, subSpeed = 1.0, aid = -1, audioDelayMs = 0,
    externalSubtitles = "", mpvSettingsAreKnown = false, audioSelection = audio, subtitleSelection = subtitle,
  )

  @Test fun nativeSelectionAndPositionReplaceSharedStateWithoutReplacingMpvIds() {
    val audio = PlaybackStatePersistence.encodeSelection(SharedTrackSelection.selected(
      TrackSelectionFingerprint(language = "eng", codec = "aac", channelCount = 6, ordinal = 1),
    ))
    val subtitle = PlaybackStatePersistence.encodeSelection(SharedTrackSelection.Disabled)
    val updated = PlaybackStatePersistence.buildEntity(saved(), snapshot(audio, subtitle), true, 80)
    assertEquals(240, updated.lastPosition)
    assertEquals(audio, updated.audioSelection)
    assertEquals(subtitle, updated.subtitleSelection)
    assertEquals(3, updated.aid)
    assertEquals(2, updated.sid)
    assertEquals(SharedTrackSelection.Disabled, PlaybackStatePersistence.decodeSelection(updated.subtitleSelection))
  }

  @Test fun unknownTeardownSelectionKeepsMostRecentSharedChoices() {
    val latest = saved().copy(audioSelection = "native-audio", subtitleSelection = "native-subtitle")
    val updated = PlaybackStatePersistence.buildEntity(latest, snapshot().copy(
      currentPosition = 0, duration = 0, positionIsKnown = false, playbackSettingsAreKnown = false,
      externalSubtitlesAreKnown = false,
    ), true, 80)
    assertEquals(600, updated.lastPosition)
    assertEquals("native-audio", updated.audioSelection)
    assertEquals("native-subtitle", updated.subtitleSelection)
  }

  @Test fun selectionsAreSavedEvenWhenPositionSavingIsDisabled() {
    val updated = PlaybackStatePersistence.buildEntity(saved(), snapshot("new-audio", "new-subtitle"), false, 80)
    assertEquals(600, updated.lastPosition)
    assertEquals("new-audio", updated.audioSelection)
    assertEquals("new-subtitle", updated.subtitleSelection)
  }

  @Test fun externalSubtitleIdentityRoundTripsAndInvalidJsonIsUnknown() {
    val selection = SharedTrackSelection.selected(TrackSelectionFingerprint(
      language = "ja", label = "Signs & Songs \"HD\"", codec = "ass",
      externalUri = "content://downloads/subs/a%20b.ass?token=x|y", ordinal = 2,
    ))
    assertEquals(selection, PlaybackStatePersistence.decodeSelection(PlaybackStatePersistence.encodeSelection(selection)))
    assertNull(PlaybackStatePersistence.decodeSelection("not-json"))
    assertNull(PlaybackStatePersistence.decodeSelection(null))
  }
}
