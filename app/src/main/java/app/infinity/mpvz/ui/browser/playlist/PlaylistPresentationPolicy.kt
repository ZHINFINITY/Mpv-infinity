/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.browser.playlist

/** Pure, host-JVM-testable policies for playlist presentation. */
object PlaylistPresentationPolicy {
  const val FILTER_ALL = "__m3u_all__"
  const val FILTER_FAVORITES = "__m3u_favorites__"

  enum class EmptyKind { FILTERED, RADIO, TV, SONGS, VIDEOS }

  fun emptyKind(
    hasUnfilteredItems: Boolean,
    hasActiveSearch: Boolean,
    hasCategoryFilter: Boolean,
    isRadio: Boolean,
    isM3u: Boolean,
    isAudio: Boolean,
  ): EmptyKind = when {
    hasUnfilteredItems || hasActiveSearch || hasCategoryFilter -> EmptyKind.FILTERED
    isRadio -> EmptyKind.RADIO
    isM3u -> EmptyKind.TV
    isAudio -> EmptyKind.SONGS
    else -> EmptyKind.VIDEOS
  }

  fun <T> filterPlaylists(items: List<T>, query: String, nameOf: (T) -> String): List<T> {
    val normalized = query.trim()
    return if (normalized.isEmpty()) items else items.filter { nameOf(it).contains(normalized, ignoreCase = true) }
  }

  fun <T> splitByKind(items: List<T>, isRadioOf: (T) -> Boolean): Pair<List<T>, List<T>> =
    items.partition { !isRadioOf(it) }

  fun <T> filterM3uItems(
    items: List<T>,
    selectedFilter: String,
    categoryOf: (T) -> String?,
    isFavoriteOf: (T) -> Boolean,
  ): List<T> = when (selectedFilter) {
    FILTER_ALL -> items
    FILTER_FAVORITES -> items.filter(isFavoriteOf)
    else -> items.filter { categoryOf(it) == selectedFilter }
  }

  /** Counts categories in one pass; preserves the supplied category order exactly. */
  fun <T> categoryCounts(items: List<T>, categories: List<String>, categoryOf: (T) -> String?): Map<String, Int> {
    val counts = HashMap<String, Int>(categories.size)
    items.forEach { item -> categoryOf(item)?.let { category -> counts[category] = (counts[category] ?: 0) + 1 } }
    return categories.associateWith { counts[it] ?: 0 }
  }
}
