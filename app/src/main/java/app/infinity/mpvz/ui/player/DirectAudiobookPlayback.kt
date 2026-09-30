package app.infinity.mpvz.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.infinity.mpvz.domain.audiobook.AudiobookFolderTrack
import app.infinity.mpvz.domain.audiobook.directAudiobookFolderIdentity
import app.infinity.mpvz.domain.audiobook.directAudiobookSelectionIdentity
import app.infinity.mpvz.domain.audiobook.directAudiobookTrackIdentity
import app.infinity.mpvz.utils.media.MediaUtils

internal fun createDirectAudiobookTrackPlaybackItem(
  queueIdentity: String,
  track: AudiobookFolderTrack,
): PlaybackItem {
  val title = track.name.substringBeforeLast('.', track.name)
  val mimeType = track.mimeType?.takeIf { it.startsWith("audio/", ignoreCase = true) } ?: "audio/*"
  return PlaybackItem.fromUri(
    uri = track.uri,
    stableId = PlaybackIdentity.forUri(track.uri),
    title = title,
    mimeType = mimeType,
  ).copy(
    directAudiobook = DirectAudiobookPlaybackInfo(
      queueIdentity = queueIdentity,
      trackIdentity = directAudiobookTrackIdentity(track.uri),
    ),
  )
}

/** Creates ephemeral queue entries that reference existing SAF URIs without copying or importing audio. */
internal fun buildDirectAudiobookQueue(
  queueIdentity: String,
  tracks: List<AudiobookFolderTrack>,
): List<PlaybackItem> = tracks.map { track -> createDirectAudiobookTrackPlaybackItem(queueIdentity, track) }

internal fun directAudiobookResumeIndex(
  items: List<PlaybackItem>,
  savedTrackIdentity: String?,
): Int =
  items.indexOfFirst { item -> item.directAudiobook?.trackIdentity == savedTrackIdentity }
    .takeIf { it >= 0 }
    ?: 0

internal object DirectAudiobookResumeStore {
  private const val PREFERENCES_NAME = "direct_audiobook_resume"
  private const val KEY_PREFIX = "queue_track_"

  fun lastTrackIdentity(context: Context, queueIdentity: String): String? =
    context.applicationContext
      .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .getString(KEY_PREFIX + queueIdentity, null)

  fun recordPlaying(context: Context, info: DirectAudiobookPlaybackInfo) {
    if (info.queueIdentity.isBlank() || info.trackIdentity.isBlank()) return
    context.applicationContext
      .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_PREFIX + info.queueIdentity, info.trackIdentity)
      .apply()
  }
}

internal fun launchDirectAudiobookFolder(
  context: Context,
  folderUri: String,
  tracks: List<AudiobookFolderTrack>,
  selectedTrackUri: String? = null,
  restart: Boolean = false,
) {
  launchDirectAudiobookQueue(context, directAudiobookFolderIdentity(folderUri), tracks, selectedTrackUri, restart)
}

internal fun launchDirectAudiobookFiles(
  context: Context,
  tracks: List<AudiobookFolderTrack>,
) {
  val queueIdentity = directAudiobookSelectionIdentity(tracks.map { it.uri })
  launchDirectAudiobookQueue(context, queueIdentity, tracks)
}

private fun launchDirectAudiobookQueue(
  context: Context,
  queueIdentity: String,
  tracks: List<AudiobookFolderTrack>,
  selectedTrackUri: String? = null,
  restart: Boolean = false,
) {
  if (tracks.isEmpty()) return
  val items = buildDirectAudiobookQueue(queueIdentity, tracks)
  val requestedIndex = if (restart) 0 else
    selectedTrackUri?.let { uri -> items.indexOfFirst { it.originalUri == uri }.takeIf { it >= 0 } }
      ?: directAudiobookResumeIndex(items, DirectAudiobookResumeStore.lastTrackIdentity(context, queueIdentity))
  val selectedIndex = requestedIndex.coerceIn(items.indices)
  val selected = items[selectedIndex]
  val selectedUri = Uri.parse(selected.originalUri)
  val token = PreparedPlaybackLaunchStore.stage(items, selectedIndex, isExplicitQueue = true)
  context.startActivity(
    Intent(context, PlayerActivity::class.java).apply {
      action = Intent.ACTION_VIEW
      setDataAndType(selectedUri, selected.mimeType ?: "audio/*")
      putExtra(Intent.EXTRA_STREAM, selectedUri)
      putExtra("internal_launch", true)
      putExtra("is_audio", true)
      putExtra("launch_source", "audiobook_direct")
      putExtra("media_identifier", selected.stableId)
      selected.title?.let {
        putExtra("title", it)
        putExtra(MediaUtils.EXTRA_MEDIA_TITLE, it)
      }
      putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, true)
      putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_TOKEN, token)
      putExtra("playlist_index", selectedIndex)
      putExtra(AudiobookPlayback.EXTRA_DIRECT_RESTART, restart)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    },
  )
}
