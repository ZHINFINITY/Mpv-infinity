/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.catalog

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
enum class MediaType { MOVIE, TV }

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
