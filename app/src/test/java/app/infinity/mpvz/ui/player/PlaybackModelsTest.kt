package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
