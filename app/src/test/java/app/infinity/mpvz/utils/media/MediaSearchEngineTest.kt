/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.utils.media

import app.infinity.mpvz.domain.media.model.VideoFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSearchEngineTest {
  @Test
  fun searchFoldersUsesPathAsStableTieBreaker() {
    MediaSearchEngine.buildIndex(
      folders = listOf(
        folder("/storage/emulated/0/Zeta/Folder"),
        folder("/storage/emulated/0/Alpha/Folder"),
      ),
      videosByFolder = emptyMap(),
    )
    val result = MediaSearchEngine.search("folder")
    assertEquals("/storage/emulated/0/Alpha/Folder", (result[0] as VideoFolder).path)
    assertEquals("/storage/emulated/0/Zeta/Folder", (result[1] as VideoFolder).path)
  }

  @Test
  fun nonPositiveLimitDoesNotThrowOrReturnResults() {
    assertTrue(MediaSearchEngine.search("folder", 0).isEmpty())
    assertTrue(MediaSearchEngine.search("folder", -1).isEmpty())
  }

  private fun folder(path: String) = VideoFolder(
    bucketId = path,
    name = "Folder",
    path = path,
    videoCount = 0,
  )
}
