package app.infinity.mpvz.ui.browser.audiobooks

import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookArtworkSourcesTest {
  @Test
  fun storedCoverIsPreferredAndEmbeddedAudioIsTheFallback() {
    val sources = audiobookArtworkSources(
      coverUri = "content://covers/book-cover",
      fallbackTrackUri = "content://audio/first-track",
      fallbackTrackPath = "/books/example/first-track.m4b",
    )

    assertEquals(
      listOf(AudiobookArtworkSourceKind.COVER_URI, AudiobookArtworkSourceKind.EMBEDDED_AUDIO),
      sources.map { it.kind },
    )
    assertEquals("/books/example/first-track.m4b", sources.last().mediaPath)
  }

  @Test
  fun missingStoredCoverStillUsesEmbeddedTrackMetadata() {
    val sources = audiobookArtworkSources(null, "content://audio/first-track")

    assertEquals(1, sources.size)
    assertEquals(AudiobookArtworkSourceKind.EMBEDDED_AUDIO, sources.single().kind)
    assertEquals(emptyList<AudiobookArtworkSource>(), audiobookArtworkSources(" ", null))
  }
}
