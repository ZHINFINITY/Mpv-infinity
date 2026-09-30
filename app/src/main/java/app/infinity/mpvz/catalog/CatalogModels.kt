package app.infinity.mpvz.catalog

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
enum class MediaType { MOVIE, TV }

@Serializable
data class CatalogSource(
  val id: String,
  val name: String,
  val manifestUrl: String,
  val isEnabled: Boolean = true,
)

data class MediaItem(
  val id: Int,
  val type: MediaType,
  val title: String,
  val overview: String,
  val posterUrl: String?,
  val backdropUrl: String?,
  val tmdbId: Int? = null,
  val imdbId: String? = null,
  val tvdbId: String? = null,
  val seasons: List<Season> = emptyList(),
  val providerId: String? = null,
  val catalogSourceId: String? = null,
  val catalogType: String? = null,
  val catalogId: String? = null,
  val catalogName: String? = null,
  val releaseYear: String? = null,
  val contentRating: String? = null,
  val duration: String? = null,
  val genres: List<String> = emptyList(),
  val historySeason: Int? = null,
  val historyEpisode: Int? = null,
  val historyStillUrl: String? = null,
  val historyProgress: Float = 0f,
)

/** A saveable, unambiguous key for catalog cards and selected-item state. */
internal fun catalogItemIdentityKey(item: MediaItem): String =
  listOf(
    item.catalogSourceId.orEmpty(),
    item.catalogType.orEmpty(),
    item.catalogId.orEmpty(),
    item.type.name,
    item.providerId?.let { "provider:$it" } ?: "id:${item.id}",
  ).joinToString(separator = "") { part -> "${part.length}:$part" }

/** TV catalog entries without episode rows can still be resolved by their provider identity. */
internal fun canResolveCatalogItemWithoutEpisodes(item: MediaItem): Boolean =
  item.type == MediaType.TV &&
    item.seasons.none { it.episodes.isNotEmpty() } &&
    (!item.providerId.isNullOrBlank() || !item.imdbId.isNullOrBlank() || item.tmdbId != null)

/** Fill missing metadata/artwork, replacing a poster only after its source URL fails. */
internal fun mergeMissingCatalogArtwork(
  item: MediaItem,
  artwork: MediaItem,
  replaceFailedPoster: Boolean = false,
): MediaItem = item.copy(
  posterUrl = if (replaceFailedPoster) {
    artwork.posterUrl?.takeIf(String::isNotBlank) ?: item.posterUrl?.takeIf(String::isNotBlank)
  } else {
    item.posterUrl?.takeIf(String::isNotBlank) ?: artwork.posterUrl?.takeIf(String::isNotBlank)
  },
  backdropUrl = item.backdropUrl?.takeIf(String::isNotBlank) ?: artwork.backdropUrl?.takeIf(String::isNotBlank),
  tmdbId = item.tmdbId ?: artwork.tmdbId,
  imdbId = item.imdbId?.takeIf(String::isNotBlank) ?: artwork.imdbId,
  tvdbId = item.tvdbId?.takeIf(String::isNotBlank) ?: artwork.tvdbId,
)

@Serializable
data class Season(
  val number: Int,
  val episodes: List<Episode> = emptyList(),
  val posterUrl: String? = null,
)

/** Prefer original provider artwork; the image loader still downsamples for the device. */
fun highQualityPosterUrl(url: String): String {
  var normalized = url
  listOf("w92", "w154", "w185", "w300", "w342", "w500", "w780", "w1280").forEach { size ->
    normalized = normalized.replace("/t/p/$size/", "/t/p/original/")
  }
  return normalized
    .replace("/poster/small/", "/poster/original/")
    .replace("/poster/medium/", "/poster/original/")
    .replace("/poster/large/", "/poster/original/")
    .replace("/background/small/", "/background/original/")
    .replace("/background/medium/", "/background/original/")
    .replace("/background/large/", "/background/original/")
    .replace("medium_portrait/", "original_untouched/")
    .replace("medium_landscape/", "original_untouched/")
}

/** Try the preferred high-resolution variant, then the provider's exact original URL. */
internal fun catalogArtworkCandidates(urls: List<String>): List<String> =
  urls.flatMap { candidate ->
    val original = candidate.trim()
    if (original.isBlank()) emptyList() else listOf(highQualityPosterUrl(original), original)
  }.distinct()

@Serializable
data class Episode(
  val number: Int,
  val title: String,
  val overview: String,
  val stillUrl: String?,
  val seasonPosterUrl: String? = null,
  val runtime: String? = null,
  val videoId: String? = null,
)

data class CatalogState(
  val query: String = "",
  val items: List<MediaItem> = emptyList(),
  val recentItems: List<MediaItem> = emptyList(),
  val isLoading: Boolean = false,
  val isLoadingMore: Boolean = false,
  val catalogPage: Int = 1,
  val canLoadMore: Boolean = true,
  val metadataLoadingKey: String? = null,
  val resolvingKey: String? = null,
  val error: String? = null,
  val streamOptions: List<StreamOption> = emptyList(),
  val streamTitle: String? = null,
  val selectedItem: MediaItem? = null,
  val selectedSeason: Int? = null,
  val selectedEpisode: Int? = null,
  val selectedEpisodeVideoId: String? = null,
)

@Serializable
data class StreamOption(
  val url: String,
  val title: String,
  val mimeType: String? = null,
  val headers: Map<String, String> = emptyMap(),
  val filename: String? = null,
  val qualityRank: Int = 0,
  val seeders: Int = 0,
  val size: String? = null,
  val source: String? = null,
  val audioCodec: String? = null,
  val videoCodec: String? = null,
  val isPlayable: Boolean = url.startsWith("http://") || url.startsWith("https://"),
  val isExternal: Boolean = false,
  val season: Int? = null,
  val episode: Int? = null,
  /** Used only by the Network torrent picker; direct Stream providers never populate it. */
  val torrentFileIndex: Int? = null,
)

/** Removes private add-on configuration and credentials before text reaches logcat. */
internal fun redactAddonConfigurationFromLog(value: String): String {
  val urlsRedacted = Regex("(?i)https?://[^\\s\\\"'<>]+")
    .replace(value) { match ->
      val raw = match.value.trimEnd('.', ',', ')', ']', '}', ';')
      val punctuation = match.value.substring(raw.length)
      val safe = runCatching {
        val uri = URI(raw)
        val authority = uri.rawAuthority?.substringAfterLast('@').orEmpty()
        if (authority.isBlank()) "[redacted-url]" else "${uri.scheme}://$authority/[redacted]${if (uri.rawQuery != null) "?[redacted]" else ""}"
      }.getOrDefault("[redacted-url]")
      safe + punctuation
    }
  val keyValuesRedacted = Regex("(?i)(\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|authorization|auth|password|secret|signature|sig)\\b\\s*[:=]\\s*)([^&;,\\s\\\"'<>}]+)")
    .replace(urlsRedacted) { "${it.groupValues[1]}[redacted]" }
  return Regex("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+")
    .replace(keyValuesRedacted) { "${it.groupValues[1]}[redacted]" }
}
