/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.domain.download

import android.net.Uri
import app.infinity.mpvz.ui.player.ytdlp.YtdlpManager

/**
 * Routes a link download to the right engine: plain HTTP(S) files go through the
 * queue-based engine, while HLS/DASH manifests and extractor sites (YouTube, ...)
 * go through the bundled yt-dlp runtime which muxes segments into a single file.
 */
class LinkDownloadCoordinator(
  private val downloadManager: AppDownloadManager,
  private val ytdlpEngine: YtdlpDownloadEngine,
) {
  enum class Route { DIRECT, YTDLP, UNSUPPORTED }

  fun routeFor(url: String): Route {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return Route.UNSUPPORTED
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return Route.UNSUPPORTED
    val path = uri.path?.lowercase().orEmpty()
    return when {
      // Manifests play directly in mpv but need yt-dlp to be downloaded as one file.
      path.endsWith(".m3u8") || path.endsWith(".mpd") -> Route.YTDLP
      YtdlpManager.requiresYtdlp(url) -> Route.YTDLP
      else -> Route.DIRECT
    }
  }

  /** Queues the link; returns the chosen route or UNSUPPORTED when nothing was queued. */
  fun enqueue(
    url: String,
    title: String?,
  ): Route {
    val route = routeFor(url)
    val directory = downloadManager.locations.linksDir()
    val displayTitle = title?.takeIf { it.isNotBlank() } ?: fileNameFromUrl(url).substringBeforeLast('.')

    when (route) {
      Route.DIRECT -> {
        val fileName = fileNameFromUrl(url)
        downloadManager.enqueueVideo(
          url = url,
          directory = directory,
          fileName = fileName,
          meta =
            DownloadMetadata(
              source = DownloadSources.LINK,
              title = displayTitle,
              sourceUrl = url,
            ),
        )
      }
      Route.YTDLP -> ytdlpEngine.enqueue(url = url, title = displayTitle, directory = directory)
      Route.UNSUPPORTED -> {}
    }
    return route
  }

  companion object {
    private val MEDIA_EXTENSION_REGEX = Regex("""\.[a-z0-9]{2,5}$""", RegexOption.IGNORE_CASE)

    fun fileNameFromUrl(url: String): String {
      val lastSegment =
        runCatching { Uri.parse(url).lastPathSegment }
          .getOrNull()
          ?.takeIf { it.isNotBlank() }
          ?: "download"
      val sanitized = DownloadLocations.sanitizeName(Uri.decode(lastSegment))
      return if (MEDIA_EXTENSION_REGEX.containsMatchIn(sanitized)) sanitized else "$sanitized.mp4"
    }
  }
}
