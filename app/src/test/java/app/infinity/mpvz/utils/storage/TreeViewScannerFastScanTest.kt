/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.utils.storage

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeViewScannerFastScanTest {
  @Test
  fun fastScanCountsDirectMediaAndKeepsNestedMediaReachable() = runBlocking {
    val tempRoot = Files.createTempDirectory("mpv-fast-folder-scan").toFile()
    try {
      val usbRoot = File(tempRoot, "USB").apply { mkdirs() }
      File(usbRoot, "Season 1").apply { mkdirs() }
        .resolve("episode.mp4").writeBytes(byteArrayOf(1))
      File(usbRoot, "Season 2/Episodes").apply { mkdirs() }
        .resolve("nested.mkv").writeBytes(byteArrayOf(1))
      File(usbRoot, ".hidden").apply { mkdirs() }
        .resolve("hidden.mp4").writeBytes(byteArrayOf(1))

      val folders =
        TreeViewScanner.getFoldersInDirectoryFast(
          parentPath = usbRoot.absolutePath,
          maxAutoFlattenLevels = 0,
        )

      assertEquals(listOf("Season 1", "Season 2"), folders.map { it.name })
      assertEquals(1, folders.first { it.name == "Season 1" }.videoCount)
      val nested = folders.first { it.name == "Season 2" }
      assertEquals(0, nested.videoCount)
      assertTrue(nested.hasSubfolders)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun fastScanFlattensOnlyWithinTheConfiguredSingleChildLimit() = runBlocking {
    val tempRoot = Files.createTempDirectory("mpv-fast-folder-flatten").toFile()
    try {
      val usbRoot = File(tempRoot, "USB").apply { mkdirs() }
      File(usbRoot, "Series/Season 1").apply { mkdirs() }
        .resolve("episode.mkv").writeBytes(byteArrayOf(1))

      val oneLevel =
        TreeViewScanner.getFoldersInDirectoryFast(
          parentPath = usbRoot.absolutePath,
          maxAutoFlattenLevels = 1,
        )
      val noFlatten =
        TreeViewScanner.getFoldersInDirectoryFast(
          parentPath = usbRoot.absolutePath,
          maxAutoFlattenLevels = 0,
        )

      assertEquals("Season 1", oneLevel.single().name)
      assertEquals("Series", noFlatten.single().name)
    } finally {
      tempRoot.deleteRecursively()
    }
  }
}
