package app.infinity.mpvz.ui.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

data class NativePlaybackSnapshot(
  val isPlaying: Boolean = false,
  val isReady: Boolean = false,
  val isBuffering: Boolean = false,
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
  private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
  private val dataSourceFactory = DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory)
  private val player = ExoPlayer.Builder(context.applicationContext)
    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
    .setRenderersFactory(
      DefaultRenderersFactory(context.applicationContext)
        // Prefer platform hardware codecs for 4K/HDR; extensions remain available as fallback.
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true),
    )
    .build()
  private var attachedView: PlayerView? = null
  private var subtitleScale = 1f
  private var subtitlePosition = 100
  private var subtitleFontSize = 55
  private var loopASeconds: Double? = null
  private var loopBSeconds: Double? = null
  private val loopHandler = Handler(Looper.getMainLooper())
  private val timelineRunnable = object : Runnable {
    override fun run() {
      if (player.currentMediaItem == null) return
      publishPlaybackSnapshot()
      loopHandler.postDelayed(this, 100L)
    }
  }
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
  private val _hasRenderedFirstFrame = MutableStateFlow(false)
  val hasRenderedFirstFrame: StateFlow<Boolean> = _hasRenderedFirstFrame.asStateFlow()
  val currentPlayer: Player get() = player
  private var metadataChapters: List<NativeChapter> = emptyList()

  private val listener = object : Player.Listener {
    override fun onRenderedFirstFrame() {
      _hasRenderedFirstFrame.value = true
    }

    override fun onMetadata(metadata: Metadata) {
      metadataChapters = metadataEntriesToChapters(metadata)
      publishSnapshot()
    }

    override fun onEvents(player: Player, events: Player.Events) {
      if (events.contains(Player.EVENT_TRACKS_CHANGED)) configureSubtitleView()
      if (events.contains(Player.EVENT_TRACKS_CHANGED) ||
        events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
        events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
        events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
        events.contains(Player.EVENT_PLAYER_ERROR)
      ) {
        publishSnapshot()
      }
    }
  }

  init {
    player.addListener(listener)
  }

  fun attach(view: PlayerView) {
    attachedView?.player = null
    attachedView = view
    view.useController = false
    // Do not let a stale portrait measurement stretch native HDR video after rotation.
    view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    view.player = player
    configureSubtitleView()
    startTimelineUpdates()
  }

  /** Re-measure the Media3 surface after the host activity changes orientation. */
  fun refreshAfterConfigurationChange() {
    val view = attachedView ?: return
    view.post {
      if (attachedView !== view) return@post
      view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
      view.requestLayout()
      view.videoSurfaceView?.requestLayout()
      view.invalidate()
      configureSubtitleView()
    }
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

  fun play(
    uri: Uri,
    startPositionMs: Long = 0L,
    autoplay: Boolean = true,
    headers: Map<String, String> = emptyMap(),
    mimeType: String? = null,
    sourceUri: Uri? = null,
  ) {
    _hasRenderedFirstFrame.value = false
    httpDataSourceFactory.setDefaultRequestProperties(headers)
    val mediaItem =
      MediaItem.Builder()
        .setUri(uri)
        .apply {
          val declaredMime = mimeType?.takeUnless { it.equals("application/octet-stream", true) }
          (declaredMime ?: nativeContainerMimeType(uri) ?: sourceUri?.let(::nativeContainerMimeType))
            ?.let(::setMimeType)
        }
        .build()
    player.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
    player.prepare()
    player.playWhenReady = autoplay
    publishSnapshot()
    startTimelineUpdates()
  }

  private fun nativeContainerMimeType(uri: Uri): String? =
    when (uri.getQueryParameter("format")?.lowercase() ?: uri.path?.substringAfterLast('.', "")?.lowercase()) {
      "mkv", "mka" -> "video/x-matroska"
      "ts", "m2ts", "mts" -> "video/mp2t"
      "mp4", "m4v" -> "video/mp4"
      "webm" -> "video/webm"
      else -> null
    }

  fun setPlaying(playing: Boolean) {
    if (playing) player.play() else player.pause()
    publishSnapshot()
    startTimelineUpdates()
  }

  fun seekTo(positionMs: Long) {
    player.seekTo(positionMs.coerceAtLeast(0L))
    publishSnapshot()
    startTimelineUpdates()
  }

  fun seekBy(offsetMs: Long) {
    player.seekTo((player.currentPosition + offsetMs).coerceAtLeast(0L))
    publishSnapshot()
    startTimelineUpdates()
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
    loopHandler.removeCallbacks(timelineRunnable)
    player.stop()
    player.clearMediaItems()
    _hasRenderedFirstFrame.value = false
    publishSnapshot()
  }

  fun release() {
    clearLoop()
    loopHandler.removeCallbacks(timelineRunnable)
    player.removeListener(listener)
    attachedView?.player = null
    attachedView = null
    player.release()
  }

  private fun publishSnapshot() {
    val groups = player.currentTracks.groups
    val trackChapters = groups.flatMap { group ->
      (0 until group.length).flatMap { index ->
        group.getTrackFormat(index).metadata?.let(::metadataEntriesToChapters).orEmpty()
      }
    }
    val chapters = (metadataChapters + trackChapters)
      .distinctBy { it.startSeconds to it.title }
      .sortedBy { it.startSeconds }
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
      isBuffering = player.playbackState == Player.STATE_BUFFERING,
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

  /** Publishes only rapidly changing playback values; track/metadata enumeration is expensive. */
  private fun publishPlaybackSnapshot() {
    val previous = _snapshot.value
    _snapshot.value = previous.copy(
      isPlaying = player.isPlaying,
      isReady = player.playbackState == Player.STATE_READY,
      isBuffering = player.playbackState == Player.STATE_BUFFERING,
      positionMs = player.currentPosition.coerceAtLeast(0L),
      durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
      speed = player.playbackParameters.speed,
    )
  }

  private fun metadataEntriesToChapters(metadata: Metadata): List<NativeChapter> =
    (0 until metadata.length()).mapNotNull { index ->
      val entry = metadata.get(index)
      val startTimeMs = runCatching {
        entry.javaClass.methods.firstOrNull { it.name == "getStartTimeMs" }?.invoke(entry) as? Number
      }.getOrNull()
      val startUs = startTimeMs?.toLong()?.times(1000L) ?: sequenceOf("getStartTimeUs", "getChapterTimeStart")
        .mapNotNull { method ->
          runCatching {
            entry.javaClass.methods.firstOrNull { it.name == method }?.invoke(entry) as? Number
          }.getOrNull()
        }.firstOrNull()
        ?: sequenceOf("startTimeUs", "chapterTimeStart")
          .mapNotNull { field ->
            runCatching {
              entry.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(entry) as Number
            }.getOrNull()
          }.firstOrNull()
        ?: return@mapNotNull null
      val title = sequenceOf("getTitle", "getChapterString")
        .mapNotNull { method ->
          runCatching {
            val value = entry.javaClass.methods.firstOrNull { it.name == method }?.invoke(entry)
            when (value) {
              is String -> value
              null -> null
              else -> value.javaClass.methods.firstOrNull { it.name == "getValue" }?.invoke(value) as? String
            }
          }.getOrNull()
        }.firstOrNull()
        ?: sequenceOf("title", "chapterString")
          .mapNotNull { field ->
            runCatching {
              entry.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(entry) as? String
            }.getOrNull()
          }.firstOrNull()
        ?: "Chapter ${index + 1}"
      NativeChapter(title, (startUs.toLong() / 1_000_000f).coerceAtLeast(0f))
    }

  private fun startTimelineUpdates() {
    loopHandler.removeCallbacks(timelineRunnable)
    if (player.currentMediaItem != null) loopHandler.post(timelineRunnable)
  }
}
