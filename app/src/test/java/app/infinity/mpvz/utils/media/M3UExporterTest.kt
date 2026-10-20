package app.infinity.mpvz.utils.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class M3UExporterTest {
  @Test
  fun exportKeepsPlaybackDirectivesAndUrls() {
    val output = M3UExporter.export(
      listOf(
        M3UPlaylistItem(
          url = "rtsp://camera.local/live",
          title = "Front \"door\"",
          duration = 0,
          tvgId = "front",
          tvgLogo = "https://example/logo.png",
          groupTitle = "Cameras",
          licenseType = "com.widevine.alpha",
          licenseKey = "https://license.example/key",
          userAgent = "Camera Client/1.0",
        ),
      ),
      playlistName = "Home cameras",
    )

    assertTrue(output.startsWith("#EXTM3U name=\"Home cameras\"\n"))
    assertTrue(output.contains("#EXTINF:0 tvg-id=\"front\" tvg-logo=\"https://example/logo.png\" group-title=\"Cameras\",Front \"door\"\n"))
    assertTrue(output.contains("#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha\n"))
    assertTrue(output.contains("#KODIPROP:inputstream.adaptive.license_key=https://license.example/key\n"))
    assertTrue(output.contains("#EXTVLCOPT:http-user-agent=Camera Client/1.0\nrtsp://camera.local/live\n"))
  }

  @Test
  fun exportRoundTripsSupportedFieldsThroughParser() {
    val item = M3UPlaylistItem(
      url = "https://example.com/live.m3u8|User-Agent=Camera Client/1.0",
      title = "Front door",
      duration = 42,
      tvgId = "front",
      tvgName = "Front door",
      tvgLogo = "https://example.com/logo.png",
      groupTitle = "Cameras",
      licenseType = "com.widevine.alpha",
      licenseKey = "https://license.example/key",
      userAgent = "Camera Client/1.0",
    )

    val result = M3UParser.parseContent(M3UExporter.export(listOf(item)))

    assertTrue(result is M3UParseResult.Success)
    assertEquals(item, (result as M3UParseResult.Success).items.single())
  }

  @Test
  fun exportRejectsLineBreaksInMetadataAndUrlInsteadOfInjectingLines() {
    val fields = listOf<(M3UPlaylistItem) -> M3UPlaylistItem>(
      { it.copy(title = "title\n#EXTINF:999") },
      { it.copy(tvgName = "name\r#EXTINF:999") },
      { it.copy(licenseKey = "key\n#EXTVLCOPT:http-user-agent=evil") },
      { it.copy(userAgent = "agent\r#KODIPROP:injected=value") },
      { it.copy(url = "https://example.com/live\n#EXTINF:999") },
    )
    val item = M3UPlaylistItem(url = "https://example.com/live", title = "Live")

    fields.forEach { mutate ->
      assertThrows(IllegalArgumentException::class.java) {
        M3UExporter.export(listOf(mutate(item)))
      }
    }
  }
}
