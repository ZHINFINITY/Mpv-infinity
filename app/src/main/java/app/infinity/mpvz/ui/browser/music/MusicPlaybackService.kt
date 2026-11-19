/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Owns the Music library's audio-only player and media session independently of video playback. */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
  private lateinit var player: ExoPlayer
  private lateinit var mediaSession: MediaSession
  private val handler = Handler(Looper.getMainLooper())

  private val positionPublisher =
    object : Runnable {
      override fun run() {
        if (!::player.isInitialized) return
        publishSnapshot()
        handler.postDelayed(this, POSITION_UPDATE_MS)
      }
    }

  private val listener =
    object : Player.Listener {
      override fun onEvents(player: Player, events: Player.Events) {
        publishSnapshot(refreshQueue = events.contains(Player.EVENT_TIMELINE_CHANGED))
      }
    }

  override fun onCreate() {
    super.onCreate()
    val renderersFactory =
      DefaultRenderersFactory(this)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    player = ExoPlayer.Builder(this).setRenderersFactory(renderersFactory).build()
    player.setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build(),
      true,
    )
    player.setHandleAudioBecomingNoisy(true)
    player.addListener(listener)

    val openPlayerIntent = Intent(this, MusicPlayerActivity::class.java)
    val sessionActivity =
      PendingIntent.getActivity(
        this,
        SESSION_ACTIVITY_REQUEST_CODE,
        openPlayerIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    mediaSession =
      MediaSession.Builder(this, player)
        .setSessionActivity(sessionActivity)
        .build()

    publishSnapshot(refreshQueue = true)
    handler.post(positionPublisher)
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

  override fun onDestroy() {
    handler.removeCallbacks(positionPublisher)
    if (::mediaSession.isInitialized) mediaSession.release()
    if (::player.isInitialized) {
      player.removeListener(listener)
      player.release()
    }
    MusicPlaybackSession.reset()
    super.onDestroy()
  }

  private fun publishSnapshot(refreshQueue: Boolean = false) {
    if (!::player.isInitialized) return
    val previous = MusicPlaybackSession.state.value
    val currentItem = player.currentMediaItem
    val metadata = currentItem?.mediaMetadata
    val queue =
      if (refreshQueue || (previous.queue.isEmpty() && player.mediaItemCount > 0)) {
        (0 until player.mediaItemCount).map { index ->
          val item = player.getMediaItemAt(index)
          MusicQueueTrackSnapshot(
            mediaId = item.mediaId,
            uri = item.localConfiguration?.uri?.toString(),
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            artworkUri = item.mediaMetadata.artworkUri?.toString(),
            durationMs = item.mediaMetadata.extras?.getLong(MUSIC_METADATA_DURATION_MS, 0L) ?: 0L,
          )
        }
      } else {
        previous.queue
      }
    val duration =
      player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        ?: metadata?.extras?.getLong(MUSIC_METADATA_DURATION_MS)?.takeIf { it > 0L }
        ?: 0L
    MusicPlaybackSession.publish(
      MusicPlaybackSnapshot(
        currentMediaId = currentItem?.mediaId,
        currentUri = currentItem?.localConfiguration?.uri?.toString(),
        title = metadata?.title?.toString(),
        artist = metadata?.artist?.toString(),
        album = metadata?.albumTitle?.toString(),
        artworkUri = metadata?.artworkUri?.toString(),
        sourceExtension = metadata?.extras?.getString(MUSIC_METADATA_SOURCE_EXTENSION),
        currentIndex = player.currentMediaItemIndex,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = duration,
        isPlaying = player.isPlaying,
        isBuffering = player.playbackState == Player.STATE_BUFFERING,
        repeatMode = player.repeatMode,
        shuffleEnabled = player.shuffleModeEnabled,
        playbackSpeed = player.playbackParameters.speed,
        queue = queue,
      ),
    )
  }

  private companion object {
    const val POSITION_UPDATE_MS = 250L
    const val SESSION_ACTIVITY_REQUEST_CODE = 4_192
  }
}
