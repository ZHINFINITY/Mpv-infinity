/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser.music

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the actual music-library callbacks and detail surfaces against opaque glass bypasses. */
class MusicGlassCallersContractTest {
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  private fun source(path: String) = File(root, path).readText()

  @Test
  fun actualArtistAlbumAndTrackEntryPointsUseConditionalGlass() {
    val library = source("app/src/main/java/app/infinity/mpvz/ui/browser/music/MusicLibraryContent.kt")
    val shared = source("app/src/main/java/app/infinity/mpvz/ui/browser/music/SharedMusicComponents.kt")

    // These are the actual callbacks from the songs/albums/artists pager tabs.
    assertTrue(library.contains("musicViewModel.playSong(context, song, songs)"))
    assertTrue(library.contains("musicViewModel.selectAlbum(album)"))
    assertTrue(library.contains("musicViewModel.selectArtist(artist)"))
    assertTrue(library.contains("AlbumDetailSheet("))
    assertTrue(library.contains("ArtistDetailSheet("))

    // Every card family and the shared detail-header seam owns a glass pane.
    assertTrue(library.contains("private fun SongGridCard") && library.contains(".settingsGlassSurface(cornerRadius = 20.dp)"))
    assertTrue(library.contains("private fun AlbumGridCard"))
    assertTrue(library.contains("private fun ArtistGridCard"))
    assertTrue(shared.contains("fun SharedMusicTrackListItem") && shared.contains("fun SharedMusicGridCard"))
    assertTrue(shared.contains("fun SharedMusicDetailHeader") && shared.contains(".settingsGlassSurface(cornerRadius = 20.dp)"))

    // Detail sheets own the independent pane. Assert within each real entry-point
    // scope so formatting/parameter additions do not turn this into a literal-wrapper test.
    fun body(name: String, next: String): String =
      library.substringAfter("private fun $name(").substringBefore("private fun $next(")
    val albumDetail = body("AlbumDetailSheet", "ArtistDetailSheet")
    val artistDetail = library.substringAfter("private fun ArtistDetailSheet(")
    for (detail in listOf(albumDetail, artistDetail)) {
      assertTrue(detail.contains("settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerLow)"))
      assertTrue(detail.contains("settingsGlassSurface(cornerRadius = 28.dp, independent = true)"))
    }
  }

  @Test
  fun glassOnRemovesUnintentionalMaterialBackplatesButOffPreservesFallbacks() {
    val library = source("app/src/main/java/app/infinity/mpvz/ui/browser/music/MusicLibraryContent.kt")
    val shared = source("app/src/main/java/app/infinity/mpvz/ui/browser/music/SharedMusicComponents.kt")

    assertTrue(library.contains("settingsGlassContainerColor(when"))
    assertTrue(library.contains("settingsGlassContainerColor(if (isSelected)"))
    assertTrue(shared.contains("settingsGlassContainerColor(when"))
    assertTrue(shared.contains("settingsGlassContainerColor(if (isSelected)"))
    assertTrue(library.contains("containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerLow)"))
    assertTrue(shared.contains(".background(settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceVariant))"))
    assertTrue(shared.contains("contentScale = ContentScale.Crop"))
  }
}
