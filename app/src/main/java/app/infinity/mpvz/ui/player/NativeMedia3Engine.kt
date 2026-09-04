package app.infinity.mpvz.ui.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.CaptionStyleCompat
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
  val chapters: List<NativeChapter> = emptyList(),
)

data class NativeTrack(
  val groupIndex: Int,
  val trackIndex: Int,
  val type: Int,
  val label: String,
  val language: String?,
  val selected: Boolean,
)

data class NativeChapter(
  val title: String,
  val startSeconds: Float,
)

/** A source-local Android Media3 playback engine. */
class NativeMedia3Engine(context: Context) {
  private val player = ExoPlayer.Builder(context.applicationContext)
    .setRenderersFactory(
      DefaultRenderersFactory(context.applicationContext)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
    )
    .setLoadControl(
      DefaultLoadControl.Builder()
        // Keep substantially more data ahead so large forward seeks on network media do not
        // immediately stall. The byte target still bounds memory use on high-bitrate 4K files.
        .setBufferDurationsMs(
          120_000,
          900_000,
          4_000,
          10_000,
        )
        .setTargetBufferBytes(128 * 1024 * 1024)
        .build(),
    )
    .build()
  private var attachedView: PlayerView? = null
  private var subtitleScale = 1f
  private var subtitlePosition = 100
  private var subtitleFontSize = 55
  private var loopASeconds: Double? = null
  private var loopBSeconds: Double? = null
  private val loopHandler = Handler(Looper.getMainLooper())
  private val loopRunnable = object : Runnable {
    override fun run() {
      val a = loopASeconds
      val b = loopBSeconds
      if (a != null && b != null && b > a && player.currentPosition >= (b * 1000.0).toLong()) {
        player.seekTo((a * 1000.0).toLong())
        if (!player.isPlaying) player.play()
      }
      if (a != null && b != null) loopHandler.postDelayed(this, 150L)
    }
  }
  private var subtitleStyle = CaptionStyleCompat(
    android.graphics.Color.WHITE,
    android.graphics.Color.TRANSPARENT,
    android.graphics.Color.TRANSPARENT,
    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
    android.graphics.Color.BLACK,
    null,
  )
  private val _snapshot = MutableStateFlow(NativePlaybackSnapshot())
  val snapshot: StateFlow<NativePlaybackSnapshot> = _snapshot.asStateFlow()
  val currentPlayer: Player get() = player

