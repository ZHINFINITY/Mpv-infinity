package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Media3 playback backend used by the existing mpvRx player surface.
 *
 * The controller deliberately has no UI of its own: the existing mpvRx Compose controls remain
 * the only visible controls, while this class owns Media3 lifecycle, playlist loading, and
 * playback state. It can therefore be introduced incrementally without changing the mpvRx look.
 */
class Media3PlaybackController(
  context: Context,
  private val onStateChanged: (State) -> Unit = {},
  private val onError: (PlaybackException) -> Unit = {},
  private val onVideoFrameRendered: () -> Unit = {},
) : Player.Listener {
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

  init {
    val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
    val renderersFactory = DefaultRenderersFactory(appContext).setEnableDecoderFallback(true)
    player =
      ExoPlayer.Builder(appContext, renderersFactory)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()
        .also { it.addListener(this) }
  }

  fun attach(view: PlayerView) {
    if (attachedView === view) return
    attachedView?.player = null
    attachedView = view
    view.useController = false
    view.player = player
  }

  fun play(
    uri: Uri,
    title: String? = null,
    headers: Map<String, String> = emptyMap(),
    startPositionMs: Long = 0L,
    playWhenReady: Boolean = true,
  ) {
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
    player.playWhenReady = value
  }

  fun stop() {
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
    player.setPlaybackSpeed(speed.coerceIn(0.1f, 16f))
  }

  fun currentState(): State = snapshot()

  fun release() {
    attachedView?.player = null
    attachedView = null
    player.removeListener(this)
    player.release()
  }

  override fun onPlaybackStateChanged(playbackState: Int) = publishState()

  override fun onIsPlayingChanged(isPlaying: Boolean) = publishState()

  override fun onEvents(player: Player, events: Player.Events) = publishState()

  override fun onPlayerError(error: PlaybackException) {
    onError(error)
    publishState()
  }

  override fun onRenderedFirstFrame() {
    onVideoFrameRendered()
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
}
