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
 * An in-app folder for snapshots. Nothing on disk moves: the folder is a grouping over
 * [FrameCaptureEntity] rows, and the images stay in the gallery's own `mpvSnaps` album.
 *
 * The unique index on [name] is the backstop for the repository's case-insensitive duplicate check —
 * it only catches exact matches, which is why the check runs first and is the stricter of the two.
 */
@Entity(
  tableName = "snapshot_folders",
  indices = [Index(value = ["name"], unique = true)],
)
data class SnapshotFolderEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val createdAt: Long = System.currentTimeMillis(),
)
