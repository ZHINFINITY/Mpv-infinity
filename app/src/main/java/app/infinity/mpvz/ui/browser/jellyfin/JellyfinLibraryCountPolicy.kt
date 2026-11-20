/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.jellyfin

import app.infinity.mpvz.domain.jellyfin.JellyfinItem
import app.infinity.mpvz.domain.jellyfin.JellyfinQueryResult

/** Separates movie/series library counts from bounded home-rail item lists. */
internal object JellyfinLibraryCountPolicy {
  fun itemTypeFor(library: JellyfinItem): String? {
    val collectionType = library.collectionType?.trim()?.lowercase().orEmpty()
    val type = library.type.trim().lowercase()
    val name = library.name.trim().lowercase()
    return when {
      collectionType == "movies" || type == "movie" || name.contains("movie") || name.contains("film") -> "Movie"
      collectionType in setOf("tvshows", "series") || type == "series" ||
        listOf("show", "series", "tv", "anime", "drama").any(name::contains) -> "Series"
      else -> null
    }
  }

  /** A missing total is unknown unless the first page is empty, which proves a zero-item library. */
  fun countFrom(result: JellyfinQueryResult?): Int? {
    result ?: return null
    return result.totalRecordCount.takeIf { it > 0 }
      ?: if (result.items.isEmpty()) 0 else null
  }
}
