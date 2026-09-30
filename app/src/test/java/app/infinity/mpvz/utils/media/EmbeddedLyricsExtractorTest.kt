package app.infinity.mpvz.utils.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedLyricsExtractorTest {
  @Test
  fun recognizesHttpAndHttpsSchemesCaseInsensitively() {
    assertTrue(isRemoteHttpMediaPath("http:"))
    assertTrue(isRemoteHttpMediaPath("HTTPS:"))
  }

  @Test
  fun leavesLocalFilesAndContentUrisEligibleForEmbeddedExtraction() {
    assertFalse(isRemoteHttpMediaPath("/storage/emulated/0/Music/local.mp3"))
    assertFalse(isRemoteHttpMediaPath("file:"))
    assertFalse(isRemoteHttpMediaPath("content:"))
  }
}
