package app.infinity.mpvz.utils.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3URadioParserTest {
  @Test
  fun keepsLiveDurationsGroupsAndExtensionlessRadioUrls() {
    val result = M3UParser.parseContent(
      """
      #EXTM3U
      # ===== CLASSICAL =====
      #EXTINF:-1 group-title="Classical",Sveriges Radio P2 - Classical
      https://live1.sr.se/p2-flac
      #EXTINF:-1 group-title="Radio Paradise",Radio Paradise - Main Mix (metadata)
      http://stream.radioparadise.com/flacm
      """.trimIndent(),
    ) as M3UParseResult.Success
    assertEquals(2, result.items.size)
    assertEquals("Sveriges Radio P2 - Classical", result.items[0].title)
    assertEquals("Classical", result.items[0].groupTitle)
    assertEquals(-1, result.items[0].duration)
    assertEquals("http://stream.radioparadise.com/flacm", result.items[1].url)
  }

  @Test
  fun rejectsHtmlFileViewRatherThanImportingMarkupAsStations() {
    assertTrue(M3UParser.parseContent("<!DOCTYPE html>\n<html>file viewer</html>") is M3UParseResult.Error)
  }
}
