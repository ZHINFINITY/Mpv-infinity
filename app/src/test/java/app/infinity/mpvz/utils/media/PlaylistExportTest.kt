/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.utils.media

import app.infinity.mpvz.database.entities.PlaylistItemEntity
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistExportTest {
  @Test
  fun assembleUsesDatabaseOrderAndPreservesM3uMetadata() {
    val items = listOf(
      PlaylistItemEntity(
        playlistId = 1, filePath = "https://example.test/second", fileName = "Second",
        position = 1, addedAt = 0, tvgId = "two", tvgLogo = "https://example.test/two.png",
        groupTitle = "News", licenseType = "widevine", licenseKey = "https://example.test/key",
        userAgent = "Test Agent",
      ),
      PlaylistItemEntity(
        playlistId = 1, filePath = "https://example.test/first", fileName = "First",
        position = 0, addedAt = 0, tvgId = "one", groupTitle = "News",
      ),
    )

    val output = PlaylistExport.assemble("Channels", items)

    assertTrue(output.indexOf("https://example.test/first") < output.indexOf("https://example.test/second"))
    assertTrue(output.contains("tvg-id=\"two\""))
    assertTrue(output.contains("#KODIPROP:inputstream.adaptive.license_type=widevine"))
    assertTrue(output.contains("#EXTVLCOPT:http-user-agent=Test Agent"))
  }

  @Test
  fun fileNameSanitizesPathSeparatorsAndAddsExtension() {
    assertTrue(PlaylistExport.fileName(" News/Live: HD ") == "News_Live_ HD.m3u")
    assertTrue(PlaylistExport.fileName("playlist.m3u") == "playlist.m3u")
    assertTrue(PlaylistExport.fileName("...") == "playlist.m3u")
  }
}
