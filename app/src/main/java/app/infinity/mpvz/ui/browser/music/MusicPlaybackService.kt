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
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.infinity.mpvz.repository.WatchStatsRepository
import app.infinity.mpvz.utils.history.RecentlyPlayedOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns the Music library's audio-only player and media session independently of video playback. */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
  private lateinit var player: ExoPlayer
  private lateinit var mediaSession: MediaSession
  private val handler = Handler(Looper.getMainLooper())
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val historyGate = MusicPlaybackHistoryGate()
  @Volatile private var pendingResumeSnapshot: MusicPlaybackSnapshot? = null
  @Volatile private var resumePersistenceJob: Job? = null
  private var lastResumePersistenceElapsedMs = 0L
  private var watchStatsJob: Job? = null
  private var watchStatsRepository: WatchStatsRepository? = null
  private var watchStatsLastRecordAtMs = 0L

  private val watchStatsTicker =
    object : Runnable {
      override fun run() {
        if (!::player.isInitialized) return
        recordWatchStatsSegment()
        handler.postDelayed(this, WATCH_STATS_TICK_MS)
      }
    }

  private fun recordWatchStatsSegment() {
    val repository = watchStatsRepository ?: return
    if (!player.isPlaying) return
    val metadata = player.currentMediaItem?.mediaMetadata ?: return
    val title =
      metadata.title?.toString()?.takeIf { it.isNotBlank() }
        ?: player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment
          ?.substringAfterLast('/')
          ?.takeIf { it.isNotBlank() }
        ?: return
    val nowMs = System.currentTimeMillis()
    // A new session starts only after a 2-minute gap, not on every tick.
    val countsAsSession = nowMs - watchStatsLastRecordAtMs > 120_000L
    watchStatsLastRecordAtMs = nowMs
    serviceScope.launch {
      runCatching {
        repository.record(
          title = title,
          isAudio = true,
          seconds = WATCH_STATS_TICK_MS / 1000L,
          artworkUri = metadata.artworkUri?.toString(),
          countsAsSession = countsAsSession,
        )
      }
    }
  }

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

      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        recordRecentlyPlayed(player.isPlaying, isMediaItemTransition = true)
      }

      override fun onIsPlayingChanged(isPlaying: Boolean) {
        recordRecentlyPlayed(isPlaying, isMediaItemTransition = false)
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
    watchStatsRepository =
      runCatching {
        org.koin.core.context.GlobalContext.get().get<WatchStatsRepository>()
      }.getOrNull()
    handler.postDelayed(watchStatsTicker, WATCH_STATS_TICK_MS)
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

  override fun onDestroy() {
    handler.removeCallbacks(positionPublisher)
    handler.removeCallbacks(watchStatsTicker)
    serviceScope.cancel()
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
            songId = item.mediaMetadata.extras?.getLong(MUSIC_METADATA_SONG_ID, 0L) ?: 0L,
            album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            albumId = item.mediaMetadata.extras?.getLong(MUSIC_METADATA_ALBUM_ID, 0L) ?: 0L,
            path = item.mediaMetadata.extras?.getString(MUSIC_METADATA_PATH),
            dateAdded = item.mediaMetadata.extras?.getLong(MUSIC_METADATA_DATE_ADDED, 0L) ?: 0L,
            trackNumber = item.mediaMetadata.extras?.getInt(MUSIC_METADATA_TRACK_NUMBER, 0) ?: 0,
            year = item.mediaMetadata.extras?.getInt(MUSIC_METADATA_YEAR, 0) ?: 0,
            size = item.mediaMetadata.extras?.getLong(MUSIC_METADATA_SIZE, 0L) ?: 0L,
          )
        }
      } else {
        previous.queue
      }
    val duration =
      player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        ?: metadata?.extras?.getLong(MUSIC_METADATA_DURATION_MS)?.takeIf { it > 0L }
        ?: 0L
    val snapshot =
      MusicPlaybackSnapshot(
        currentMediaId = currentItem?.mediaId,
        currentUri = currentItem?.localConfiguration?.uri?.toString(),
        title = metadata?.title?.toString(),
        artist = metadata?.artist?.toString(),
        album = metadata?.albumTitle?.toString(),
        artworkUri = metadata?.artworkUri?.toString(),
        sourceExtension = metadata?.extras?.getString(MUSIC_METADATA_SOURCE_EXTENSION),
        launchToken = metadata?.extras?.getString(MUSIC_METADATA_LAUNCH_TOKEN),
        launchSource = metadata?.extras?.getString(MUSIC_METADATA_LAUNCH_SOURCE),
        currentIndex = player.currentMediaItemIndex,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = duration,
        isPlaying = player.isPlaying,
        isBuffering = player.playbackState == Player.STATE_BUFFERING,
        repeatMode = player.repeatMode,
        shuffleEnabled = player.shuffleModeEnabled,
        playbackSpeed = player.playbackParameters.speed,
        queue = queue,
      )
    MusicPlaybackSession.publish(snapshot)
    scheduleResumePersistence(snapshot)
  }

  private fun scheduleResumePersistence(snapshot: MusicPlaybackSnapshot) {
    if (
      snapshot.launchToken.isNullOrBlank() ||
        snapshot.queue.isEmpty() ||
        snapshot.currentIndex !in snapshot.queue.indices
    ) {
      return
    }
    pendingResumeSnapshot = snapshot
    if (resumePersistenceJob?.isActive == true) return

    resumePersistenceJob =
      serviceScope.launch {
        while (true) {
          val elapsedSinceLastSave = SystemClock.elapsedRealtime() - lastResumePersistenceElapsedMs
          val waitMs =
            (MIN_RESUME_PERSISTENCE_INTERVAL_MS - elapsedSinceLastSave).coerceAtLeast(0L)
          if (waitMs > 0L) delay(waitMs)
          val pending = pendingResumeSnapshot ?: break
          pendingResumeSnapshot = null
          if (MusicPlaybackResumeStore.save(applicationContext, pending)) {
            lastResumePersistenceElapsedMs = SystemClock.elapsedRealtime()
          }
        }
        resumePersistenceJob = null
      }
  }

  private fun recordRecentlyPlayed(isPlaying: Boolean, isMediaItemTransition: Boolean) {
    val item = player.currentMediaItem ?: return
    val mediaId = item.mediaId
    val shouldRecord =
      if (isMediaItemTransition) {
        historyGate.onMediaItemTransition(mediaId, isPlaying)
      } else {
        historyGate.onIsPlayingChanged(mediaId, isPlaying)
      }
    if (shouldRecord == null) return

    val uri = item.localConfiguration?.uri ?: return
    val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT) ?: return
    if (scheme != "file" && scheme != "content") return
    val extras = item.mediaMetadata.extras
    val title =
      item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: "Audio Track"
    val durationMs =
      player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        ?: extras?.getLong(MUSIC_METADATA_DURATION_MS, 0L)?.coerceAtLeast(0L)
        ?: 0L
    val fileSize = extras?.getLong(MUSIC_METADATA_SIZE, 0L)?.coerceAtLeast(0L) ?: 0L
    val launchSource =
      extras?.getString(MUSIC_METADATA_LAUNCH_SOURCE)?.takeIf { it.isNotBlank() }
        ?: "music_library"

    serviceScope.launch {
      runCatching {
        val filePath = RecentlyPlayedOps.resolveRecentlyPlayedFilePath(applicationContext, uri)
        if (filePath.isNotBlank()) {
          RecentlyPlayedOps.addRecentlyPlayed(
            context = applicationContext,
            filePath = filePath,
            fileName = title,
            videoTitle = title,
            duration = durationMs,
            fileSize = fileSize,
            launchSource = launchSource,
          )
        }
      }.onFailure { error ->
        android.util.Log.w(TAG, "Unable to add Music playback to Recently Played", error)
      }
    }
  }

  private companion object {
    const val TAG = "MusicPlaybackService"
    const val POSITION_UPDATE_MS = 250L
    const val WATCH_STATS_TICK_MS = 15_000L
    const val MIN_RESUME_PERSISTENCE_INTERVAL_MS = 1_000L
    const val SESSION_ACTIVITY_REQUEST_CODE = 4_192
  }
}
