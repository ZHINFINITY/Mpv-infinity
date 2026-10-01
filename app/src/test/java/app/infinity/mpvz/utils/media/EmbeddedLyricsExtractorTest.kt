package app.infinity.mpvz.utils.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedLyricsExtractorTest {
  @Test
  fun recognizesHttpAndHttpsMediaPathsCaseInsensitively() {
    assertTrue(isRemoteHttpMediaPath("http://example.test/video.mkv"))
    assertTrue(isRemoteHttpMediaPath("HTTPS://example.test/video.mkv"))
  }

  @Test
  fun leavesLocalFilesAndContentUrisEligibleForEmbeddedExtraction() {
    assertFalse(isRemoteHttpMediaPath("/storage/emulated/0/Music/local.mp3"))
    assertFalse(isRemoteHttpMediaPath("file:///storage/emulated/0/Music/local.mp3"))
    assertFalse(isRemoteHttpMediaPath("content://media/external/audio/media/42"))
  }
}
