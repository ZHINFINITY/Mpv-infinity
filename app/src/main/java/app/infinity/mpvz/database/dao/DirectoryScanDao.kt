/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.infinity.mpvz.database.entities.DirectoryScanEntity

@Dao
interface DirectoryScanDao {
  @Query("SELECT * FROM directory_scan_index WHERE scanKey = :scanKey")
  suspend fun getEntries(scanKey: String): List<DirectoryScanEntity>

  @Query("SELECT DISTINCT rootPath FROM directory_scan_index WHERE scanKey = :scanKey AND isNoMediaRoot = 1")
  suspend fun getNoMediaRoots(scanKey: String): List<String>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entries: List<DirectoryScanEntity>)

  @Query("DELETE FROM directory_scan_index WHERE scanKey = :scanKey AND rootPath = :rootPath")
  suspend fun deleteRoot(
    scanKey: String,
    rootPath: String,
  )

  @Query("DELETE FROM directory_scan_index WHERE scanKey = :scanKey")
  suspend fun deleteScan(scanKey: String)

  @Query(
    "DELETE FROM directory_scan_index WHERE scanKey = :scanKey " +
      "AND (path = :path OR substr(path, 1, length(:pathPrefix)) = :pathPrefix)",
  )
  suspend fun deleteSubtree(
    scanKey: String,
    path: String,
    pathPrefix: String,
  )

}
