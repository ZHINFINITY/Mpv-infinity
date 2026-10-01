package app.infinity.mpvz.catalog

import app.infinity.mpvz.catalog.nuvio.TmdbMetadataRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioStreamRepositoryTest {
  @Test
  fun directImdbIdentifierWinsOverMappedFallback() {
    val item = mediaItem(imdbId = "tt1000001", providerId = "catalog_item_1")

    assertEquals(
      "tt1000001",
      stremioImdbIdentifier(item, season = null, episode = null, mappedImdbId = "tt1000002"),
    )
  }

  @Test
  fun providerSuppliedImdbIdentifierIsAcceptedDirectly() {
    val item = mediaItem(providerId = "tt1000008")

    assertEquals("tt1000008", stremioImdbIdentifier(item, season = null, episode = null))
  }

  @Test
  fun mappedTmdbIdentifierCanBeUsedForStreamLookup() {
    val item = mediaItem(tmdbId = 321)

    assertEquals(
      "tt1000003",
      stremioImdbIdentifier(item, season = null, episode = null, mappedImdbId = "tt1000003"),
    )
  }

  @Test
  fun mappedCustomCatalogIdentifierCanBeUsedForStreamLookup() {
    val item = mediaItem(providerId = "catalog_item_42")

    assertEquals(
      "tt1000004",
      stremioImdbIdentifier(item, season = null, episode = null, mappedImdbId = "tt1000004"),
    )
  }

  @Test
  fun imdbSeriesIdentifierGetsSelectedEpisodeSuffix() {
    val item = mediaItem(type = MediaType.TV, imdbId = "tt1000005")

    assertEquals("tt1000005:3:7", stremioImdbIdentifier(item, season = 3, episode = 7))
  }

  @Test
  fun originAddonReceivesItsAcceptedEpisodeVideoIdUnchanged() {
    val item = mediaItem(type = MediaType.TV, providerId = "catalog_show_1", catalogSourceId = "source-origin")
    val source = CatalogSource("source-origin", "Origin", "https://example.invalid/manifest.json")
    val manifest = manifest("series", "catalog_")

    assertEquals(
      "catalog_episode_3",
      selectStreamIdentifierForSource(
        item = item,
        source = source,
        manifest = manifest,
        season = 1,
        episode = 3,
        episodeVideoId = "catalog_episode_3",
        fallbackIdentifier = "tt1000006:1:3",
      ),
    )
  }

  @Test
  fun directCatalogEpisodeUsesItsVideoIdWhenSeasonMetadataIsUnavailable() {
    val item = mediaItem(type = MediaType.TV, providerId = "series_entry", catalogSourceId = "source-origin")
    val source = CatalogSource("source-origin", "Origin", "https://example.invalid/manifest.json")

    assertEquals(
      "episode_video_3",
      selectStreamIdentifierForSource(
        item = item,
        source = source,
        manifest = manifest("series", "episode_video_"),
        season = null,
        episode = null,
        episodeVideoId = "episode_video_3",
        fallbackIdentifier = null,
      ),
    )
  }

  @Test
  fun unrelatedAddonReceivesOnlyItsCompatibleFallbackId() {
    val item = mediaItem(type = MediaType.TV, providerId = "catalog_show_1", catalogSourceId = "source-origin")
    val source = CatalogSource("source-other", "Other", "https://example.invalid/manifest.json")

    assertEquals(
      "tt1000007:2:4",
      selectStreamIdentifierForSource(
        item = item,
        source = source,
        manifest = manifest("series", "tt"),
        season = 2,
        episode = 4,
        episodeVideoId = "catalog_episode_4",
        fallbackIdentifier = "tt1000007:2:4",
      ),
    )
  }

  @Test
  fun unsupportedManifestPrefixIsNotQueriedWhenNoFallbackExists() {
    val item = mediaItem(type = MediaType.TV, providerId = "catalog_show_1", catalogSourceId = "source-origin")
    val source = CatalogSource("source-origin", "Origin", "https://example.invalid/manifest.json")

    assertNull(
      selectStreamIdentifierForSource(
        item = item,
        source = source,
        manifest = manifest("series", "another_"),
        season = 1,
        episode = 2,
        episodeVideoId = "catalog_episode_2",
        fallbackIdentifier = null,
      ),
    )
  }

  @Test
  fun missingManifestKeepsExistingImdbFallback() {
    val item = mediaItem(type = MediaType.TV, providerId = "catalog_show_1", catalogSourceId = "source-origin")
    val source = CatalogSource("source-origin", "Origin", "https://example.invalid/manifest.json")

    assertEquals(
      "tt1000009:1:2",
      selectStreamIdentifierForSource(
        item = item,
        source = source,
        manifest = null,
        season = 1,
        episode = 2,
        episodeVideoId = "catalog_episode_2",
        fallbackIdentifier = "tt1000009:1:2",
      ),
    )
  }

  @Test
  fun manifestResourceMustAcceptBothRequestedTypeAndIdPrefix() {
    val manifest = manifest("series", "catalog_")

    assertTrue(manifestSupportsStreamId(manifest, "series", "catalog_episode_1"))
    assertFalse(manifestSupportsStreamId(manifest, "movie", "catalog_episode_1"))
    assertFalse(manifestSupportsStreamId(manifest, "series", "other_episode_1"))
  }

  @Test
  fun unqualifiedStreamResourceAcceptsAnyIdPrefix() {
    val manifest = Json.parseToJsonElement("""{"resources":["stream"]}""").jsonObject

    assertTrue(manifestSupportsStreamId(manifest, "series", "catalog_episode_1"))
  }

  @Test
  fun streamEndpointPreservesManifestQueryAndEpisodeSeparators() {
    assertEquals(
      "https://example.invalid/stream/series/catalog_episode_1:2.json?key=fixture",
      stremioStreamRequestUrl(
        "https://example.invalid/manifest.json?key=fixture",
        "series",
        "catalog_episode_1:2",
      ),
    )
  }

  @Test
  fun parsedEpisodeRetainsItsStremioVideoId() {
    val videos = Json.parseToJsonElement(
      """[{"id":"catalog_episode_1","season":1,"episode":2,"name":"Episode"}]""",
    ).jsonArray
    val seasons = StremioMetadataRepository().parseSeasons(videos, null, null, null, null)

    assertEquals("catalog_episode_1", seasons.single().episodes.single().videoId)
  }

  @Test
  fun metadataEnrichmentMergeKeepsOriginalEpisodeVideoId() {
    val original = listOf(Season(1, listOf(Episode(2, "", "", null, videoId = "catalog_episode_2"))))
    val enriched = listOf(Season(1, listOf(Episode(2, "Enriched", "", null))))

    val merged = TmdbMetadataRepository().mergeSeasons(original, enriched)

    assertEquals("catalog_episode_2", merged.single().episodes.single().videoId)
    assertEquals("Enriched", merged.single().episodes.single().title)
  }

  @Test
  fun absentApiKeyLeavesUnmappedItemWithoutAnImdbId() = runBlocking {
    val item = mediaItem(providerId = "catalog_item_99")

    assertNull(TmdbMetadataRepository().resolveImdbId(item, ""))
    assertNull(stremioImdbIdentifier(item, season = null, episode = null))
  }

  private fun manifest(type: String, prefix: String) = Json.parseToJsonElement(
    """{"resources":[{"name":"stream","types":["$type"],"idPrefixes":["$prefix"]}]}""",
  ).jsonObject

  private fun mediaItem(
    type: MediaType = MediaType.MOVIE,
    tmdbId: Int? = null,
    imdbId: String? = null,
    providerId: String? = null,
    catalogSourceId: String? = null,
  ) = MediaItem(
    id = 1,
    type = type,
    title = "",
    overview = "",
    posterUrl = null,
    backdropUrl = null,
    tmdbId = tmdbId,
    imdbId = imdbId,
    providerId = providerId,
    catalogSourceId = catalogSourceId,
  )
}
