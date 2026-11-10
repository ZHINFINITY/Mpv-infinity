/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.jellyfin

/** Page-boundary rules for Jellyfin servers that may omit TotalRecordCount. */
internal object JellyfinPaginationPolicy {
  fun hasMore(
    totalRecordCount: Int,
    startIndex: Int,
    pageItemCount: Int,
    pageSize: Int,
    pageHasNewItems: Boolean,
  ): Boolean {
    if (!pageHasNewItems || pageItemCount <= 0 || startIndex < 0 || pageSize <= 0) return false
    return if (totalRecordCount > 0) {
      startIndex.toLong() + pageItemCount < totalRecordCount
    } else {
      true
    }
  }

  fun nextStartIndex(startIndex: Int, pageItemCount: Int): Int =
    (startIndex + pageItemCount.coerceAtLeast(0)).coerceAtLeast(startIndex)

  fun estimatedTotalRecordCount(totalRecordCount: Int, startIndex: Int, pageItemCount: Int): Int =
    totalRecordCount.takeIf { it > 0 } ?: nextStartIndex(startIndex, pageItemCount)
}
