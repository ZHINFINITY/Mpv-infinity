/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the UI wiring of the features restored for 2.2.0 so a later revert fails loudly. */
class RestoredFeatureWiringContractTest {
  private fun source(relative: String): String {
    val path = "src/main/java/app/infinity/mpvz/$relative"
    return listOf(File(path), File("app/$path")).first { it.exists() }.readText()
  }

  @Test
  fun globalSearchResultsHaveTheirOwnLongPressSelectionAndActions() {
    val s = source("ui/browser/folderlist/FolderListScreen.kt")
    assertTrue(s.contains("val searchSelectionManager ="))
    assertTrue(s.contains("onVideoLongClick = { video -> searchSelectionManager.handleLongClick(video) }"))
    // Grid and list layouts each render search videos; both must select on long press.
    assertEquals(2, Regex(Regex.escape("isSelected = selectionManager.isSelected(video)")).findAll(s).count())
    assertEquals(2, Regex(Regex.escape("onLongClick = { onVideoLongClick(video) }")).findAll(s).count())
    assertTrue(s.contains("GlobalSearchSelectionPolicy.shouldClear("))
    assertTrue(s.contains("showAddToPlaylist = effectiveIsSearching"))
    assertTrue(s.contains("pendingDeleteSearchVideos = searchSelectionManager.getSelectedItems()"))
    assertTrue(s.contains("searchSelectionManager.renameSelected(name)"))
  }

  @Test
  fun playlistDetailOffersExportAndShareThroughTheGlassMenu() {
    val s = source("ui/browser/playlist/PlaylistDetailScreen.kt")
    assertTrue(s.contains("R.string.playlist_export_m3u"))
    assertTrue(s.contains("R.string.playlist_share_m3u"))
    assertTrue(s.contains("GlassDropdownMenu("))
    assertFalse("export menu must follow Liquid Glass", Regex("""(?<!Glass)DropdownMenu\(""").containsMatchIn(s))
  }

  @Test
  fun openAiBaseUrlSettingValidatesBeforeSaving() {
    val s = source("ui/preferences/AiIntegrationScreen.kt")
    assertTrue(s.contains("OpenAiBaseUrlInput.normalize(value)"))
    assertTrue(s.contains("preferences.openaiBaseUrl.set(normalized)"))
    assertTrue(s.contains("R.string.pref_openai_base_url_invalid"))
    assertTrue(source("repository/ai/OpenAiClient.kt").contains("preferences.openaiBaseUrl.get()"))
  }
}
