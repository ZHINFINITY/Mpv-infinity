/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.browser.folderlist

import app.infinity.mpvz.domain.media.model.VideoFolder
import app.infinity.mpvz.preferences.FolderBlacklistMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Applies the video-folder path scope and blacklist to the folder-list flow. */
internal object FolderListBlacklistFilter {
  fun observe(
    folders: Flow<List<VideoFolder>>,
    blacklistedPaths: Flow<Set<String>>,
    rootPath: String?,
  ): Flow<List<VideoFolder>> =
    combine(folders, blacklistedPaths) { allFolders, blacklist ->
      allFolders.filter { folder ->
        (rootPath.isNullOrBlank() || folder.path.equals(rootPath, ignoreCase = true) ||
          folder.path.startsWith(rootPath.trimEnd('/') + "/", ignoreCase = true)) &&
          !FolderBlacklistMatcher.isBlacklisted(folder.path, blacklist)
      }
    }
}
