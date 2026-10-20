/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.domain.browser

import androidx.compose.runtime.Immutable
import app.infinity.mpvz.domain.media.model.Video

/**
 * Represents an item in the filesystem browser (either a folder or a video file)
 */
@Immutable
sealed class FileSystemItem {
  abstract val name: String
  abstract val path: String
  abstract val lastModified: Long

  @Immutable
  data class Folder(
    override val name: String,
    override val path: String,
    override val lastModified: Long,
    val videoCount: Int = 0,
    val totalSize: Long = 0L,
    val totalDuration: Long = 0L,
    val hasSubfolders: Boolean = false,
    val newCount: Int = 0,
  ) : FileSystemItem()

  @Immutable
  data class VideoFile(
    override val name: String,
    override val path: String,
    override val lastModified: Long,
    val video: Video,
  ) : FileSystemItem()
}

/**
 * Represents a path component in the breadcrumb navigation
 */
@Immutable
data class PathComponent(
  val name: String,
  val fullPath: String,
)
