/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.browser.music

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicThumbnailDefaultsTest {
  @Test
  fun defaultIs100AndCompactRowsBoundWithoutChangingRequestedPreference() {
    assertEquals(100, MusicThumbnailDefaults.DEFAULT_DP)
    assertEquals(100, MusicThumbnailDefaults.boundedForList(100, 320))
    assertEquals(84, MusicThumbnailDefaults.boundedForList(240, 240))
    assertEquals(240,  MusicThumbnailDefaults.boundedForList(240, 1000))
  }

  @Test
  fun realMusicRoutesReadTheSharedPreferenceAndDoNotResetSavedValues() {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val browserPreferences = File(root, "app/src/main/java/app/infinity/mpvz/preferences/BrowserPreferences.kt").readText()
    val library = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/music/MusicLibraryContent.kt").readText()
    val navidrome = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/navidrome/NavidromeMusicView.kt").readText()

    assertTrue(browserPreferences.contains("getInt(\"music_cover_art_size\", MusicThumbnailDefaults.DEFAULT_DP)"))
    assertTrue(!browserPreferences.contains("music_cover_art_size\", 48"))
    assertTrue(library.contains("browserPreferences.musicCoverArtSize.collectAsState()"))
    assertTrue(library.contains("coverArtSizeDp = coverArtSizeDp"))
    assertTrue(navidrome.contains("browserPreferences.musicCoverArtSize.collectAsState()"))
    assertTrue(library.contains("browserPreferences.musicCoverArtSize.collectAsState()"))
    assertTrue(library.contains("coverArtSizeDp = coverArtSizeDp"))
    assertTrue(navidrome.contains("browserPreferences.musicCoverArtSize.collectAsState()"))
    val rows = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/music/SharedMusicComponents.kt").readText()
    assertTrue(rows.contains("val itemArtSize = boundedSizeDp.dp"))
    assertTrue(rows.contains("MusicThumbnailDefaults.boundedForList"))
  }
}
