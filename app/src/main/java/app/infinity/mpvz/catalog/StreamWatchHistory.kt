/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