  private val listener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
      configureSubtitleView()
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
    configureSubtitleView()
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
    subtitleScale = scale.coerceIn(0.1f, 5f)
    configureSubtitleView()
  }

  fun setSubtitlePosition(position: Int) {
    subtitlePosition = position.coerceIn(0, 150)
    configureSubtitleView()
  }

  fun addExternalSubtitle(uri: Uri, select: Boolean): Boolean {
    val current = player.currentMediaItem?.localConfiguration ?: return false
    val mimeType = when (uri.toString().substringAfterLast('.', "").lowercase()) {
      "srt" -> "application/x-subrip"
      "vtt" -> "text/vtt"
      "ass", "ssa" -> "text/x-ssa"
      else -> "text/plain"
    }
    val configuration = MediaItem.SubtitleConfiguration.Builder(uri)
      .setMimeType(mimeType)
      .setSelectionFlags(if (select) C.SELECTION_FLAG_DEFAULT else 0)
      .build()
    val wasPlaying = player.isPlaying
    val positionMs = player.currentPosition.coerceAtLeast(0L)
    val updated = MediaItem.Builder()
      .setUri(current.uri)
      .setSubtitleConfigurations(current.subtitleConfigurations + configuration)
      .build()
    player.setMediaItem(updated, positionMs)
    player.prepare()
    player.playWhenReady = wasPlaying
    return true
  }

  fun setSubtitleStyle(
    textColor: Int,
    backgroundColor: Int,
    borderColor: Int,
    borderSize: Int,
    fontSize: Int,
    fontFamily: String? = null,
    bold: Boolean = false,
    italic: Boolean = false,
  ) {
    subtitleFontSize = fontSize.coerceIn(8, 160)
    val typefaceStyle = when {
      bold && italic -> android.graphics.Typeface.BOLD_ITALIC
      bold -> android.graphics.Typeface.BOLD
      italic -> android.graphics.Typeface.ITALIC
      else -> android.graphics.Typeface.NORMAL
    }
    subtitleStyle = CaptionStyleCompat(
      textColor,
      // MPV's background preference is not a cue rectangle. Keeping it transparent prevents
      // the black artifact behind text and signs reported on Native playback.
      android.graphics.Color.TRANSPARENT,
      android.graphics.Color.TRANSPARENT,
      CaptionStyleCompat.EDGE_TYPE_OUTLINE,
      borderColor,
      android.graphics.Typeface.create(fontFamily?.takeIf { it.isNotBlank() }, typefaceStyle),
    )
    configureSubtitleView()
  }

  private fun configureSubtitleView() {
    val view = attachedView ?: return
    view.subtitleView?.apply {
      setApplyEmbeddedStyles(true)
      setApplyEmbeddedFontSizes(false)
      setStyle(subtitleStyle)
      setFractionalTextSize((subtitleFontSize / 1000f).coerceIn(0.01f, 0.16f))
      pivotX = width / 2f
      pivotY = height.toFloat()
      scaleX = subtitleScale
      scaleY = subtitleScale
      translationY = ((subtitlePosition - 100) / 100f * height * 0.5f)
        .coerceIn(-height * 0.5f, height * 0.5f)
      setBottomPaddingFraction(0f)
    }
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

  fun setLoopA(positionSeconds: Double?) {
    loopASeconds = positionSeconds?.coerceAtLeast(0.0)
    restartLoopMonitor()
  }

  fun setLoopB(positionSeconds: Double?) {
    loopBSeconds = positionSeconds?.coerceAtLeast(0.0)
    restartLoopMonitor()
  }

  fun clearLoop() {
    loopASeconds = null
    loopBSeconds = null
    loopHandler.removeCallbacks(loopRunnable)
  }

  private fun restartLoopMonitor() {
    loopHandler.removeCallbacks(loopRunnable)
    if (loopASeconds != null && loopBSeconds != null) loopHandler.post(loopRunnable)
  }

  fun setSpeed(speed: Float, pitchCorrection: Boolean = true) {
    val clampedSpeed = speed.coerceIn(0.25f, 4f)
    player.setPlaybackParameters(
      PlaybackParameters(clampedSpeed, if (pitchCorrection) 1f else clampedSpeed),
    )
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
    clearLoop()
    player.stop()
    player.clearMediaItems()
    publishSnapshot()
  }

  fun release() {
    clearLoop()
    player.removeListener(listener)
    attachedView?.player = null
    attachedView = null
    player.release()
  }

  private fun publishSnapshot() {
    val groups = player.currentTracks.groups
    val chapters = groups.flatMap { group ->
      (0 until group.length).flatMap { index ->
        group.getTrackFormat(index).metadata?.let { metadata ->
          (0 until metadata.length).map { metadata.get(it) }
        }.orEmpty().mapNotNull { entry ->
          if (entry.javaClass.simpleName != "Chapter") return@mapNotNull null
          val startUs = runCatching {
            entry.javaClass.getMethod("getStartTimeUs").invoke(entry) as Number
          }.getOrNull() ?: return@mapNotNull null
          val title = runCatching {
            entry.javaClass.getMethod("getTitle").invoke(entry) as? String
          }.getOrNull().orEmpty().ifBlank { "Chapter ${index + 1}" }
          NativeChapter(title, (startUs.toLong() / 1_000_000f).coerceAtLeast(0f))
        }
      }
    }.distinctBy { it.startSeconds to it.title }.sortedBy { it.startSeconds }
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
      chapters = chapters,
    )
  }
}
