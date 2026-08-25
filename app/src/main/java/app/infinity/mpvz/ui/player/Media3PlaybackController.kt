package app.infinity.mpvz.ui.player

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import app.infinity.mpvz.presentation.crash.AppDebugLog
import androidx.media3.common.C
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.TextOutput
import app.infinity.mpvz.ui.player.TrackNode
import app.infinity.mpvz.preferences.AudioChannels
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.ui.CaptionStyleCompat
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
  private var onStateChanged: (State) -> Unit = {},
  private var onError: (PlaybackException) -> Unit = {},
  private var onVideoFrameRendered: () -> Unit = {},
  private var onEnded: () -> Unit = {},
  private var onChaptersChanged: (List<dev.vivvvek.seeker.Segment>) -> Unit = {},
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
  private val channelMixingProcessor = ChannelMixingAudioProcessor()
  private val nativeAudioEffectsProcessor = NativeAudioEffectsProcessor()
  private var audioChannels = AudioChannels.AutoSafe
  private var audioPitchCorrection = true
  private val trackSelector = DefaultTrackSelector(appContext)
  // Media3 1.11 uses a 1-second minimum buffer for local playback. That is too small for
  // high-bitrate local HEVC/Dolby Vision files: after a large seek the player can resume with
  // roughly one second of lead, immediately drain it, and repeat the buffering cycle. Keep the
  // streaming defaults unchanged, but give local playback enough post-seek and rebuffer headroom.
  private val loadControl =
    DefaultLoadControl.Builder()
      .setBufferDurationsMsForLocalPlayback(
        /* minBufferMs = */ 30_000,
        /* maxBufferMs = */ 180_000,
        /* bufferForPlaybackMs = */ 1_500,
        /* bufferForPlaybackAfterRebufferMs = */ 5_000,
      )
      .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(true)
      .setBackBuffer(15_000, false)
      .build()
  private val subtitleCuesByRenderer = mutableMapOf<Int, List<Cue>>()
  // Selection overrides use global Media3 renderer indexes, while SubtitleTextOutput receives the
  // stable custom text-renderer slot (0 or 1). Keep both namespaces explicit so the stale-cue guard
  // does not reject every callback when video/audio renderers precede the text renderers.
  private val subtitleTrackIdsByRenderer = mutableMapOf<Int, Set<Int>>()
  private val subtitleTrackIdsBySlot = mutableMapOf<Int, Set<Int>>()
  private val signRendererByIndex = mutableMapOf<Int, Boolean>()
  private data class SubtitleTrackKey(
    val groupId: String?,
    val trackIndex: Int,
    val trackId: String?,
    val label: String?,
    val language: String?,
    val sampleMimeType: String?,
  )
  private var subtitleTrackKeysById: Map<Int, SubtitleTrackKey> = emptyMap()
  // Stable desired selection survives Media3 track-map rebuilds, even when synthetic UI IDs change.
  private val desiredSubtitleTrackKeys = linkedSetOf<SubtitleTrackKey>()
  private var subtitleCueGeneration = 0L
  private var subtitleSelectionRetryAttempts = 0
  private val subtitleSelectionRetry =
    object : Runnable {
      override fun run() {
        if (!preserveSubtitleSelection || selectedSubtitleTrackIds.isEmpty()) return
        applySubtitleTrackSelection()
        subtitleSelectionRetryAttempts++
        if (subtitleSelectionRetryAttempts < 4) {
          stateTickerHandler.postDelayed(this, 120L)
        }
      }
    }
  private val player: ExoPlayer
  private lateinit var normalMediaSourceFactory: DefaultMediaSourceFactory
  private lateinit var fastMediaSourceFactory: DefaultMediaSourceFactory
  private var fastStartActive = false
  private var restoreSeekParametersWhenReady = false
  private var attachedView: PlayerView? = null
  private val assDrawingCommandPattern =
    Regex(
      """^\s*(?:m|n|l|b|s|p|c)\s+-?\d+(?:\s+-?\d+)+(?:\s+(?:m|n|l|b|s|p|c)\s+-?\d+(?:\s+-?\d+)+)*\s*$""",
      RegexOption.IGNORE_CASE,
    )
  private val signSubtitleTitlePattern = Regex("\\b(signs?|songs?|lyrics?)\\b")
  // SubtitleView may rebuild its cue layout when tracks change. Keep the user scale outside the
  // view instance so a renderer reset cannot silently restore the default 1.0x size.
  private var subtitleScale = 1f
  private var subtitlePosition = 100
  private var nativeSubtitleApplyEmbeddedStyles = true
  private var nativeSubtitleStyle =
    CaptionStyleCompat(
      android.graphics.Color.WHITE,
      android.graphics.Color.TRANSPARENT,
      android.graphics.Color.TRANSPARENT,
      CaptionStyleCompat.EDGE_TYPE_OUTLINE,
      android.graphics.Color.BLACK,
      null,
    )
  private var lastPlaybackState = Player.STATE_IDLE
  private var latestVideoFormat: Format? = null
  private var latestVideoSize: VideoSize? = null
  private var latestAudioTracks: List<TrackNode> = emptyList()
  private var latestSubtitleTracks: List<TrackNode> = emptyList()
  private var latestChapters: List<dev.vivvvek.seeker.Segment> = emptyList()
  private var latestVideoDecoderName: String? = null
  private var lastPublishedState: State? = null
  private var media3AudioTrackGroups: Map<Int, Pair<androidx.media3.common.TrackGroup, Int>> = emptyMap()
  private var media3SubtitleTrackGroups: Map<Int, Pair<androidx.media3.common.TrackGroup, Int>> = emptyMap()
  private val selectedSubtitleTrackIds = mutableSetOf<Int>()
  // Once the user changes subtitle selection, do not let Media3's single selected-track
  // snapshot re-add or remove tracks while the renderer overrides are being applied.
  private var preserveSubtitleSelection = false
  private var requestedAudioTrackId: Int? = null
  private var pendingSeekPositionMs: Long? = null
  private var pendingSeekRequestedAtMs: Long = 0L
  private var postSeekResumeWhenBuffered = false
  private var postSeekResumeDeadlineMs = 0L
  private var lastKnownDurationMs: Long = 0L
  private var loopAPositionMs: Long? = null
  private var loopBPositionMs: Long? = null
  private val loopHandler = Handler(Looper.getMainLooper())
  private val stateTickerHandler = Handler(Looper.getMainLooper())
  private val stateTicker = object : Runnable {
    override fun run() {
      if (player.currentMediaItem != null) {
        maybeResumeAfterPostSeekBuffer()
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
    applyChannelMixingMatrices(audioChannels)
    val renderersFactory =
      NativeRenderersFactory(appContext)
        // Keep Android hardware/platform renderers first for formats the device supports, then
        // fall back to the bundled FFmpeg renderer for DTS/DTS-HD/TrueHD and other unsupported
        // platform formats. Prefer-mode would make FFmpeg decode every compatible audio track,
        // adding avoidable native startup and CPU cost for E-AC-3/AC-3 on this device.
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    player =
      ExoPlayer.Builder(appContext, renderersFactory)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(normalMediaSourceFactory)
        .build()
        .also {
          it.addListener(this)
          it.addAnalyticsListener(this)
        }
    logInfo(
      "controller created decoderFallback=true ffmpegRenderer=platform-first " +
        "channelMixing=true nativeEffects=true fastLargeMatroska=true " +
        "localBufferMs=30000..180000 seekStartMs=1500 rebufferMs=5000 postSeekLeadMs=5000",
    )
  }

  fun setAudioChannels(channels: AudioChannels) {
    audioChannels = channels
    applyChannelMixingMatrices(channels)
    logInfo("native audio channels=${channels.name}")
  }

  fun setAudioProcessing(volumeNormalization: Boolean, drcEnabled: Boolean) {
    nativeAudioEffectsProcessor.volumeNormalizationEnabled = volumeNormalization
    nativeAudioEffectsProcessor.drcEnabled = drcEnabled
    logInfo("native audio processing normalization=$volumeNormalization drc=$drcEnabled")
  }

  fun setAudioPitchCorrection(enabled: Boolean) {
    audioPitchCorrection = enabled
    applyPlaybackParameters(player.playbackParameters.speed)
    logInfo("native audio pitch correction=$enabled speed=${player.playbackParameters.speed}")
  }

  private inner class NativeRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
      context: Context,
      output: TextOutput,
      outputLooper: Looper,
      extensionRendererMode: Int,
      out: ArrayList<Renderer>,
    ) {
      // One text renderer can expose only one selected text group. Two renderer slots let the
      // DefaultTrackSelector assign dialogue and signs independently; both outputs are merged by
      // the controller before the existing SubtitleView receives them.
      out += TextRenderer(SubtitleTextOutput(0), outputLooper)
      out += TextRenderer(SubtitleTextOutput(1), outputLooper)
    }

    override fun buildAudioSink(
      context: Context,
      enableFloatOutput: Boolean,
      enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? =
      DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        // Use Media3's SonicAudioProcessor for speed/pitch correction. The platform AudioOutput
        // playback-parameter path is device-dependent and can ignore the requested pitch behavior.
        .setEnableAudioOutputPlaybackParameters(false)
        .setAudioProcessors(arrayOf(channelMixingProcessor, nativeAudioEffectsProcessor))
        .build()
  }

  private inner class SubtitleTextOutput(
    private val slot: Int,
  ) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
      // A disabled renderer may deliver one final CueGroup after its selection is removed.
      // Accepting that callback would repopulate stale sign cues after the selection clear.
      if (slot !in subtitleTrackIdsBySlot) {
        if (subtitleCuesByRenderer.remove(slot) != null) {
          postMergedSubtitleCues()
        }
        return
      }
      subtitleCuesByRenderer[slot] = cueGroup.cues
      postMergedSubtitleCues()
    }
  }

  private fun applyChannelMixingMatrices(channels: AudioChannels) {
    // Tangled and similar UHD remuxes can expose 7.1 audio as eight decoded channels.
    // Register matrices through 8 channels so Auto/AutoSafe downmixes to stereo instead
    // of letting AudioTrack attempt an unsupported eight-channel output on OEM devices.
    for (inputChannels in 1..8) {
      val matrix =
        when (channels) {
          AudioChannels.Mono ->
            ChannelMixingMatrix(
              inputChannels,
              1,
              FloatArray(inputChannels) { 1f / inputChannels },
            )
          AudioChannels.ReverseStereo ->
            when (inputChannels) {
              1 -> ChannelMixingMatrix(1, 2, floatArrayOf(1f, 1f))
              2 -> ChannelMixingMatrix(2, 2, floatArrayOf(0f, 1f, 1f, 0f))
              else -> stereoMatrix(inputChannels, reverse = true)
            }
          AudioChannels.Stereo -> stereoMatrix(inputChannels, reverse = false)
          AudioChannels.Auto -> identityMatrix(inputChannels)
          AudioChannels.AutoSafe ->
            // Preserve the layouts that worked in the earlier Native builds. The device failure
            // reported for Tangled is specifically the 7.1/eight-channel AudioTrack path, so only
            // seven- and eight-channel input is downmixed automatically. Downmixing six-channel
            // DTS/DTS-HD here regressed Wuthering With You even though its Native path previously
            // reached READY and rendered correctly.
            if (inputChannels >= 7) {
              stereoMatrix(inputChannels, reverse = false)
            } else {
              identityMatrix(inputChannels)
            }
        }
      channelMixingProcessor.putChannelMixingMatrix(matrix)
    }
  }

  private fun identityMatrix(inputChannels: Int): ChannelMixingMatrix =
    ChannelMixingMatrix(
      inputChannels,
      inputChannels,
      FloatArray(inputChannels * inputChannels) { index ->
        if (index / inputChannels == index % inputChannels) 1f else 0f
      },
    )

  private fun stereoMatrix(inputChannels: Int, reverse: Boolean): ChannelMixingMatrix {
    val coefficients = FloatArray(inputChannels * 2)
    for (input in 0 until inputChannels) {
      val left = if (input == 0) 1f else if (input == 1 && reverse) 1f else if (input > 1) 0.35f else 0f
      val right = if (input == 1) 1f else if (input == 0 && reverse) 1f else if (input > 1) 0.35f else 0f
      coefficients[input * 2] = left
      coefficients[input * 2 + 1] = right
    }
    return ChannelMixingMatrix(inputChannels, 2, coefficients)
  }

  fun attach(view: PlayerView) {
    if (attachedView === view) return
    attachedView?.player = null
    attachedView = view
    view.useController = false
    view.player = player
    applyNativeSubtitleStyle(view)
    applySubtitleScale(view)
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
    restoreSeekParametersWhenReady = false
    player.setSeekParameters(SeekParameters.EXACT)
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
    restoreSeekParametersWhenReady = false
    player.setSeekParameters(SeekParameters.EXACT)
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
    if (!value) {
      // A manual pause must cancel an automatic post-seek resume.
      postSeekResumeWhenBuffered = false
      postSeekResumeDeadlineMs = 0L
    }
    player.playWhenReady = value
  }

  fun stop() {
    logInfo("stop requested")
    stateTickerHandler.removeCallbacks(stateTicker)
    clearABLoop()
    fastStartActive = false
    restoreSeekParametersWhenReady = false
    postSeekResumeWhenBuffered = false
    postSeekResumeDeadlineMs = 0L
    player.setSeekParameters(SeekParameters.EXACT)
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
        "wasPlaying=$shouldPlay seekMode=closestSync",
    )
    // The initial fast-start timeline has no Cues index. Recreate it with a closest-sync target so
    // ExoPlayer can resume from the nearest keyframe instead of doing an exact long decode from an
    // earlier keyframe on high-bitrate HEVC/Dolby Vision files.
    val previousSeekParameters = player.seekParameters
    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    restoreSeekParametersWhenReady = previousSeekParameters != SeekParameters.CLOSEST_SYNC
    player.setMediaSource(normalMediaSourceFactory.createMediaSource(currentItem), targetPositionMs)
    player.prepare()
    armPostSeekResumeGate(shouldPlay, "initial nonzero seek")
    if (!restoreSeekParametersWhenReady) {
      player.setSeekParameters(previousSeekParameters)
    }
    return true
  }

  fun seekTo(positionMs: Long, fast: Boolean = false) {
    val targetPositionMs = positionMs.coerceAtLeast(0L)
    clearSubtitleCueBuffers()
    if (restoreSeekableTimelineIfNeeded(targetPositionMs)) return
    if (rebuildAfterLargeBackwardSeekIfNeeded(targetPositionMs, "seekTo")) return
    pendingSeekPositionMs = targetPositionMs
    pendingSeekRequestedAtMs = android.os.SystemClock.elapsedRealtime()
    logInfo("seekTo requested positionMs=$targetPositionMs fast=$fast")
    // Gesture seeking should land on the nearest keyframe. Exact seeks can require decoding
    // a long interval from the previous keyframe on 4K HEVC/Dolby Vision files.
    val previousSeekParameters = player.seekParameters
    val shouldGateResume =
      (player.playWhenReady || postSeekResumeWhenBuffered) &&
        kotlin.math.abs(player.currentPosition - targetPositionMs) >= LARGE_BACKWARD_SEEK_RESET_MS
    armPostSeekResumeGate(shouldGateResume, "large seekTo")
    player.setSeekParameters(if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
    player.seekTo(targetPositionMs)
    player.setSeekParameters(previousSeekParameters)
  }

  fun seekBy(offsetMs: Long) {
    val targetPositionMs = (player.currentPosition + offsetMs).coerceAtLeast(0L)
    clearSubtitleCueBuffers()
    if (restoreSeekableTimelineIfNeeded(targetPositionMs)) return
    if (rebuildAfterLargeBackwardSeekIfNeeded(targetPositionMs, "seekBy offsetMs=$offsetMs")) return
    pendingSeekPositionMs = targetPositionMs
    pendingSeekRequestedAtMs = android.os.SystemClock.elapsedRealtime()
    logInfo("seekBy requested offsetMs=$offsetMs targetPositionMs=$targetPositionMs seekMode=closestSync")
    val previousSeekParameters = player.seekParameters
    val shouldGateResume =
      (player.playWhenReady || postSeekResumeWhenBuffered) &&
        kotlin.math.abs(player.currentPosition - targetPositionMs) >= LARGE_BACKWARD_SEEK_RESET_MS
    armPostSeekResumeGate(shouldGateResume, "large seekBy")
    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    player.seekTo(targetPositionMs)
    player.setSeekParameters(previousSeekParameters)
  }

  /**
   * Moves by one decoded-frame duration using an exact seek request. Media3 has no native
   * frame-step command, so this is the closest safe equivalent; playback is paused by the caller.
   */
  fun seekFrameBy(offsetMs: Long) {
    val targetPositionMs = (player.currentPosition + offsetMs).coerceAtLeast(0L)
    clearSubtitleCueBuffers()
    if (restoreSeekableTimelineIfNeeded(targetPositionMs)) return
    pendingSeekPositionMs = targetPositionMs
    pendingSeekRequestedAtMs = android.os.SystemClock.elapsedRealtime()
    logInfo("frame seek requested offsetMs=$offsetMs targetPositionMs=$targetPositionMs seekMode=exact")
    val previousSeekParameters = player.seekParameters
    player.setSeekParameters(SeekParameters.EXACT)
    player.seekTo(targetPositionMs)
    player.setSeekParameters(previousSeekParameters)
  }

  private fun rebuildAfterLargeBackwardSeekIfNeeded(targetPositionMs: Long, reason: String): Boolean {
    val currentItem = player.currentMediaItem ?: return false
    val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
    if (currentPositionMs - targetPositionMs < LARGE_BACKWARD_SEEK_RESET_MS) return false

    val shouldPlay = player.playWhenReady
    val previousSeekParameters = player.seekParameters
    pendingSeekPositionMs = targetPositionMs
    pendingSeekRequestedAtMs = android.os.SystemClock.elapsedRealtime()
    fastStartActive = false
    restoreSeekParametersWhenReady = previousSeekParameters != SeekParameters.EXACT
    clearSubtitleCueBuffers()
    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    player.setMediaSource(normalMediaSourceFactory.createMediaSource(currentItem), targetPositionMs)
    player.prepare()
    armPostSeekResumeGate(shouldPlay, "large backward source rebuild")
    if (!restoreSeekParametersWhenReady) {
      player.setSeekParameters(previousSeekParameters)
    }
    logInfo(
      "large backward seek rebuilt Media3 source reason=$reason " +
        "fromPositionMs=$currentPositionMs targetPositionMs=$targetPositionMs " +
        "resetThresholdMs=$LARGE_BACKWARD_SEEK_RESET_MS wasPlaying=$shouldPlay",
    )
    return true
  }

  private fun armPostSeekResumeGate(shouldPlay: Boolean, reason: String) {
    postSeekResumeWhenBuffered = shouldPlay
    postSeekResumeDeadlineMs =
      if (shouldPlay) {
        android.os.SystemClock.elapsedRealtime() + POST_SEEK_RESUME_TIMEOUT_MS
      } else {
        0L
      }
    if (shouldPlay) {
      player.playWhenReady = false
      logInfo("large seek waiting for buffer lead targetMs=$POST_SEEK_BUFFER_LEAD_MS reason=$reason")
    }
  }

  private fun maybeResumeAfterPostSeekBuffer() {
    if (!postSeekResumeWhenBuffered) return
    val now = android.os.SystemClock.elapsedRealtime()
    val bufferedLeadMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
    val timedOut = now >= postSeekResumeDeadlineMs
    if (bufferedLeadMs < POST_SEEK_BUFFER_LEAD_MS && !timedOut) return
    postSeekResumeWhenBuffered = false
    postSeekResumeDeadlineMs = 0L
    if (player.playbackState == Player.STATE_READY) {
      player.playWhenReady = true
      logInfo("large seek resumed bufferedLeadMs=$bufferedLeadMs timedOut=$timedOut")
    }
  }

  /** Detach Activity-owned callbacks while allowing an intentional detached Media3 session to play. */
  fun detachUiCallbacks() {
    onStateChanged = {}
    onError = {}
    onVideoFrameRendered = {}
    onEnded = {}
    onChaptersChanged = {}
  }

  /** Position to use when handing playback to MPV after a Media3 error. */
  fun positionForEngineHandoffMs(): Long =
    maxOf(player.currentPosition.coerceAtLeast(0L), pendingSeekPositionMs ?: 0L)

  fun setPlaybackSpeed(speed: Float) {
    val clampedSpeed = speed.coerceIn(0.1f, 8f)
    applyPlaybackParameters(clampedSpeed)
    logInfo("playback speed=$clampedSpeed pitchCorrection=$audioPitchCorrection")
  }

  private fun applyPlaybackParameters(speed: Float) {
    val clampedSpeed = speed.coerceIn(0.1f, 8f)
    val pitch = if (audioPitchCorrection) 1f else clampedSpeed
    player.setPlaybackParameters(PlaybackParameters(clampedSpeed, pitch))
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
    preserveSubtitleSelection = true
    selectedSubtitleTrackIds += trackId
    subtitleTrackKeysById[trackId]?.let { desiredSubtitleTrackKeys += it }
    latestSubtitleTracks = latestSubtitleTracks.map { track ->
      if (track.id == trackId) track.copy(selected = true) else track
    }
    // A TextRenderer is not guaranteed to emit an empty CueGroup immediately when its
    // SelectionOverride changes. Clear the merged output before applying the new selection so a
    // previously visible sign cannot survive a dialogue-only selection.
    clearSubtitleCueBuffers()
    applySubtitleTrackSelection()
    stateTickerHandler.postDelayed({ scheduleSubtitleSelectionRetry() }, 80L)
    publishState()
    val (group, trackIndex) = selection
    logInfo(
      "selecting subtitle track id=$trackId group=${group.id} index=$trackIndex " +
        "selectedIds=${selectedSubtitleTrackIds.sorted().joinToString(",")}",
    )
    return true
  }

  fun unselectSubtitleTrack(trackId: Int): Boolean {
    val selectedInSnapshot = latestSubtitleTracks.any { it.id == trackId && it.selected == true }
    if (trackId !in selectedSubtitleTrackIds && !selectedInSnapshot) return false
    preserveSubtitleSelection = true
    selectedSubtitleTrackIds -= trackId
    subtitleTrackKeysById[trackId]?.let { desiredSubtitleTrackKeys -= it }
    latestSubtitleTracks = latestSubtitleTracks.map { track ->
      if (track.id == trackId) track.copy(selected = false) else track
    }
    // Media3 may leave the old renderer's last CueGroup visible until another cue arrives.
    clearSubtitleCueBuffers()
    applySubtitleTrackSelection()
    stateTickerHandler.postDelayed({ scheduleSubtitleSelectionRetry() }, 80L)
    publishState()
    logInfo(
      "unselecting subtitle track id=$trackId " +
        "selectedIds=${selectedSubtitleTrackIds.sorted().joinToString(",")}",
    )
    return true
  }

  private fun scheduleSubtitleSelectionRetry() {
    if (!preserveSubtitleSelection || selectedSubtitleTrackIds.isEmpty()) return
    stateTickerHandler.removeCallbacks(subtitleSelectionRetry)
    subtitleSelectionRetryAttempts = 0
    stateTickerHandler.post(subtitleSelectionRetry)
  }

  private fun applySubtitleTrackSelection() {
    subtitleTrackIdsByRenderer.clear()
    subtitleTrackIdsBySlot.clear()
    signRendererByIndex.clear()
    val selectedTracks =
      selectedSubtitleTrackIds
        .mapNotNull { id ->
          media3SubtitleTrackGroups[id]?.let { (group, trackIndex) ->
            Triple(id, group, trackIndex)
          }
        }
        .sortedWith(
          compareBy<Triple<Int, androidx.media3.common.TrackGroup, Int>> { selectedTrack ->
            val title = latestSubtitleTracks.firstOrNull { it.id == selectedTrack.first }?.title.orEmpty()
            signSubtitleTitlePattern.containsMatchIn(title)
          }.thenBy { it.first },
        )
    val parameters =
      trackSelector
        .buildUponParameters()
        // Do not clear audio/video overrides when the subtitle sheet changes selection.
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selectedTracks.isEmpty())
    val mappedTrackInfo = trackSelector.currentMappedTrackInfo
    if (mappedTrackInfo != null && selectedTracks.isNotEmpty()) {
      // A single Media3 TrackGroup can contain both dialogue and sign tracks. TrackGroup
      // selection is renderer-scoped, so assigning one index to each renderer makes the second
      // index lose to the first renderer's already-assigned group. Batch all selected indices from
      // each group into one SelectionOverride instead.
      val tracksByGroup = selectedTracks.groupBy { it.second }
      val assignedGroups = mutableSetOf<androidx.media3.common.TrackGroup>()
      var textRendererSlot = 0
      for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_TEXT) continue
        val slot = textRendererSlot++
        val rendererGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
        @Suppress("DEPRECATION")
        parameters.clearSelectionOverrides(rendererIndex)
        val entry =
          tracksByGroup.entries.firstOrNull { (group, _) ->
            group !in assignedGroups &&
              (0 until rendererGroups.length).any { rendererGroups[it] == group }
          }
        if (entry == null) {
          parameters.setRendererDisabled(rendererIndex, true)
          continue
        }
        val (group, tracksInGroup) = entry
        val rendererGroupIndex =
          (0 until rendererGroups.length).firstOrNull { rendererGroups[it] == group } ?: continue
        val trackIndices = tracksInGroup.map { it.third }.distinct().toIntArray()
        @Suppress("DEPRECATION")
        parameters.setSelectionOverride(
          rendererIndex,
          rendererGroups,
          DefaultTrackSelector.SelectionOverride(rendererGroupIndex, *trackIndices),
        )
        parameters.setRendererDisabled(rendererIndex, false)
        val rendererTrackIds = tracksInGroup.map { it.first }.toSet()
        subtitleTrackIdsByRenderer[rendererIndex] = rendererTrackIds
        subtitleTrackIdsBySlot[slot] = rendererTrackIds
        signRendererByIndex[slot] =
          rendererTrackIds.any { trackId ->
            signSubtitleTitlePattern.containsMatchIn(
              latestSubtitleTracks.firstOrNull { it.id == trackId }?.title.orEmpty(),
            )
          }
        assignedGroups += group
        if (assignedGroups.size >= tracksByGroup.size) break
      }
    } else if (mappedTrackInfo != null) {
      for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_TEXT) continue
        @Suppress("DEPRECATION")
        parameters.clearSelectionOverrides(rendererIndex)
        parameters.setRendererDisabled(rendererIndex, true)
      }
    }
    trackSelector.setParameters(parameters)
  }

  fun disableSubtitles(): Boolean {
    preserveSubtitleSelection = true
    selectedSubtitleTrackIds.clear()
    desiredSubtitleTrackKeys.clear()
    latestSubtitleTracks = latestSubtitleTracks.map { track -> track.copy(selected = false) }
    publishState()
    logInfo("disabling subtitles")
    clearSubtitleCueBuffers()
    applySubtitleTrackSelection()
    return true
  }

  fun isSubtitleSelected(trackId: Int): Boolean = trackId in selectedSubtitleTrackIds

  fun hasSelectedSubtitle(): Boolean =
    selectedSubtitleTrackIds.isNotEmpty() || latestSubtitleTracks.any { it.selected == true }

  /**
   * Configure Media3's subtitle renderer. Keep embedded ASS/SSA positioning and alignment so sign
   * cues honor tags such as \\pos and \\an, while the app still controls the effective font size.
   */
  private fun configureNativeSubtitleView(view: PlayerView? = attachedView) {
    view?.subtitleView?.apply {
      setApplyEmbeddedStyles(nativeSubtitleApplyEmbeddedStyles)
      setApplyEmbeddedFontSizes(false)
      setStyle(nativeSubtitleStyle)
      // SubtitleView's fractional text size does not resize bitmap cues such as Tangled's PGS
      // subtitles. Scale the complete renderer so both text and bitmap cues respond to the same
      // app setting and pinch gesture.
      setFractionalTextSize(0.0533f.coerceIn(0.005f, 0.25f))
      pivotX = width / 2f
      pivotY = height.toFloat()
      scaleX = subtitleScale
      scaleY = subtitleScale
      // Apply position as a renderer translation as well as bottom padding. This works for PGS
      // bitmap cues, whose internal cue geometry cannot be restyled like text cues.
      translationY = ((subtitlePosition - 100) / 100f * height * 0.5f).coerceIn(-height * 0.5f, height * 0.5f)
      // Use the renderer translation as the single position source. A zero bottom-padding value
      // avoids double-applying the preference and keeps PGS bitmap cues aligned with text cues.
      setBottomPaddingFraction(0f)
    }
  }

  /** Applies the active Native caption style to Media3's actual subtitle renderer. */
  private fun applyNativeSubtitleStyle(view: PlayerView? = attachedView) {
    configureNativeSubtitleView(view)
  }

  /**
   * Applies a user-selected Native caption style. Media3 does not expose every MPV subtitle
   * property, but its CaptionStyleCompat covers the visible text, background, and edge treatment.
   */
  fun setSubtitleStyle(
    textColor: Int,
    backgroundColor: Int,
    edgeType: Int,
    edgeColor: Int,
    shadowColor: Int = android.graphics.Color.BLACK,
    applyEmbeddedStyles: Boolean = true,
    fontFamily: String? = null,
    bold: Boolean = false,
    italic: Boolean = false,
  ): Boolean {
    nativeSubtitleApplyEmbeddedStyles = applyEmbeddedStyles
    val typefaceStyle =
      when {
        bold && italic -> Typeface.BOLD_ITALIC
        bold -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
      }
    val typeface = Typeface.create(fontFamily?.takeIf { it.isNotBlank() }, typefaceStyle)
    nativeSubtitleStyle =
      CaptionStyleCompat(
        textColor,
        backgroundColor,
        android.graphics.Color.TRANSPARENT,
        edgeType,
        if (edgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW) shadowColor else edgeColor,
        typeface,
      )
    applyNativeSubtitleStyle()
    logInfo(
      "subtitle style text=$textColor background=$backgroundColor edgeType=$edgeType " +
        "edgeColor=$edgeColor nativeSubtitleView=${attachedView?.subtitleView != null}",
    )
    return true
  }

  /**
   * Applies the shared subtitle scale to Media3's actual subtitle renderer. Media3's standard
   * subtitle size is a fraction of the view height; multiplying that baseline keeps 1.0x visually
   * unchanged while allowing the existing player pinch gesture to work for Native playback.
   */
  private fun applySubtitleScale(view: PlayerView? = attachedView) {
    configureNativeSubtitleView(view)
  }

  fun setSubtitleScale(scale: Float): Boolean {
    subtitleScale = scale.coerceIn(0.1f, 5.0f)
    applySubtitleScale()
    logInfo("subtitle scale=$subtitleScale nativeSubtitleView=${attachedView?.subtitleView != null}")
    // Retain the value even before the PlayerView is attached; attach() reapplies it later.
    return true
  }

  fun setSubtitlePosition(position: Int): Boolean {
    subtitlePosition = position.coerceIn(0, 150)
    applyNativeSubtitleStyle()
    logInfo("subtitle position=$subtitlePosition nativeSubtitleView=${attachedView?.subtitleView != null}")
    return true
  }

  fun currentState(): State = snapshot()

  fun release() {
    logInfo("controller releasing")
    detachUiCallbacks()
    stateTickerHandler.removeCallbacks(stateTicker)
    stateTickerHandler.removeCallbacks(subtitleSelectionRetry)
    subtitleSelectionRetryAttempts = 0
    clearABLoop()
    clearSubtitleCueBuffers()
    subtitleTrackIdsByRenderer.clear()
    signRendererByIndex.clear()
    preserveSubtitleSelection = false
    attachedView?.player = null
    attachedView = null
    player.removeListener(this)
    player.removeAnalyticsListener(this)
    player.release()
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    maybeResumeAfterPostSeekBuffer()
    if (playbackState == Player.STATE_READY && restoreSeekParametersWhenReady) {
      restoreSeekParametersWhenReady = false
      player.setSeekParameters(SeekParameters.EXACT)
      logInfo("restored exact seek parameters after Cues timeline became ready")
    }
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
    clearSubtitleCueBuffers()
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
    val previouslySelectedSubtitleKeys =
      selectedSubtitleTrackIds.mapNotNull { subtitleTrackKeysById[it] }.toSet()
    val audioEntries = mutableListOf<TrackNode>()
    val audioSelections = mutableMapOf<Int, Pair<androidx.media3.common.TrackGroup, Int>>()
    val subtitleEntries = mutableListOf<TrackNode>()
    val subtitleSelections = mutableMapOf<Int, Pair<androidx.media3.common.TrackGroup, Int>>()
    val subtitleKeys = mutableMapOf<Int, SubtitleTrackKey>()
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
            subtitleKeys[id] =
              SubtitleTrackKey(
                groupId = group.mediaTrackGroup.id,
                trackIndex = trackIndex,
                trackId = format.id,
                label = format.label,
                language = format.language,
                sampleMimeType = format.sampleMimeType,
              )
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
    subtitleTrackKeysById = subtitleKeys
    if (preserveSubtitleSelection) {
      // Prefer persistent desired keys. The previous ID-to-key snapshot is only a fallback for
      // selections created before this stable-key state existed.
      if (desiredSubtitleTrackKeys.isEmpty()) desiredSubtitleTrackKeys += previouslySelectedSubtitleKeys
      selectedSubtitleTrackIds.clear()
      selectedSubtitleTrackIds +=
        subtitleKeys
          .filterValues { it in desiredSubtitleTrackKeys }
          .keys
    } else {
      selectedSubtitleTrackIds.retainAll(subtitleSelections.keys)
    }
    if (!preserveSubtitleSelection) {
      subtitleEntries.filter { it.selected == true }.forEach {
        selectedSubtitleTrackIds += it.id
        subtitleKeys[it.id]?.let { key -> desiredSubtitleTrackKeys += key }
      }
    }
    val normalizedSubtitleEntries =
      subtitleEntries.map { track ->
        track.copy(selected = track.id in selectedSubtitleTrackIds)
      }
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
    latestSubtitleTracks = normalizedSubtitleEntries
    if (selectedSubtitleTrackIds.isNotEmpty() || preserveSubtitleSelection) {
      applySubtitleTrackSelection()
      scheduleSubtitleSelectionRetry()
    }

    // Matroska commonly delivers chapters through each track Format.metadata rather than the
    // global Player.Listener.onMetadata callback.
    val trackChapters = chaptersFromTrackMetadata(tracks)
    if (trackChapters.isNotEmpty() && trackChapters != latestChapters) {
      latestChapters = trackChapters
      onChaptersChanged(trackChapters)
      logInfo("chapters from track metadata count=${trackChapters.size}")
    }

    // Track changes can rebuild the cue renderer and restore its default caption style and text
    // size. Reapply both after every track rebuild.
    applyNativeSubtitleStyle()
    applySubtitleScale()
    publishState()
  }

  private fun chaptersFromTrackMetadata(tracks: Tracks): List<dev.vivvvek.seeker.Segment> =
    tracks.groups
      .flatMap { group ->
        (0 until group.length).flatMap { trackIndex ->
          val metadata = group.getTrackFormat(trackIndex).metadata ?: return@flatMap emptyList()
          (0 until metadata.length()).mapNotNull { metadataIndex ->
            val chapter = metadata[metadataIndex] as? Chapter ?: return@mapNotNull null
            if (chapter.isHidden()) return@mapNotNull null
            val startTimeMs = chapter.getStartTimeMs()
            if (startTimeMs == C.TIME_UNSET || startTimeMs < 0L) return@mapNotNull null
            val title = chapter.getTitle()?.value?.trim().orEmpty()
            dev.vivvvek.seeker.Segment(
              title.ifBlank { "Chapter ${metadataIndex + 1}" },
              startTimeMs / 1000f,
            )
          }
        }
      }
      .distinctBy { it.start }

  override fun onCues(@Suppress("UNUSED_PARAMETER") cueGroup: CueGroup) {
    // The two custom TextRenderer instances are the authoritative cue sources because they
    // preserve renderer identity. Do not copy this renderer-agnostic callback into slot 0: after a
    // selection change it can reinsert a stale sign CueGroup after the sign track is disabled.
  }

  private fun postMergedSubtitleCues() {
    val generation = subtitleCueGeneration
    val filteredCues =
      subtitleCuesByRenderer.entries
        .flatMap { (rendererIndex, cues) ->
          val signRenderer = isLikelySignRenderer(rendererIndex)
          cues.map { cue -> cue to signRenderer }
        }
        .filterNot { (cue, _) ->
          cue.text?.toString()?.trim()?.let { text -> assDrawingCommandPattern.matches(text) } == true
        }
        .map { (cue, signRenderer) -> makeEmbeddedCueReadable(cue, signRenderer) }
    val view = attachedView ?: return
    view.post {
      if (attachedView === view && subtitleCueGeneration == generation) {
        runCatching { view.subtitleView?.setCues(filteredCues) }
          .onFailure { error ->
            AppDebugLog.error(
              TAG,
              "Media3 subtitle cue render failed; keeping playback alive: ${error.message}",
              error,
            )
          }
      }
    }
  }

  /**
   * Keep embedded ASS/SSA window colors, but prevent opaque sign windows from hiding the source
   * sign underneath. The cue builder preserves the original text, position, anchors, alignment,
   * size, and vertical writing fields, so this changes readability without moving sign cues.
   */
  private fun isLikelySignRenderer(rendererIndex: Int): Boolean =
    signRendererByIndex[rendererIndex] == true

  private fun makeEmbeddedCueReadable(cue: Cue, signRenderer: Boolean = false): Cue {
    if (signRenderer) {
      // Sign tracks commonly share coordinates with the source sign. Match MPV's readable look:
      // keep the embedded position/alignment, use an opaque compact window, and reduce only an
      // explicitly supplied oversized cue size. Dialogue cues keep their original sizing.
      val builder =
        cue
          .buildUpon()
          .setWindowColor(android.graphics.Color.BLACK)
      val originalTextSize = cue.textSize
      if (originalTextSize > 0f && originalTextSize.isFinite()) {
        builder.setTextSize((originalTextSize * 0.72f).coerceAtLeast(0.01f), cue.textSizeType)
      }
      return builder.build()
    }
    if (!cue.windowColorSet) return cue
    val originalColor = cue.windowColor
    val originalAlpha = android.graphics.Color.alpha(originalColor)
    if (originalAlpha == 0) return cue
    val readableAlpha = originalAlpha.coerceIn(0x66, 0xB8)
    if (readableAlpha == originalAlpha) return cue
    return cue
      .buildUpon()
      .setWindowColor(
        android.graphics.Color.argb(
          readableAlpha,
          android.graphics.Color.red(originalColor),
          android.graphics.Color.green(originalColor),
          android.graphics.Color.blue(originalColor),
        ),
      ).build()
  }

  private fun clearSubtitleCueBuffers() {
    subtitleCueGeneration++
    subtitleCuesByRenderer.clear()
    postMergedSubtitleCues()
  }

  override fun onMetadata(metadata: Metadata) {
    val chapters =
      (0 until metadata.length()).mapNotNull { index ->
        val chapter = metadata[index] as? Chapter ?: return@mapNotNull null
        if (chapter.isHidden()) return@mapNotNull null
        val startTimeMs = chapter.getStartTimeMs()
        if (startTimeMs == C.TIME_UNSET || startTimeMs < 0L) return@mapNotNull null
        val title = chapter.getTitle()?.value?.trim().orEmpty()
        dev.vivvvek.seeker.Segment(
          title.ifBlank { "Chapter ${index + 1}" },
          startTimeMs / 1000f,
        )
      }.distinctBy { it.start }

    // Preserve chapters already extracted from track Format.metadata. Media3 can emit an empty
    // global metadata callback after the track callback for Matroska files.
    if (chapters.isNotEmpty() || latestChapters.isEmpty()) {
      if (chapters != latestChapters) {
        latestChapters = chapters
        onChaptersChanged(chapters)
        logInfo("chapters changed count=${chapters.size}")
      }
    }
  }

  fun getChapters(): List<dev.vivvvek.seeker.Segment> = latestChapters


  override fun onPlayerError(error: PlaybackException) {
    val cause = error.cause
    AppDebugLog.error(
      TAG,
      "Media3: player error code=${error.errorCode} name=${error.errorCodeName} " +
        "message=${error.message.orEmpty()} cause=${cause?.javaClass?.name}: ${cause?.message} " +
        "video=${latestVideoFormat?.sampleMimeType}/${latestVideoFormat?.width}x${latestVideoFormat?.height} " +
        "size=${latestVideoSize?.width}x${latestVideoSize?.height} " +
        "audio=${latestAudioTracks.joinToString { it.codec.orEmpty() }} " +
        "subs=${latestSubtitleTracks.joinToString { it.codec.orEmpty() }}",
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

  override fun onAudioInputFormatChanged(
    eventTime: AnalyticsListener.EventTime,
    format: Format,
    decoderReuseEvaluation: DecoderReuseEvaluation?,
  ) {
    logInfo(
      "audio input format changed ${formatDescription(format)} " +
        "channelCount=${format.channelCount} sampleRate=${format.sampleRate}",
    )
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
    lastPublishedState = null
    latestVideoFormat = null
    latestVideoSize = null
    latestVideoDecoderName = null
    latestAudioTracks = emptyList()
    latestSubtitleTracks = emptyList()
    latestChapters = emptyList()
    onChaptersChanged(emptyList())
    media3AudioTrackGroups = emptyMap()
    media3SubtitleTrackGroups = emptyMap()
    subtitleTrackKeysById = emptyMap()
    subtitleTrackIdsByRenderer.clear()
    signRendererByIndex.clear()
    clearSubtitleCueBuffers()
    selectedSubtitleTrackIds.clear()
    desiredSubtitleTrackKeys.clear()
    preserveSubtitleSelection = false
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
    val state = snapshot()
    if (state == lastPublishedState) return
    lastPublishedState = state
    onStateChanged(state)
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
    const val TAG = "MpvInfinity"
    const val LARGE_BACKWARD_SEEK_RESET_MS = 30_000L
    const val POST_SEEK_BUFFER_LEAD_MS = 5_000L
    const val POST_SEEK_RESUME_TIMEOUT_MS = 8_000L
  }
}
