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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

data class NativePlaybackSnapshot(
  val isPlaying: Boolean = false,
  val isReady: Boolean = false,
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
  val videoMimeType: String? = null,
  val videoCodec: String? = null,
  val videoBitrate: Int = 0,
  val audioCodec: String? = null,
  val audioChannels: Int = 0,
  val audioSampleRate: Int = 0,
  val speed: Float = 1f,
  val subtitleTracks: List<NativeTrack> = emptyList(),
  val audioTracks: List<NativeTrack> = emptyList(),
)

data class NativeTrack(
  val groupIndex: Int,
  val trackIndex: Int,
  val type: Int,
  val label: String,
  val language: String?,
  val selected: Boolean,
)

/** A source-local Android Media3 playback engine. */
class NativeMedia3Engine(context: Context) {
  private val player = ExoPlayer.Builder(context.applicationContext).build()
  private var attachedView: PlayerView? = null
  private val _snapshot = MutableStateFlow(NativePlaybackSnapshot())
  val snapshot: StateFlow<NativePlaybackSnapshot> = _snapshot.asStateFlow()
  val currentPlayer: Player get() = player

  private val listener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
      publishSnapshot()
    }
  }

  init {
    player.addListener(listener)
  }

  fun attach(view: PlayerView) {
    attachedView?.player = null
    attachedView = view
    view.useController = false
    view.player = player
  }

  fun setVideoAspect(aspect: VideoAspect) {
    attachedView?.resizeMode = when (aspect) {
      VideoAspect.Fit -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
      VideoAspect.Crop -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
      VideoAspect.Stretch -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
  }

  fun setZoom(zoom: Float) {
    val scale = 2f.pow(zoom.coerceIn(-1f, 3f))
    attachedView?.scaleX = scale
    attachedView?.scaleY = scale
  }

  fun setPan(x: Float, y: Float) {
    attachedView?.translationX = x
    attachedView?.translationY = y
  }

  fun setSubtitleScale(scale: Float) {
    attachedView?.subtitleView?.setFractionalTextSize((0.053f * scale.coerceIn(0.1f, 5f)).coerceIn(0.01f, 0.2f))
  }

  fun setSubtitlePosition(position: Int) {
    attachedView?.subtitleView?.setBottomPaddingFraction(((100 - position.coerceIn(0, 100)) / 100f).coerceIn(0f, 1f))
  }

  fun play(uri: Uri, startPositionMs: Long = 0L, autoplay: Boolean = true) {
    player.setMediaItem(MediaItem.fromUri(uri), startPositionMs.coerceAtLeast(0L))
    player.prepare()
    player.playWhenReady = autoplay
    publishSnapshot()
  }

  fun setPlaying(playing: Boolean) {
    if (playing) player.play() else player.pause()
    publishSnapshot()
  }

  fun seekTo(positionMs: Long) {
    player.seekTo(positionMs.coerceAtLeast(0L))
    publishSnapshot()
  }

  fun seekBy(offsetMs: Long) {
    player.seekTo((player.currentPosition + offsetMs).coerceAtLeast(0L))
    publishSnapshot()
  }

  fun setSpeed(speed: Float) {
    player.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
    publishSnapshot()
  }

  fun selectTrack(track: NativeTrack) {
    val group = player.currentTracks.groups.getOrNull(track.groupIndex) ?: return
    if (group.type != track.type || track.trackIndex !in 0 until group.length) return
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(track.type, false)
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
      .build()
    publishSnapshot()
  }

  fun selectAudioTrack(group: Tracks.Group, trackIndex: Int) {
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
      .build()
    publishSnapshot()
  }

  fun selectSubtitleTrack(group: Tracks.Group, trackIndex: Int) {
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
      .build()
    publishSnapshot()
  }

  fun disableSubtitles() {
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
      .build()
    publishSnapshot()
  }

  fun addListener(listener: Player.Listener) = player.addListener(listener)
  fun removeListener(listener: Player.Listener) = player.removeListener(listener)

  fun stop() {
    player.stop()
    player.clearMediaItems()
    publishSnapshot()
  }

  fun release() {
    player.removeListener(listener)
    attachedView?.player = null
    attachedView = null
    player.release()
  }

  private fun publishSnapshot() {
    val groups = player.currentTracks.groups
    fun tracksOfType(type: Int, fallback: String): List<NativeTrack> =
      groups.mapIndexedNotNull { groupIndex, group ->
        if (group.type != type) return@mapIndexedNotNull null
        (0 until group.length).map { trackIndex ->
          val format = group.getTrackFormat(trackIndex)
          NativeTrack(
            groupIndex = groupIndex,
            trackIndex = trackIndex,
            type = group.type,
            label = format.label ?: format.language ?: "$fallback ${trackIndex + 1}",
            language = format.language,
            selected = group.isTrackSelected(trackIndex),
          )
        }
      }.flatten()
    val subtitles = tracksOfType(C.TRACK_TYPE_TEXT, "Subtitle")
    val audioTracks = tracksOfType(C.TRACK_TYPE_AUDIO, "Audio")
    val video = groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
      ?.getTrackFormat(0)
    val audio = groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
      ?.getTrackFormat(0)
    _snapshot.value = NativePlaybackSnapshot(
      isPlaying = player.isPlaying,
      isReady = player.playbackState == Player.STATE_READY,
      positionMs = player.currentPosition.coerceAtLeast(0L),
      durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
      videoWidth = video?.width ?: 0,
      videoHeight = video?.height ?: 0,
      videoMimeType = video?.sampleMimeType,
      videoCodec = video?.codecs,
      videoBitrate = video?.bitrate?.takeIf { it > 0 } ?: 0,
      audioCodec = audio?.codecs ?: audio?.sampleMimeType,
      audioChannels = audio?.channelCount ?: 0,
      audioSampleRate = audio?.sampleRate ?: 0,
      speed = player.playbackParameters.speed,
      subtitleTracks = subtitles,
      audioTracks = audioTracks,
    )
  }
}
