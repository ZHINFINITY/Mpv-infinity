/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.components

import android.content.Context
import android.widget.Toast
import app.infinity.mpvz.R
import app.infinity.mpvz.domain.media.model.Video
import app.infinity.mpvz.ui.player.PlaybackIdentity
import app.infinity.mpvz.ui.player.PlaybackItem
import app.infinity.mpvz.ui.player.PlaybackSession

enum class QueueInsertion {
  PlayNext,
  AddToEnd,
}

fun addVideosToPlaybackQueue(
  context: Context,
  videos: List<Video>,
  insertion: QueueInsertion,
): Boolean {
  if (videos.isEmpty()) return false
  if (!PlaybackSession.queue.value.hasItems) {
    Toast.makeText(context, R.string.queue_requires_playback, Toast.LENGTH_SHORT).show()
    return false
  }

  val items =
    videos.map { video ->
      val uri = video.uri.toString()
      PlaybackItem.fromUri(
        uri = uri,
        stableId =
          video.path
            .takeIf { path -> path.isNotBlank() && !path.contains("://") }
            ?.let(PlaybackIdentity::forLocalPath),
        title = video.displayName.ifBlank { video.title },
        mimeType = video.mimeType,
      )
    }
  val added =
    when (insertion) {
      QueueInsertion.PlayNext -> PlaybackSession.insertQueueItemsNext(items)
      QueueInsertion.AddToEnd -> PlaybackSession.appendQueueItems(items)
    }
  if (added) {
    val message =
      when (insertion) {
        QueueInsertion.PlayNext -> R.string.queue_added_next
        QueueInsertion.AddToEnd -> R.string.queue_added_end
      }
    Toast.makeText(context, context.getString(message, items.size), Toast.LENGTH_SHORT).show()
  }
  return added
}