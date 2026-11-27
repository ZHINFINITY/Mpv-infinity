/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.utils.media

/**
 * Cache-key normaliser for media and artwork URLs.
 *
 * Jellyfin/Navidrome artwork and stream URLs carry the session token in the query string
 * (`api_key`, `X-Emby-Token`, ...), and that token is reissued or rotated between app launches.
 * Hashing the raw URL therefore produced a fresh cache key on every start, so thumbnails looked
 * like they were re-downloading from scratch each time the app opened. Dropping the credential
 * parameters keeps the key stable; the request itself still uses the untouched URL.
 */
internal object VolatileUrlKey {
  private val VOLATILE_PARAMS =
    setOf(
      "api_key",
      "apikey",
      "x-emby-token",
      "x-emby-authorization",
      "token",
      "access_token",
      "session",
      "sessionid",
      "session_id",
      "deviceid",
      "device_id",
    )

  fun normalize(url: String): String {
    val trimmed = url.trim()
    val queryStart = trimmed.indexOf('?')
    if (queryStart < 0) return trimmed.substringBefore('#')
    val base = trimmed.substring(0, queryStart)
    // Fragments are client-side only (`#t=10`), so they never belong in an identity key.
    val query = trimmed.substring(queryStart + 1).substringBefore('#')
    val kept =
      query.split('&')
        .filter { it.isNotBlank() }
        .filterNot { it.substringBefore('=').lowercase() in VOLATILE_PARAMS }
    return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
  }
}
