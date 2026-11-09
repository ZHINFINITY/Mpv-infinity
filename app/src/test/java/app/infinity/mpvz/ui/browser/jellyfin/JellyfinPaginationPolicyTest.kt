/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.jellyfin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinPaginationPolicyTest {
  @Test fun knownTotalUsesServerPageBoundary() {
    assertTrue(JellyfinPaginationPolicy.hasMore(totalRecordCount = 250, startIndex = 0, pageItemCount = 100, pageSize = 100))
    assertTrue(JellyfinPaginationPolicy.hasMore(totalRecordCount = 250, startIndex = 100, pageItemCount = 100, pageSize = 100))
    assertFalse(JellyfinPaginationPolicy.hasMore(totalRecordCount = 250, startIndex = 200, pageItemCount = 50, pageSize = 100))
  }

  @Test fun unknownTotalContinuesOnlyAfterAFullPage() {
    assertTrue(JellyfinPaginationPolicy.hasMore(totalRecordCount = 0, startIndex = 0, pageItemCount = 100, pageSize = 100))
    assertTrue(JellyfinPaginationPolicy.hasMore(totalRecordCount = 0, startIndex = 100, pageItemCount = 100, pageSize = 100))
    assertFalse(JellyfinPaginationPolicy.hasMore(totalRecordCount = 0, startIndex = 200, pageItemCount = 37, pageSize = 100))
    assertFalse(JellyfinPaginationPolicy.hasMore(totalRecordCount = 0, startIndex = 200, pageItemCount = 0, pageSize = 100))
  }

  @Test fun nextOffsetUsesRemotePageSizeNotDeduplicatedItemCount() {
    assertEquals(200, JellyfinPaginationPolicy.nextStartIndex(startIndex = 100, pageItemCount = 100))
    assertEquals(137, JellyfinPaginationPolicy.estimatedTotalRecordCount(totalRecordCount = 0, startIndex = 100, pageItemCount = 37))
    assertEquals(250, JellyfinPaginationPolicy.estimatedTotalRecordCount(totalRecordCount = 250, startIndex = 200, pageItemCount = 50))
  }

  @Test fun listAndGridPaginationObserveChangingItemCounts() {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val content = File(root, "app/infinity/mpvz/ui/browser/jellyfin/JellyfinContent.kt").readText()

    assertTrue(content.contains("remember(listState, items.size)"))
    assertTrue(content.contains("remember(gridState, items.size)"))
    assertTrue(content.contains("LaunchedEffect(shouldLoadMore.value, uiState.hasMore, uiState.isLoading, uiState.isLoadingMore, items.size)"))
  }

  @Test fun libraryViewModelUsesRemotePageCountForUnknownTotals() {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val viewModel = File(root, "app/infinity/mpvz/ui/browser/jellyfin/JellyfinViewModel.kt").readText()
    val loadLibrary = viewModel.substringAfter("private fun loadLibraryItems(").substringBefore("fun loadMoreItems()")

    assertTrue(loadLibrary.contains("JellyfinPaginationPolicy.nextStartIndex(startIndex, queryResult.items.size)"))
    assertTrue(loadLibrary.contains("JellyfinPaginationPolicy.estimatedTotalRecordCount("))
    assertTrue(loadLibrary.contains("JellyfinPaginationPolicy.hasMore("))
    assertTrue(loadLibrary.contains("pageSize = 100"))
  }
}
