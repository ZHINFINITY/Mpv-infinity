package app.infinity.mpvz.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogItemIdentityTest {
  @Test
  fun compositeKeySeparatesDelimiterAmbiguousCatalogIdentities() {
    val first = mediaItem(sourceId = "alpha-beta", catalogId = "gamma", providerId = "record")
    val second = mediaItem(sourceId = "alpha", catalogId = "beta-gamma", providerId = "record")

    assertNotEquals(catalogItemIdentityKey(first), catalogItemIdentityKey(second))
    assertEquals(2, listOf(first, second).distinctBy(::catalogItemIdentityKey).size)
  }

  @Test
  fun compositeKeySeparatesEntriesFromDifferentSourcesEvenWhenNumericIdsMatch() {
    val first = mediaItem(id = 41, sourceId = "source-one", catalogId = "catalog", providerId = "entry-one")
    val second = mediaItem(id = 41, sourceId = "source-two", catalogId = "catalog", providerId = "entry-two")

    assertNotEquals(catalogItemIdentityKey(first), catalogItemIdentityKey(second))
  }

  @Test
  fun tvCatalogEntryWithoutEpisodeRowsCanOfferDirectPlay() {
    val directEntry = mediaItem(
      type = MediaType.TV,
      sourceId = "source-fixture",
      catalogId = "catalog-fixture",
      providerId = "episode-entry",
    )

    assertTrue(canResolveCatalogItemWithoutEpisodes(directEntry))
    assertFalse(
      canResolveCatalogItemWithoutEpisodes(
        directEntry.copy(seasons = listOf(Season(1, listOf(Episode(1, "Episode", "", null))))),
      ),
    )
  }

  private fun mediaItem(
    id: Int = 1,
    type: MediaType = MediaType.MOVIE,
    sourceId: String,
    catalogId: String,
    providerId: String,
  ) = MediaItem(
    id = id,
    type = type,
    title = "Fixture",
    overview = "",
    posterUrl = null,
    backdropUrl = null,
    providerId = providerId,
    catalogSourceId = sourceId,
    catalogType = if (type == MediaType.TV) "series" else "movie",
    catalogId = catalogId,
  )
}
