/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLayoutPolicyTest {
  private val sourceRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .map { File(it, "app/src/main/java/app/infinity/mpvz") }.first { it.isDirectory }

  private fun source(path: String) = File(sourceRoot, path).readText()
  private fun function(source: String, name: String): String =
    source.substringAfter("private fun $name(").substringBefore("\n}")

  @Test fun sharedGeometryPreservesClassicAndSeparatesGlassPanes() {
    val classic = BrowserLayoutPolicy.geometry(glassEnabled = false)
    assertEquals(8f, classic.edgeInset.value, 0f)
    assertEquals(4f, classic.gridGap.value, 0f)
    assertEquals(0f, classic.listGap.value, 0f)
    val glass = BrowserLayoutPolicy.geometry(glassEnabled = true)
    assertEquals(12f, glass.edgeInset.value, 0f)
    assertEquals(12f, glass.gridGap.value, 0f)
    assertEquals(12f, glass.listGap.value, 0f)
  }

  @Test fun musicRowsAndParentListsNeverBothAddOuterGaps() {
    val classic = BrowserLayoutPolicy.musicRowPadding(parentOwnsSpacing = false)
    assertEquals(12f, classic.calculateLeftPadding(LayoutDirection.Ltr).value, 0f)
    assertEquals(6f, classic.calculateTopPadding().value, 0f)
    val parentOwned = BrowserLayoutPolicy.musicRowPadding(parentOwnsSpacing = true)
    assertEquals(0f, parentOwned.calculateLeftPadding(LayoutDirection.Ltr).value, 0f)
    assertEquals(0f, parentOwned.calculateTopPadding().value, 0f)
    val rows = source("ui/browser/music/SharedMusicComponents.kt")
    assertTrue(rows.contains("parentOwnsSpacing: Boolean = false"))
    assertTrue(rows.contains(".padding(BrowserLayoutPolicy.musicRowPadding(parentOwnsSpacing))"))
  }

  @Test fun actualFolderVideoGridAndListBranchesUseThePolicy() {
    val video = source("ui/browser/videolist/VideoListScreen.kt")
    assertTrue(video.contains("Arrangement.spacedBy(geometry.gridGap)"))
    assertTrue(video.contains("Arrangement.spacedBy(geometry.listGap)"))
    val folders = source("ui/browser/folderlist/FolderListScreen.kt")
    assertTrue(function(folders, "GridContent").contains("if (glassEnabled) geometry.gridGap else 12.dp"))
    assertTrue(function(folders, "ListContent").contains("if (glassEnabled) geometry.listGap else 12.dp"))
    assertTrue(function(folders, "SearchResultsContent").contains("if (glassEnabled) geometry.gridGap else 10.dp"))
  }

  @Test fun songsAlbumsArtistsAndPlaylistsEachWireTheRealListBranch() {
    val music = source("ui/browser/music/MusicLibraryContent.kt")
    for (name in listOf("SongsTabContent", "AlbumsTabContent", "ArtistsTabContent", "PlaylistsTabContent")) {
      val route = function(music, name)
      assertTrue(name, route.contains("BrowserLayoutPolicy.listPadding"))
      assertTrue(name, route.contains("Arrangement.spacedBy(BrowserLayoutPolicy.geometry(true).listGap)"))
      assertTrue("classic inset retained for $name", route.contains("PaddingValues(start = 0.dp, top = 8.dp, end = 32.dp"))
    }
    for (name in listOf("SongsTabContent", "AlbumsTabContent", "ArtistsTabContent")) {
      assertTrue(name, function(music, name).contains("parentOwnsSpacing = glassEnabled"))
    }
  }

  @Test fun actualMusicPopupsUseOneReadableOuterSurface() {
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
