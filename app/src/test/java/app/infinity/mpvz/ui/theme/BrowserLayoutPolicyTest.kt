/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLayoutPolicyTest {
  private val sourceRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .map { File(it, "app/src/main/java/app/infinity/mpvz") }.first { it.isDirectory }
  private fun source(path: String) = File(sourceRoot, path).readText()
  private fun function(source: String, name: String): String =
    source.substringAfter("private fun $name(").substringBefore("\n}")

  @Test fun compatibilityGeometryDoesNotResizeWhenGlassChanges() {
    for (enabled in listOf(false, true)) {
      val geometry = BrowserLayoutPolicy.geometry(glassEnabled = enabled)
      assertEquals(8f, geometry.edgeInset.value, 0f)
      assertEquals(4f, geometry.gridGap.value, 0f)
      assertEquals(0f, geometry.listGap.value, 0f)
    }
  }

  @Test fun musicRowsOwnTheirReferenceInsetWithoutNewParentOwnedCards() {
    val padding = BrowserLayoutPolicy.musicRowPadding(parentOwnsSpacing = false)
    assertEquals(16f, padding.calculateLeftPadding(LayoutDirection.Ltr).value, 0f)
    assertEquals(12f, padding.calculateTopPadding().value, 0f)
    val parentOwned = BrowserLayoutPolicy.musicRowPadding(parentOwnsSpacing = true)
    assertEquals(0f, parentOwned.calculateLeftPadding(LayoutDirection.Ltr).value, 0f)
    val rows = source("ui/browser/music/SharedMusicComponents.kt")
    assertFalse(rows.contains("parentOwnsSpacing"))
    assertTrue(rows.contains(".padding(horizontal = 16.dp, vertical = 12.dp)"))
    assertTrue(rows.contains("MusicThumbnailDefaults.boundedForList("))
  }

  @Test fun actualFolderAndVideoCallersKeepTheirDifferentReferenceGridGaps() {
    val video = source("ui/browser/videolist/VideoListScreen.kt")
    assertTrue(video.contains("Arrangement.spacedBy(4.dp)"))
    assertFalse(video.contains("BrowserLayoutPolicy"))
    val folders = source("ui/browser/folderlist/FolderListScreen.kt")
    val grid = function(folders, "GridContent")
    assertTrue(grid.contains("val itemSpacing = 2.dp"))
    assertTrue(grid.contains("horizontalArrangement = Arrangement.spacedBy(2.dp)"))
    assertTrue(grid.contains("verticalArrangement = Arrangement.spacedBy(2.dp)"))
    val rows = function(folders, "ListContent")
    assertTrue(rows.contains("start = 8.dp") && rows.contains("end = 8.dp"))
    assertFalse(rows.contains("verticalArrangement = Arrangement.spacedBy"))
    assertTrue(function(folders, "SearchResultsContent").contains("Arrangement.spacedBy(4.dp)"))
    assertFalse(folders.contains("BrowserLayoutPolicy"))
  }

  @Test fun songsAlbumsArtistsAndPlaylistsKeepReferenceListInsets() {
    val music = source("ui/browser/music/MusicLibraryContent.kt")
    for (name in listOf("SongsTabContent", "AlbumsTabContent", "ArtistsTabContent", "PlaylistsTabContent")) {
      val route = function(music, name)
      assertTrue(name, route.contains("PaddingValues(start = 0.dp, top = 8.dp, end = 32.dp"))
      assertFalse(name, route.contains("parentOwnsSpacing"))
      assertFalse(name, route.contains("BrowserLayoutPolicy"))
    }
  }

  @Test fun actualMusicPopupsKeepLatestReadableIndependentSurface() {
    val music = source("ui/browser/music/MusicLibraryContent.kt")
    for (name in listOf("AlbumDetailSheet", "ArtistDetailSheet", "CreatePlaylistDialog")) {
      val popup = function(music, name)
      assertTrue(name, popup.contains("settingsGlassSurface(cornerRadius = 28.dp, independent = true)"))
      assertTrue(name, popup.contains("containerColor = settingsGlassContainerColor("))
    }
    for (state in listOf("selectedSongForOptions", "selectedAlbumForOptions", "selectedArtistForOptions", "selectedPlaylistForOptions")) {
      val popup = music.substringAfter("onDismissRequest = { $state = null },").substringBefore("sheetState =")
      assertTrue(state, popup.contains("independent = true"))
      assertTrue(state, popup.contains("containerColor = settingsGlassContainerColor("))
    }
  }
}
