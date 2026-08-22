/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.utils.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.infinity.mpvz.R
import app.infinity.mpvz.domain.media.model.Video
import app.infinity.mpvz.ui.player.PlaybackItem
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.PlayerActivity

/**
 * Process-local queue for short-lived listening sessions such as a jog.
 *
 * The explicit snapshot is kept separately from the player session because opening a normal music
 * playlist or another media item can legitimately replace PlaybackSession.queue. Starting the
 * temporary queue restores the complete user-selected snapshot before launching the player, so a
 * mixed audio/video queue cannot silently become a regular music-only playlist.
 */
object TemporaryPlaybackQueue {
  private val lock = Any()
  private var snapshotItems: List<PlaybackItem> = emptyList()
  private var snapshotIndex: Int = 0

  fun add(context: Context, videos: List<Video>) {
    val additions = videos.map(::toPlaybackItem).distinctBy { it.stableId }
    if (additions.isEmpty()) return

    val current = PlaybackSession.queue.value
    val snapshot = synchronized(lock) { snapshotItems to snapshotIndex }
    val base =
      when {
        snapshot.first.isNotEmpty() -> snapshot.first
        current.isTemporaryQueue -> current.items
        else -> emptyList()
      }
    val existingIds = base.mapTo(HashSet()) { it.stableId }
    val merged = base + additions.filterNot { it.stableId in existingIds }
    if (merged.isEmpty()) return

    val currentStableId = current.currentItem?.stableId
    val currentIndex =
      when {
        snapshot.first.isNotEmpty() -> snapshot.second.coerceIn(merged.indices)
        current.isTemporaryQueue && currentStableId != null ->
          merged.indexOfFirst { it.stableId == currentStableId }.takeIf { it >= 0 } ?: 0
        else -> 0
      }

    synchronized(lock) {
      snapshotItems = merged
      snapshotIndex = currentIndex
    }
    PlaybackSession.replaceQueue(
      items = merged,
      currentIndex = currentIndex,
      isExplicitQueue = true,
      isM3u = false,
      isTemporaryQueue = true,
    )
    Toast.makeText(
      context,
      context.getString(R.string.queue_items_added, additions.size),
      Toast.LENGTH_SHORT,
    ).show()
  }

  /**
   * Captures the current temporary queue after a player-side reorder or navigation operation.
   */
  fun syncFromSession() {
    val queue = PlaybackSession.queue.value
    if (!queue.isTemporaryQueue || queue.items.isEmpty()) return
    synchronized(lock) {
      snapshotItems = queue.items
      snapshotIndex = queue.currentIndex.coerceIn(queue.items.indices)
    }
  }

  /** Drops only the saved temporary snapshot; the caller may then publish a normal playlist. */
  fun discardSnapshot() {
    synchronized(lock) {
      snapshotItems = emptyList()
      snapshotIndex = 0
    }
  }

  /**
   * Restores the explicitly selected temporary queue into PlaybackSession before opening it.
   * This is intentionally idempotent and also repairs the queue if another launch replaced it.
   */
  fun start(context: Context) {
    val restoredQueue = synchronized(lock) {
      snapshotItems.takeIf { it.isNotEmpty() }?.let { items ->
        val session = PlaybackSession.queue.value
        val sessionCurrentId = session.currentItem?.stableId
        val index =
          if (session.isTemporaryQueue && sessionCurrentId != null) {
            items.indexOfFirst { it.stableId == sessionCurrentId }.takeIf { it >= 0 } ?: snapshotIndex
          } else {
            snapshotIndex
          }
        PlaybackSession.replaceQueue(
          items = items,
          currentIndex = index.coerceIn(items.indices),
          isExplicitQueue = true,
          isM3u = false,
          isTemporaryQueue = true,
        )
        PlaybackSession.queue.value
      }
    } ?: PlaybackSession.queue.value

    val queue = restoredQueue
    if (!queue.isTemporaryQueue) {
      Toast.makeText(context, R.string.queue_empty, Toast.LENGTH_SHORT).show()
      return
    }
    val item = queue.currentItem ?: queue.items.firstOrNull() ?: run {
      Toast.makeText(context, R.string.queue_empty, Toast.LENGTH_SHORT).show()
      return
    }
    val isAudio = item.mimeType?.startsWith("audio/", ignoreCase = true) == true

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.originalUri)).apply {
      setClass(context, PlayerActivity::class.java)
      addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      putExtra("internal_launch", true)
      putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, true)
      putExtra("playlist_index", queue.currentIndex.coerceAtLeast(0))
      putExtra("title", item.title)
      putExtra("is_audio", isAudio)
      putExtra("media_library_audio", isAudio)
      putExtra("launch_source", "temporary_queue")
    }
    context.startActivity(intent)
  }

  fun clear() {
    discardSnapshot()
    PlaybackSession.clearQueue()
  }

  private fun toPlaybackItem(video: Video): PlaybackItem =
    PlaybackItem.fromUri(
      uri = video.uri.toString(),
      title = video.title.takeIf { it.isNotBlank() } ?: video.displayName,
      mimeType = video.mimeType.takeIf { it.isNotBlank() },
      artworkUri = null,
      artist = video.artist,
    )
}
