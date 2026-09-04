/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.domain.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.infinity.mpvz.preferences.DownloadPreferences
import java.io.File

/**
 * Resolves where downloads are written.
 *
 * The user picks a public folder with the system SAF picker; the tree URI is resolved to a real
 * filesystem path (the app ships with broad storage access for its file browser) so the download
 * engine, mpv and the sidecar-subtitle autoload all work on plain files. When no folder is picked
 * or resolution fails, downloads land in the app-private external files dir.
 */
class DownloadLocations(
  private val context: Context,
  private val preferences: DownloadPreferences,
) {
  /** Root directory downloads are written under, created on demand. */
  fun root(): File {
    val configured = resolveConfiguredRoot()
    val dir = configured ?: defaultRoot()
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  fun defaultRoot(): File = File(context.getExternalFilesDir(null), "Downloads")

  fun isUsingCustomLocation(): Boolean = resolveConfiguredRoot() != null

  /** Persist a picked SAF tree; returns the resolved path or null when unusable. */
  fun setLocationFromTree(treeUri: Uri): String? {
    val path = resolveTreeUriToPath(treeUri) ?: return null
    val dir = File(path)
    if (!dir.isDirectory || !dir.canWrite()) return null
    preferences.downloadLocationTreeUri.set(treeUri.toString())
    preferences.downloadLocationPath.set(path)
    return path
  }

  fun clearCustomLocation() {
    preferences.downloadLocationTreeUri.set("")
    preferences.downloadLocationPath.set("")
  }

  fun linksDir(): File = subDir(root(), "Links")

  fun jellyfinMovieDir(title: String): File = subDir(subDir(root(), "Jellyfin"), sanitizeName(title))

  fun jellyfinSeasonDir(
    seriesName: String,
    seasonNumber: Int?,
  ): File {
    val seriesDir = subDir(subDir(root(), "Jellyfin"), sanitizeName(seriesName))
    return if (seasonNumber != null) subDir(seriesDir, "Season %02d".format(seasonNumber)) else seriesDir
  }

  private fun subDir(
    parent: File,
    name: String,
  ): File = File(parent, name).apply { if (!exists()) mkdirs() }

  private fun resolveConfiguredRoot(): File? {
    val cachedPath = preferences.downloadLocationPath.get()
    if (cachedPath.isNotBlank()) {
      val dir = File(cachedPath)
      if (dir.isDirectory && dir.canWrite()) return dir
    }
    val treeUri = preferences.downloadLocationTreeUri.get()
    if (treeUri.isBlank()) return null
    val resolved = resolveTreeUriToPath(Uri.parse(treeUri)) ?: return null
    val dir = File(resolved)
    if (!dir.isDirectory || !dir.canWrite()) return null
    preferences.downloadLocationPath.set(resolved)
    return dir
  }

  private fun resolveTreeUriToPath(treeUri: Uri): String? =
    runCatching {
      val docId = DocumentsContract.getTreeDocumentId(treeUri)
      val volume = docId.substringBefore(':')
      val relativePath = docId.substringAfter(':', "")
      val candidates =
        if (volume.equals("primary", ignoreCase = true)) {
          listOf("/storage/emulated/0")
        } else {
          listOf("/storage/$volume", "/mnt/media_rw/$volume")
        }
      candidates
        .map { base -> if (relativePath.isBlank()) base else "$base/$relativePath" }
        .firstOrNull { File(it).isDirectory }
    }.getOrNull()

  companion object {
    /** Strips characters that are invalid in filenames across Android filesystems. */
    fun sanitizeName(name: String): String =
      name
        .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.')
        .take(120)
        .ifBlank { "download" }
  }
}
