package app.infinity.mpvz.domain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarSubtitlePolicyTest {
  private val extensions = setOf("srt", "ass", "vtt")

  @Test
  fun ownershipUsesExactVideoBasenameNotLoosePrefix() {
    assertTrue(owns("Movie.mkv", "Movie.srt"))
    assertTrue(owns("Movie.mkv", "Movie.en.srt"))
    assertTrue(owns("Movie.mkv", "Movie.en.forced.srt"))
    assertFalse(owns("Movie.mkv", "Movie2.en.srt"))
    assertFalse(owns("Movie.mkv", "Movie 2.en.srt"))
    assertFalse(owns("Movie.mkv", "Movie.2.srt"))
    assertFalse(owns("Movie.mkv", "Movie.backup.srt"))
  }

  @Test
  fun normalAndForcedTracksHaveDistinctNamesInEitherOrder() {
    val normal = SidecarSubtitlePolicy.sidecarFileName("Movie.mkv", "en", "English", false, 0, "srt")
    val forced = SidecarSubtitlePolicy.sidecarFileName("Movie.mkv", "en", "English", true, 1, "srt")
    assertEquals("Movie.en.srt", normal)
    assertEquals("Movie.en.forced.srt", forced)
    assertEquals(normal, SidecarSubtitlePolicy.sidecarFileName("Movie.mkv", "en", "English", false, 1, "srt"))
    assertEquals(forced, SidecarSubtitlePolicy.sidecarFileName("Movie.mkv", "en", "English", true, 0, "srt"))
  }

  @Test
  fun preservesSubtitleExtensionAndRecognizesRoleNames() {
    assertEquals("Movie.eng.forced.ass", SidecarSubtitlePolicy.sidecarFileName("Movie.mp4", "eng", "English", true, 0, "ass"))
    assertTrue(owns("Movie.mp4", "Movie.eng.forced.ass"))
    assertTrue(owns("Movie.mp4", "Movie.en.vtt"))
    assertFalse(owns("Movie.mp4", "Movie.en.normal.srt"))
  }

  private fun owns(video: String, candidate: String): Boolean =
    SidecarSubtitlePolicy.belongsToVideo(video, candidate, extensions)
}
