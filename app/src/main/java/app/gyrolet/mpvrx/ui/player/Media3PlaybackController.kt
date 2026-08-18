package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.gyrolet.mpvrx.presentation.crash.AppDebugLog
import androidx.media3.common.C
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import app.gyrolet.mpvrx.ui.player.TrackNode
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
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
  private val onEnded: () -> Unit = {},
) : Player.Listener, AnalyticsListener {
  data class State(
    val playbackState: Int = Player.STATE_IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = C.TIME_UNSET,
    val bufferedPositionMs: Long = 0L,
    val mediaItemIndex: Int = 0,
    val playbackSpeed: Float = 1f,
    val videoMimeType: String? = null,
    val videoCodecs: String? = null,
    val videoProfile: String? = null,
    val videoDecoderName: String? = null,
    val videoWidth: Int = C.LENGTH_UNSET,
    val videoHeight: Int = C.LENGTH_UNSET,
    val videoFrameRate: Float = -1f,
    val videoColorSpace: Int = -1,
    val videoColorTransfer: Int = -1,
    val audioTracks: List<TrackNode> = emptyList(),
    val subtitleTracks: List<TrackNode> = emptyList(),
  )

  private val appContext = context.applicationContext
  private val httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
  private val player: ExoPlayer
  private lateinit var normalMediaSourceFactory: DefaultMediaSourceFactory
  private lateinit var fastMediaSourceFactory: DefaultMediaSourceFactory
  private var fastStartActive = false
  private var attachedView: PlayerView? = null
  private var lastPlaybackState = Player.STATE_IDLE
  private var latestVideoFormat: Format? = null
  private var latestVideoSize: VideoSize? = null
  private var latestAudioTracks: List<TrackNode> = emptyList()
  private var latestSubtitleTracks: List<TrackNode> = emptyList()
  private var latestVideoDecoderName: String? = null
  private var media3AudioTrackGroups: Map<Int, Pair<androidx.media3.common.TrackGroup, Int>> = emptyMap()
  private var media3SubtitleTrackGroups: Map<Int, Pair<androidx.media3.common.TrackGroup, Int>> = emptyMap()
  private var requestedAudioTrackId: Int? = null
  private var pendingSeekPositionMs: Long? = null
  private var pendingSeekRequestedAtMs: Long = 0L
  private var lastKnownDurationMs: Long = 0L
  private var loopAPositionMs: Long? = null
  private var loopBPositionMs: Long? = null
  private val loopHandler = Handler(Looper.getMainLooper())
  private val stateTickerHandler = Handler(Looper.getMainLooper())
  private val stateTicker = object : Runnable {
    override fun run() {
      if (player.currentMediaItem != null) {
        publishState()
      }
      stateTickerHandler.postDelayed(this, 250L)
    }
  }
  private val loopCheck = object : Runnable {
    override fun run() {
      val a = loopAPositionMs
      val b = loopBPositionMs
      if (a != null && b != null && b > a) {
        if (player.isPlaying && player.currentPosition >= b) {
          logInfo("A-B loop reached B=${b}ms; seeking to A=${a}ms")
          pendingSeekPositionMs = a
          player.seekTo(a)
        }
        loopHandler.postDelayed(this, 250L)
      }
    }
  }

  init {
    val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
    // Keep Matroska Cues enabled. Disabling the Cues seek path makes fresh large-file startup
    // slightly faster, but ExoPlayer can then accept a seek and immediately recreate the timeline
    // at position zero. Reliable seekbar and gesture seeking takes priority over that optimization.
    normalMediaSourceFactory =
      DefaultMediaSourceFactory(dataSourceFactory, DefaultExtractorsFactory())
    fastMediaSourceFactory =
      DefaultMediaSourceFactory(
        dataSourceFactory,
        DefaultExtractorsFactory()
          .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES),
      )
    val renderersFactory =
      DefaultRenderersFactory(appContext)
        // Keep Android hardware/platform renderers first for formats the device supports, then
        // fall back to the bundled FFmpeg renderer for DTS/DTS-HD/TrueHD and other unsupported
        // platform formats. Prefer-mode would make FFmpeg decode every compatible audio track,
        // adding avoidable native startup and CPU cost for E-AC-3/AC-3 on this device.
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    player =
      ExoPlayer.Builder(appContext, renderersFactory)
        .setMediaSourceFactory(normalMediaSourceFactory)
        .build()
        .also {
          it.addListener(this)
          it.addAnalyticsListener(this)
        }
    logInfo("controller created decoderFallback=true ffmpegRenderer=platform-first fastLargeMatroska=true")
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
    fastStart: Boolean = false,
  ) {
    stateTickerHandler.removeCallbacks(stateTicker)
    stateTickerHandler.post(stateTicker)
    logInfo(
      "play requested uri=$uri title=${title.orEmpty().ifBlank { "<untitled>" }} " +
        "headers=${headers.keys.sorted().joinToString(",").ifBlank { "none" }} " +
        "startPositionMs=${startPositionMs.coerceAtLeast(0L)} playWhenReady=$playWhenReady",
    )
    httpFactory.setDefaultRequestProperties(headers)
    val requestedStartPositionMs = startPositionMs.coerceAtLeast(0L)
    val loadedUri = player.currentMediaItem?.localConfiguration?.uri
    if (
      loadedUri == uri &&
        player.currentMediaItem != null &&
        player.playbackState != Player.STATE_IDLE
    ) {
      val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
      val pendingPositionMs = pendingSeekPositionMs
      val duplicateTargetMs = pendingPositionMs ?: requestedStartPositionMs
      if (kotlin.math.abs(currentPositionMs - duplicateTargetMs) > 1_500L) {
        seekTo(duplicateTargetMs, fast = false)
      }
      player.playWhenReady = playWhenReady
      publishState()
      logInfo(
        "duplicate same-item play ignored uri=$uri currentPositionMs=$currentPositionMs " +
          "targetPositionMs=$duplicateTargetMs pendingSeek=${pendingPositionMs != null}",
      )
      return
    }
    resetMediaMetadata()
    val item = mediaItem(uri, title, headers)
    if (fastStart && requestedStartPositionMs <= 0L) {
      fastStartActive = true
      logInfo(
        "fast-start enabled for fresh large-file load; seek-safe Cues timeline will be restored " +
          "on first nonzero seek uri=$uri",
      )
      player.setMediaSource(fastMediaSourceFactory.createMediaSource(item), requestedStartPositionMs)
    } else {
      fastStartActive = false
      player.setMediaItem(item, requestedStartPositionMs)
    }
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
    stateTickerHandler.removeCallbacks(stateTicker)
    stateTickerHandler.post(stateTicker)
    logInfo(
      "playlist requested count=${uris.size} startIndex=$startIndex " +
        "startPositionMs=${startPositionMs.coerceAtLeast(0L)} playWhenReady=$playWhenReady",
    )
    httpFactory.setDefaultRequestProperties(headers)
    val requestedStartPositionMs = startPositionMs.coerceAtLeast(0L)
    val requestedUri = uris.getOrNull(startIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0)))
    val loadedUri = player.currentMediaItem?.localConfiguration?.uri
    if (
      requestedUri != null &&
        uris.size == 1 &&
        loadedUri == requestedUri &&
        player.currentMediaItem != null &&
        player.playbackState != Player.STATE_IDLE
    ) {
      val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
      val pendingPositionMs = pendingSeekPositionMs
      val duplicateTargetMs = pendingPositionMs ?: requestedStartPositionMs
      if (kotlin.math.abs(currentPositionMs - duplicateTargetMs) > 1_500L) {
        seekTo(duplicateTargetMs, fast = false)
      }
      player.playWhenReady = playWhenReady
      publishState()
      logInfo(
        "duplicate same-item playlist ignored uri=$requestedUri currentPositionMs=$currentPositionMs " +
          "targetPositionMs=$duplicateTargetMs pendingSeek=${pendingPositionMs != null}",
      )
      return
    }
    resetMediaMetadata()
    fastStartActive = false
    if (uris.isEmpty()) {
      player.clearMediaItems()
      return
    }
    player.setMediaItems(
      uris.mapIndexed { index, uri -> mediaItem(uri, titles.getOrNull(index), headers) },
      startIndex.coerceIn(0, uris.lastIndex),
      requestedStartPositionMs,
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
    stateTickerHandler.removeCallbacks(stateTicker)
    clearABLoop()
    fastStartActive = false
    player.stop()
    player.clearMediaItems()
  }

  fun setLoopA(positionMs: Long) {
    loopAPositionMs = positionMs.coerceAtLeast(0L)
    if (loopBPositionMs != null && loopBPositionMs!! <= loopAPositionMs!!) {
      loopBPositionMs = null
    }
    logInfo("A-B loop A=${loopAPositionMs}ms B=${loopBPositionMs ?: "unset"}")
    startLoopMonitorIfReady()
  }

  fun setLoopB(positionMs: Long) {
    val a = loopAPositionMs
    if (a == null || positionMs <= a) return
    loopBPositionMs = positionMs
    logInfo("A-B loop A=${a}ms B=$positionMs")
    startLoopMonitorIfReady()
  }

  fun clearABLoop() {
    loopAPositionMs = null
    loopBPositionMs = null
    loopHandler.removeCallbacks(loopCheck)
  }

  fun media3LoopA(): Long? = loopAPositionMs

  fun media3LoopB(): Long? = loopBPositionMs

  private fun startLoopMonitorIfReady() {
    if (loopAPositionMs == null || loopBPositionMs == null) return
    loopHandler.removeCallbacks(loopCheck)
    loopHandler.post(loopCheck)
  }

  private fun restoreSeekableTimelineIfNeeded(targetPositionMs: Long): Boolean {
    if (!fastStartActive || targetPositionMs <= 0L) return false
    val currentItem = player.currentMediaItem ?: return false
    val shouldPlay = player.playWhenReady
    fastStartActive = false
    pendingSeekPositionMs = targetPositionMs
    pendingSeekRequestedAtMs = android.os.SystemClock.elapsedRealtime()
    logInfo(
      "restoring Cues-enabled timeline for first nonzero seek targetPositionMs=$targetPositionMs " +
        "wasPlaying=$shouldPlay",
    )
    player.setMediaSource(normalMediaSourceFactory.createMediaSource(currentItem), targetPositionMs)
    player.prepare()
    player.playWhenReady = shouldPlay
    return true
  }

  fun seekTo(positionMs: Long, fast: Boolean = false) {
    val targetPositionMs = positionMs.coerceAtLeast(0L)
    if (restoreSeekableTimelineIfNeeded(targetPositionMs)) return
    pendingSeekPositionMs = targetPositionMs
    pendingSeekRequestedAtMs = android.os.SystemClock.elapsedRealtime()
    logInfo("seekTo requested positionMs=$targetPositionMs fast=$fast")
    // Gesture seeking should land on the nearest keyframe. Exact seeks can require decoding
    // a long interval from the previous keyframe on 4K HEVC/Dolby Vision files.
    val previousSeekParameters = player.seekParameters
    player.setSeekParameters(if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
    player.seekTo(targetPositionMs)
    player.setSeekParameters(previousSeekParameters)
  }

  fun seekBy(offsetMs: Long) {
    val targetPositionMs = (player.currentPosition + offsetMs).coerceAtLeast(0L)
    if (restoreSeekableTimelineIfNeeded(targetPositionMs)) return
    pendingSeekPositionMs = targetPositionMs
    pendingSeekRequestedAtMs = android.os.SystemClock.elapsedRealtime()
    logInfo("seekBy requested offsetMs=$offsetMs targetPositionMs=$targetPositionMs")
    player.seekTo(targetPositionMs)
  }

  /** Position to use when handing playback to MPV after a Media3 error. */
  fun positionForEngineHandoffMs(): Long =
    maxOf(player.currentPosition.coerceAtLeast(0L), pendingSeekPositionMs ?: 0L)

  fun setPlaybackSpeed(speed: Float) {
    val clampedSpeed = speed.coerceIn(0.1f, 16f)
    logInfo("playback speed=$clampedSpeed")
    player.setPlaybackSpeed(clampedSpeed)
  }

  fun setRepeatMode(mode: Int) {
    if (mode !in setOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)) return
    player.repeatMode = mode
    logInfo("repeat mode=$mode")
  }

  fun selectAudioTrack(trackId: Int): Boolean {
    val selection = media3AudioTrackGroups[trackId] ?: return false
    val (group, trackIndex) = selection
    // Track IDs are created from the current Tracks snapshot, so the mapped
    // group/index pair is already valid for the active player timeline.
    requestedAudioTrackId = trackId
    logInfo("selecting audio track id=$trackId group=${group.id} index=$trackIndex")
    player.trackSelectionParameters =
      player.trackSelectionParameters
        .buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        .setOverrideForType(TrackSelectionOverride(group, listOf(trackIndex)))
        .build()
    return true
  }

  fun selectSubtitleTrack(trackId: Int): Boolean {
    val selection = media3SubtitleTrackGroups[trackId] ?: return false
    val (group, trackIndex) = selection
    logInfo("selecting subtitle track id=$trackId group=${group.id} index=$trackIndex")
    player.trackSelectionParameters =
      player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setOverrideForType(TrackSelectionOverride(group, listOf(trackIndex)))
        .build()
    return true
  }

  fun disableSubtitles(): Boolean {
    logInfo("disabling subtitles")
    player.trackSelectionParameters =
      player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()
    return true
  }

  fun isSubtitleSelected(trackId: Int): Boolean {
    return latestSubtitleTracks.any { it.id == trackId && it.selected == true }
  }

  fun currentState(): State = snapshot()

  fun release() {
    logInfo("controller releasing")
    stateTickerHandler.removeCallbacks(stateTicker)
    clearABLoop()
    attachedView?.player = null
    attachedView = null
    player.removeListener(this)
    player.removeAnalyticsListener(this)
    player.release()
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    val stateChanged = playbackState != lastPlaybackState
    if (stateChanged) {
      logInfo(
        "playback state=${playbackStateName(playbackState)} " +
          "isPlaying=${player.isPlaying} positionMs=${player.currentPosition} " +
          "bufferedPositionMs=${player.bufferedPosition}",
      )
      lastPlaybackState = playbackState
      if (playbackState == Player.STATE_ENDED) {
        onEnded()
      }
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
    publishState()
  }

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int,
  ) {
    logInfo(
      "position discontinuity reason=$reason oldPositionMs=${oldPosition.positionMs} " +
        "newPositionMs=${newPosition.positionMs} currentPositionMs=${player.currentPosition} " +
        "mediaItemIndex=${player.currentMediaItemIndex}",
    )
    publishState()
  }
  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    logInfo(
      "media item transition reason=$reason uri=${mediaItem?.localConfiguration?.uri ?: "none"} " +
        "mediaId=${mediaItem?.mediaId ?: "none"}",
    )
    publishState()
  }

  override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
    logInfo("timeline changed reason=$reason windows=${timeline.windowCount} periods=${timeline.periodCount}")
    publishState()
  }

  override fun onEvents(player: Player, events: Player.Events) = publishState()

  override fun onTracksChanged(tracks: Tracks) {
    val audioEntries = mutableListOf<TrackNode>()
    val audioSelections = mutableMapOf<Int, Pair<androidx.media3.common.TrackGroup, Int>>()
    val subtitleEntries = mutableListOf<TrackNode>()
    val subtitleSelections = mutableMapOf<Int, Pair<androidx.media3.common.TrackGroup, Int>>()
    var audioId = 1
    var subtitleId = 10_001
    tracks.groups.forEach { group ->
      when (group.type) {
        C.TRACK_TYPE_AUDIO -> {
          (0 until group.length).forEach { trackIndex ->
            val format = group.getTrackFormat(trackIndex)
            val supported = group.getTrackSupport(trackIndex) == C.FORMAT_HANDLED
            val id = audioId++
            // Keep unsupported entries visible for transparency, but never submit them to Media3.
            if (supported) {
              audioSelections[id] = group.mediaTrackGroup to trackIndex
            }
            audioEntries +=
              TrackNode(
                id = id,
                type = "audio",
                title = format.label ?: format.id ?: format.codecs,
                lang = format.language,
                selected = group.isTrackSelected(trackIndex),
                default = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                forced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0,
                codec = format.codecs ?: format.sampleMimeType,
                audioChannels = format.channelCount.takeIf { it != C.LENGTH_UNSET }?.toLong(),
                formatName = format.sampleMimeType,
                supported = supported,
              )
          }
        }
        C.TRACK_TYPE_TEXT -> {
          (0 until group.length).forEach { trackIndex ->
            val format = group.getTrackFormat(trackIndex)
            val id = subtitleId++
            subtitleSelections[id] = group.mediaTrackGroup to trackIndex
            subtitleEntries +=
              TrackNode(
                id = id,
                type = "sub",
                title = format.label ?: format.id ?: format.codecs,
                lang = format.language,
                selected = group.isTrackSelected(trackIndex),
                default = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                forced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0,
                codec = format.codecs ?: format.sampleMimeType,
                formatName = format.sampleMimeType,
              )
          }
        }
      }
    }
    media3AudioTrackGroups = audioSelections
    media3SubtitleTrackGroups = subtitleSelections
    latestVideoFormat =
      tracks.groups
        .asSequence()
        .filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { group -> (0 until group.length).asSequence().map { group.getTrackFormat(it) } }
        .firstOrNull()
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
        "groups=${tracks.groups.size} audioTracks=${audioEntries.size}",
    )
    latestAudioTracks = audioEntries
    latestSubtitleTracks = subtitleEntries
    publishState()
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
    latestVideoDecoderName = decoderName
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
    latestVideoFormat = format
    logInfo("video input format changed ${formatDescription(format)}")
    publishState()
  }

  override fun onVideoSizeChanged(videoSize: VideoSize) {
    latestVideoSize = videoSize
    logInfo(
      "video size changed width=${videoSize.width} height=${videoSize.height} " +
        "pixelWidthHeightRatio=${videoSize.pixelWidthHeightRatio} " +
        "unappliedRotationDegrees=${videoSize.unappliedRotationDegrees}",
    )
    publishState()
  }

  private fun resetMediaMetadata() {
    latestVideoFormat = null
    latestVideoSize = null
    latestVideoDecoderName = null
    latestAudioTracks = emptyList()
    latestSubtitleTracks = emptyList()
    media3AudioTrackGroups = emptyMap()
    media3SubtitleTrackGroups = emptyMap()
    requestedAudioTrackId = null
    pendingSeekPositionMs = null
    pendingSeekRequestedAtMs = 0L
    lastKnownDurationMs = 0L
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

  private fun snapshot(): State {
    val livePositionMs = player.currentPosition.coerceAtLeast(0L)
    val pendingPositionMs = pendingSeekPositionMs
    val positionMs =
      if (pendingPositionMs != null) {
        val distanceMs = kotlin.math.abs(livePositionMs - pendingPositionMs)
        val ageMs = android.os.SystemClock.elapsedRealtime() - pendingSeekRequestedAtMs
        when {
          distanceMs <= 1_500L -> {
            pendingSeekPositionMs = null
            pendingSeekRequestedAtMs = 0L
            livePositionMs
          }
          ageMs in 0L..3_000L -> pendingPositionMs
          else -> {
            pendingSeekPositionMs = null
            pendingSeekRequestedAtMs = 0L
            livePositionMs
          }
        }
      } else {
        livePositionMs
      }
    val liveDurationMs = player.duration
    if (liveDurationMs > 0L && liveDurationMs != C.TIME_UNSET) {
      lastKnownDurationMs = liveDurationMs
    }
    val stableDurationMs =
      liveDurationMs.takeIf { it > 0L && it != C.TIME_UNSET } ?: lastKnownDurationMs
    val videoSize = latestVideoSize
    val rawWidth = videoSize?.width?.takeIf { it > 0 } ?: latestVideoFormat?.width ?: C.LENGTH_UNSET
    val rawHeight = videoSize?.height?.takeIf { it > 0 } ?: latestVideoFormat?.height ?: C.LENGTH_UNSET
    val rotated = videoSize?.unappliedRotationDegrees == 90 || videoSize?.unappliedRotationDegrees == 270
    val videoWidth = if (rotated) rawHeight else rawWidth
    val videoHeight = if (rotated) rawWidth else rawHeight
    return State(
      playbackState = player.playbackState,
      isPlaying = player.isPlaying,
      positionMs = positionMs,
      durationMs = stableDurationMs,
      bufferedPositionMs = player.bufferedPosition,
      mediaItemIndex = player.currentMediaItemIndex,
      playbackSpeed = player.playbackParameters.speed,
      videoMimeType = latestVideoFormat?.sampleMimeType,
      videoCodecs = latestVideoFormat?.codecs,
      videoProfile = latestVideoFormat?.codecs,
      videoDecoderName = latestVideoDecoderName,
      videoWidth = videoWidth,
      videoHeight = videoHeight,
      videoFrameRate = latestVideoFormat?.frameRate ?: -1f,
      videoColorSpace = latestVideoFormat?.colorInfo?.colorSpace ?: -1,
      videoColorTransfer = latestVideoFormat?.colorInfo?.colorTransfer ?: -1,
      audioTracks = latestAudioTracks,
      subtitleTracks = latestSubtitleTracks,
    )
  }

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
