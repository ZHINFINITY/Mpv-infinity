/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import app.infinity.mpvz.MainActivity
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.player.AudioPlaybackResumeStore
import app.infinity.mpvz.ui.player.AudioResumeLaunch
import app.infinity.mpvz.ui.player.MediaPlaybackService
import app.infinity.mpvz.ui.player.PlaybackItem
import app.infinity.mpvz.ui.player.PlaybackQueueState
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.PlayerActivity

/**
 * Home-screen widget for the audio player. Shows the current (or last persisted) track and offers
 * previous / play-pause / next buttons. Buttons work while the app process is dead: the transport
 * broadcast either reuses the live playback service (same actions the media notification uses) or
 * cold-launches [PlayerActivity] with the persisted resume snapshot and a pending media action.
 */
class MusicPlayerWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    val pendingResult = goAsync()
    Thread {
      try {
        refreshAllInternal(context, appWidgetManager, appWidgetIds)
      } finally {
        pendingResult.finish()
      }
    }.start()
  }

  override fun onReceive(context: Context, intent: Intent) {
    super.onReceive(context, intent)
    when (intent.action) {
      ACTION_PREVIOUS -> handleTransport(context, MediaAction.PREVIOUS)
      ACTION_PLAY_PAUSE -> handleTransport(context, MediaAction.PLAY_PAUSE)
      ACTION_NEXT -> handleTransport(context, MediaAction.NEXT)
    }
  }

  private enum class MediaAction { PREVIOUS, PLAY_PAUSE, NEXT }

  private fun handleTransport(context: Context, action: MediaAction) {
    val appContext = context.applicationContext
    if (PlaybackSession.isInitialized) {
      val serviceAction = when (action) {
        MediaAction.PREVIOUS -> MediaPlaybackService.ACTION_NOTIFICATION_PREVIOUS
        MediaAction.PLAY_PAUSE -> MediaPlaybackService.ACTION_NOTIFICATION_PLAY_PAUSE
        MediaAction.NEXT -> MediaPlaybackService.ACTION_NOTIFICATION_NEXT
      }
      val delivered = runCatching {
        ContextCompat.startForegroundService(
          appContext,
          Intent(appContext, MediaPlaybackService::class.java).setAction(serviceAction),
        )
      }.isSuccess
      if (delivered) return
    }

    // Process was dead (or the service start was rejected): cold-resume from the persisted store.
    val snapshot = runCatching { AudioPlaybackResumeStore.load(appContext) }.getOrNull()
    val item = snapshot?.currentItem
    if (snapshot != null && item != null) {
      runCatching {
        appContext.startActivity(buildResumeIntent(appContext, item, snapshot.queueState, action))
      }.onFailure { Log.w(TAG, "Widget cold-resume launch failed", it) }
    } else {
      openApp(appContext)
    }
  }

  private fun openApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: Intent(context, MainActivity::class.java)
    runCatching {
      context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Log.w(TAG, "Widget app launch failed", it) }
  }

  private fun buildResumeIntent(
    context: Context,
    item: PlaybackItem,
    queueState: PlaybackQueueState,
    action: MediaAction,
  ): Intent =
    Intent(context, PlayerActivity::class.java).apply {
      // Mirrors PlayerActivity.restoreAudioPlaybackSessionIfNeeded extras: when the uri is set the
      // activity takes this intent as the audio source and still restores the persisted queue.
      AudioResumeLaunch.applyTo(this, item.originalUri, item.mimeType)
      putExtra("is_audio", true)
      putExtra("media_library_audio", true)
      putExtra("title", item.title)
      putExtra("media_artist", item.artist)
      putExtra("media_identifier", item.stableId)
      putExtra("playlist_index", queueState.currentIndex)
      item.artworkUri?.takeIf { it.isNotBlank() }?.let { artwork ->
        putStringArrayListExtra("playlist_artwork_urls", arrayListOf(artwork))
      }
      val headerPairs = item.headers.flatMap { (name, value) -> listOf(name, value) }
      if (headerPairs.isNotEmpty()) putExtra("headers", headerPairs.toTypedArray())
      item.networkSource?.let { source ->
        putExtra("network_connection_id", source.connectionId)
        putExtra("network_file_path", source.relativePath)
      }
      putExtra(EXTRA_WIDGET_MEDIA_ACTION, action.name)
      addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
          Intent.FLAG_ACTIVITY_CLEAR_TOP or
          Intent.FLAG_ACTIVITY_SINGLE_TOP,
      )
    }

  private fun buildRemoteViews(context: Context): RemoteViews {
    val appContext = context.applicationContext
    val item = if (PlaybackSession.isInitialized) {
      PlaybackSession.state.value.currentItem
    } else {
      runCatching { AudioPlaybackResumeStore.load(appContext) }.getOrNull()?.currentItem
    }
    val paused = if (PlaybackSession.isInitialized) {
      PlaybackSession.state.value.paused
    } else {
      true
    }

    val views = RemoteViews(appContext.packageName, R.layout.widget_music_player)
    views.setTextViewText(
      R.id.widget_track_title,
      item?.title ?: appContext.getString(R.string.widget_music_no_media),
    )
    views.setTextViewText(
      R.id.widget_track_artist,
      item?.artist ?: appContext.getString(R.string.widget_music_open_player_hint),
    )
    views.setImageViewResource(
      R.id.widget_button_play_pause,
      if (paused) R.drawable.materialsymbols_ic_play_arrow_rounded
      else R.drawable.materialsymbols_ic_pause_rounded,
    )

    val openPlayer = openPlayerPendingIntent(appContext)
    views.setOnClickPendingIntent(R.id.widget_music_player_root, openPlayer)
    views.setOnClickPendingIntent(
      R.id.widget_button_previous,
      transportPendingIntent(appContext, ACTION_PREVIOUS, REQUEST_CODE_PREVIOUS),
    )
    views.setOnClickPendingIntent(
      R.id.widget_button_play_pause,
      transportPendingIntent(appContext, ACTION_PLAY_PAUSE, REQUEST_CODE_PLAY_PAUSE),
    )
    views.setOnClickPendingIntent(
      R.id.widget_button_next,
      transportPendingIntent(appContext, ACTION_NEXT, REQUEST_CODE_NEXT),
    )
    return views
  }

  private fun transportPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      requestCode,
      Intent(context, MusicPlayerWidgetProvider::class.java).setAction(action),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  private fun openPlayerPendingIntent(context: Context): PendingIntent {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: Intent(context, MainActivity::class.java)
    return PendingIntent.getActivity(
      context,
      REQUEST_CODE_OPEN_PLAYER,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  companion object {
    private const val TAG = "MusicPlayerWidget"

    const val ACTION_PREVIOUS = "app.infinity.mpvz.action.WIDGET_PREVIOUS"
    const val ACTION_PLAY_PAUSE = "app.infinity.mpvz.action.WIDGET_PLAY_PAUSE"
    const val ACTION_NEXT = "app.infinity.mpvz.action.WIDGET_NEXT"

    /** Extra set on the cold-resume [PlayerActivity] launch so it applies the widget action once playing. */
    const val EXTRA_WIDGET_MEDIA_ACTION = "app.infinity.mpvz.extra.WIDGET_MEDIA_ACTION"

    private const val REQUEST_CODE_OPEN_PLAYER = 4200
    private const val REQUEST_CODE_PREVIOUS = 4201
    private const val REQUEST_CODE_PLAY_PAUSE = 4202
    private const val REQUEST_CODE_NEXT = 4203

    fun refreshAll(context: Context) {
      refreshAllInternal(context.applicationContext)
    }

    private fun refreshAllInternal(
      context: Context,
      manager: AppWidgetManager = AppWidgetManager.getInstance(context),
      appWidgetIds: IntArray = manager.getAppWidgetIds(
        ComponentName(context, MusicPlayerWidgetProvider::class.java),
      ),
    ) {
      if (appWidgetIds.isEmpty()) return
      runCatching {
        val views = MusicPlayerWidgetProvider().buildRemoteViews(context)
        manager.updateAppWidget(appWidgetIds, views)
      }.onFailure { Log.w(TAG, "Widget refresh failed", it) }
    }
  }
}
