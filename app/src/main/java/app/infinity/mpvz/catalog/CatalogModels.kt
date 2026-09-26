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
)

@Serializable
data class Season(
  val number: Int,
  val episodes: List<Episode> = emptyList(),
  val posterUrl: String? = null,
)

@Serializable
data class Episode(
  val number: Int,
  val title: String,
  val overview: String,
  val stillUrl: String?,
  val runtime: String? = null,
)

data class CatalogState(
  val query: String = "",
  val items: List<MediaItem> = emptyList(),
  val isLoading: Boolean = false,
  val isLoadingMore: Boolean = false,
  val catalogPage: Int = 1,
  val canLoadMore: Boolean = true,
  val metadataLoadingId: Int? = null,
  val resolvingId: Int? = null,
  val error: String? = null,
  val streamOptions: List<StreamOption> = emptyList(),
  val streamTitle: String? = null,
  val selectedItem: MediaItem? = null,
  val selectedSeason: Int? = null,
  val selectedEpisode: Int? = null,
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
