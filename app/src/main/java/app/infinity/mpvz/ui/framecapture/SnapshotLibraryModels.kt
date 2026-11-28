/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.framecapture

import app.infinity.mpvz.domain.framecapture.FrameCapture
import app.infinity.mpvz.domain.framecapture.SnapshotFolder

/** A folder plus the snapshot count the UI shows on it. */
data class SnapshotFolderRow(
  val id: Long,
  val name: String,
  val captureCount: Int,
)

/**
 * Everything both snapshot screens derive from the same two flows.
 *
 * Counts are computed here rather than in SQL so that the number on a folder and the number of rows
 * inside it come from one list and cannot disagree — a `GROUP BY` would count rows whose `folderId`
 * points at a deleted folder into a folder that no longer exists.
 */
data class SnapshotLibraryData(
  val folders: List<SnapshotFolderRow>,
  val rootCaptures: List<FrameCapture>,
  val allCaptures: List<FrameCapture>,
) {
  companion object {
    fun of(
      folders: List<SnapshotFolder>,
      captures: List<FrameCapture>,
    ): SnapshotLibraryData {
      // A folderId with no matching folder is treated as the root, so a dangling reference shows the
      // snapshot at the top level instead of hiding it from every screen.
      val knownIds = folders.mapTo(HashSet()) { it.id }
      return SnapshotLibraryData(
        folders =
          folders.map { folder ->
            SnapshotFolderRow(
              id = folder.id,
              name = folder.name,
              captureCount = captures.count { it.folderId == folder.id },
            )
          },
        rootCaptures = captures.filter { it.folderId == null || it.folderId !in knownIds },
        allCaptures = captures,
      )
    }
  }
}
