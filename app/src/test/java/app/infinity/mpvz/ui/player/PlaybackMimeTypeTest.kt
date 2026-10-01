package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackMimeTypeTest {
  @Test
  fun intentMimeTypeTakesPrecedence() {
    assertEquals("video/x-mpegurl", resolvePlaybackMimeType("video/x-mpegurl", "audio/mpeg"))
  }

  @Test
  fun metadataMimeTypeIsUsedWhenIntentTypeIsMissingOrBlank() {
    assertEquals("audio/mpeg", resolvePlaybackMimeType(null, "audio/mpeg"))
    assertEquals("audio/mpeg", resolvePlaybackMimeType("  ", " audio/mpeg "))
  }

  @Test
  fun blankMimeTypesResolveToNull() {
    assertNull(resolvePlaybackMimeType(" ", null))
    assertNull(resolvePlaybackMimeType(null, " "))
  }
}
