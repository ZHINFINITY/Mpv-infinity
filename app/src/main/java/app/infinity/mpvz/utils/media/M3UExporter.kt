/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.utils.media

/**
 * Serializes parsed playlist entries for sharing or export.
 *
 * The parser intentionally keeps the directives that affect playback. Exporting
 * them here avoids reducing an IPTV playlist to title + URL pairs: EPG,
 * grouping, per-stream headers, and Kodi DRM hints remain usable by clients
 * which understand them. Unknown source directives cannot be reconstructed after
 * parsing because they are not retained by [M3UPlaylistItem].
 */
object M3UExporter {
  fun export(items: Iterable<M3UPlaylistItem>, playlistName: String? = null): String = buildString {
    append("#EXTM3U")
    playlistName?.let { name ->
      requireLineSafe("playlistName", name)
      name.trim().takeIf { it.isNotEmpty() }?.let {
        append(" name=\"").append(escapeAttribute(it)).append('"')
      }
    }
    append('\n')
    items.forEach { item ->
      append("#EXTINF:").append(item.duration)
      item.tvgId?.let { attribute("tvg-id", it) }
      item.tvgName?.let { attribute("tvg-name", it) }
      item.tvgLogo?.let { attribute("tvg-logo", it) }
      item.groupTitle?.let { attribute("group-title", it) }
      append(',').appendLineSafe("title", item.title.orEmpty())
      item.licenseType?.let {
        append("#KODIPROP:inputstream.adaptive.license_type=")
          .appendLineSafe("licenseType", it)
      }
      item.licenseKey?.let {
        append("#KODIPROP:inputstream.adaptive.license_key=")
          .appendLineSafe("licenseKey", it)
      }
      item.userAgent?.let {
        append("#EXTVLCOPT:http-user-agent=").appendLineSafe("userAgent", it)
      }
      appendLineSafe("url", item.url)
    }
  }

  private fun StringBuilder.attribute(name: String, value: String) {
    requireLineSafe(name, value)
    append(' ').append(name).append("=\"").append(escapeAttribute(value)).append('"')
  }

  private fun StringBuilder.appendLineSafe(fieldName: String, value: String): StringBuilder {
    requireLineSafe(fieldName, value)
    return append(value).append('\n')
  }

  private fun requireLineSafe(fieldName: String, value: String) {
    require(value.none { it == '\n' || it == '\r' }) {
      "$fieldName must not contain CR or LF"
    }
  }

  private fun escapeAttribute(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
}
