package app.infinity.mpvz.utils.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WatchStatisticsStoreTest {
  @Test
  fun backfillPreservesLegacyRowCountAndFutureWritesCountUniqueItems() {
    val initial = WatchStatisticsState.fromHistory(listOf("/test/media-a", "/test/media-a", "/test/media-b"))
    assertEquals(3, initial.count)
    assertEquals(3, initial.record("/test/media-a").count)
    assertEquals(4, initial.record("/test/media-c").count)
    assertEquals(4, initial.record("/test/media-c").count)
  }

  @Test
  fun reconciliationAddsRowsButNeverReducesTheSnapshot() {
    val initial = WatchStatisticsState.fromHistory(listOf("/test/media-a"))
    val updated = initial.reconcile(listOf("/test/media-a", "/test/media-b"))

    assertEquals(2, updated.count)
    assertEquals(2, updated.reconcile(emptyList()).count)
    assertEquals(2, updated.reconcile(listOf("/test/media-a", "/test/media-b")).count)
  }

  @Test
  fun aliasesDeduplicateOneMediaItemAndRenameKeepsItsCount() {
    val initial = WatchStatisticsState.fromHistory(emptyList())
      .record("/test/old-name", aliases = listOf("opaque-test-key"))
    val deduplicated = initial.record("opaque-test-key", aliases = listOf("/test/old-name"))
    val renamed = deduplicated.rename("/test/old-name", "/test/new-name")

    assertEquals(1, deduplicated.count)
    assertEquals(1, renamed.count)
    assertEquals(1, renamed.record("/test/new-name").count)
  }

  @Test
  fun streamHistoryKeysAreDeduplicatedAndHashedBeforePersistence() {
    val streamKey = "opaque-test-stream-key"
    val state = WatchStatisticsState.fromHistory(emptyList()).reconcileStreamIdentities(listOf(streamKey, streamKey))

    assertEquals(1, state.count)
    assertFalse(state.itemKeyDigests.contains(streamKey))
    assertEquals(64, state.itemKeyDigests.single().length)
  }

  @Test
  fun backfillAliasesConnectRecentAndStreamIdentitiesWithoutDoubleCounting() {
    val historyPath = "/test/media-stream"
    val streamKey = "opaque-test-stream-key"
    val history = listOf(historyPath)
    val aliases = mapOf(historyPath to listOf(streamKey))
    val state =
      WatchStatisticsState.fromHistory(history)
        .reconcile(history, aliases)
        .reconcileStreamIdentities(listOf(streamKey))

    assertEquals(1, state.count)
    assertEquals(2, state.itemKeyDigests.size)
    val existingSnapshot =
      WatchStatisticsState.fromHistory(emptyList())
        .record(historyPath)
        .reconcile(history, aliases)
        .reconcileStreamIdentities(listOf(streamKey))
    assertEquals(1, existingSnapshot.count)
  }

  @Test
  fun persistedIdentityValuesAreDigestsRatherThanRawPaths() {
    val rawIdentity = "/test/media-a"
    val state = WatchStatisticsState.fromHistory(listOf(rawIdentity))

    assertFalse(state.itemKeyDigests.contains(rawIdentity))
    assertEquals(64, state.itemKeyDigests.single().length)
    assertEquals(WatchStatisticsState.MAX_ITEMS, WatchStatisticsState.fromHistory((0..205).map { "/test/item-$it" }).count)
  }
}
