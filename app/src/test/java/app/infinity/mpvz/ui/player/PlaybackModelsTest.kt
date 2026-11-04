package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModelsTest {
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
  fun intentMimeTypeRemainsAuthoritativeWhenPresent() {
    assertEquals("video/webm", resolvePlaybackMimeType("video/webm", "video/mp4"))
  }

  @Test
  fun blankMimeTypesAreIgnored() {
    assertEquals("video/mp4", resolvePlaybackMimeType("  ", "video/mp4"))
    assertNull(resolvePlaybackMimeType(null, " "))
  }

  @Test
  fun extensionlessJellyfinVideoStreamCanUseNativeAndAutoDetectsHdr() {
    val uri = "https://media.example/Videos/item-1/stream?static=true&api_key=test-only"
    val item =
      PlaybackItem(
        stableId = "jellyfin-item-1",
        originalUri = uri,
        title = "Episode 1",
        hdrMetadata = true,
      )

    assertTrue(isJellyfinDirectVideoStreamUrl(uri))
    assertEquals(DeclaredPlaybackMediaKind.VIDEO, item.declaredMediaKind())
    assertFalse(item.requiresWebPageResolutionBeforeNative())
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.NATIVE, item))
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, item))
    assertFalse(isJellyfinDirectVideoStreamUrl("https://media.example/Audio/item-1/stream"))
  }

  @Test
  fun jellyfinVideoWildcardDoesNotForceAnExtensionlessDirectStreamToMpv() {
    val item =
      PlaybackItem(
        stableId = "jellyfin-item-2",
        originalUri = "https://media.example/Videos/item-2/stream?static=true",
        title = "Episode 2",
        mimeType = "video/*",
      )

    assertFalse(item.requiresWebPageResolutionBeforeNative())
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.NATIVE, item))
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
