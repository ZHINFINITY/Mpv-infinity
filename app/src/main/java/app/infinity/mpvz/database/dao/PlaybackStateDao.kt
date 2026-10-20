/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.infinity.mpvz.database.entities.PlaybackStateEntity

@Dao
interface PlaybackStateDao {
  @Upsert
  suspend fun upsert(playbackStateEntity: PlaybackStateEntity)

  @Query("SELECT * FROM PlaybackStateEntity WHERE mediaTitle = :mediaTitle LIMIT 1")
  suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity?

  @Query("DELETE FROM PlaybackStateEntity")
  suspend fun clearAllPlaybackStates()

  @Query("DELETE FROM PlaybackStateEntity WHERE mediaTitle = :mediaTitle")
  suspend fun deleteByTitle(mediaTitle: String)

  @Query(
    """
    UPDATE PlaybackStateEntity 
    SET mediaTitle = :newTitle 
    WHERE mediaTitle = :oldTitle
  """,
  )
  suspend fun updateMediaTitle(
    oldTitle: String,
    newTitle: String,
  )

  @Query("SELECT * FROM PlaybackStateEntity")
  suspend fun getAllPlaybackStates(): List<PlaybackStateEntity>

  @Query("UPDATE PlaybackStateEntity SET videoAspect = 'Fit', customAspectRatio = -1.0")
  suspend fun resetAllVideoAspectSettings()

  @Upsert
  suspend fun upsertAll(playbackStates: List<PlaybackStateEntity>)
}
