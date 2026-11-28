/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.infinity.mpvz.database.entities.SnapshotFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SnapshotFolderDao {
  /** Ordered here rather than in the UI so the unsorted default is already stable. */
  @Query("SELECT * FROM snapshot_folders ORDER BY name COLLATE NOCASE ASC")
  fun observeAll(): Flow<List<SnapshotFolderEntity>>

  @Query("SELECT * FROM snapshot_folders WHERE id = :id")
  suspend fun findById(id: Long): SnapshotFolderEntity?

  /**
   * Case-insensitive on purpose: two folders called "Fights" and "fights" are indistinguishable in
   * the UI, so the duplicate check treats them as the same name even though the unique index does not.
   */
  @Query("SELECT * FROM snapshot_folders WHERE name = :name COLLATE NOCASE LIMIT 1")
  suspend fun findByName(name: String): SnapshotFolderEntity?

  @Insert
  suspend fun insert(folder: SnapshotFolderEntity): Long

  @Query("UPDATE snapshot_folders SET name = :name WHERE id = :id")
  suspend fun updateName(
    id: Long,
    name: String,
  )

  @Query("DELETE FROM snapshot_folders WHERE id = :id")
  suspend fun delete(id: Long)
}
