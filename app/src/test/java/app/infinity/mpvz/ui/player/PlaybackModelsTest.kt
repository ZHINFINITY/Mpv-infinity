package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModelsTest {
  @Test
  fun failedLoadPauseKeepsScreenOnButReadyUserPauseStillClearsIt() {
    assertFalse(shouldClearKeepScreenOnForPause(PlaybackPhase.ERROR, keepScreenOnWhenPaused = false))
    assertTrue(shouldClearKeepScreenOnForPause(PlaybackPhase.READY, keepScreenOnWhenPaused = false))
    assertFalse(shouldClearKeepScreenOnForPause(PlaybackPhase.READY, keepScreenOnWhenPaused = true))
  }

  @Test
  fun terminalLoadFailureClearsFlagOnlyWhenPreferenceIsDisabled() {
    assertTrue(shouldClearKeepScreenOnAfterLoadFailure(keepScreenOnWhenPaused = false))
    assertFalse(shouldClearKeepScreenOnAfterLoadFailure(keepScreenOnWhenPaused = true))
  }

  @Test
  fun nativePreferencePassesThroughForExtensionlessStreamVideo() {
    val item =
      PlaybackItem(
        stableId = "stream-item",
        originalUri = "opaque-stream-id",
        title = "Episode 1",
      )

    assertEquals(DeclaredPlaybackMediaKind.UNKNOWN, item.declaredMediaKind())
    assertEquals(DeclaredPlaybackMediaKind.VIDEO, item.declaredMediaKind(videoHint = true))
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.NATIVE, item))
  }

  @Test
  fun knownAudioMetadataOutranksStreamVideoHint() {
    val item =
      PlaybackItem(
        stableId = "stream-audio",
        originalUri = "opaque-audio-id",
        title = "Audio item",
        mimeType = "audio/mp4",
      )

    assertEquals(DeclaredPlaybackMediaKind.AUDIO, item.declaredMediaKind(videoHint = true))
  }

  @Test
  fun autoStillUsesMpvForSdrAndNativeForHdr() {
    val sdrItem =
      PlaybackItem(
        stableId = "stream-sdr",
        originalUri = "opaque-sdr-id",
        title = "Episode 1",
        mimeType = "video/mp4",
      )
    val hdrItem = sdrItem.copy(hdrMetadata = true)

    assertEquals(PlaybackEngineMode.MPV, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, sdrItem))
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, hdrItem))
  }

  @Test
  fun folderNamesDoNotTurnSdrMediaIntoHdr() {
    val item =
      PlaybackItem(
        stableId = "sdr-in-hdr-folder",
        originalUri = "file:///storage/Movies/HDR Projects/Episode 1.mkv",
        title = "Episode 1",
        mimeType = "video/x-matroska",
      )

    assertFalse(item.isHdrOrDolbyVision())
    assertEquals(PlaybackEngineMode.MPV, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, item))
  }

  @Test
  fun unresolvedWebPageStaysOnMpvUntilAPlayableHdrUrlIsAvailable() {
    val page =
      PlaybackItem(
        stableId = "hdr-page",
        originalUri = "https://video.example/watch?id=1",
        title = "HDR episode",
        hdrMetadata = true,
      )
    val resolved =
      page.copy(
        playableUri = "https://cdn.example/episode-hdr.mkv",
        mimeType = "video/x-matroska",
      )

    assertEquals(PlaybackEngineMode.MPV, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, page))
    assertEquals(PlaybackEngineMode.MPV, resolvePlaybackEngineMode(PlaybackEngineMode.NATIVE, page))
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, resolved))
  }

  @Test
  fun streamMimeTypeExtraClassifiesExtensionlessVideo() {
    val mimeType = resolvePlaybackMimeType(intentMimeType = null, mimeTypeExtra = "video/mp4")
    val item =
      PlaybackItem(
        stableId = "stream-item",
        originalUri = "opaque-stream-id",
        title = "Episode 1",
        mimeType = mimeType,
      )

    assertEquals(DeclaredPlaybackMediaKind.VIDEO, item.declaredMediaKind())
  }

  @Test
  fun trustedStreamVideoHintAllowsExtensionlessDirectHttpsUrlOnNative() {
    val item =
      PlaybackItem(
        stableId = "trusted-stream",
        originalUri = "https://cdn.example/live?id=opaque",
        title = "Episode 1",
      )

    assertTrue(item.requiresWebPageResolutionBeforeNative())
    assertFalse(item.requiresWebPageResolutionBeforeNative(videoHint = true))
    assertEquals(PlaybackEngineMode.MPV, resolvePlaybackEngineMode(PlaybackEngineMode.NATIVE, item))
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.NATIVE, item, videoHint = true))
  }

  @Test
  fun resolvedExtensionlessVideoCanUseNativeButUnresolvedWebPageCannot() {
    val page =
      PlaybackItem(
        stableId = "external-page",
        originalUri = "https://video.example/watch?id=opaque",
        title = "Episode 1",
      )
    val resolved = page.copy(playableUri = "https://cdn.example/media?signature=opaque")

    assertEquals(PlaybackEngineMode.MPV, resolvePlaybackEngineMode(PlaybackEngineMode.NATIVE, page))
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.NATIVE, resolved, videoHint = true))
  }

  @Test
  fun intentMimeTypeRemainsAuthoritativeWhenPresent() {
    assertEquals("video/webm", resolvePlaybackMimeType("video/webm", "video/mp4"))
  }

  @Test
  fun blankMimeTypesAreIgnored() {
    assertEquals("video/mp4", resolvePlaybackMimeType("  ", "video/mp4"))
    assertNull(resolvePlaybackMimeType(null, " "))
  }

  @Test
  fun optionalMkvTailProbeIsSkippedOnlyForRemoteHttpSources() {
    assertTrue(shouldSkipRemoteMkvDurationProbe("https://cdn.example/video.mkv"))
    assertTrue(shouldSkipRemoteMkvDurationProbe("http://media.example/video.mkv"))
    assertFalse(shouldSkipRemoteMkvDurationProbe("file:///storage/Movies/video.mkv"))
    assertFalse(shouldSkipRemoteMkvDurationProbe("content://media/external/video/1"))
  }

  @Test
  fun rapidQueueAdvancesKeepCurrentItemAndIndexAligned() {
    val items = listOf(
      PlaybackItem("a", "file:///a", title = "A"),
      PlaybackItem("b", "file:///b", title = "B"),
      PlaybackItem("c", "file:///c", title = "C"),
    )
    var state = PlaybackQueueReducer.replace(PlaybackQueueState(), items, 0, true, false)

    state = PlaybackQueueReducer.next(state)!!
    assertEquals(1, state.currentIndex)
    assertEquals("b", state.currentItem?.stableId)
    state = PlaybackQueueReducer.next(state)!!
    assertEquals(2, state.currentIndex)
    assertEquals("c", state.currentItem?.stableId)
  }
}
