package app.infinity.mpvz.utils.history

import android.content.Context
import app.infinity.mpvz.database.entities.RecentlyPlayedEntity

internal data class WatchStatisticsState(
  val count: Int,
  val itemKeyDigests: List<String>,
) {
  fun record(
    itemKey: String,
    aliases: List<String> = emptyList(),
  ): WatchStatisticsState {
    val digests = (listOf(itemKey) + aliases)
      .filter(String::isNotBlank)
      .map(::digestHistoryIdentity)
      .distinct()
    if (digests.isEmpty()) return this

    val alreadyCounted = digests.any(itemKeyDigests::contains)
    val updatedKeys = (digests + itemKeyDigests.filterNot(digests::contains)).take(MAX_ITEMS)
    return copy(
      count = if (alreadyCounted) count else (count + 1).coerceAtMost(MAX_ITEMS),
      itemKeyDigests = updatedKeys,
    )
  }

  fun rename(
    oldKey: String,
    newKey: String,
  ): WatchStatisticsState {
    if (oldKey.isBlank() || newKey.isBlank()) return this
    val oldDigest = digestHistoryIdentity(oldKey)
    val newDigest = digestHistoryIdentity(newKey)
    if (oldDigest !in itemKeyDigests) return this
    val updatedKeys = (listOf(newDigest) + itemKeyDigests.filterNot { it == oldDigest || it == newDigest })
      .take(MAX_ITEMS)
    return copy(itemKeyDigests = updatedKeys)
  }

  fun reconcile(
    filePaths: List<String>,
    aliasesByFilePath: Map<String, List<String>> = emptyMap(),
  ): WatchStatisticsState =
    filePaths.take(MAX_ITEMS).fold(this) { state, filePath ->
      state.record(filePath, aliasesByFilePath[filePath].orEmpty())
    }

  fun reconcileStreamIdentities(streamKeys: List<String>): WatchStatisticsState =
    streamKeys.take(MAX_ITEMS).fold(this) { state, streamKey -> state.record(streamKey) }

  companion object {
    const val MAX_ITEMS = 200

    fun fromHistory(filePaths: List<String>): WatchStatisticsState {
      val rows = filePaths.take(MAX_ITEMS)
      return WatchStatisticsState(
        count = rows.size,
        itemKeyDigests = rows.filter(String::isNotBlank).map(::digestHistoryIdentity).distinct().take(MAX_ITEMS),
      )
    }
  }
}

internal object WatchStatisticsStore {
  const val PREFERENCES_NAME = "watch_statistics_snapshot"
  private const val INITIALIZED_KEY = "initialized"
  private const val COUNT_KEY = "count"
  private const val ITEM_KEYS_KEY = "item_key_digests"
  private val lock = Any()

  fun isInitialized(context: Context): Boolean =
    synchronized(lock) {
      preferences(context).getBoolean(INITIALIZED_KEY, false)
    }

  fun backfillIfNeeded(
    context: Context,
    history: List<RecentlyPlayedEntity>,
    streamHistoryKeys: List<String> = emptyList(),
    historyAliases: Map<String, List<String>> = emptyMap(),
  ) {
    val historyPaths = history.map { it.filePath }
    synchronized(lock) {
      val preferences = preferences(context)
      val current = if (preferences.getBoolean(INITIALIZED_KEY, false)) {
        read(preferences)
          .reconcile(historyPaths, historyAliases)
          .reconcileStreamIdentities(streamHistoryKeys)
      } else {
        WatchStatisticsState.fromHistory(historyPaths)
          .reconcile(historyPaths, historyAliases)
          .reconcileStreamIdentities(streamHistoryKeys)
      }
      persist(preferences, current)
    }
  }

  fun record(
    context: Context,
    itemKey: String,
    aliases: List<String> = emptyList(),
  ) {
    if (itemKey.isBlank()) return
    synchronized(lock) {
      val preferences = preferences(context)
      check(preferences.getBoolean(INITIALIZED_KEY, false)) {
        "Watch statistics must be backfilled before recording new activity"
      }
      persist(preferences, read(preferences).record(itemKey, aliases))
    }
  }

  fun rename(
    context: Context,
    oldKey: String,
    newKey: String,
  ) {
    if (oldKey.isBlank() || newKey.isBlank()) return
    synchronized(lock) {
      val preferences = preferences(context)
      if (!preferences.getBoolean(INITIALIZED_KEY, false)) return
      persist(preferences, read(preferences).rename(oldKey, newKey))
    }
  }

  fun count(context: Context): Int =
    synchronized(lock) {
      preferences(context).getInt(COUNT_KEY, 0).coerceIn(0, WatchStatisticsState.MAX_ITEMS)
    }

  private fun preferences(context: Context) =
    context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  private fun read(preferences: android.content.SharedPreferences): WatchStatisticsState {
    val digests = preferences.getString(ITEM_KEYS_KEY, "").orEmpty()
      .split(',')
      .filter(String::isNotBlank)
      .take(WatchStatisticsState.MAX_ITEMS)
    return WatchStatisticsState(
      count = preferences.getInt(COUNT_KEY, 0).coerceIn(0, WatchStatisticsState.MAX_ITEMS),
      itemKeyDigests = digests,
    )
  }

  private fun persist(
    preferences: android.content.SharedPreferences,
    state: WatchStatisticsState,
  ) {
    check(
      preferences.edit()
        .putBoolean(INITIALIZED_KEY, true)
        .putInt(COUNT_KEY, state.count.coerceIn(0, WatchStatisticsState.MAX_ITEMS))
        .putString(ITEM_KEYS_KEY, state.itemKeyDigests.distinct().take(WatchStatisticsState.MAX_ITEMS).joinToString(","))
        .commit(),
    ) {
      "Unable to persist Watch Statistics"
    }
  }
}
