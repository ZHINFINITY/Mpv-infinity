package app.infinity.mpvz.utils.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFilenameMatcherTest {
  @Test
  fun acceptsLanguageAndTrackSuffixesIncludingGreek() {
    assertTrue(SubtitleFilenameMatcher.matches("My Video.el.srt", "My Video.mp4"))
    assertTrue(SubtitleFilenameMatcher.matches("Μάθημα.el.srt", "Μάθημα.mkv"))
    assertTrue(SubtitleFilenameMatcher.matches("My Video_el.srt", "My Video.mp4"))
    assertTrue(SubtitleFilenameMatcher.matches("My Video.en-US.forced.srt", "My Video.mp4"))
    assertTrue(SubtitleFilenameMatcher.matches("My Video-Greek.srt", "My Video.mp4"))
  }

  @Test
  fun acceptsExactBasenameAndRejectsDifferentVideoPrefix() {
    assertTrue(SubtitleFilenameMatcher.matches("episode.srt", "episode.webm"))
    assertFalse(SubtitleFilenameMatcher.matches("episode2.el.srt", "episode.mp4"))
    assertFalse(SubtitleFilenameMatcher.matches("other.el.srt", "episode.mp4"))
  }

  @Test
  fun rejectsArbitrarySuffixesAndNumericLookalikes() {
    assertFalse(SubtitleFilenameMatcher.matches("episode.comment.srt", "episode.mp4"))
    assertFalse(SubtitleFilenameMatcher.matches("episode-2.srt", "episode.mp4"))
    assertFalse(SubtitleFilenameMatcher.matches("episode.notes.srt", "episode.mp4"))
  }
}
