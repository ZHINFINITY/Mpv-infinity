/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.navidrome

import java.io.File
import app.infinity.mpvz.data.network.ServerUrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavidromePlaybackQueuePreparationTest {
  @Test
  fun largeQueuePreparationRunsOffMainAndUsesPrebuiltPlaybackItems() {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val viewModel = File(root, "app/infinity/mpvz/ui/browser/navidrome/NavidromeViewModel.kt").readText()
    val playTrackList =
      viewModel.substringAfter("private fun playTrackList(")
        .substringBefore("private data class PreparedNavidromeTrack")

    val backgroundDispatcher = playTrackList.indexOf("withContext(Dispatchers.Default)")
    val streamUrlBuild = playTrackList.indexOf("navidromeRepository.getStreamUrl(server, song.id)")
    assertTrue("Navidrome stream URLs must be built off Main", backgroundDispatcher >= 0 && streamUrlBuild > backgroundDispatcher)
    assertTrue(playTrackList.contains("PlaybackItem.fromUri("))
    assertTrue(playTrackList.contains("withContext(Dispatchers.Main.immediate)"))
    assertTrue(playTrackList.contains("preparedQueueItems = preparedQueue.playbackItems"))

    val mediaUtils = File(root, "app/infinity/mpvz/utils/media/MediaUtils.kt").readText()
    assertTrue(mediaUtils.contains("preparedQueueItems.takeIf"))
    assertTrue(mediaUtils.contains("items = queueItems"))
  }

  @Test
  fun initialCatalogPublishesSectionsIndependentlyAndDefersTheFullAlbumList() {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val viewModel = File(root, "app/infinity/mpvz/ui/browser/navidrome/NavidromeViewModel.kt").readText()
    val initialLoad = viewModel.substringAfter("private suspend fun loadAllDataInternal(")
      .substringBefore("private fun loadAlbumsIfNeeded(")
    val albumLoader = viewModel.substringAfter("private fun loadAlbumsIfNeeded(")
      .substringBefore("private fun favoritesPlaylist(")

    assertTrue(initialLoad.contains("sectionJobs.joinAll()"))
    assertTrue(initialLoad.contains("state.copy(jumpBackIn = tracks.take(12), tracks = tracks)"))
    assertFalse("The initial screen must not wait on the 500-album endpoint", initialLoad.contains("size = 500"))
    assertTrue(viewModel.contains("if (tab == NavidromeMusicTab.ALBUMS) loadAlbumsIfNeeded()"))
    assertTrue(albumLoader.contains("type = \"alphabeticalByName\", size = 500"))
    assertTrue(viewModel.contains("activeConnectionChanged"))
  }

  @Test
  fun bareServerHostsAreNormalizedAndNavidromeStreamsBypassYtdlp() {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val client = File(root, "app/infinity/mpvz/data/navidrome/NavidromeClient.kt").readText()
    val ytdlp = File(root, "app/infinity/mpvz/ui/player/ytdlp/YtdlpManager.kt").readText()

    assertEquals("https://demo.navidrome.org", ServerUrlUtils.normalizeUrl("demo.navidrome.org"))
    assertEquals("http://192.168.1.23:4533", ServerUrlUtils.normalizeUrl("192.168.1.23:4533"))
    assertTrue(client.contains("ServerUrlUtils.normalizeUrl(server.serverUrl).trimEnd('/')"))
    assertTrue(client.contains("val cleanBase = normalizedServerUrl(server)"))
    assertTrue(client.contains("${'$'}{normalizedServerUrl(server)}${'$'}coverArtId"))
    assertTrue(ytdlp.contains("/rest/stream%.view[?#]"))
    assertTrue(ytdlp.contains("uri.path?.endsWith(\"/rest/stream.view\", ignoreCase = true) == true"))
    assertTrue(ytdlp.contains("ytdl_hook-exclude=${'$'}DIRECT_MEDIA_EXCLUDE"))
  }
}
