package app.infinity.mpvz.catalog.nuvio

import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.catalog.catalogArtworkCandidates
import app.infinity.mpvz.catalog.catalogTmdbId
import app.infinity.mpvz.catalog.catalogTvdbId
import app.infinity.mpvz.catalog.mergeMissingCatalogArtwork
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogArtworkMappingTest {
  @Test
  fun tmdbUsesMovieForMoviesAndTvForSeriesAndAnime() {
    assertEquals("movie", tmdbMediaKind(item(MediaType.MOVIE, "movie")))
    assertEquals("tv", tmdbMediaKind(item(MediaType.TV, "series")))
    assertEquals("tv", tmdbMediaKind(item(MediaType.TV, "anime")))
  }

  @Test
  fun tvdbTitlePosterUsesSeriesImageField() {
    val series = Json.parseToJsonElement("""{"image":"https://images.example.invalid/series-poster.jpg"}""").jsonObject
    assertEquals("https://images.example.invalid/series-poster.jpg", tvdbSeriesPosterUrl(series))
  }

  @Test
  fun tvdbTitlePosterMayBeAbsent() {
    val series = Json.parseToJsonElement("""{"name":"Example Series"}""").jsonObject
    assertNull(tvdbSeriesPosterUrl(series))
  }

  @Test
  fun catalogExternalIdsMapFromTopLevelAndNestedFields() {
    val topLevel = Json.parseToJsonElement("""{"tmdb_id":321,"tvdbId":"6543"}""").jsonObject
    val nested = Json.parseToJsonElement("""{"ids":{"tmdb":{"id":321},"tvdb":"6543"}}""").jsonObject

    assertEquals(321, catalogTmdbId(topLevel))
    assertEquals("6543", catalogTvdbId(topLevel))
    assertEquals(321, catalogTmdbId(nested))
    assertEquals("6543", catalogTvdbId(nested))
  }

  @Test
  fun artworkCandidatesKeepExactSourceAfterPreferredVariant() {
    val source = "https://images.example.invalid/t/p/w500/poster.jpg"

    assertEquals(
      listOf("https://images.example.invalid/t/p/original/poster.jpg", source),
      catalogArtworkCandidates(listOf(source)),
    )
  }

  @Test
  fun missingArtworkIsFilledWithoutReplacingExistingArtOrIdentity() {
    val original = item(MediaType.TV, "anime").copy(
      catalogSourceId = "source-a",
      catalogId = "catalog-a",
      providerId = "provider-a",
      backdropUrl = "https://images.example.invalid/source-backdrop.jpg",
    )
    val fetched = original.copy(
      posterUrl = "https://images.example.invalid/tmdb-poster.jpg",
      backdropUrl = "https://images.example.invalid/tmdb-backdrop.jpg",
      tmdbId = 123,
      tvdbId = "6543",
    )

    val merged = mergeMissingCatalogArtwork(original, fetched)

    assertEquals("https://images.example.invalid/tmdb-poster.jpg", merged.posterUrl)
    assertEquals("https://images.example.invalid/source-backdrop.jpg", merged.backdropUrl)
    assertEquals(original.catalogSourceId, merged.catalogSourceId)
    assertEquals(original.catalogId, merged.catalogId)
    assertEquals(original.providerId, merged.providerId)
    assertEquals(123, merged.tmdbId)
    assertEquals("6543", merged.tvdbId)
  }

  @Test
  fun existingPosterIsNotOverwritten() {
    val original = item(MediaType.MOVIE, "movie").copy(posterUrl = "https://images.example.invalid/source-poster.jpg")
    val fetched = original.copy(posterUrl = "https://images.example.invalid/provider-poster.jpg")

    assertEquals(original.posterUrl, mergeMissingCatalogArtwork(original, fetched).posterUrl)
  }

  @Test
  fun confirmedFailedPosterCanBeReplacedByMetadataArtwork() {
    val original = item(MediaType.MOVIE, "movie").copy(posterUrl = "https://images.example.invalid/broken-poster.jpg")
    val fetched = original.copy(posterUrl = "https://images.example.invalid/tmdb-poster.jpg")

    assertEquals(
      "https://images.example.invalid/tmdb-poster.jpg",
      mergeMissingCatalogArtwork(original, fetched, replaceFailedPoster = true).posterUrl,
    )
  }

  private fun item(type: MediaType, catalogType: String) = MediaItem(
    id = 1,
    type = type,
    title = "Example Title",
    overview = "",
    posterUrl = null,
    backdropUrl = null,
    catalogType = catalogType,
  )
}
