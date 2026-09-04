package app.infinity.mpvz.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Standalone Google Media3 playback engine for the uploaded mpvRx source.
 * It intentionally depends only on Media3 and source-independent Android types.
 */
class GoogleMedia3Engine(context: Context) {
  private val player = ExoPlayer.Builder(context.applicationContext).build()
  private var attachedView: PlayerView? = null

  val currentPlayer: Player get() = player
  val isPlaying: Boolean get() = player.isPlaying
  val positionMs: Long get() = player.currentPosition
  val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L

  fun attach(view: PlayerView) {
    attachedView?.player = null
    attachedView = view
    view.useController = false
    view.player = player
  }

  fun play(uri: Uri, startPositionMs: Long = 0L, autoplay: Boolean = true) {
    player.setMediaItem(MediaItem.fromUri(uri), startPositionMs.coerceAtLeast(0L))
    player.prepare()
    player.playWhenReady = autoplay
  }

  fun setPlaying(playing: Boolean) {
    player.playWhenReady = playing
  }

  fun seekTo(positionMs: Long) {
    player.seekTo(positionMs.coerceAtLeast(0L))
  }

  fun seekBy(offsetMs: Long) {
    player.seekTo((player.currentPosition + offsetMs).coerceAtLeast(0L))
  }

  fun setSpeed(speed: Float) {
    player.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
  }

  fun selectAudioTrack(group: Tracks.Group, trackIndex: Int) {
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
      .build()
  }

  fun selectSubtitleTrack(group: Tracks.Group, trackIndex: Int) {
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
      .build()
  }

  fun disableSubtitles() {
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
      .build()
  }

  fun addListener(listener: Player.Listener) = player.addListener(listener)
  fun removeListener(listener: Player.Listener) = player.removeListener(listener)

  fun stop() {
    player.stop()
    player.clearMediaItems()
  }

  fun release() {
    attachedView?.player = null
    attachedView = null
    player.release()
  }
}
