/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.database.repository

import app.infinity.mpvz.database.MpvInfinityDatabase
import app.infinity.mpvz.database.entities.PlaybackStateEntity
import app.infinity.mpvz.domain.playbackstate.repository.PlaybackStateRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackStateRepositoryImpl(
  private val database: MpvInfinityDatabase,
) : PlaybackStateRepository {
  // Library screens can ask for the complete playback-state set several times while navigating or
  // refreshing. Keep one process-local snapshot after the first unavoidable Room load and maintain
  // it incrementally on writes instead of allocating/querying the full table on every request.
  private val stateCache = ConcurrentHashMap<String, PlaybackStateEntity>()
  private val allStatesLoaded = AtomicBoolean(false)
  private val fullLoadMutex = Mutex()

  override suspend fun upsert(playbackState: PlaybackStateEntity) {
    database.videoDataDao().upsert(playbackState)
    stateCache[playbackState.mediaTitle] = playbackState
  }

  override suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity? {
    stateCache[mediaTitle]?.let { return it }
    return database.videoDataDao().getVideoDataByTitle(mediaTitle)?.also { state ->
      stateCache[state.mediaTitle] = state
    }
  }

  override suspend fun getAllPlaybackStates(): List<PlaybackStateEntity> {
    if (allStatesLoaded.get()) return stateCache.values.toList()

    return fullLoadMutex.withLock {
      if (!allStatesLoaded.get()) {
        val loaded = database.videoDataDao().getAllPlaybackStates()
        stateCache.clear()
        loaded.forEach { state -> stateCache[state.mediaTitle] = state }
        allStatesLoaded.set(true)
      }
      stateCache.values.toList()
    }
  }

  override suspend fun resetAllVideoAspectSettings() {
    database.videoDataDao().resetAllVideoAspectSettings()
    stateCache.replaceAll { _, state -> state.copy(videoAspect = "Fit", customAspectRatio = -1f) }
  }

  override suspend fun clearAllPlaybackStates() {
    database.videoDataDao().clearAllPlaybackStates()
    stateCache.clear()
    allStatesLoaded.set(true)
  }

  override suspend fun deleteByTitle(mediaTitle: String) {
    database.videoDataDao().deleteByTitle(mediaTitle)
    stateCache.remove(mediaTitle)
  }

  override suspend fun updateMediaTitle(
    oldTitle: String,
    newTitle: String,
  ) {
    database.videoDataDao().updateMediaTitle(oldTitle, newTitle)
    stateCache.remove(oldTitle)?.let { state ->
      stateCache[newTitle] = state.copy(mediaTitle = newTitle)
    }
  }
}
