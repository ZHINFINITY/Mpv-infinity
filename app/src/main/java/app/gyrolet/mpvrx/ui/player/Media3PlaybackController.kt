package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.net.Uri
import app.gyrolet.mpvrx.presentation.crash.AppDebugLog
import androidx.media3.common.C
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Media3 playback backend used by the existing mpvRx player surface.
 *
 * The controller deliberately has no UI of its own: the existing mpvRx Compose controls remain
 * the only visible controls, while this class owns Media3 lifecycle, playlist loading, and
 * playback state. It can therefore be introduced incrementally without changing the mpvRx look.
 */
@OptIn(UnstableApi::class)
class Media3PlaybackController(
  context: Context,
  private val onStateChanged: (State) -> Unit = {},
  private val onError: (PlaybackException) -> Unit = {},
  private val onVideoFrameRendered: () -> Unit = {},
) : Player.Listener, AnalyticsListener {
  data class State(
    val playbackState: Int = Player.STATE_IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = C.TIME_UNSET,
    val bufferedPositionMs: Long = 0L,
    val mediaItemIndex: Int = 0,
  )

  private val appContext = context.applicationContext
  private val httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
  private val player: ExoPlayer
  private var attachedView: PlayerView? = null
  private var lastPlaybackState = Player.STATE_IDLE

  init {
    val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
    val renderersFactory = DefaultRenderersFactory(appContext).setEnableDecoderFallback(true)
    player =
      ExoPlayer.Builder(appContext, renderersFactory)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()
        .also {
          it.addListener(this)
          it.addAnalyticsListener(this)
        }
    logInfo("controller created decoderFallback=true")
  }

  fun attach(view: PlayerView) {
    if (attachedView === view) return
    attachedView?.player = null
    attachedView = view
    view.useController = false
    view.player = player
    logInfo(
      "surface attached view=${view.javaClass.simpleName} " +
        "layout=${view.width}x${view.height} visibility=${view.visibility} children=${view.childCount}",
    )
  }

  fun play(
    uri: Uri,
    title: String? = null,
    headers: Map<String, String> = emptyMap(),
    startPositionMs: Long = 0L,
    playWhenReady: Boolean = true,
  ) {
    logInfo(
      "play requested uri=$uri title=${title.orEmpty().ifBlank { "<untitled>" }} " +
        "headers=${headers.keys.sorted().joinToString(",").ifBlank { "none" }} " +
        "startPositionMs=${startPositionMs.coerceAtLeast(0L)} playWhenReady=$playWhenReady",
    )
    httpFactory.setDefaultRequestProperties(headers)
    player.setMediaItem(mediaItem(uri, title, headers), startPositionMs.coerceAtLeast(0L))
    player.prepare()
    player.playWhenReady = playWhenReady
  }

  fun playPlaylist(
    uris: List<Uri>,
    titles: List<String?> = emptyList(),
    headers: Map<String, String> = emptyMap(),
    startIndex: Int = 0,
    startPositionMs: Long = 0L,
    playWhenReady: Boolean = true,
  ) {
    logInfo(
      "playlist requested count=${uris.size} startIndex=$startIndex " +
        "startPositionMs=${startPositionMs.coerceAtLeast(0L)} playWhenReady=$playWhenReady",
    )
    httpFactory.setDefaultRequestProperties(headers)
    if (uris.isEmpty()) {
      player.clearMediaItems()
      return
    }
    player.setMediaItems(
      uris.mapIndexed { index, uri -> mediaItem(uri, titles.getOrNull(index), headers) },
      startIndex.coerceIn(0, uris.lastIndex),
      startPositionMs.coerceAtLeast(0L),
    )
    player.prepare()
    player.playWhenReady = playWhenReady
  }

  fun setPlayWhenReady(value: Boolean) {
    logInfo("playWhenReady=$value")
    player.playWhenReady = value
  }

  fun stop() {
    logInfo("stop requested")
    player.stop()
    player.clearMediaItems()
  }

  fun seekTo(positionMs: Long) {
    player.seekTo(positionMs.coerceAtLeast(0L))
  }

  fun seekBy(offsetMs: Long) {
    player.seekTo((player.currentPosition + offsetMs).coerceAtLeast(0L))
  }

  fun setPlaybackSpeed(speed: Float) {
    val clampedSpeed = speed.coerceIn(0.1f, 16f)
    logInfo("playback speed=$clampedSpeed")
    player.setPlaybackSpeed(clampedSpeed)
  }

  fun currentState(): State = snapshot()

  fun release() {
    logInfo("controller releasing")
    attachedView?.player = null
    attachedView = null
    player.removeListener(this)
    player.removeAnalyticsListener(this)
    player.release()
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState != lastPlaybackState) {
      logInfo(
        "playback state=${playbackStateName(playbackState)} " +
          "isPlaying=${player.isPlaying} positionMs=${player.currentPosition} " +
          "bufferedPositionMs=${player.bufferedPosition}",
      )
      lastPlaybackState = playbackState
    }
    publishState()
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    logInfo("isPlaying=$isPlaying state=${playbackStateName(player.playbackState)}")
    publishState()
  }

  override fun onIsLoadingChanged(isLoading: Boolean) {
    logInfo(
      "loading changed isLoading=$isLoading state=${playbackStateName(player.playbackState)} " +
        "positionMs=${player.currentPosition} bufferedPositionMs=${player.bufferedPosition}",
    )
  }

  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    logInfo(
      "media item transition reason=$reason uri=${mediaItem?.localConfiguration?.uri ?: "none"} " +
        "mediaId=${mediaItem?.mediaId ?: "none"}",
    )
  }

  override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
    logInfo("timeline changed reason=$reason windows=${timeline.windowCount} periods=${timeline.periodCount}")
  }

  override fun onEvents(player: Player, events: Player.Events) = publishState()

  override fun onTracksChanged(tracks: Tracks) {
    val trackDetails =
      tracks.groups.flatMap { group ->
        (0 until group.length).mapNotNull { index ->
          val format = group.getTrackFormat(index)
          if (!group.isTrackSelected(index)) return@mapNotNull null
          "${trackTypeName(group.type)}:${formatDescription(format)}"
        }
      }
    logInfo(
      "tracks changed selected=${trackDetails.joinToString(" | ").ifBlank { "none" }} " +
        "groups=${tracks.groups.size}",
    )
  }

  override fun onPlayerError(error: PlaybackException) {
    val cause = error.cause
    AppDebugLog.error(
      TAG,
      "Media3: player error code=${error.errorCode} name=${error.errorCodeName} " +
        "message=${error.message.orEmpty()} cause=${cause?.javaClass?.name}: ${cause?.message}",
      error,
    )
    onError(error)
    publishState()
  }

  override fun onRenderedFirstFrame() {
    logInfo(
      "first video frame rendered surface=${attachedView?.javaClass?.simpleName ?: "none"} " +
        "layout=${attachedView?.width ?: 0}x${attachedView?.height ?: 0}",
    )
    onVideoFrameRendered()
  }

  override fun onVideoDecoderInitialized(
    eventTime: AnalyticsListener.EventTime,
    decoderName: String,
    initializedTimestampMs: Long,
    initializationDurationMs: Long,
  ) {
    logInfo(
      "video decoder initialized name=$decoderName " +
        "initializationDurationMs=$initializationDurationMs",
    )
  }

  override fun onVideoInputFormatChanged(
    eventTime: AnalyticsListener.EventTime,
    format: Format,
    decoderReuseEvaluation: DecoderReuseEvaluation?,
  ) {
    logInfo("video input format changed ${formatDescription(format)}")
  }

  override fun onVideoSizeChanged(videoSize: VideoSize) {
    logInfo(
      "video size changed width=${videoSize.width} height=${videoSize.height} " +
        "pixelWidthHeightRatio=${videoSize.pixelWidthHeightRatio} " +
        "unappliedRotationDegrees=${videoSize.unappliedRotationDegrees}",
    )
  }

  private fun mediaItem(
    uri: Uri,
    title: String?,
    headers: Map<String, String>,
  ): MediaItem =
    MediaItem.Builder()
      .setUri(uri)
      .setMediaId(uri.toString())
      .setRequestMetadata(
        MediaItem.RequestMetadata.Builder()
          .setMediaUri(uri)
          .build(),
      )
      .setTag(headers)
      .build()

  private fun publishState() {
    onStateChanged(snapshot())
  }

  private fun snapshot(): State =
    State(
      playbackState = player.playbackState,
      isPlaying = player.isPlaying,
      positionMs = player.currentPosition,
      durationMs = player.duration,
      bufferedPositionMs = player.bufferedPosition,
      mediaItemIndex = player.currentMediaItemIndex,
    )

  private fun logInfo(message: String) {
    AppDebugLog.info(TAG, "Media3: $message")
  }

  private fun formatDescription(format: Format): String =
    "name=${format.label ?: format.language ?: format.id ?: "<unnamed>"} " +
      "mime=${format.sampleMimeType ?: format.containerMimeType ?: "<unknown>"} " +
      "codecs=${format.codecs ?: "<unknown>"} profile=${format.codecs ?: "<unknown>"} " +
      "size=${format.width}x${format.height} bitrate=${format.bitrate}"

  private fun trackTypeName(trackType: Int): String =
    when (trackType) {
      C.TRACK_TYPE_VIDEO -> "video"
      C.TRACK_TYPE_AUDIO -> "audio"
      C.TRACK_TYPE_TEXT -> "text"
      else -> "type=$trackType"
    }

  private fun playbackStateName(playbackState: Int): String =
    when (playbackState) {
      Player.STATE_IDLE -> "IDLE"
      Player.STATE_BUFFERING -> "BUFFERING"
      Player.STATE_READY -> "READY"
      Player.STATE_ENDED -> "ENDED"
      else -> "UNKNOWN($playbackState)"
    }

  private companion object {
    const val TAG = "mpvrx"
  }
}
