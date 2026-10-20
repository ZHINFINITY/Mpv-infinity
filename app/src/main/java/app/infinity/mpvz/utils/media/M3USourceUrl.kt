/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.utils.media

import java.net.URI

/** Only rewrite known playlist file-view links; never scrape arbitrary HTML as media. */
object M3USourceUrl {
  fun normalize(source: String): String {
    val trimmed = source.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
    if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return trimmed
    if (!uri.host.equals("gitlab.com", true)) return trimmed
    val path = uri.rawPath.orEmpty()
    if (!path.endsWith(".m3u", true) && !path.endsWith(".m3u8", true)) return trimmed
    val marker = "/-/blob/"
    val index = path.indexOf(marker)
    if (index < 0) return trimmed
    val rawPath = path.replaceRange(index, index + marker.length, "/-/raw/")
    return buildString {
      append(uri.scheme).append("://").append(uri.rawAuthority).append(rawPath)
      uri.rawQuery?.let { append('?').append(it) }
      uri.rawFragment?.let { append('#').append(it) }
    }
  }
}
