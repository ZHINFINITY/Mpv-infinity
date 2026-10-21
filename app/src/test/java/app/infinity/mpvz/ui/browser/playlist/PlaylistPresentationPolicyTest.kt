package app.infinity.mpvz.ui.browser.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

private data class PlaylistFixture(val name: String, val radio: Boolean)
private data class ItemFixture(val category: String?, val favorite: Boolean)

class PlaylistPresentationPolicyTest {
  @Test fun filtersPlaylistNamesIgnoringCaseAndWhitespace() {
    val items = listOf(PlaylistFixture("News", false), PlaylistFixture("Rock", false))
    assertEquals(listOf("News"), PlaylistPresentationPolicy.filterPlaylists(items, "  news ") { it.name }.map { it.name })
    assertEquals(2, PlaylistPresentationPolicy.filterPlaylists(items, " ") { it.name }.size)
  }

  @Test fun separatesRadioFromOrdinaryPlaylistsWithoutChangingOrder() {
    val items = listOf(PlaylistFixture("A", false), PlaylistFixture("R", true), PlaylistFixture("B", false))
    val (ordinary, radio) = PlaylistPresentationPolicy.splitByKind(items) { it.radio }
    assertEquals(listOf("A", "B"), ordinary.map { it.name })
    assertEquals(listOf("R"), radio.map { it.name })
  }

  @Test fun m3uFiltersAndCountsUseStoredMetadata() {
    val items = listOf(ItemFixture("News", true), ItemFixture("Sports", false), ItemFixture("News", false))
    assertEquals(3, PlaylistPresentationPolicy.filterM3uItems(items, PlaylistPresentationPolicy.FILTER_ALL, { it.category }, { it.favorite }).size)
    assertEquals(1, PlaylistPresentationPolicy.filterM3uItems(items, PlaylistPresentationPolicy.FILTER_FAVORITES, { it.category }, { it.favorite }).size)
    assertEquals(2, PlaylistPresentationPolicy.filterM3uItems(items, "News", { it.category }, { it.favorite }).size)
  }

  @Test fun categoryCountsVisitsEachItemOnceAndPreservesCategoryOrder() {
    val items = (0 until 1000).map { ItemFixture("Category${it % 500}", it % 3 == 0) }
    var visits = 0
    val categories = listOf("Category499", "Category0", "Missing")
    val counts = PlaylistPresentationPolicy.categoryCounts(items, categories) { visits++; it.category }
    assertEquals(1000, visits)
    assertEquals(listOf("Category499", "Category0", "Missing"), counts.keys.toList())
    assertEquals(2, counts["Category499"])
    assertEquals(2, counts["Category0"])
    assertEquals(0, counts["Missing"])
  }

  @Test fun filteredStateTakesPriorityEvenForMusicOrRadio() {
    assertEquals(PlaylistPresentationPolicy.EmptyKind.FILTERED,
      PlaylistPresentationPolicy.emptyKind(true, false, false, true, true, true))
    assertEquals(PlaylistPresentationPolicy.EmptyKind.FILTERED,
      PlaylistPresentationPolicy.emptyKind(false, true, false, false, false, true))
    assertEquals(PlaylistPresentationPolicy.EmptyKind.FILTERED,
      PlaylistPresentationPolicy.emptyKind(false, false, true, false, true, false))
  }

  @Test fun realRadioIdentityPrecedesM3uAndAudioFlags() {
    assertEquals(PlaylistPresentationPolicy.EmptyKind.RADIO,
      PlaylistPresentationPolicy.emptyKind(false, false, false, true, true, true))
    assertEquals(PlaylistPresentationPolicy.EmptyKind.TV,
      PlaylistPresentationPolicy.emptyKind(false, false, false, false, true, false))
  }

  @Test fun ordinaryPlaylistsAreNeverMislabelledAsEmptyTv() {
    assertEquals(PlaylistPresentationPolicy.EmptyKind.SONGS,
      PlaylistPresentationPolicy.emptyKind(false, false, false, false, false, true))
    assertEquals(PlaylistPresentationPolicy.EmptyKind.VIDEOS,
      PlaylistPresentationPolicy.emptyKind(false, false, false, false, false, false))
  }
}
