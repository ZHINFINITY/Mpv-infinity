/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.utils.media

import app.infinity.mpvz.database.entities.PlaylistItemEntity

object PlaylistExport {
  fun assemble(
    playlistName: String,
    items: Iterable<PlaylistItemEntity>,
  ): String =
    M3UExporter.export(
      items.sortedBy { it.position }.map { item ->
        M3UPlaylistItem(
          url = item.filePath,
          title = item.fileName,
          tvgId = item.tvgId,
          tvgLogo = item.tvgLogo,
          groupTitle = item.groupTitle,
          licenseType = item.licenseType,
          licenseKey = item.licenseKey,
          userAgent = item.userAgent,
        )
      },
      playlistName = playlistName,
    )

  fun fileName(playlistName: String): String {
    val safeName = playlistName
      .trim()
      .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
      .trim('.', ' ')
      .ifBlank { "playlist" }
    return if (safeName.lowercase().endsWith(".m3u")) safeName else "$safeName.m3u"
  }
}
