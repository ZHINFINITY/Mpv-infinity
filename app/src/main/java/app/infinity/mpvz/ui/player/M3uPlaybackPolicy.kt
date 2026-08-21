/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player

import android.net.Uri
import app.infinity.mpvz.utils.media.HttpUtils
import java.net.URI

object M3uPlaybackPolicy {
  private val networkSchemes =
    setOf("http", "https", "ftp", "ftps", "rtmp", "rtmps", "rtsp", "rtsps", "mms", "mmsh")
  private val m3uMimeTypes =
    setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/x-mpegurl", "video/x-mpegurl")

  fun shouldExpandInApp(
    playableUri: String,
    originalUri: String?,
    fileName: String,
    mimeType: String?,
    hasExistingPlaylist: Boolean,
    hasPlaylistId: Boolean,
  ): Boolean {
    if (hasExistingPlaylist || hasPlaylistId) return false

    // A direct remote manifest is already the media item. Do not treat a signed
    // `.m3u8?token=...` URL as an in-app M3U playlist: expanding it attempts to
    // parse the stream as a local playlist and loses the direct-player request
    // context (including Referer and other HTTP headers).
    if (listOfNotNull(playableUri, originalUri).any(::isDirectHlsUrl)) return false

    if (!looksLikeM3uForPlayback(playableUri, originalUri, fileName, mimeType)) return false

    // Remote M3U/HLS URLs that are playlist containers should still use the
    // existing in-app expansion path. Direct HLS manifests are handled above.
    return true
  }

  internal fun looksLikeM3uForPlayback(
    playableUri: String,
    originalUri: String?,
    fileName: String,
    mimeType: String?,
  ): Boolean {
    val candidates = listOfNotNull(playableUri, originalUri, fileName).map { it.lowercase() }
    return candidates.any(::hasM3uMarker) ||
      mimeType?.lowercase()?.let { type ->
        type.contains("mpegurl") || type.contains("x-mpegurl") || type.contains("vnd.apple.mpegurl")
      } == true ||
      mimeType?.lowercase()?.let { type ->
        m3uMimeTypes.contains(type)
      } == true
  }

  private fun isDirectHlsUrl(value: String): Boolean =
    runCatching { HttpUtils.isDirectMediaUrl(Uri.parse(value)) }.getOrDefault(false)

  private fun hasM3uMarker(value: String): Boolean {
    val uriParts =
      runCatching { URI(value) }
        .map { uri -> listOfNotNull(uri.rawPath, uri.rawQuery, uri.rawFragment) }
        .getOrDefault(
          listOf(
            value.substringBefore('?').substringBefore('#'),
            value.substringAfter('?', "").substringBefore('#'),
            value.substringAfter('#', ""),
          ),
        )

    return uriParts.any { part ->
      val lowerPart = part.lowercase()
      lowerPart.endsWith(".m3u") ||
        lowerPart.endsWith(".m3u8") ||
        lowerPart.contains(".m3u?") ||
        lowerPart.contains(".m3u8?") ||
        lowerPart.contains(".m3u#") ||
        lowerPart.contains(".m3u8#") ||
        lowerPart.contains(".m3u&") ||
        lowerPart.contains(".m3u8&") ||
        lowerPart.contains("=m3u") ||
        lowerPart.contains("=m3u8")
    }
  }

  fun shouldProxyHls(
    playableUri: String,
    mimeType: String? = null,
    enableHlsProxy: Boolean = true,
  ): Boolean {
    if (!enableHlsProxy) return false
    val lower = playableUri.lowercase()
    if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
    if (lower.contains("127.0.0.1") || lower.contains("localhost")) return false
    return lower.contains(".m3u8") ||
      mimeType?.lowercase()?.contains("mpegurl") == true ||
      lower.contains("/hls/") ||
      lower.contains("=m3u8") ||
      lower.contains("format=m3u8")
  }

  private fun isNetworkUri(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    val scheme = value.substringBefore(":", missingDelimiterValue = "").lowercase()
    return scheme in networkSchemes
  }
}
