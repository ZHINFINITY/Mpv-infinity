package app.infinity.mpvz.ui.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.SurfaceView
import java.io.File
import app.infinity.mpvz.BuildConfig
import app.infinity.mpvz.R
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.AudioAttributes
import androidx.media3.common.text.CueGroup
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import app.infinity.mpvz.ui.player.LibassSubtitleSurfaceView
import app.infinity.mpvz.preferences.MpvFlowComputeApi
import androidx.media3.subtitle.libass.LibassSubtitleRenderer
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.math.pow

private object NativeMedia3Cache {
  @Volatile private var cache: SimpleCache? = null
  @Synchronized
  fun get(context: Context): SimpleCache {
    return cache ?: SimpleCache(
      java.io.File(context.applicationContext.cacheDir, "media3-native-cache"),
      LeastRecentlyUsedCacheEvictor(512L * 1024L * 1024L),
      StandaloneDatabaseProvider(context.applicationContext),
    ).also { cache = it }
  }
}

data class NativePlaybackSnapshot(
  val isPlaying: Boolean = false,
  val isReady: Boolean = false,
  val isBuffering: Boolean = false,
  val isLoading: Boolean = false,
  val isEnded: Boolean = false,
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  val totalBufferedDurationMs: Long = 0L,
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
  val videoOutputWidth: Int = 0,
  val videoOutputHeight: Int = 0,
  val videoPixelWidthHeightRatio: Float? = null,
  val videoOutputPixelWidthHeightRatio: Float? = null,
  val videoFrameRate: Float = 0f,
  val videoMimeType: String? = null,
  val videoCodec: String? = null,
  val videoDecoder: String? = null,
  val audioDecoder: String? = null,
  val videoDynamicRange: String? = null,
  val videoColorSpace: String? = null,
  val videoLumaBitDepth: Int? = null,
  val videoChromaBitDepth: Int? = null,
  val videoColorPrimariesIsoCode: Int? = null,
  val videoTransferCharacteristicsIsoCode: Int? = null,
  val videoMatrixCoefficientsIsoCode: Int? = null,
  val videoColorRange: String? = null,
  val videoHasHdrStaticMetadata: Boolean? = null,
  val videoTrackBitrate: Int = 0,
  val videoRenderedOutputBuffers: Long? = null,
  val videoSkippedInputBuffers: Long? = null,
  val videoSkippedOutputBuffers: Long? = null,
  val videoDroppedBuffers: Long? = null,
  val frameProcessingOffsetAverageUs: Long = 0L,
  val frameProcessingOffsetLastUs: Long = 0L,
  val frameProcessingSampleCount: Long = 0L,
  val audioCodec: String? = null,
  val audioBitrate: Int = 0,
  val audioChannels: Int = 0,
  val audioSampleRate: Int = 0,
  val bandwidthEstimateBps: Long = 0L,
  val bandwidthBytesLoaded: Long = 0L,
  val sourceSizeBytes: Long = 0L,
  val speed: Float = 1f,
  val media3Flow: Media3FlowDiagnostics = Media3FlowDiagnostics(),
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
  val formatId: String? = null,
  val external: Boolean = false,
  val sampleMimeType: String? = null,
  val codecs: String? = null,
  val channelCount: Int = 0,
)

internal fun media3ResizeModeForAspect(aspect: VideoAspect): Int = when (aspect) {
  VideoAspect.Fit -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
  VideoAspect.Crop -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
  VideoAspect.Stretch -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
}

internal class Media3AspectModeState(initialAspect: VideoAspect = VideoAspect.Fit) {
  private var selectedAspect = initialAspect

  fun select(aspect: VideoAspect) {
    selectedAspect = aspect
  }

  fun resizeMode(): Int = media3ResizeModeForAspect(selectedAspect)

  fun aspect(): VideoAspect = selectedAspect
}

private data class NativeSubtitleSelection(
  val groupId: String?,
  val formatId: String?,
  val label: String?,
  val language: String?,
  val mimeType: String?,
  val codecs: String?,
)

private data class NativeVideoFrameCounts(
  val renderedOutputBuffers: Long,
  val skippedInputBuffers: Long,
  val skippedOutputBuffers: Long,
  val droppedBuffers: Long,
) {
  fun since(previous: NativeVideoFrameCounts) = NativeVideoFrameCounts(
    renderedOutputBuffers = (renderedOutputBuffers - previous.renderedOutputBuffers).coerceAtLeast(0L),
    skippedInputBuffers = (skippedInputBuffers - previous.skippedInputBuffers).coerceAtLeast(0L),
    skippedOutputBuffers = (skippedOutputBuffers - previous.skippedOutputBuffers).coerceAtLeast(0L),
    droppedBuffers = (droppedBuffers - previous.droppedBuffers).coerceAtLeast(0L),
  )

  companion object {
    val ZERO = NativeVideoFrameCounts(0L, 0L, 0L, 0L)

    fun from(counters: DecoderCounters) = NativeVideoFrameCounts(
      renderedOutputBuffers = counters.renderedOutputBufferCount.toLong(),
      skippedInputBuffers = counters.skippedInputBufferCount.toLong(),
      skippedOutputBuffers = counters.skippedOutputBufferCount.toLong(),
      droppedBuffers = counters.droppedBufferCount.toLong(),
    )
  }
}

data class NativeChapter(
  val title: String,
  val startSeconds: Float,
)

