/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.jellyfin

import app.infinity.mpvz.domain.jellyfin.JellyfinItem
import app.infinity.mpvz.domain.jellyfin.JellyfinQueryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinLibraryCountPolicyTest {
  @Test
  fun classifiesMovieAndSeriesLibrariesButNotOtherCollections() {
    assertEquals("Movie", JellyfinLibraryCountPolicy.itemTypeFor(library("Movies", collectionType = "movies")))
    assertEquals("Series", JellyfinLibraryCountPolicy.itemTypeFor(library("TV", collectionType = "tvshows")))
    assertEquals("Series", JellyfinLibraryCountPolicy.itemTypeFor(library("Anime")))
    assertNull(JellyfinLibraryCountPolicy.itemTypeFor(library("Music", collectionType = "music")))
  }

  @Test
  fun usesServerTotalAndDoesNotInventCountsWhenTotalIsUnknown() {
    assertEquals(275, JellyfinLibraryCountPolicy.countFrom(JellyfinQueryResult(items = listOf(item()), totalRecordCount = 275, startIndex = 0)))
    assertNull(JellyfinLibraryCountPolicy.countFrom(JellyfinQueryResult(items = listOf(item()), totalRecordCount = 0, startIndex = 0)))
    assertEquals(0, JellyfinLibraryCountPolicy.countFrom(JellyfinQueryResult(items = emptyList(), totalRecordCount = 0, startIndex = 0)))
    assertNull(JellyfinLibraryCountPolicy.countFrom(null))
  }

  private fun library(name: String, collectionType: String? = null) =
    JellyfinItem(id = name, name = name, type = "CollectionFolder", collectionType = collectionType)

  private fun item() = JellyfinItem(id = "item", name = "Example", type = "Movie")
}
