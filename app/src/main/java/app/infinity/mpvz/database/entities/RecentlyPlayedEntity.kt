/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Six DAO queries filter or update by filePath and the history views order by timestamp; without
// these the table is scanned end to end every time.
@Entity(
  indices = [
    Index(value = ["filePath"]),
    Index(value = ["timestamp"]),
  ],
)
data class RecentlyPlayedEntity(
  @PrimaryKey(autoGenerate = true) val id: Int = 0,
  val filePath: String,
  val fileName: String,
  val videoTitle: String? = null,
  val duration: Long = 0,
  val fileSize: Long = 0,
  val width: Int = 0,
  val height: Int = 0,
  val timestamp: Long,
  val launchSource: String? = null,
  val playlistId: Int? = null,
)