/** A source-local Android Media3 playback engine. */
class NativeMedia3Engine(
  context: Context,
  enableMedia3Flow: Boolean = false,
  media3FlowTargetFps: Int = 60,
  media3FlowQuality: Int = 60,
  media3FlowComputeApi: MpvFlowComputeApi = MpvFlowComputeApi.AUTO,
  media3FlowVisibilityResolverDebug: Boolean = false,
  media3FlowSoftBlendDebug: Boolean = false,
) {
  private val appContext = context.applicationContext
  private val logTag = "Mpv∞-Media3"
  private val media3FlowDebugSettings =
    Media3FlowDebugSettingsPolicy.effective(
      debugBuild = BuildConfig.DEBUG,
      requestedVisibilityResolver = media3FlowVisibilityResolverDebug,
      requestedSoftBlend = media3FlowSoftBlendDebug,
    )
  private val effectiveMedia3FlowComputeApi =
    if (media3FlowDebugSettings.visibilityResolverEnabled || media3FlowDebugSettings.softBlendEnabled) {
      MpvFlowComputeApi.VULKAN
    } else {
      media3FlowComputeApi
    }
  private val flowDeviceSupported =
    enableMedia3Flow && Media3FlowVideoSink.deviceSupportsVulkanFlow(appContext)
  private val media3FlowSink = if (flowDeviceSupported) {
    Media3FlowVideoSink(
      appContext,
      media3FlowTargetFps,
      media3FlowQuality,
      effectiveMedia3FlowComputeApi,
      media3FlowDebugSettings.visibilityResolverEnabled,
      media3FlowDebugSettings.softBlendEnabled,
      ::publishFlowDiagnostics,
    )
  } else {
    null
  }
  private val _flowDiagnostics = MutableStateFlow(
    when {
      !enableMedia3Flow -> Media3FlowDiagnostics()
      !flowDeviceSupported -> Media3FlowDiagnostics(
        enabled = true,
        targetFps = media3FlowTargetFps,
        state = "unavailable",
        bypassReason = "vulkan_hardwarebuffer_unavailable",
      )
      media3FlowSink?.isPreflightAvailable() != true -> Media3FlowDiagnostics(
        enabled = true,
        targetFps = media3FlowTargetFps,
        state = "unavailable",
        bypassReason = "vulkan_hardwarebuffer_preflight_failed",
      )
      else -> Media3FlowDiagnostics(
        enabled = true,
        targetFps = media3FlowTargetFps,
        state = "waiting_for_supported_video",
        bypassReason = "awaiting_sdr_format",
      )
    },
  )
  init {
    if (enableMedia3Flow) {
      val diagnostics = _flowDiagnostics.value
      Log.i(
        logTag,
        "media3_flow_route enabled=${diagnostics.enabled} state=${diagnostics.state} " +
          "reason=${diagnostics.bypassReason ?: "none"} targetFps=${diagnostics.targetFps} " +
          "deviceVulkanFlowSupported=$flowDeviceSupported sinkCreated=${media3FlowSink != null}",
      )
    }
  }

  private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    .setAllowCrossProtocolRedirects(true)
    .setConnectTimeoutMs(15_000)
    .setReadTimeoutMs(30_000)
  private val cacheDataSourceFactory = CacheDataSource.Factory()
    .setCache(NativeMedia3Cache.get(context))
    .setUpstreamDataSourceFactory(httpDataSourceFactory)
    .setCacheReadDataSourceFactory(FileDataSource.Factory())
    .setFlags(
      CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
    )
  private val dataSourceFactory = DefaultDataSource.Factory(context.applicationContext, cacheDataSourceFactory)
  private val assHandler = AssHandler(renderType = AssRenderType.OVERLAY_CANVAS)
  private val assSubtitleParserFactory = NativeSubtitleParserFactory(AssSubtitleParserFactory(assHandler))
  private val defaultExtractorsFactory =
    androidx.media3.extractor.DefaultExtractorsFactory()
      .setSubtitleParserFactory(assSubtitleParserFactory)
  private val extractorsFactory: ExtractorsFactory = ExtractorsFactory {
    // Equivalent to withAssMkvSupport, but its API requires a concrete ASS factory and cannot
    // accept the bitmap passthrough policy. Keep the same ASS/font-aware Matroska extractor.
    defaultExtractorsFactory.createExtractors().map { extractor ->
      if (extractor is androidx.media3.extractor.mkv.MatroskaExtractor) {
        AssMatroskaExtractor(assSubtitleParserFactory, assHandler)
      } else {
        extractor
      }
    }.toTypedArray()
  }
  private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
    .setSubtitleParserFactory(assSubtitleParserFactory)
  private val player = ExoPlayer.Builder(context.applicationContext)
    .setLoadControl(
      DefaultLoadControl.Builder()
        .setBufferDurationsMs(
          10_000,
          120_000,
          1_000,
          3_000,
        )
        .setBackBuffer(10_000, false)
        // Keep a bounded amount of compressed media buffered; the disk cache handles repeated
        // network reads without forcing a large memory buffer on 4K HDR devices.
        .setTargetBufferBytes(128 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build(),
    )
    // Xiaomi's 4K HDR decoder can report no loading progress while the SurfaceView and codec are
    // being handed over from MPV. Disable this watchdog for Native; a real player/codec error is
    // still delivered through Player.Listener.onPlayerError.
    .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE)
    .setMediaSourceFactory(mediaSourceFactory)
    .setRenderersFactory(createRenderersFactory())
    .build()
  private var attachedView: PlayerView? = null
  private val videoAspect = Media3AspectModeState()
  private var subtitleOverlay: LibassSubtitleSurfaceView? = null
  private var libassRenderer: LibassSubtitleRenderer? = null
  private val externalAssEnabled = mutableMapOf<String, Boolean>()
  private var subtitleScale = 1f
  private var subtitlePosition = 100
  private var subtitleFontSize = 55
  private var subtitleFontFamily = "sans-serif"
  private var subtitleBold = false
  private var subtitleItalic = false
  private var subtitleTextColor = android.graphics.Color.WHITE
  private var subtitleBorderColor = android.graphics.Color.BLACK
  private var subtitleBackgroundColor = android.graphics.Color.TRANSPARENT
  private var subtitleBorderSize = 3
  private var subtitlePresentationVisible = true
  private val subtitleStyleRunnable = Runnable { applyAssStyle() }
  private val subtitleViewRunnable = Runnable { configureSubtitleView() }
  private var loopASeconds: Double? = null
  private var loopBSeconds: Double? = null
  private val loopHandler = Handler(Looper.getMainLooper())
  private var pendingSeekDisplayPositionMs: Long? = null
  private var initialResumeTargetMs: Long? = null
  private var lastKnownDurationMs: Long = 0L
  private var pendingExternalSelectionId: String? = null
  var hasEstablishedPlaybackPosition: Boolean = false
    private set
  private val seekDispatcher = NativeSeekDispatcher(
    nowMs = { SystemClock.uptimeMillis() },
    postDelayed = { task, delayMs -> loopHandler.postDelayed(task, delayMs); Unit },
    removeCallbacks = { task -> loopHandler.removeCallbacks(task) },
    dispatch = { targetMs, committed ->
      pendingSeekDisplayPositionMs = targetMs
      // Preserve the tested UHD/Dolby Vision keyframe policy. Preview seeks are throttled;
      // releasing the thumb immediately dispatches the final target without preparing again.
      Log.d(logTag, "seek targetMs=$targetMs committed=$committed ${bufferingLogState()}")
      player.seekTo(targetMs)
      publishPlaybackSnapshot()
    },
  )
  private val timelineRunnable = object : Runnable {
    override fun run() {
      // Keep the subtitle clock alive while paused, buffering, and immediately
      // after a seek. MPV renders from its current clock in all of these states.
      if (player.currentMediaItem == null) return
      publishSnapshot()
      subtitleOverlay?.setPositionUs(player.currentPosition.coerceAtLeast(0L) * 1000L)
      // Keep the seekbar responsive without forcing a 10 Hz Compose/native snapshot loop on a
      // 4K HDR decoder. Direct commands remain immediate; the UI only needs a quarter-second tick.
      loopHandler.postDelayed(this, 250L)
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
  private val _snapshot = MutableStateFlow(NativePlaybackSnapshot(media3Flow = _flowDiagnostics.value))
  val snapshot: StateFlow<NativePlaybackSnapshot> = _snapshot.asStateFlow()
  private val _subtitleCueText = MutableStateFlow("")
  val subtitleCueText: StateFlow<String> = _subtitleCueText.asStateFlow()
  private var selectedNativeSubtitleKey: Pair<Int, Int>? = null
  private val _hasRenderedFirstFrame = MutableStateFlow(false)
  val hasRenderedFirstFrame: StateFlow<Boolean> = _hasRenderedFirstFrame.asStateFlow()
  val currentPlayer: Player get() = player
  var onEnded: (() -> Unit)? = null
  var onPlaybackError: ((PlaybackException) -> Unit)? = null
  private var endedUri: Uri? = null
  private var metadataChapters: List<NativeChapter> = emptyList()
  private var preparationStartedAtMs: Long = 0L
  private var preparationUri: Uri? = null
  private var sourceSizeBytes: Long = 0L
  private var videoDecoderName: String? = null
  private var audioDecoderName: String? = null
  private var currentVideoFormat: Format? = null
  private var currentAudioFormat: Format? = null
  private var currentVideoSize: VideoSize? = null
  private var videoDecoderCounters: DecoderCounters? = null
  private var videoFrameCounterBaseline = NativeVideoFrameCounts.ZERO
  private var frameProcessingOffsetTotalUs = 0L
  private var frameProcessingSampleCount = 0L
  private var frameProcessingOffsetLastUs = 0L
  private var bandwidthEstimateBps = 0L
  private var bandwidthBytesLoaded = 0L
  private var selectedNativeSubtitleSelection: NativeSubtitleSelection? = null
  private var restoringNativeSubtitleSelection = false
  private var desiredAudioSelection: TrackSelection? = null
  private var desiredSubtitleSelection: TrackSelection? = null
  private var audioSelectionRestored = false
  private var subtitleSelectionRestored = false
  private var restoringTrackSelections = false

  private val analyticsListener = object : AnalyticsListener {
    override fun onVideoEnabled(
      eventTime: AnalyticsListener.EventTime,
      decoderCounters: DecoderCounters,
    ) {
      if (this@NativeMedia3Engine.videoDecoderCounters !== decoderCounters) {
        decoderCounters.ensureUpdated()
        videoFrameCounterBaseline = NativeVideoFrameCounts.ZERO
      }
      this@NativeMedia3Engine.videoDecoderCounters = decoderCounters
      publishSnapshot()
    }

    override fun onVideoDisabled(
      eventTime: AnalyticsListener.EventTime,
      decoderCounters: DecoderCounters,
    ) {
      decoderCounters.ensureUpdated()
      this@NativeMedia3Engine.videoDecoderCounters = decoderCounters
      publishSnapshot()
    }

    override fun onVideoDecoderInitialized(
      eventTime: AnalyticsListener.EventTime,
      decoderName: String,
      initializedTimestampMs: Long,
      initializationDurationMs: Long,
    ) {
      videoDecoderName = decoderName
      publishSnapshot()
    }

    override fun onAudioDecoderInitialized(
      eventTime: AnalyticsListener.EventTime,
      decoderName: String,
      initializedTimestampMs: Long,
      initializationDurationMs: Long,
    ) {
      audioDecoderName = decoderName
      publishSnapshot()
    }

    override fun onVideoInputFormatChanged(
      eventTime: AnalyticsListener.EventTime,
      format: Format,
      decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
      currentVideoFormat = format
      publishSnapshot()
    }

    override fun onAudioInputFormatChanged(
      eventTime: AnalyticsListener.EventTime,
      format: Format,
      decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
      currentAudioFormat = format
      publishSnapshot()
    }

    override fun onDroppedVideoFrames(
      eventTime: AnalyticsListener.EventTime,
      droppedFrames: Int,
      elapsedMs: Long,
    ) {
      if (droppedFrames > 0) {
        publishPlaybackSnapshot()
      }
    }

    override fun onVideoFrameProcessingOffset(
      eventTime: AnalyticsListener.EventTime,
      totalProcessingOffsetUs: Long,
      frameCount: Int,
    ) {
      if (frameCount > 0) {
        frameProcessingOffsetTotalUs += totalProcessingOffsetUs
        frameProcessingSampleCount += frameCount.toLong()
        frameProcessingOffsetLastUs = totalProcessingOffsetUs / frameCount
        publishPlaybackSnapshot()
      }
    }

    override fun onBandwidthEstimate(
      eventTime: AnalyticsListener.EventTime,
      totalLoadTimeMs: Int,
      totalBytesLoaded: Long,
      bitrateEstimate: Long,
    ) {
      bandwidthEstimateBps = bitrateEstimate.coerceAtLeast(0L)
      bandwidthBytesLoaded = totalBytesLoaded.coerceAtLeast(0L)
      publishPlaybackSnapshot()
    }
  }

  private val listener = object : Player.Listener {
    override fun onVideoSizeChanged(videoSize: VideoSize) {
      currentVideoSize = videoSize
      publishPlaybackSnapshot()
    }

    override fun onRenderedFirstFrame() {
      val uri = player.currentMediaItem?.localConfiguration?.uri
      val elapsed = preparationStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
      Log.d(logTag, "first frame rendered uri=$uri prepareElapsedMs=$elapsed")
      _hasRenderedFirstFrame.value = true
      attachedView?.post {
        ensureLibassRenderer()
        configureSubtitleView()
      }
    }
    override fun onPlaybackStateChanged(playbackState: Int) {
      if (playbackState == Player.STATE_READY) {
        hasEstablishedPlaybackPosition = true
        if (seekDispatcher.pendingPositionMs == null) {
          pendingSeekDisplayPositionMs = null
          initialResumeTargetMs = null
        }
      }
      Log.d(logTag, "playback state=$playbackState ${bufferingLogState()}")
      if (playbackState == Player.STATE_ENDED) {
        val uri = player.currentMediaItem?.localConfiguration?.uri
        if (uri != null && uri != endedUri) {
          endedUri = uri
          onEnded?.invoke()
        }
      }
    }
    override fun onPlayerError(error: PlaybackException) {
      Log.e(logTag, "player error uri=${player.currentMediaItem?.localConfiguration?.uri}", error)
      onPlaybackError?.let { callback ->
        runCatching { callback(error) }
          .onFailure { Log.e(logTag, "Native playback error callback failed", it) }
      }
    }

    override fun onCues(cueGroup: CueGroup) {
      // This is only a text bridge for embedded translation. Media3/ass-media still renders the
      // original cue through PlayerView/libass; no cue is converted or re-rendered here.
      _subtitleCueText.value = cueGroup.cues
        .mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
        .joinToString("\n")
    }
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
      val elapsed = preparationStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
      Log.d(logTag, "timeline changed reason=$reason windowCount=${timeline.windowCount} prepareElapsedMs=$elapsed uri=$preparationUri")
    }

    override fun onTracksChanged(tracks: Tracks) {
      val elapsed = preparationStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
      val types = tracks.groups.joinToString(",") { it.type.toString() }
      val selectedText = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        .sumOf { group -> (0 until group.length).count { group.isTrackSelected(it) } }
      Log.d(logTag, "tracks changed groups=${tracks.groups.size} types=$types selectedText=$selectedText prepareElapsedMs=$elapsed uri=$preparationUri")
      tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { group ->
        (0 until group.length).forEach { index ->
          val format = group.getTrackFormat(index)
          Log.d(logTag, "text track group=${group.mediaTrackGroup.id} index=$index selected=${group.isTrackSelected(index)} mime=${format.sampleMimeType} codecs=${format.codecs} label=${format.label} language=${format.language}")
        }
      }
      applyRestoredTrackSelections(tracks)
      restoreSelectedNativeSubtitle(tracks)
      applyPendingExternalSelection()
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
      val elapsed = preparationStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
      Log.d(logTag, "loading=$isLoading prepareElapsedMs=$elapsed ${bufferingLogState()}")
    }

    override fun onMetadata(metadata: Metadata) {
      // Media3 emits transient metadata callbacks while the indexed source is replacing the fast
      // source. Do not erase the already-published chapter list when that callback has no chapters.
      metadataEntriesToChapters(metadata).takeIf { it.isNotEmpty() }?.let { metadataChapters = it }
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
    // Keep Native playback in Android's media-audio focus lifecycle. A reused Media3 player can
    // otherwise continue buffering after a video item while the next music item has no focus.
    player.setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build(),
      true,
    )
    player.setHandleAudioBecomingNoisy(true)
    assHandler.init(player)
    Log.i(logTag, "Native Media3 configured: stuckBufferingDetectionTimeoutMs=${Int.MAX_VALUE}")
    // Large UHD/Dolby Vision files can take a long time to decode an exact frame after a seek.
    // Start at the nearest keyframe so the decoder can resume immediately and refill forward.
    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    player.addListener(listener)
    player.addAnalyticsListener(analyticsListener)
  }

  fun attach(view: PlayerView) {
    attachedView?.player = null
    attachedView = view
    subtitleOverlay = view.rootView.findViewById(R.id.media3_subtitle_overlay)
    view.subtitleView?.withAssSupport(assHandler)
    ensureLibassRenderer()
    // SurfaceView is composed in a separate layer and can cover normal sibling Views. Mark it as
    // a media layer so the standalone libass bitmap remains visible above the video surface.
    (view.videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(true)
    view.useController = false
    view.resizeMode = videoAspect.resizeMode()
    view.player = player
    media3FlowSink?.setVideoAspect(videoAspect.aspect())
    configureSubtitleView()
    view.post { if (attachedView === view) configureSubtitleView() }
    startTimelineUpdates()
  }

  /** Re-measure the Media3 surface after the host activity changes orientation. */
  fun refreshAfterConfigurationChange() {
    val view = attachedView ?: return
    view.post {
      if (attachedView !== view) return@post
      view.resizeMode = videoAspect.resizeMode()
      view.requestLayout()
      view.videoSurfaceView?.requestLayout()
      view.invalidate()
      configureSubtitleView()
    }
  }

  fun setVideoAspect(aspect: VideoAspect) {
    videoAspect.select(aspect)
    media3FlowSink?.setVideoAspect(aspect)
    attachedView?.resizeMode = videoAspect.resizeMode()
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
    loopHandler.removeCallbacks(subtitleViewRunnable)
    configureSubtitleView()
  }

  fun setSubtitlePosition(position: Int) {
    subtitlePosition = position.coerceIn(0, 150)
    loopHandler.removeCallbacks(subtitleViewRunnable)
    configureSubtitleView()
  }

  /** Hide/show subtitle presentation only; keep the selected Media3 text track active. */
  fun setSubtitlePresentationVisible(visible: Boolean) {
    subtitlePresentationVisible = visible
    attachedView?.subtitleView?.visibility = if (visible) View.VISIBLE else View.GONE
    subtitleOverlay?.visibility = if (visible) View.VISIBLE else View.GONE
  }

  fun addExternalSubtitle(uri: Uri, select: Boolean): Boolean {
    val current = player.currentMediaItem ?: return false
    val localConfiguration = current.localConfiguration ?: return false
    val mimeType = when (subtitleExtension(uri)) {
      "srt" -> "application/x-subrip"
      "vtt" -> "text/vtt"
      "ass", "ssa" -> "text/x-ssa"
      else -> "text/plain"
    }
    val configuration = MediaItem.SubtitleConfiguration.Builder(uri)
      .setId("external:$uri")
      // Keep the identity available even when an ASS/SSA parser replaces the Format id.
      // The snapshot code uses this marker to keep the track in the external section only.
      .setLabel("external:$uri")
      .setMimeType(mimeType)
      .setSelectionFlags(if (select) C.SELECTION_FLAG_DEFAULT else 0)
      .build()
    if (localConfiguration.subtitleConfigurations.any { it.id == configuration.id }) {
      if (select) {
        pendingExternalSelectionId = configuration.id
        loopHandler.post { applyPendingExternalSelection() }
      }
      return true
    }
    val wasPlaying = player.isPlaying
    val positionMs = player.currentPosition.coerceAtLeast(0L)
    // Keep the original MediaItem (headers, DRM, metadata and stream identity) intact. Rebuilding
    // from only current.uri makes downloaded online subtitles fail on authenticated/network media.
    val updated = current.buildUpon()
      .setSubtitleConfigurations(localConfiguration.subtitleConfigurations + configuration)
      .build()
    player.setMediaItem(updated, positionMs)
    player.prepare()
    player.playWhenReady = wasPlaying
    if (select) {
      pendingExternalSelectionId = configuration.id
      loopHandler.post { applyPendingExternalSelection() }
    }
    return true
  }

  fun toggleExternalSubtitle(uri: Uri): Boolean? {
    val id = "external:$uri"
    val match = player.currentTracks.groups
      .asSequence()
      .filter { it.type == C.TRACK_TYPE_TEXT }
      .flatMap { group ->
        (0 until group.length).asSequence().map { index -> group to index }
      }
      .firstOrNull { (group, index) ->
        val format = group.getTrackFormat(index)
        format.id == id || format.label == id
      }
      ?: return null
    if (!restoringTrackSelections) clearDesiredSelection(C.TRACK_TYPE_TEXT)
    val group = match.first
    val trackIndex = match.second
    val enabled = !group.isTrackSelected(trackIndex)
    val builder = player.trackSelectionParameters.buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .clearOverridesOfType(C.TRACK_TYPE_TEXT)
    if (enabled) {
      builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
    } else {
      val fallback = player.currentTracks.groups
        .asSequence()
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { candidate -> (0 until candidate.length).asSequence().map { candidate to it } }
        .firstOrNull { (candidate, index) ->
          candidate !== group && !candidate.getTrackFormat(index).id.orEmpty().startsWith("external:")
        }
      if (fallback != null) {
        builder.addOverride(TrackSelectionOverride(fallback.first.mediaTrackGroup, fallback.second))
      } else {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
      }
    }
    player.trackSelectionParameters = builder.build()
    return enabled
  }

  private fun applyPendingExternalSelection() {
    val id = pendingExternalSelectionId ?: return
    val match = player.currentTracks.groups
      .asSequence()
      .filter { it.type == C.TRACK_TYPE_TEXT }
      .flatMap { group -> (0 until group.length).asSequence().map { group to it } }
      .firstOrNull { (group, index) ->
        val format = group.getTrackFormat(index)
        format.id == id || format.label == id
      }
      ?: return
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .clearOverridesOfType(C.TRACK_TYPE_TEXT)
      .addOverride(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
      .build()
    pendingExternalSelectionId = null
    publishSnapshot()
  }

  fun captureAudioSelection(): String? = captureTrackSelection(C.TRACK_TYPE_AUDIO)

  fun captureSubtitleSelection(): String? = captureTrackSelection(C.TRACK_TYPE_TEXT)

  fun restoreTrackSelections(audio: String?, subtitle: String?) {
    desiredAudioSelection = PlaybackStatePersistence.decodeSelection(audio)
    desiredSubtitleSelection = PlaybackStatePersistence.decodeSelection(subtitle)
    audioSelectionRestored = desiredAudioSelection == null
    subtitleSelectionRestored = desiredSubtitleSelection == null
    applyRestoredTrackSelections(player.currentTracks)
  }

  private fun captureTrackSelection(type: Int): String? {
    val desired = if (type == C.TRACK_TYPE_AUDIO) desiredAudioSelection else desiredSubtitleSelection
    val applied = if (type == C.TRACK_TYPE_AUDIO) audioSelectionRestored else subtitleSelectionRestored
    if (desired != null && !applied) return PlaybackStatePersistence.encodeSelection(desired)
    val groups = player.currentTracks.groups.filter { it.type == type }
    if (groups.isEmpty()) return null
    if (player.trackSelectionParameters.disabledTrackTypes.contains(type)) {
      return PlaybackStatePersistence.encodeSelection(SharedTrackSelection.Disabled)
    }
    val selected = groups.flatMap { group ->
      (0 until group.length).filter { group.isTrackSelected(it) }.map { group to it }
    }.firstOrNull() ?: return null
    val ordinal = groups.takeWhile { it !== selected.first }.sumOf { it.length } + selected.second
    val fingerprint = trackSelectionMetadata(selected.first.getTrackFormat(selected.second))
      ?.let { TrackSelectionFingerprint.capture(it, TrackSelectionState.SELECTED, ordinal) }
      ?: return null
    return PlaybackStatePersistence.encodeSelection(SharedTrackSelection.selected(fingerprint))
  }

  private fun applyRestoredTrackSelections(tracks: Tracks) {
    if (restoringTrackSelections) return
    restoringTrackSelections = true
    try {
      desiredAudioSelection?.takeIf { !audioSelectionRestored }?.let {
        if (applyRestoredSelection(C.TRACK_TYPE_AUDIO, it, tracks)) audioSelectionRestored = true
      }
      desiredSubtitleSelection?.takeIf { !subtitleSelectionRestored }?.let {
        if (applyRestoredSelection(C.TRACK_TYPE_TEXT, it, tracks)) subtitleSelectionRestored = true
      }
    } finally {
      restoringTrackSelections = false
    }
  }

  private fun applyRestoredSelection(type: Int, selection: TrackSelection, tracks: Tracks): Boolean {
    if (selection.state == TrackSelectionState.UNKNOWN) return false
    if (selection.state == TrackSelectionState.DISABLED) {
      player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(type, true).clearOverridesOfType(type).build()
      if (type == C.TRACK_TYPE_TEXT) {
        selectedNativeSubtitleKey = null
        selectedNativeSubtitleSelection = null
      }
      return tracks.groups.any { it.type == type }
    }
    val groups = tracks.groups.filter { it.type == type }
    val candidates = groups.flatMap { group ->
      (0 until group.length).map { trackSelectionMetadata(group.getTrackFormat(it)) ?: TrackSelectionMetadata() }
    }
    val index = TrackSelectionMatcher.findMatchIndex(selection, candidates)
      ?: return false
    if (index < 0) return false
    var remaining = index
    val match = groups.firstNotNullOfOrNull { group ->
      if (remaining < group.length) group to remaining else { remaining -= group.length; null }
    } ?: return false
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
      .setTrackTypeDisabled(type, false).clearOverridesOfType(type)
      .setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second)).build()
    if (type == C.TRACK_TYPE_TEXT) {
      selectedNativeSubtitleKey = tracks.groups.indexOf(match.first) to match.second
      selectedNativeSubtitleSelection = subtitleSelectionFor(match.first, match.second)
    }
    return true
  }

  private fun trackSelectionMetadata(format: Format): TrackSelectionMetadata? {
    val externalUri = format.id?.takeIf { it.startsWith("external:") }?.removePrefix("external:")
      ?: format.label?.takeIf { it.startsWith("external:") }?.removePrefix("external:")
    val metadata = TrackSelectionMetadata(format.language, format.label, format.codecs,
      format.sampleMimeType, format.channelCount.takeIf { it > 0 }, externalUri)
    return metadata.takeIf { it.language != null || it.label != null || it.codec != null ||
      it.mimeType != null || it.channelCount != null || it.externalUri != null }
  }

  private fun subtitleSelectionFor(group: Tracks.Group, trackIndex: Int): NativeSubtitleSelection {
    val format = group.getTrackFormat(trackIndex)
    return NativeSubtitleSelection(
      groupId = group.mediaTrackGroup.id,
      formatId = format.id,
      label = format.label,
      language = format.language,
      mimeType = format.sampleMimeType,
      codecs = format.codecs,
    )
  }

  /** Rebind an explicit user choice if Media3 replaces its TrackGroup after preparation. */
  private fun restoreSelectedNativeSubtitle(tracks: Tracks) {
    val wanted = selectedNativeSubtitleSelection ?: return
    if (restoringNativeSubtitleSelection) return
    val match = tracks.groups
      .asSequence()
      .filter { it.type == C.TRACK_TYPE_TEXT }
      .flatMap { group -> (0 until group.length).asSequence().map { group to it } }
      .firstOrNull { (group, index) ->
        val candidate = subtitleSelectionFor(group, index)
        val stableIdMatches = wanted.formatId != null && wanted.formatId == candidate.formatId
        val descriptiveFieldsMatch =
          wanted.label == candidate.label &&
            wanted.language == candidate.language &&
            wanted.mimeType == candidate.mimeType &&
            wanted.codecs == candidate.codecs
        (wanted.groupId == candidate.groupId && descriptiveFieldsMatch) ||
          stableIdMatches || descriptiveFieldsMatch
      }
      ?: return
    if (match.first.isTrackSelected(match.second)) {
      selectedNativeSubtitleKey = tracks.groups.indexOf(match.first) to match.second
      return
    }
    restoringNativeSubtitleSelection = true
    try {
      player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .addOverride(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
        .build()
      selectedNativeSubtitleKey = tracks.groups.indexOf(match.first) to match.second
      Log.d(logTag, "Restored selected native subtitle after track-group refresh index=${match.second}")
    } finally {
      restoringNativeSubtitleSelection = false
    }
  }

  /** Content-provider URIs can hide the actual downloaded filename in their last path segment. */
  private fun disableNativeTextTracks() {
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
      .clearOverridesOfType(C.TRACK_TYPE_TEXT)
      .build()
    publishSnapshot()
  }

  private fun subtitleExtension(uri: Uri): String {
    val uriName = Uri.decode(uri.lastPathSegment.orEmpty())
    val providerName =
      if (uri.scheme == "content") {
        runCatching {
          appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
              val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull().orEmpty()
      } else {
        ""
      }
    return listOf(providerName, uriName)
      .firstOrNull { it.substringAfterLast('.', "").lowercase() in setOf("srt", "vtt", "ass", "ssa", "sub") }
      ?.substringAfterLast('.', "")
      ?.lowercase()
      .orEmpty()
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
    subtitleFontFamily = fontFamily?.takeIf { it.isNotBlank() } ?: "sans-serif"
    subtitleBold = bold
    subtitleItalic = italic
    subtitleTextColor = textColor
    subtitleBorderColor = borderColor
    subtitleBackgroundColor = backgroundColor
    subtitleBorderSize = borderSize.coerceAtLeast(0)
    val typefaceStyle = when {
      bold && italic -> android.graphics.Typeface.BOLD_ITALIC
      bold -> android.graphics.Typeface.BOLD
      italic -> android.graphics.Typeface.ITALIC
      else -> android.graphics.Typeface.NORMAL
    }
    subtitleStyle = CaptionStyleCompat(
      textColor,
      backgroundColor,
      android.graphics.Color.TRANSPARENT,
      CaptionStyleCompat.EDGE_TYPE_OUTLINE,
      borderColor,
      android.graphics.Typeface.create(fontFamily?.takeIf { it.isNotBlank() }, typefaceStyle),
    )
    loopHandler.removeCallbacks(subtitleStyleRunnable)
    applyAssStyle()
    loopHandler.removeCallbacks(subtitleViewRunnable)
    configureSubtitleView()
  }

  private fun applyAssStyle() {
    libassRenderer?.setStyle(
      subtitleFontFamily,
      subtitleFontSize,
      subtitleTextColor,
      subtitleBorderColor,
      subtitleBackgroundColor,
      subtitleBorderSize,
      subtitleBold,
      subtitleItalic,
    )
  }

  private fun scheduleSubtitleViewConfiguration() {
    loopHandler.removeCallbacks(subtitleViewRunnable)
    loopHandler.postDelayed(subtitleViewRunnable, 40L)
  }


  private fun ensureLibassRenderer(): LibassSubtitleRenderer? {
    val view = subtitleOverlay ?: return null
    val sourceWidth = snapshot.value.videoWidth.takeIf { it > 0 } ?: view.width.takeIf { it > 0 } ?: 1280
    val sourceHeight = snapshot.value.videoHeight.takeIf { it > 0 } ?: view.height.takeIf { it > 0 } ?: 720
    // libass uses the video's storage dimensions; the in-layout TextureView is transparent and
    // follows the same FIT container as Media3 so ASS coordinates remain in video space.
    val width = sourceWidth.coerceAtLeast(1)
    val height = sourceHeight.coerceAtLeast(1)
    if (width <= 0 || height <= 0) return null
    libassRenderer?.let {
      if (it.getWidth() != width || it.getHeight() != height) it.setSize(width, height)
      return it
    }
    return runCatching {
      LibassSubtitleRenderer(width, height, null).also {
        libassRenderer = it
        view.setRenderer(it)
        applyAssStyle()
        Log.i(logTag, "libass initialized size=${width}x${height} tracks=0")
      }
    }.onFailure { Log.e(logTag, "libass initialization failed", it) }.getOrNull()
  }

  private fun configureSubtitleView() {
    val view = attachedView ?: return
    view.subtitleView?.apply {
      // ass-media installs its ASS overlay inside this subtitle view. Keep the parent visible;
      // normal SRT/WebVTT cues continue to use the same Media3 view.
      visibility = if (subtitlePresentationVisible) View.VISIBLE else View.GONE
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
    subtitleOverlay?.apply {
      visibility = if (subtitlePresentationVisible) View.VISIBLE else View.GONE
      pivotX = width / 2f
      pivotY = height.toFloat()
      scaleX = subtitleScale
      scaleY = subtitleScale
      translationY = ((subtitlePosition - 100) / 100f * height * 0.5f)
        .coerceIn(-height * 0.5f, height * 0.5f)
    }
  }

  private fun isAssFormat(format: androidx.media3.common.Format): Boolean {
    val mime = format.sampleMimeType?.lowercase() ?: return false
    val codecs = format.codecs?.lowercase() ?: ""
    return mime.contains("ssa") || mime.contains("ass") || codecs.contains("ssa") || codecs.contains("ass")
  }

  fun play(
    uri: Uri,
    startPositionMs: Long = 0L,
    autoplay: Boolean = true,
    headers: Map<String, String> = emptyMap(),
    mimeType: String? = null,
    sourceUri: Uri? = null,
  ) {
    seekDispatcher.cancel()
    pendingSeekDisplayPositionMs = startPositionMs.coerceAtLeast(0L)
    initialResumeTargetMs = startPositionMs.coerceAtLeast(0L)
    hasEstablishedPlaybackPosition = false
    _hasRenderedFirstFrame.value = false
    endedUri = null
    lastKnownDurationMs = 0L
    videoDecoderName = null
    audioDecoderName = null
    currentVideoFormat = null
    currentAudioFormat = null
    currentVideoSize = null
    resetVideoFrameCounterBaseline()
    frameProcessingOffsetTotalUs = 0L
    frameProcessingSampleCount = 0L
    frameProcessingOffsetLastUs = 0L
    bandwidthEstimateBps = 0L
    bandwidthBytesLoaded = 0L
    // The player instance survives item changes; reset any ducked/zero output level before the
    // first audio-only item after a video transition.
    player.volume = 1f
    setSubtitlePresentationVisible(true)
    pendingExternalSelectionId = null
    selectedNativeSubtitleSelection = null
    desiredAudioSelection = null
    desiredSubtitleSelection = null
    audioSelectionRestored = false
    subtitleSelectionRestored = false
    externalAssEnabled.clear()
    libassRenderer?.getTrackIds()?.keys?.toList()?.forEach { id ->
      libassRenderer?.removeTrack(id)
    }
    // Uri.parse("/storage/...") has no scheme. Make local paths explicit so Media3 selects
    // FileDataSource instead of treating the original MediaStore URI as the playable source.
    val mediaUri =
      if (uri.scheme.isNullOrBlank() && uri.path?.startsWith("/") == true) {
        Uri.fromFile(File(uri.path!!))
      } else {
        uri
      }
    Log.d(logTag, "play uri=$mediaUri source=$sourceUri positionMs=$startPositionMs autoplay=$autoplay")
    httpDataSourceFactory.setDefaultRequestProperties(headers)
    val mediaItem =
      MediaItem.Builder()
        .setUri(mediaUri)
        .apply {
          // Database/network metadata may contain broad values such as video/*; passing those
          // to Media3 prevents extractor sniffing and makes otherwise valid MP4/WebM sources fail.
          val declaredMime = mimeType
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("application/octet-stream", true) && !it.endsWith("/*") }
          (declaredMime ?: nativeContainerMimeType(mediaUri) ?: sourceUri?.let(::nativeContainerMimeType))
            ?.let(::setMimeType)
        }
        .build()
    Log.i(logTag, "ass-media extractor enabled uri=$mediaUri")
    Log.d(logTag, "Media3 MediaItem uri=${mediaItem.localConfiguration?.uri} scheme=${mediaUri.scheme}")
    preparationStartedAtMs = SystemClock.elapsedRealtime()
    preparationUri = mediaItem.localConfiguration?.uri
    sourceSizeBytes = resolveLocalSize(mediaUri)
    if (sourceSizeBytes <= 0L && (mediaUri.scheme.equals("http", true) || mediaUri.scheme.equals("https", true))) {
      Thread {
        val resolved = resolveHttpSize(mediaUri, headers)
        if (resolved > 0L && preparationUri == mediaUri) {
          sourceSizeBytes = resolved
          loopHandler.post { publishSnapshot() }
        }
      }.apply { name = "native-media-size"; isDaemon = true }.start()
    }
    metadataChapters = emptyList()
    Log.d(logTag, "prepare begin uri=$preparationUri")
    player.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
    // The PlayerView is attached once during Activity creation. Preparing immediately here is
    // required for local HDR files; deferring this through View.post can leave Media3 in BUFFERING
    // without ever starting the local data pipeline on Xiaomi devices.
    player.prepare()
    Log.d(logTag, "prepare returned elapsedMs=${SystemClock.elapsedRealtime() - preparationStartedAtMs} uri=$preparationUri")
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
      "mov", "qt" -> "video/quicktime"
      "3gp", "3g2" -> "video/3gpp"
      "avi" -> "video/avi"
      "flv" -> "video/x-flv"
      "mpeg", "mpg" -> "video/mpeg"
      "ogv" -> "video/ogg"
      "m3u8" -> "application/x-mpegURL"
      "mpd" -> "application/dash+xml"
      else -> null
    }

  private fun resolveLocalSize(uri: Uri): Long {
    if (uri.scheme.equals("file", true)) return File(uri.path.orEmpty()).length()
    if (uri.scheme.equals("content", true)) {
      return runCatching {
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
      }.getOrNull()?.takeIf { it > 0L } ?: 0L
    }
    return 0L
  }

  private fun resolveHttpSize(uri: Uri, requestHeaders: Map<String, String>): Long =
    runCatching {
      fun open(method: String, range: String? = null) =
        (java.net.URL(uri.toString()).openConnection() as java.net.HttpURLConnection).apply {
          requestMethod = method
          connectTimeout = 4_000
          readTimeout = 4_000
          range?.let { setRequestProperty("Range", it) }
          requestHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
        }
      open("HEAD").let { connection ->
        try {
          if (connection.responseCode in 200..399 && connection.contentLengthLong > 0L) connection.contentLengthLong else -1L
        } finally {
          connection.disconnect()
        }
      }.takeIf { it > 0L } ?: open("GET", "bytes=0-0").let { connection ->
        try {
          val range = connection.getHeaderField("Content-Range").orEmpty()
          Regex("/(\\d+)$").find(range)?.groupValues?.get(1)?.toLongOrNull()
            ?: connection.contentLengthLong.takeIf { it > 0L }
            ?: -1L
        } finally {
          connection.disconnect()
        }
      }
    }.getOrDefault(-1L).coerceAtLeast(0L)

  fun setPlaying(playing: Boolean) {
    if (playing) {
      if (player.playbackState == Player.STATE_ENDED) endedUri = null
      player.volume = 1f
      player.play()
    } else {
      player.pause()
    }
    publishSnapshot()
    startTimelineUpdates()
  }

  /** The app queue owns repeat semantics because Native loads one queue item at a time. */
  fun setRepeatMode(repeatMode: RepeatMode) {
    // Do not let Media3 repeat its single MediaItem. PlayerActivity advances the app queue for ALL
    // and restarts the current item for ONE, matching the libmpv queue behavior.
    player.repeatMode = Player.REPEAT_MODE_OFF
  }

  fun seekTo(positionMs: Long) {
    seekDispatcher.commit(positionMs)
    // Seek controls must not enumerate every subtitle/audio metadata entry on the UI thread.
    publishPlaybackSnapshot()
    startTimelineUpdates()
  }

  fun cancelSeekPreview() {
    seekDispatcher.cancel()
  }

  fun seekPreviewTo(positionMs: Long) {
    seekDispatcher.preview(positionMs)
    publishPlaybackSnapshot()
    startTimelineUpdates()
  }

  fun seekBy(offsetMs: Long) {
    val basePositionMs = seekDispatcher.pendingPositionMs ?: player.currentPosition
    seekDispatcher.preview((basePositionMs + offsetMs).coerceAtLeast(0L))
    // Keep repeated seek-bar updates lightweight; the track/metadata snapshot is unchanged.
    publishPlaybackSnapshot()
    startTimelineUpdates()
  }

  /** Desired position for persistence, including a seek that has not reached the player yet. */
  fun positionForPersistenceMs(): Long? {
    if (player.currentMediaItem == null) return null
    return seekDispatcher.pendingPositionMs
      ?: pendingSeekDisplayPositionMs
      ?: initialResumeTargetMs?.takeIf { player.playbackState != Player.STATE_READY }
      ?: player.currentPosition.coerceAtLeast(0L)
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
    // Keep Native aligned with the shared speed sheet and ViewModel, both of which support up to
    // 8x. The old 4x clamp made the 4x-8x presets appear selectable but silently ineffective.
    val clampedSpeed = speed.coerceIn(0.25f, 8f)
    player.setPlaybackParameters(
      PlaybackParameters(clampedSpeed, if (pitchCorrection) 1f else clampedSpeed),
    )
    publishSnapshot()
  }

  fun selectTrack(track: NativeTrack) {
    val group = player.currentTracks.groups.getOrNull(track.groupIndex) ?: return
    if (group.type != track.type || track.trackIndex !in 0 until group.length) return
    if (!restoringTrackSelections) clearDesiredSelection(track.type)
    if (track.type == C.TRACK_TYPE_TEXT) {
      selectedNativeSubtitleSelection = subtitleSelectionFor(group, track.trackIndex)
      val format = group.getTrackFormat(track.trackIndex)
      if (isAssFormat(format.sampleMimeType, format.codecs)) {
        selectedNativeSubtitleKey = track.groupIndex to track.trackIndex
        player.trackSelectionParameters = player.trackSelectionParameters
          .buildUpon()
          .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
          .clearOverridesOfType(C.TRACK_TYPE_TEXT)
          .addOverride(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
          .build()
        publishSnapshot()
        return
      }
      selectedNativeSubtitleKey = track.groupIndex to track.trackIndex
      val builder = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
      builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
      player.trackSelectionParameters = builder.build()
      publishSnapshot()
      return
    }
    val override = if (track.type == C.TRACK_TYPE_TEXT) {
      val existing = player.trackSelectionParameters.overrides[group.mediaTrackGroup]?.trackIndices.orEmpty()
      TrackSelectionOverride(group.mediaTrackGroup, (existing + track.trackIndex).distinct())
    } else {
      TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
    }
    val builder = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(track.type, false)
    if (track.type == C.TRACK_TYPE_TEXT) {
      builder.addOverride(override)
    } else {
      builder.setOverrideForType(override)
    }
    player.trackSelectionParameters = builder.build()
    publishSnapshot()
  }

  /**
   * Toggle an embedded subtitle using the current Media3 track state.
   *
   * The UI snapshot is asynchronous and can still contain the previous selected flag when a
   * track-change callback arrives. Making the enable/disable decision from currentTracks avoids
   * turning subtitles off immediately after selecting another track.
   */
  fun toggleSubtitle(track: NativeTrack) {
    val group = player.currentTracks.groups.getOrNull(track.groupIndex) ?: return
    if (group.type != C.TRACK_TYPE_TEXT || track.trackIndex !in 0 until group.length) return
    if (group.isTrackSelected(track.trackIndex)) {
      disableSubtitles()
    } else {
      selectTrack(track)
    }
  }

  fun selectAudioTrack(group: Tracks.Group, trackIndex: Int) {
    if (!restoringTrackSelections) clearDesiredSelection(C.TRACK_TYPE_AUDIO)
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
      .build()
    publishSnapshot()
  }

  fun selectSubtitleTrack(groupIndex: Int, trackIndex: Int) {
    val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
    if (group.type != C.TRACK_TYPE_TEXT) return
    if (trackIndex !in 0 until group.length) return
    if (!restoringTrackSelections) clearDesiredSelection(C.TRACK_TYPE_TEXT)
    selectedNativeSubtitleSelection = subtitleSelectionFor(group, trackIndex)
    val format = group.getTrackFormat(trackIndex)
    if (isAssFormat(format.sampleMimeType, format.codecs)) {
      selectedNativeSubtitleKey = groupIndex to trackIndex
      player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        .build()
      publishSnapshot()
      return
    }
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .clearOverridesOfType(C.TRACK_TYPE_TEXT)
      .addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
      .build()
    selectedNativeSubtitleKey = groupIndex to trackIndex
    Log.i(logTag, "silent subtitle selection enabled group=${group.mediaTrackGroup.id} requested=$trackIndex")
    publishSnapshot()
  }

  fun disableSubtitles() {
    if (!restoringTrackSelections) clearDesiredSelection(C.TRACK_TYPE_TEXT)
    disableAssTracks()
    selectedNativeSubtitleKey = null
    selectedNativeSubtitleSelection = null
    player.trackSelectionParameters = player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
      .clearOverridesOfType(C.TRACK_TYPE_TEXT)
      .build()
    publishSnapshot()
  }

  private fun clearDesiredSelection(type: Int) {
    if (type == C.TRACK_TYPE_AUDIO) {
      desiredAudioSelection = null
      audioSelectionRestored = false
    } else if (type == C.TRACK_TYPE_TEXT) {
      desiredSubtitleSelection = null
      subtitleSelectionRestored = false
    }
  }

  private fun isAssFormat(mime: String?, codecs: String?): Boolean {
    val value = "${mime.orEmpty()} ${codecs.orEmpty()}".lowercase()
    return value.contains("ass") || value.contains("ssa")
  }

  private fun disableAssTracks() {
    libassRenderer?.getTrackIds()?.keys?.forEach { id -> libassRenderer?.setTrackEnabled(id, false) }
  }

  private fun createRenderersFactory(): RenderersFactory {
    val factory: DefaultRenderersFactory = media3FlowSink?.takeIf { it.isPreflightAvailable() }
      ?.let { Media3FlowRenderersFactory(appContext, it) }
      ?: DefaultRenderersFactory(appContext)
    // Keep platform/extension codecs and Media3's standard renderer candidates available for
    // HDR, DRM, unsupported geometry, or failed Vulkan/HardwareBuffer preflight.
    factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    factory.setEnableDecoderFallback(true)
    return NativeSubtitleRenderersFactory(factory.withAssSupport(assHandler))
  }

  private fun publishFlowDiagnostics(diagnostics: Media3FlowDiagnostics) {
    _flowDiagnostics.value = diagnostics
    _snapshot.value = _snapshot.value.copy(media3Flow = diagnostics)
  }

  fun addListener(listener: Player.Listener) = player.addListener(listener)
  fun removeListener(listener: Player.Listener) = player.removeListener(listener)

  private fun bufferingLogState(): String =
    "positionMs=${player.currentPosition} bufferedMs=${player.totalBufferedDuration} " +
      "loading=${player.isLoading} speed=${player.playbackParameters.speed} " +
      "videoDecoder=${videoDecoderName ?: "unknown"} " +
      "uri=${player.currentMediaItem?.localConfiguration?.uri}"

  fun stop() {
    clearLoop()
    loopHandler.removeCallbacks(timelineRunnable)
    seekDispatcher.cancel()
    pendingSeekDisplayPositionMs = null
    initialResumeTargetMs = null
    player.stop()
    player.clearMediaItems()
    currentVideoFormat = null
    currentVideoSize = null
    videoDecoderName = null
    _subtitleCueText.value = ""
    selectedNativeSubtitleKey = null
    selectedNativeSubtitleSelection = null
    desiredAudioSelection = null
    desiredSubtitleSelection = null
    audioSelectionRestored = false
    subtitleSelectionRestored = false
    externalAssEnabled.clear()
    libassRenderer?.let { renderer ->
      renderer.getTrackIds().keys.toList().forEach { renderer.removeTrack(it) }
    }
    _hasRenderedFirstFrame.value = false
    publishSnapshot()
  }

  fun startFlowDiagnosticCapture(
    sessionId: String,
    baseDirectory: String,
    pairedOutput: Boolean = false,
  ): String? =
    if (media3FlowSink == null) "Media3 Flow/Vulkan unavailable" else
      media3FlowSink.startFlowDiagnosticCapture(sessionId, baseDirectory, pairedOutput)

  fun stopFlowDiagnosticCapture(sessionId: String): String? =
    if (media3FlowSink == null) "Media3 Flow/Vulkan unavailable" else media3FlowSink.stopFlowDiagnosticCapture(sessionId)

  /** App-side timing only; SurfaceView scanout requires a companion FrameTimeline trace. */
  fun flowDiagnosticTimingSnapshot(): JSONObject {
    val d = _flowDiagnostics.value
    fun nullable(value: Number?): Any = value ?: JSONObject.NULL
    fun duration(value: FlowDurationSummary) = JSONObject()
      .put("average_us", nullable(value.averageUs))
      .put("p95_us", nullable(value.p95Us))
      .put("maximum_us", nullable(value.maximumUs))
      .put("sample_count", value.sampleCount)
    fun stage(value: FlowVulkanStageTiming) = JSONObject()
      .put("status", value.status)
      .put("supported", value.supported)
      .put("dispatches", value.dispatches)
      .put("completed_samples", value.completedSamples)
      .put("pending_or_dropped", value.pendingOrDropped)
      .put("average_us", nullable(value.averageUs))
      .put("maximum_us", nullable(value.maximumUs))

    val vulkan = d.vulkanTimings
    val gles = d.glesTimings
    val cpu = d.cpuTimings
    val coverage = d.pixelCoverage
    val gapContexts = JSONObject()
    d.outputTickGapContexts.toSortedMap().forEach { (reason, count) -> gapContexts.put(reason, count) }
    val outputFailureReasons = JSONObject()
    d.outputSubmissionFailureReasons.toSortedMap().forEach { (reason, count) -> outputFailureReasons.put(reason, count) }
    val presentationPathFailureReasons = JSONObject()
    d.presentationPathFailureReasons.toSortedMap().forEach { (reason, count) -> presentationPathFailureReasons.put(reason, count) }
    val noWritableSlotStates = JSONObject()
    d.lastNoWritablePresentationSlotStates.toSortedMap().forEach { (state, count) -> noWritableSlotStates.put(state, count) }
    return JSONObject()
      .put("schema_version", 1)
      .put("engine", "native-media3")
      .put("state", d.state)
      .put("bypass_reason", d.bypassReason ?: JSONObject.NULL)
      .put("target_fps", d.targetFps)
      .put("captured_source_fps_median_pts", d.capturedSourceFps.toDouble())
      .put("captured_pts_sample_count", d.capturedPtsSampleCount)
      .put("successful_egl_swap_return_fps", d.outputFps.toDouble())
      .put("generated_output_fps", d.generatedFps.toDouble())
      .put("generated_output_frames", d.generatedFrames)
      .put("sink_dropped_frames", d.droppedFrames)
      .put("inferred_skipped_output_ticks", d.skippedOutputTicks)
      .put("output_submission_failures", d.outputSubmissionFailures)
      .put("last_submission_failure_reason", d.lastOutputSubmissionFailureReason)
      .put("output_submission_failure_reasons", outputFailureReasons)
      .put("presentation_path_failure_reasons", presentationPathFailureReasons)
      .put("last_no_writable_presentation_slot_states", noWritableSlotStates)
      .put("rate_semantics", "eglSwapBuffers return cadence and generated submits; neither is SurfaceFlinger scanout")
      .put("actual_displayed_fps_available", false)
      .put("actual_displayed_fps_source", "Perfetto FrameTimeline companion trace required for SurfaceView")
      .put("motion_estimate_submit_ms", d.motionEstimateSubmitMs.toDouble())
      .put("motion_pass_average_gpu_us", nullable(d.motionPassAverageGpuUs))
      .put("confidence", nullable(d.confidence?.toDouble()))
      .put("motion_grid", JSONObject().put("width", d.motionGridWidth).put("height", d.motionGridHeight))
      .put("processing_dimensions", JSONObject().put("width", d.processingWidth).put("height", d.processingHeight))
      .put("frame_storage_dimensions", JSONObject().put("width", d.frameStorageWidth).put("height", d.frameStorageHeight))
      .put("cpu_timings", JSONObject()
        .put("input_queue_wait", duration(cpu.inputQueueWait))
        .put("frame_handler_call", duration(cpu.frameHandlerCall))
        .put("egl_swap_wait", duration(cpu.eglSwapWait))
        .put("vulkan_input_prepare_submit", duration(cpu.vulkanInputPrepare))
        .put("vulkan_synthesis_submit", duration(cpu.vulkanSynthesisSubmit)))
      .put("gles_timings", JSONObject()
        .put("status", gles.status)
        .put("supported", gles.supported)
        .put("valid_results", gles.validResults)
        .put("pending_results", gles.pendingResults)
        .put("disjoint_results_discarded", gles.disjointResultsDiscarded)
        .put("zero_duration_results", gles.zeroDurationResults)
        .put("skipped_because_pool_full", gles.skippedBecausePoolFull)
        .put("presentation_staging_copy", duration(gles.presentationStagingCopy)))
      .put("vulkan_timings", JSONObject()
        .put("status", vulkan.status)
        .put("timestamps_supported", vulkan.timestampsSupported)
        .put("timestamp_valid_bits", vulkan.timestampValidBits)
        .put("timestamp_period_ns", vulkan.timestampPeriodNs)
        .put("timestamp_compute_and_graphics", vulkan.timestampComputeAndGraphics)
        .put("compute_queue_family", vulkan.computeQueueFamily)
        .put("preprocess", stage(vulkan.preprocess))
        .put("analysis", stage(vulkan.analysis))
        .put("motion_fine", stage(vulkan.motionFine))
        .put("motion_middle", stage(vulkan.motionMiddle))
        .put("motion_coarse", stage(vulkan.motionCoarse))
        .put("synthesis", stage(vulkan.synthesis)))
      .put("pixel_coverage", JSONObject()
        .put("samples", coverage.samples)
        .put("sampled_pixels", coverage.sampledPixels)
        .put("motion_warp_pixels", coverage.motionWarpPixels)
        .put("source_fallback_pixels", coverage.sourceFrameFallbackPixels)
        .put("static_blend_pixels", coverage.staticBlendPixels)
        .put("interframe_changed_pixels", coverage.interframeChangedPixels)
        .put("changed_warp_pixels", coverage.interframeChangedWarpPixels)
        .put("changed_source_fallback_pixels", coverage.interframeChangedSourceFallbackPixels)
        .put("changed_static_blend_pixels", coverage.interframeChangedStaticBlendPixels)
        .put("static_vector_likely_motion", coverage.staticVectorLikelyMotionSamples)
        .put("static_vector_uncertain_motion", coverage.staticVectorUncertainMotionSamples))
      .put("queues", JSONObject()
        .put("pending_input_depth", d.pendingInputDepth)
        .put("input_in_flight", d.inputInFlight)
        .put("oldest_pending_input_age_ms", d.oldestPendingInputAgeMs.toDouble())
        .put("source_frame_depth", d.sourceFrameDepth)
        .put("ready_motion_pair_depth", d.readyMotionPairDepth)
        .put("presentation_queue_depth", d.presentationQueueDepth)
        .put("presentation_in_flight", d.presentationInFlight)
        .put("presentation_coalesced_frames", d.presentationCoalescedFrames))
      .put("frame_timing", JSONObject()
        .put("selected_source_pts_age_ms", nullable(d.selectedSourcePtsAgeMs?.toDouble()))
        .put("source_pair_pts_gap_ms", nullable(d.sourcePairPtsGapMs?.toDouble()))
        .put("frame0_capture_age_ms", nullable(d.frame0CaptureAgeMs?.toDouble()))
        .put("frame1_capture_age_ms", nullable(d.frame1CaptureAgeMs?.toDouble()))
        .put("output_callback_lateness_ms", d.outputCallbackLatenessMs.toDouble())
        .put("last_output_tick_gap_reason", d.lastOutputTickGapReason)
        .put("output_tick_gap_contexts", gapContexts))
  }

  fun release() {
    clearLoop()
    loopHandler.removeCallbacks(timelineRunnable)
    seekDispatcher.cancel()
    pendingSeekDisplayPositionMs = null
    initialResumeTargetMs = null
    desiredAudioSelection = null
    desiredSubtitleSelection = null
    audioSelectionRestored = false
    subtitleSelectionRestored = false
    player.removeListener(listener)
    player.removeAnalyticsListener(analyticsListener)
    attachedView?.player = null
    libassRenderer?.close()
    libassRenderer = null
    subtitleOverlay?.setRenderer(null)
    subtitleOverlay = null
    attachedView = null
    assHandler.release()
    player.release()
    media3FlowSink?.release()
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
        (0 until group.length).mapNotNull { trackIndex ->
          val format = group.getTrackFormat(trackIndex)
          val externalUri = format.id
            ?.takeIf { it.startsWith("external:") }
            ?.removePrefix("external:")
            ?: format.label
              ?.takeIf { it.startsWith("external:") }
              ?.removePrefix("external:")
          val isExternal = externalUri != null
          NativeTrack(
            groupIndex = groupIndex,
            trackIndex = trackIndex,
            type = group.type,
            label = if (isExternal) {
              externalUri.orEmpty().substringAfterLast('/').ifBlank { externalUri.orEmpty() }
            } else {
              format.label ?: format.language ?: "$fallback ${trackIndex + 1}"
            },
            language = format.language,
            formatId = externalUri?.let { "external:$it" } ?: format.id,
            external = isExternal,
            sampleMimeType = format.sampleMimeType,
            codecs = format.codecs,
            channelCount = format.channelCount.coerceAtLeast(0),
            selected = if (type == C.TRACK_TYPE_TEXT && selectedNativeSubtitleKey == (groupIndex to trackIndex)) {
              true
            } else group.isTrackSelected(trackIndex),
          )
        }
      }.flatten()
    val subtitles = tracksOfType(C.TRACK_TYPE_TEXT, "Subtitle")
    val audioTracks = tracksOfType(C.TRACK_TYPE_AUDIO, "Audio")
    // Track groups can describe unselected alternatives; video telemetry is sourced only from
    // the format actually consumed by the renderer.
    val video = currentVideoFormat
    val audio = currentAudioFormat
      ?: groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
        ?.getTrackFormat(0)
    val reportedDurationMs = player.duration
      .takeIf { it != C.TIME_UNSET && it > 0L }
      ?.also { lastKnownDurationMs = it }
      ?: lastKnownDurationMs
    val colorInfo = video?.colorInfo
    val dynamicRange = video?.let {
      val mime = it.sampleMimeType.orEmpty().lowercase()
      val codecs = it.codecs.orEmpty().lowercase()
      when {
        mime.contains("dolby-vision") || codecs.startsWith("dvhe") || codecs.startsWith("dvh1") -> "Dolby Vision"
        colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
        colorInfo?.colorTransfer == C.COLOR_TRANSFER_SDR -> "SDR"
        else -> null
      }
    }
    val colorSpace =
      when (colorInfo?.colorSpace) {
        C.COLOR_SPACE_BT2020 -> "BT.2020"
        C.COLOR_SPACE_BT709 -> "BT.709"
        C.COLOR_SPACE_BT601 -> "BT.601"
        else -> null
      }
    val activeVideoSize = currentVideoSize.takeIf { video != null }
    val videoFrameCounters = currentVideoFrameCounters()
    fun isoColorCode(code: Int?, convert: (Int) -> Int): Int? =
      code
        ?.takeIf { it != Format.NO_VALUE }
        ?.let(convert)
        ?.takeIf { it > 0 }
    _snapshot.value = NativePlaybackSnapshot(
      isPlaying = player.isPlaying,
      isReady = player.playbackState == Player.STATE_READY,
      isBuffering = player.playbackState == Player.STATE_BUFFERING,
      isLoading = player.isLoading,
      isEnded = player.playbackState == Player.STATE_ENDED,
      positionMs = (pendingSeekDisplayPositionMs ?: player.currentPosition).coerceAtLeast(0L),
      durationMs = reportedDurationMs,
      totalBufferedDurationMs = player.totalBufferedDuration.coerceAtLeast(0L),
      videoWidth = video?.width ?: 0,
      videoHeight = video?.height ?: 0,
      videoOutputWidth = activeVideoSize?.width ?: 0,
      videoOutputHeight = activeVideoSize?.height ?: 0,
      videoPixelWidthHeightRatio = video?.pixelWidthHeightRatio?.takeIf { it > 0f && it.isFinite() },
      videoOutputPixelWidthHeightRatio = activeVideoSize?.pixelWidthHeightRatio?.takeIf { it > 0f && it.isFinite() },
      videoFrameRate = video?.frameRate?.takeIf { it > 0f } ?: 0f,
      videoMimeType = video?.sampleMimeType,
      videoCodec = video?.codecs,
      videoDecoder = videoDecoderName,
      audioDecoder = audioDecoderName,
      videoDynamicRange = dynamicRange,
      videoColorSpace = colorSpace,
      videoLumaBitDepth = colorInfo?.lumaBitdepth?.takeIf { it > 0 },
      videoChromaBitDepth = colorInfo?.chromaBitdepth?.takeIf { it > 0 },
      videoColorPrimariesIsoCode =
        isoColorCode(colorInfo?.colorSpace) { ColorInfo.colorSpaceToIsoColorPrimaries(it) },
      videoTransferCharacteristicsIsoCode =
        isoColorCode(colorInfo?.colorTransfer) { ColorInfo.colorTransferToIsoTransferCharacteristics(it) },
      videoMatrixCoefficientsIsoCode =
        isoColorCode(colorInfo?.colorSpace) { ColorInfo.colorSpaceToIsoMatrixCoefficients(it) },
      videoColorRange = when (colorInfo?.colorRange) {
        C.COLOR_RANGE_FULL -> "Full"
        C.COLOR_RANGE_LIMITED -> "Limited"
        else -> null
      },
      videoHasHdrStaticMetadata = colorInfo?.hdrStaticInfo?.let { it.size > 0 },
      videoTrackBitrate = video?.bitrate?.takeIf { it > 0 } ?: 0,
      videoRenderedOutputBuffers = videoFrameCounters?.renderedOutputBuffers,
      videoSkippedInputBuffers = videoFrameCounters?.skippedInputBuffers,
      videoSkippedOutputBuffers = videoFrameCounters?.skippedOutputBuffers,
      videoDroppedBuffers = videoFrameCounters?.droppedBuffers,
      frameProcessingOffsetAverageUs =
        if (frameProcessingSampleCount > 0L) frameProcessingOffsetTotalUs / frameProcessingSampleCount else 0L,
      frameProcessingOffsetLastUs = frameProcessingOffsetLastUs,
      frameProcessingSampleCount = frameProcessingSampleCount,
      audioCodec = audio?.codecs ?: audio?.sampleMimeType,
      audioBitrate = audio?.bitrate?.takeIf { it > 0 } ?: 0,
      audioChannels = audio?.channelCount ?: 0,
      audioSampleRate = audio?.sampleRate ?: 0,
      bandwidthEstimateBps = bandwidthEstimateBps,
      bandwidthBytesLoaded = bandwidthBytesLoaded,
      sourceSizeBytes = sourceSizeBytes,
      speed = player.playbackParameters.speed,
      media3Flow = _flowDiagnostics.value,
      subtitleTracks = subtitles,
      audioTracks = audioTracks,
      chapters = chapters,
    )
  }

  /** Publishes only rapidly changing playback values; track/metadata enumeration is expensive. */
  private fun publishPlaybackSnapshot() {
    val previous = _snapshot.value
    val reportedDurationMs = player.duration
      .takeIf { it != C.TIME_UNSET && it > 0L }
      ?.also { lastKnownDurationMs = it }
      ?: lastKnownDurationMs
    val activeVideoSize = currentVideoSize.takeIf { currentVideoFormat != null }
    val videoFrameCounters = currentVideoFrameCounters()
    _snapshot.value = previous.copy(
      isPlaying = player.isPlaying,
      isReady = player.playbackState == Player.STATE_READY,
      isBuffering = player.playbackState == Player.STATE_BUFFERING,
      isLoading = player.isLoading,
      isEnded = player.playbackState == Player.STATE_ENDED,
      positionMs = (pendingSeekDisplayPositionMs ?: player.currentPosition).coerceAtLeast(0L),
      durationMs = reportedDurationMs,
      totalBufferedDurationMs = player.totalBufferedDuration.coerceAtLeast(0L),
      videoOutputWidth = activeVideoSize?.width ?: 0,
      videoOutputHeight = activeVideoSize?.height ?: 0,
      videoOutputPixelWidthHeightRatio = activeVideoSize?.pixelWidthHeightRatio?.takeIf { it > 0f && it.isFinite() },
      videoRenderedOutputBuffers = videoFrameCounters?.renderedOutputBuffers,
      videoSkippedInputBuffers = videoFrameCounters?.skippedInputBuffers,
      videoSkippedOutputBuffers = videoFrameCounters?.skippedOutputBuffers,
      videoDroppedBuffers = videoFrameCounters?.droppedBuffers,
      frameProcessingOffsetAverageUs =
        if (frameProcessingSampleCount > 0L) frameProcessingOffsetTotalUs / frameProcessingSampleCount else 0L,
      frameProcessingOffsetLastUs = frameProcessingOffsetLastUs,
      frameProcessingSampleCount = frameProcessingSampleCount,
      bandwidthEstimateBps = bandwidthEstimateBps,
      bandwidthBytesLoaded = bandwidthBytesLoaded,
      sourceSizeBytes = sourceSizeBytes,
      speed = player.playbackParameters.speed,
      media3Flow = _flowDiagnostics.value,
    )
  }

  private fun resetVideoFrameCounterBaseline() {
    val counters = videoDecoderCounters
    if (counters == null) {
      videoFrameCounterBaseline = NativeVideoFrameCounts.ZERO
      return
    }
    counters.ensureUpdated()
    videoFrameCounterBaseline = NativeVideoFrameCounts.from(counters)
  }

  private fun currentVideoFrameCounters(): NativeVideoFrameCounts? {
    if (currentVideoFormat == null) return null
    val counters = videoDecoderCounters ?: return null
    counters.ensureUpdated()
    return NativeVideoFrameCounts.from(counters).since(videoFrameCounterBaseline)
  }

  private fun metadataEntriesToChapters(metadata: Metadata): List<NativeChapter> =
    (0 until metadata.length()).mapNotNull { index ->
      val entry = metadata.get(index)
      if (entry is Chapter) {
        val startTimeMs = entry.getStartTimeMs()
        if (startTimeMs == C.TIME_UNSET || startTimeMs < 0L) return@mapNotNull null
        val title = entry.getTitle()?.value?.trim().orEmpty()
        return@mapNotNull NativeChapter(
          title.ifBlank { "Chapter ${index + 1}" },
          startTimeMs / 1000f,
        )
      }
      if (!entry.javaClass.simpleName.contains("chapter", ignoreCase = true)) return@mapNotNull null
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
