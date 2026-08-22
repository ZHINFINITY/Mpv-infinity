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
import android.widget.Toast
import app.infinity.mpvz.R
import app.infinity.mpvz.domain.media.model.Video
import app.infinity.mpvz.ui.player.PlaybackItem
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.PlayerActivity

/**
 * Process-local queue for short-lived listening sessions such as a jog.
 *
 * It intentionally does not persist as a database playlist. A manual media launch clears it in
 * PlayerActivity, while the player itself can continue navigating through mixed audio/video items.
 */
object TemporaryPlaybackQueue {
  fun add(context: Context, videos: List<Video>) {
    val additions = videos.map(::toPlaybackItem).distinctBy { it.stableId }
    if (additions.isEmpty()) return

    val current = PlaybackSession.queue.value
    val base = if (current.isExplicitQueue) current.items else emptyList()
    val existingIds = base.mapTo(HashSet()) { it.stableId }
    val merged = base + additions.filterNot { it.stableId in existingIds }
    val currentIndex = current.currentIndex.takeIf { it in merged.indices } ?: 0

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

  fun start(context: Context) {
    val queue = PlaybackSession.queue.value
    if (!queue.isTemporaryQueue) {
      Toast.makeText(context, R.string.queue_empty, Toast.LENGTH_SHORT).show()
      return
    }
    val item = queue.currentItem ?: queue.items.firstOrNull() ?: run {
      Toast.makeText(context, R.string.queue_empty, Toast.LENGTH_SHORT).show()
      return
    }
    val isAudio = item.mimeType?.startsWith("audio/", ignoreCase = true) == true

    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(item.originalUri)).apply {
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
