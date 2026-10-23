/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.folderlist

/**
 * Global search owns a selection scope separate from the folder list. The selection is
 * dropped when search ends, or as soon as any selected video is no longer among the
 * visible results, so actions never run on items the user can no longer see.
 */
internal object GlobalSearchSelectionPolicy {
  fun shouldClear(
    isSearching: Boolean,
    selectedIds: Collection<Long>,
    resultIds: Collection<Long>,
  ): Boolean {
    if (!isSearching) return true
    if (selectedIds.isEmpty()) return false
    val visible = resultIds.toHashSet()
    return selectedIds.any { it !in visible }
  }
}
