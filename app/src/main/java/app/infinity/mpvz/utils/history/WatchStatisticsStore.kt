package app.infinity.mpvz.utils.history

import android.content.Context
import app.infinity.mpvz.database.entities.RecentlyPlayedEntity

internal data class WatchStatisticsState(
  val count: Int,
  val itemKeyDigests: List<String>,
  val aliasDigests: List<String> = emptyList(),
) {
  fun record(
    itemKey: String,
    aliases: List<String> = emptyList(),
  ): WatchStatisticsState {
    val primaryDigest = itemKey.takeIf(String::isNotBlank)?.let(::digestHistoryIdentity)
    val requestedAliasDigests = aliases
      .filter(String::isNotBlank)
      .map(::digestHistoryIdentity)
      .distinct()
      .filterNot { it == primaryDigest }
    val digests = (listOfNotNull(primaryDigest) + requestedAliasDigests).distinct()
    if (digests.isEmpty()) return this

    val alreadyCounted = digests.any { it in itemKeyDigests || it in aliasDigests }
    val updatedItemKeys =
      if (primaryDigest == null) {
        itemKeyDigests.filterNot { it in requestedAliasDigests }.take(MAX_ITEMS)
      } else {
        (listOf(primaryDigest) +
          itemKeyDigests.filterNot {
            it == primaryDigest || it in requestedAliasDigests || it in aliasDigests
          }).take(MAX_ITEMS)
      }
    val updatedAliasDigests =
      (requestedAliasDigests +
        aliasDigests.filterNot { it == primaryDigest || it in requestedAliasDigests })
        .take(MAX_ITEMS)
    return copy(
      count = if (alreadyCounted) count else (count + 1).coerceAtMost(MAX_ITEMS),
      itemKeyDigests = updatedItemKeys,
      aliasDigests = updatedAliasDigests,
    )
  }

  fun rename(
    oldKey: String,
    newKey: String,
  ): WatchStatisticsState {
    if (oldKey.isBlank() || newKey.isBlank()) return this
    val oldDigest = digestHistoryIdentity(oldKey)
    val newDigest = digestHistoryIdentity(newKey)
    val oldIsPrimary = oldDigest in itemKeyDigests
    val oldIsAlias = oldDigest in aliasDigests
    if (!oldIsPrimary && !oldIsAlias) return this
    val renameAsPrimary = oldIsPrimary || newDigest in itemKeyDigests
    val updatedItemKeys =
      if (renameAsPrimary) {
        (listOf(newDigest) + itemKeyDigests.filterNot { it == oldDigest || it == newDigest }).take(MAX_ITEMS)
      } else {
        itemKeyDigests.filterNot { it == newDigest }
      }
    val updatedAliasDigests =
      if (!renameAsPrimary && (oldIsAlias || newDigest in aliasDigests)) {
        (listOf(newDigest) + aliasDigests.filterNot { it == oldDigest || it == newDigest }).take(MAX_ITEMS)
      } else {
        aliasDigests.filterNot { it == oldDigest || it == newDigest }
      }
    return copy(itemKeyDigests = updatedItemKeys, aliasDigests = updatedAliasDigests)
  }

  fun reconcile(
    filePaths: List<String>,
    aliasesByFilePath: Map<String, List<String>> = emptyMap(),
  ): WatchStatisticsState =
    filePaths.take(MAX_ITEMS).fold(this) { state, filePath ->
      state.record(filePath, aliasesByFilePath[filePath].orEmpty())
    }

  fun reconcileStreamIdentities(streamKeys: List<String>): WatchStatisticsState =
    streamKeys.take(MAX_ITEMS).fold(this) { state, streamKey -> state.recordStreamIdentity(streamKey) }

  private fun recordStreamIdentity(streamKey: String): WatchStatisticsState {
    if (streamKey.isBlank()) return this
    val digest = digestHistoryIdentity(streamKey)
    val alreadyCounted = digest in itemKeyDigests || digest in aliasDigests
    return copy(
      count = if (alreadyCounted) count else (count + 1).coerceAtMost(MAX_ITEMS),
      itemKeyDigests = itemKeyDigests.filterNot { it == digest },
      aliasDigests = (listOf(digest) + aliasDigests.filterNot { it == digest }).take(MAX_ITEMS),
    )
  }

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
  private const val ALIAS_KEYS_KEY = "alias_key_digests"
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
    val aliasDigests = preferences.getString(ALIAS_KEYS_KEY, "").orEmpty()
      .split(',')
      .filter(String::isNotBlank)
      .take(WatchStatisticsState.MAX_ITEMS)
    val digests = preferences.getString(ITEM_KEYS_KEY, "").orEmpty()
      .split(',')
      .filter(String::isNotBlank)
      .filterNot(aliasDigests::contains)
      .take(WatchStatisticsState.MAX_ITEMS)
    return WatchStatisticsState(
      count = preferences.getInt(COUNT_KEY, 0).coerceIn(0, WatchStatisticsState.MAX_ITEMS),
      itemKeyDigests = digests,
      aliasDigests = aliasDigests,
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
        .putString(ALIAS_KEYS_KEY, state.aliasDigests.distinct().take(WatchStatisticsState.MAX_ITEMS).joinToString(","))
        .commit(),
    ) {
      "Unable to persist Watch Statistics"
    }
  }
}
