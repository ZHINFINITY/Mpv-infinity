/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
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
