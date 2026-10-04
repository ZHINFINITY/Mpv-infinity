/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.preferences

import java.io.File

/**
 * Matches blacklist entries to individual folder paths.
 *
 * A blacklisted folder does not implicitly blacklist its descendants. Each folder is an
 * independent entry so an inverted selection can preserve explicitly selected child folders.
 */
internal object FolderBlacklistMatcher {
  fun isBlacklisted(
    folderPath: String,
    blacklistedPaths: Set<String>,
  ): Boolean {
    val normalizedFolderPath = normalize(folderPath)
    return blacklistedPaths.any { blacklistedPath ->
      normalizedFolderPath.equals(normalize(blacklistedPath), ignoreCase = true)
    }
  }

  fun isMediaFileBlacklisted(
    mediaFilePath: String,
    blacklistedPaths: Set<String>,
  ): Boolean {
    val containingFolder = File(mediaFilePath).parent ?: return false
    return isBlacklisted(containingFolder, blacklistedPaths)
  }

  private fun normalize(path: String): String {
    val slashNormalized = path.replace('\\', '/')
    if (slashNormalized.isEmpty()) return ""
    return slashNormalized.trimEnd('/').ifEmpty { "/" }
  }
}
