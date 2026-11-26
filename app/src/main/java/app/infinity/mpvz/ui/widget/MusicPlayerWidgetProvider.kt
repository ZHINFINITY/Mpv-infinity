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
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt
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
 * previous / play-pause / next buttons, rendered as one of three size-aware layouts: the wide
 * 4x2 with rounded artwork and the full transport row, a tall variant for portrait footprints,
 * and the compact single-row layout for small cells. A tint derived from the track's artwork
 * colors the progress bar and play button so the widget follows the music. Buttons work while
 * the app process is dead: the transport broadcast either reuses the live playback service (same
 * actions the media notification uses) or cold-launches [PlayerActivity] with the persisted
 * resume snapshot and a pending media action.
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
      } catch (t: Throwable) {
        // A failed refresh must never crash the process: the launcher would then show
        // "Can't load widget" until the widget is re-added.
        Log.e(TAG, "Widget update failed", t)
      } finally {
        pendingResult.finish()
      }
    }.start()
  }

  override fun onAppWidgetOptionsChanged(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    newOptions: Bundle?,
  ) {
    // Resizing is how the user moves between the wide / tall / compact layouts. Decode and
    // build off the main thread so a resize never janks the launcher.
    val pendingResult = goAsync()
    Thread {
      try {
        refreshAllInternal(context, appWidgetManager, intArrayOf(appWidgetId))
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

  companion object {
    const val TAG = "MusicPlayerWidget"

    const val ACTION_PREVIOUS = "app.infinity.mpvz.action.WIDGET_PREVIOUS"
    const val ACTION_PLAY_PAUSE = "app.infinity.mpvz.action.WIDGET_PLAY_PAUSE"
    const val ACTION_NEXT = "app.infinity.mpvz.action.WIDGET_NEXT"

    /** Extra set on the cold-resume [PlayerActivity] launch so it applies the widget action once playing. */
    const val EXTRA_WIDGET_MEDIA_ACTION = "app.infinity.mpvz.extra.WIDGET_MEDIA_ACTION"

    const val REQUEST_CODE_OPEN_PLAYER = 4200
    const val REQUEST_CODE_PREVIOUS = 4201
    const val REQUEST_CODE_PLAY_PAUSE = 4202
    const val REQUEST_CODE_NEXT = 4203

    /** Wide layout from this dp width up; below it the tall layout; small cells go compact. */
    const val WIDE_MIN_WIDTH = 320

    /** Below this height the tall layout's stacked artwork would squash; use a row layout. */
    const val TALL_MIN_HEIGHT = 180

    /** Below this dp width the widget renders the compact single-row layout. */
    const val COMPACT_MAX_WIDTH = 180

    /** Height/width ratio above which a cell is portrait enough for the tall stacked layout. */
    const val PORTRAIT_ASPECT = 1.15f

    /** Default palette seed when the track has no decodable artwork. */
    const val DEFAULT_SEED = 0xFF2B2536.toInt()

    /** Latest playback position (seconds) pushed by the service before each widget refresh. */
    @Volatile var latestPositionSeconds: Float? = null

    /** Latest track duration (seconds) pushed by the service; null for live/unknown lengths. */
    @Volatile var latestDurationSeconds: Float? = null

    /** Called by the service just before a refresh so the progress bar tracks playback. */
    fun publishPosition(seconds: Double) {
      latestPositionSeconds = seconds.toFloat()
    }

    /** Position plus duration, so the widget's progress bar can reflect real progress. */
    fun publishProgress(positionSeconds: Double, durationMs: Long) {
      latestPositionSeconds = positionSeconds.toFloat()
      latestDurationSeconds = if (durationMs > 0L) durationMs / 1000f else null
    }

    /** Decoded artwork cached across refreshes; artwork changes are rare and decodes are not. */
    @Volatile private var cachedArtworkUri: String? = null
    @Volatile private var cachedArtwork: Bitmap? = null

    fun refreshAll(context: Context) {
      // The service calls this on its main thread; artwork (often a remote cover URL) must
      // decode off it, and the widget update itself is asynchronous anyway.
      val appContext = context.applicationContext
      Thread { refreshAllInternal(appContext) }.start()
    }

    private fun refreshAllInternal(
      context: Context,
      manager: AppWidgetManager = AppWidgetManager.getInstance(context),
      appWidgetIds: IntArray = manager.getAppWidgetIds(
        ComponentName(context, MusicPlayerWidgetProvider::class.java),
      ),
    ) {
      if (appWidgetIds.isEmpty()) return
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
      val artwork = decodeArtwork(appContext, item?.artworkUri)
      val accent = accentColor(artwork)
      for (id in appWidgetIds) {
        runCatching {
          val options = manager.getAppWidgetOptions(id)
          val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
          val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
          manager.updateAppWidget(
            id,
            buildRemoteViews(appContext, item, paused, artwork, accent, width, height),
          )
        }.onFailure { Log.w(TAG, "Widget refresh failed", it) }
      }
    }

    private fun decodeArtwork(context: Context, artworkUri: String?): Bitmap? {
      val uri = artworkUri?.takeIf { it.isNotBlank() } ?: return null
      cachedArtworkUri?.let { cachedUri ->
        if (cachedUri == uri) return cachedArtwork
      }
      // EmbeddedArtworkResolver handles file/content/http(s) (streams expose remote cover
      // URLs, which ContentResolver cannot open) and keeps its own memory cache.
      val bitmap =
        app.infinity.mpvz.domain.thumbnail.EmbeddedArtworkResolver.decodeArtworkUri(
          context.applicationContext,
          uri,
        )
      cachedArtworkUri = uri
      cachedArtwork = bitmap
      return bitmap
    }

    /** Artwork-derived accent (light, low-saturation) for the progress bar and play button. */
    private fun accentColor(artwork: Bitmap?): Int {
      val seed = artwork?.let { art ->
        val scaled = Bitmap.createScaledBitmap(art, 1, 1, false)
        scaled.getPixel(0, 0).also { if (scaled !== art) scaled.recycle() }
      } ?: DEFAULT_SEED
      val hsl = FloatArray(3)
      ColorUtils.colorToHSL(seed, hsl)
      hsl[1] = hsl[1].coerceIn(0f, 0.5f)
      hsl[2] = 0.82f
      return ColorUtils.HSLToColor(hsl)
    }

    /** Picks the layout for the widget's current cell size and fills it. */
    fun buildRemoteViews(
      context: Context,
      item: PlaybackItem?,
      paused: Boolean,
      artwork: Bitmap?,
      accent: Int,
      widthDp: Int,
      @Suppress("UNUSED_PARAMETER") heightDp: Int,
    ): RemoteViews {
      // Layout choice uses width and height: clearly portrait cells (taller than wide) get the
      // tall stacked-artwork layout, everything wide or square enough gets the wide layout with
      // the artwork beside the text, and small or flat cells fall back to the compact row.
      val layoutRes = when {
        widthDp < COMPACT_MAX_WIDTH -> R.layout.widget_music_player
        heightDp.toFloat() > widthDp * PORTRAIT_ASPECT -> R.layout.widget_music_tall
        widthDp >= WIDE_MIN_WIDTH -> R.layout.widget_music_wide
        heightDp >= TALL_MIN_HEIGHT -> R.layout.widget_music_tall
        else -> R.layout.widget_music_player
      }
      val views = RemoteViews(context.packageName, layoutRes)

      views.setTextViewText(
        R.id.widget_track_title,
        item?.title ?: context.getString(R.string.widget_music_no_media),
      )
      views.setTextViewText(
        R.id.widget_track_artist,
        item?.artist ?: context.getString(R.string.widget_music_open_player_hint),
      )
      views.setImageViewResource(
        R.id.widget_button_play_pause,
        if (paused) R.drawable.materialsymbols_ic_play_arrow_rounded
        else R.drawable.materialsymbols_ic_pause_rounded,
      )

      if (artwork != null) {
        views.setViewVisibility(R.id.widget_artwork_placeholder, android.view.View.GONE)
        // The wide and compact layouts ship widget_artwork as visibility="gone"; without this
        // the decoded bitmap is set on an invisible view and the cell shows only the placeholder.
        views.setViewVisibility(R.id.widget_artwork, android.view.View.VISIBLE)
        views.setImageViewBitmap(R.id.widget_artwork, artwork)
      } else {
        views.setViewVisibility(R.id.widget_artwork, android.view.View.GONE)
        views.setViewVisibility(R.id.widget_artwork_placeholder, android.view.View.VISIBLE)
      }

      // ProgressBar has no setColorFilter(int): reflecting it from RemoteViews throws
      // ActionException at apply time and the launcher shows "Can't load widget". Use the
      // ColorStateList setter on API 31+, and leave the layout's white tint otherwise.
      if (android.os.Build.VERSION.SDK_INT >= 31) {
        views.setColorStateList(
          R.id.widget_progress,
          "setProgressTintList",
          android.content.res.ColorStateList.valueOf(accent),
        )
      }
      // The play/pause button sits on a light pill with a dark icon; no
      // runtime tint — setBackgroundColor would square off the pill drawable.

      // Reflect real playback progress when the service published a usable duration.
      val positionSeconds = latestPositionSeconds
      val durationSeconds = latestDurationSeconds
      if (positionSeconds != null && durationSeconds != null && durationSeconds > 0f) {
        val max = durationSeconds.roundToInt().coerceAtLeast(1)
        views.setInt(R.id.widget_progress, "setMax", max)
        views.setInt(R.id.widget_progress, "setProgress", positionSeconds.roundToInt().coerceIn(0, max))
      }

      // No track in the session or resume store: dim the transport so the empty state reads
      // as "tap to open the player" (the root pending intent opens the app).
      val transportAlpha = if (item == null) 100 else 255
      views.setInt(R.id.widget_button_previous, "setImageAlpha", transportAlpha)
      views.setInt(R.id.widget_button_play_pause, "setImageAlpha", transportAlpha)
      views.setInt(R.id.widget_button_next, "setImageAlpha", transportAlpha)

      val openPlayer = openPlayerPendingIntent(context)
      views.setOnClickPendingIntent(R.id.widget_music_player_root, openPlayer)
      views.setOnClickPendingIntent(
        R.id.widget_button_previous,
        transportPendingIntent(context, ACTION_PREVIOUS, REQUEST_CODE_PREVIOUS),
      )
      views.setOnClickPendingIntent(
        R.id.widget_button_play_pause,
        transportPendingIntent(context, ACTION_PLAY_PAUSE, REQUEST_CODE_PLAY_PAUSE),
      )
      views.setOnClickPendingIntent(
        R.id.widget_button_next,
        transportPendingIntent(context, ACTION_NEXT, REQUEST_CODE_NEXT),
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
  }
}
