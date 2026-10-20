/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.catalog

import android.content.Context

internal object StreamWatchHistory {
  const val PREFERENCES_NAME = "stream_watch_history"
  const val PROGRESS_PREFERENCES_NAME = "stream_watch_progress"
  const val ORDER_KEY = "order"
  const val KEYS_KEY = "keys"
  private const val HISTORY_LIMIT = 30

  fun keysForDisplay(
    enabled: Boolean,
    order: String,
    fallbackKeys: Set<String>,
  ): List<String> {
    if (!enabled) return emptyList()
    return order
      .split('|')
      .filter { it.isNotBlank() }
      .ifEmpty { fallbackKeys.toList() }
  }

  fun aliasesForItem(
    enabled: Boolean,
    launchKey: String?,
    itemIdentifier: String,
  ): List<String> {
    if (!enabled || itemIdentifier.isBlank()) return emptyList()
    return listOfNotNull(launchKey?.takeIf { it.isNotBlank() && it == itemIdentifier })
  }

  fun fallbackKeysAfterPlayback(ordered: List<String>): Set<String> =
    ordered.take(HISTORY_LIMIT).toSet()

  fun orderAfterPlayback(
    enabled: Boolean,
    currentOrder: String,
    key: String,
  ): List<String>? {
    if (!enabled || key.isBlank()) return null
    val previous = currentOrder.split('|').filter { it.isNotBlank() && it != key }
    return (listOf(key) + previous).take(HISTORY_LIMIT)
  }

  fun clear(context: Context) {
    context.applicationContext
      .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .edit()
      .remove(KEYS_KEY)
      .remove(ORDER_KEY)
      .commit()
      .also { check(it) { "Unable to clear stream history" } }
  }
}
