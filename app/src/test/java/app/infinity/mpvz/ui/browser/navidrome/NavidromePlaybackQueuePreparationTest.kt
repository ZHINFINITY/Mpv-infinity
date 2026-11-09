/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.navidrome

import java.io.File
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
}
