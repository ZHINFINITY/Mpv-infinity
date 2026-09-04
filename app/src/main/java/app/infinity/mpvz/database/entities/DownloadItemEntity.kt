/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Registry of file downloads handed to the system DownloadManager, joined with the
 * app metadata needed to render the Downloads screen and resolve offline copies.
 */
@Entity(
  tableName = "download_items",
  indices = [Index("systemDownloadId"), Index("jellyfinItemId")],
)
data class DownloadItemEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  /** ID returned by the system DownloadManager; -1 when not currently enqueued. */
  val systemDownloadId: Long = -1,
  val url: String,
  val dirPath: String,
  val fileName: String,
  /** Request headers for the transfer, one `name: value` per line. */
  val stagingPath: String? = null,
  /** Name of [app.infinity.mpvz.domain.download.AppDownloadStatus]. */
  val status: String = "QUEUED",
  val progress: Int = 0,
  val totalBytes: Long = 0,
  val failureReason: String? = null,
  val timeQueued: Long = System.currentTimeMillis(),
  val source: String = "link",
  val title: String = "",
  val posterUrl: String? = null,
  val sourceUrl: String? = null,
  val jellyfinServerId: String? = null,
  val jellyfinItemId: String? = null,
  val jellyfinSeriesName: String? = null,
  val seasonNumber: Int? = null,
  val episodeNumber: Int? = null,
  val isAudio: Boolean = false,
)
