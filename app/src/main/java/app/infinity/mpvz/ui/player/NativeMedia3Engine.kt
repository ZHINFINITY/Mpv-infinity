package app.infinity.mpvz.ui.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import androidx.media3.common.C
import androidx.media3.common.text.CueGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

private class HentaiStreamLoggingDataSource(
  private val upstream: DataSource,
) : DataSource {
  private companion object {
    const val RANGE_CHUNK_BYTES = 1L * 1024L * 1024L
  }

  override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

  override fun open(dataSpec: DataSpec): Long {
    val isHentai = dataSpec.uri.host?.contains("hentaistream-addon.", ignoreCase = true) == true &&
      dataSpec.uri.path?.contains("/video-proxy", ignoreCase = true) == true
    val requestSpec = if (isHentai) {
      val start = dataSpec.position
      // The proxy rejects open-ended ranges after the initial response with HTTP 416. Media3
      // requests those ranges for normal continuation and seek operations, so make every unset
      // request an explicit bounded chunk. The next read will request the following chunk.
      val requestedLength =
        if (dataSpec.length == C.LENGTH_UNSET.toLong()) RANGE_CHUNK_BYTES else dataSpec.length
      val end = (start + requestedLength - 1).coerceAtLeast(start)
      val separator = if (dataSpec.uri.query.isNullOrBlank()) "?" else "&"
      val cacheKey = "${start}_${end}_${System.nanoTime()}"
      val requestUri = Uri.parse("${dataSpec.uri}$separator" + "mpvinfinity_range=$cacheKey")
      val headers = dataSpec.httpRequestHeaders.toMutableMap().apply {
        if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
          put("Range", "bytes=$start-$end")
        }
      }
      dataSpec.buildUpon().setUri(requestUri).setHttpRequestHeaders(headers).build()
    } else dataSpec
    val returned = runCatching { upstream.open(requestSpec) }.onFailure { error ->
      Log.e("Mpv∞-StreamHTTP", "open failed uri=${dataSpec.uri} position=${dataSpec.position} length=${dataSpec.length}", error)
    }.getOrThrow()
    val headers = upstream.responseHeaders
    fun header(name: String): String = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.joinToString("|") ?: "unknown"
    Log.i(
      "Mpv∞-StreamHTTP",
      "response uri=${requestSpec.uri} position=${dataSpec.position} requested=${dataSpec.length} " +
        "returned=$returned contentLength=${header("Content-Length")} contentRange=${header("Content-Range")} " +
        "acceptRanges=${header("Accept-Ranges")} contentType=${header("Content-Type")}",
    )
    return returned
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
  override fun getUri(): Uri? = upstream.uri
  override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
  override fun close() = upstream.close()
}

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
  val videoBitrateEstimated: Boolean = false,
  val audioCodec: String? = null,
  val audioBitrate: Int = 0,
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
  private val logTag = "Mpv∞-Media3"
  private val appContext = context.applicationContext
  @Volatile private var sourceSizeBytes: Long = 0L
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
  // HentaiStream proxy responses are cache-keyed upstream and may change between requests. A
  // local Media3 cache can otherwise preserve a 5-second placeholder and replay it for a later
  // episode or seek. Keep this path network-only; Torrentio and ordinary HTTP sources retain the
  // existing cache behavior.
  private val directNetworkDataSourceFactory =
    DefaultDataSource.Factory(context.applicationContext, DataSource.Factory {
      HentaiStreamLoggingDataSource(httpDataSourceFactory.createDataSource())
    })
  // Local files must not be routed through the network cache. Apart from adding an unnecessary
  // cache lookup, the cache factory's upstream is HTTP-only and cannot provide a local file.
  private val directLocalDataSourceFactory = DefaultDataSource.Factory(context.applicationContext)
  // Match v1.0.7: let Media3 construct its standard extractor set and subtitle parser.
  // Keep the custom data sources below for local/WebDAV transport; only the subtitle/extractor
  // pipeline is restored to the known-working default implementation.
  private val extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory()
  private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
  private val directLocalMediaSourceFactory =
    ProgressiveMediaSource.Factory(directLocalDataSourceFactory, extractorsFactory)
  private val directNetworkMediaSourceFactory =
    ProgressiveMediaSource.Factory(directNetworkDataSourceFactory, extractorsFactory)
  private var player = ExoPlayer.Builder(context.applicationContext)
    // Xiaomi's 4K HDR decoder can report no loading progress while the SurfaceView and codec are
    // being handed over from MPV. Disable this watchdog for Native; a real player/codec error is
    // still delivered through Player.Listener.onPlayerError.
    .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE)
    .setMediaSourceFactory(mediaSourceFactory)
    .setRenderersFactory(
      DefaultRenderersFactory(context.applicationContext)
        // Prefer platform hardware codecs for 4K/HDR; extensions remain available as fallback.
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // Keep Media3's decoder fallback enabled. Some HDR profile/codec combinations on Xiaomi
        // devices reject the first candidate even though a compatible Media3 decoder is available.
        .setEnableDecoderFallback(true),
    )
    .build()
  private var activePlayer: ExoPlayer = player
  private var attachedView: PlayerView? = null
  private var subtitleScale = 1f
  private var subtitlePosition = 100
  private var subtitleFontSize = 55
  private var subtitleOverlayVisible = true
  private var lastSelectedSubtitleTrack: NativeTrack? = null
  private var loopASeconds: Double? = null
  private var loopBSeconds: Double? = null
  private val loopHandler = Handler(Looper.getMainLooper())
  private var pendingSeekPositionMs: Long? = null
  private var pendingSeekDisplayPositionMs: Long? = null
  private val settleSeekRunnable = Runnable {
    val target = pendingSeekDisplayPositionMs ?: return@Runnable
    if (kotlin.math.abs(activePlayer.currentPosition - target) <= 1_500L) {
      pendingSeekDisplayPositionMs = null
      publishPlaybackSnapshot()
    }
  }
  private val seekRunnable = Runnable {
    val positionMs = pendingSeekPositionMs ?: return@Runnable
    pendingSeekPositionMs = null
    val targetMs = positionMs.coerceAtLeast(0L)
    pendingSeekDisplayPositionMs = targetMs
    // The source is prepared once with a seek-capable extractor. Seeking only moves the existing
    // extractor/decoder forward; it never rebuilds the MediaSource or flushes the surface.
    activePlayer.seekTo(targetMs)
    publishPlaybackSnapshot()
  }
  private val timelineRunnable = object : Runnable {
    override fun run() {
      if (activePlayer.currentMediaItem == null || (!activePlayer.isPlaying && !activePlayer.playWhenReady)) return
      publishPlaybackSnapshot()
      // Keep the seekbar responsive without forcing a 10 Hz Compose/native snapshot loop on a
      // 4K HDR decoder. Direct commands remain immediate; the UI only needs a quarter-second tick.
      loopHandler.postDelayed(this, 250L)
    }
  }
  private val loopRunnable = object : Runnable {
    override fun run() {
      val a = loopASeconds
      val b = loopBSeconds
      if (a != null && b != null && b > a && activePlayer.currentPosition >= (b * 1000.0).toLong()) {
        activePlayer.seekTo((a * 1000.0).toLong())
        if (!activePlayer.isPlaying) activePlayer.play()
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
  private val _subtitleCueText = MutableStateFlow("")
  val subtitleCueText: StateFlow<String> = _subtitleCueText.asStateFlow()
  val currentPlayer: Player get() = activePlayer
  private var metadataChapters: List<NativeChapter> = emptyList()
  private var preparationStartedAtMs: Long = 0L
  private var preparationUri: Uri? = null

  private val listener = object : Player.Listener {
    override fun onRenderedFirstFrame() {
      val uri = activePlayer.currentMediaItem?.localConfiguration?.uri
      val elapsed = preparationStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
      Log.d(logTag, "first frame rendered uri=$uri prepareElapsedMs=$elapsed")
      _hasRenderedFirstFrame.value = true
      attachedView?.post { configureSubtitleView() }
    }
    override fun onPlaybackStateChanged(playbackState: Int) {
      Log.d(logTag, "playback state=$playbackState uri=${activePlayer.currentMediaItem?.localConfiguration?.uri}")
    }
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
      if (reason == Player.DISCONTINUITY_REASON_SEEK) {
        val target = pendingSeekDisplayPositionMs
        if (target != null && kotlin.math.abs(newPosition.positionMs - target) <= 1_500L) {
          loopHandler.removeCallbacks(settleSeekRunnable)
          loopHandler.postDelayed(settleSeekRunnable, 750L)
        }
      }
    }
    override fun onPlayerError(error: PlaybackException) {
      Log.e(logTag, "player error uri=${activePlayer.currentMediaItem?.localConfiguration?.uri}", error)
    }

    override fun onCues(cueGroup: CueGroup) {
      _subtitleCueText.value = cueGroup.cues
        .mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
        .joinToString("\n")
      // Keep delivering cues to the ViewModel for translation, but do not allow Media3's
      // SubtitleView to draw the original cue underneath the translated Compose overlay.
      if (!subtitleOverlayVisible) {
        attachedView?.subtitleView?.post {
          if (!subtitleOverlayVisible) attachedView?.subtitleView?.setCues(emptyList())
        }
      }
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
      val elapsed = preparationStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
      Log.d(logTag, "timeline changed reason=$reason windowCount=${timeline.windowCount} prepareElapsedMs=$elapsed uri=$preparationUri")
      publishSnapshot()
    }

    override fun onTracksChanged(tracks: Tracks) {
      val elapsed = preparationStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
      val types = tracks.groups.joinToString(",") { it.type.toString() }
      Log.d(logTag, "tracks changed groups=${tracks.groups.size} types=$types prepareElapsedMs=$elapsed uri=$preparationUri")
      ensureEmbeddedSubtitleSelected(tracks)
      publishSnapshot()
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
      val elapsed = preparationStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }
      Log.d(logTag, "loading=$isLoading prepareElapsedMs=$elapsed uri=$preparationUri")
    }

    override fun onMetadata(metadata: Metadata) {
      Log.d(
        logTag,
        "metadata received entries=${metadata.length()} types=${(0 until metadata.length()).joinToString(",") { metadata[it].javaClass.simpleName }}",
      )
      // Do not erase the already-published chapter list when a transient callback has no chapters.
      metadataEntriesToChapters(metadata).takeIf { it.isNotEmpty() }?.let { metadataChapters = it }
      publishSnapshot()
    }

    override fun onEvents(player: Player, events: Player.Events) {
      if (events.contains(Player.EVENT_TRACKS_CHANGED)) configureSubtitleView()
      if (events.contains(Player.EVENT_TRACKS_CHANGED) ||
        events.contains(Player.EVENT_METADATA) ||
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
    Log.i(logTag, "Native Media3 configured: stuckBufferingDetectionTimeoutMs=${Int.MAX_VALUE}")
    // Large UHD/Dolby Vision files can take a long time to decode an exact frame after a seek.
    // Start at the nearest keyframe so the decoder can resume immediately and refill forward.
    activePlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    // Media3 can leave embedded text tracks unselected when a stream has no explicit
    // default subtitle flag. The previous MPV path selected these tracks automatically.
    // Keep text tracks enabled and allow undetermined-language subtitles to be selected;
    // the existing subtitle preferences and track controls still govern visibility/choice.
    activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .setSelectUndeterminedTextLanguage(true)
      .build()
    activePlayer.addListener(listener)
  }

  /** Select exact frame seeking or the faster nearest-keyframe seek mode. */
  fun setPreciseSeeking(enabled: Boolean) {
    activePlayer.setSeekParameters(if (enabled) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
  }

  fun attach(view: PlayerView) {
    attachedView?.player = null
    attachedView = view
    view.useController = false
    // Do not let a stale portrait measurement stretch native HDR video after rotation.
    view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    view.player = activePlayer
    configureSubtitleView()
    view.post { if (attachedView === view) configureSubtitleView() }
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

  fun setSubtitleOverlayVisible(visible: Boolean) {
    subtitleOverlayVisible = visible
    attachedView?.subtitleView?.apply {
      visibility = android.view.View.VISIBLE
      alpha = if (visible) 1f else 0f
    }
  }

  fun addExternalSubtitle(uri: Uri, select: Boolean): Boolean {
    val current = activePlayer.currentMediaItem?.localConfiguration ?: return false
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
    val wasPlaying = activePlayer.isPlaying
    val positionMs = activePlayer.currentPosition.coerceAtLeast(0L)
    val updated = MediaItem.Builder()
      .setUri(current.uri)
      .setSubtitleConfigurations(current.subtitleConfigurations + configuration)
      .build()
    activePlayer.setMediaItem(updated, positionMs)
    activePlayer.prepare()
    activePlayer.playWhenReady = wasPlaying
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
      backgroundColor,
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
      // This is the known-working Media3 configuration: keep embedded ASS/SSA/Matroska
      // font/style attributes, while retaining the app subtitle-size preference as the
      // controlled fallback used by the previous native implementation.
      setApplyEmbeddedStyles(true)
      setApplyEmbeddedFontSizes(false)
      setStyle(subtitleStyle)
      visibility = android.view.View.VISIBLE
      alpha = if (subtitleOverlayVisible) 1f else 0f
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
    // Uri.parse("/storage/...") has no scheme. Make local paths explicit so Media3 selects
    // FileDataSource instead of treating the original MediaStore URI as the playable source.
    val mediaUri =
      if (uri.scheme.isNullOrBlank() && uri.path?.startsWith("/") == true) {
        Uri.fromFile(File(uri.path!!))
      } else {
        uri
      }
    val isLocalUri = mediaUri.scheme.equals("file", ignoreCase = true) ||
      mediaUri.scheme.equals("content", ignoreCase = true)
    // Engine-selection observers can deliver the same request more than once while the
    // Activity is settling. Re-preparing the same URI resets Media3's extractor and clears
    // embedded subtitle track/cue state before it can render. Treat an active identical source
    // as idempotent and only update the requested play state.
    val activeUri = activePlayer.currentMediaItem?.localConfiguration?.uri
    if (activeUri == mediaUri && activePlayer.playbackState != Player.STATE_IDLE) {
      activePlayer.playWhenReady = autoplay
      if (autoplay && !activePlayer.isPlaying) activePlayer.play()
      Log.d(logTag, "play ignored duplicate active uri=$mediaUri state=${activePlayer.playbackState}")
      return
    }
    sourceSizeBytes = resolveLocalSize(mediaUri)
    val isHentaiStreamUri = mediaUri.host?.contains("hentaistream-addon.", ignoreCase = true) == true &&
      mediaUri.path?.contains("/video-proxy", ignoreCase = true) == true
    Log.d(
      logTag,
      "play uri=$mediaUri scheme=${mediaUri.scheme} source=${when {
        isLocalUri -> "direct-local"
        isHentaiStreamUri -> "direct-network"
        else -> "cached-network"
      }} " +
        "sourceUri=$sourceUri positionMs=$startPositionMs autoplay=$autoplay",
    )
    val requestHeaders = if (isHentaiStreamUri) {
      headers.toMutableMap().apply {
        put("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/151.0.7922.199 Mobile Safari/537.36")
        put("Referer", "https://hentaistream-addon.keypop3750.workers.dev/")
        put("Accept", "video/mp4,video/*;q=0.9,*/*;q=0.8")
        put("Cache-Control", "no-cache")
      }
    } else headers
    httpDataSourceFactory.setDefaultRequestProperties(requestHeaders)
    val mediaItem =
      MediaItem.Builder()
        .setUri(mediaUri)
        .apply {
          val declaredMime = mimeType?.takeUnless { it.equals("application/octet-stream", true) }
          (declaredMime ?: nativeContainerMimeType(mediaUri) ?: sourceUri?.let(::nativeContainerMimeType))
            ?.let(::setMimeType)
        }
        .build()
    Log.d(logTag, "Media3 MediaItem uri=${mediaItem.localConfiguration?.uri} scheme=${mediaUri.scheme}")
    preparationStartedAtMs = SystemClock.elapsedRealtime()
    preparationUri = mediaItem.localConfiguration?.uri
    if (sourceSizeBytes <= 0L && !isLocalUri && (mediaUri.scheme.equals("http", true) || mediaUri.scheme.equals("https", true))) {
      Thread {
        val resolved = resolveHttpSize(mediaUri, requestHeaders)
        if (resolved > 0L && preparationUri == mediaUri) {
          sourceSizeBytes = resolved
          Log.d(logTag, "resolved network media size=$resolved uri=$mediaUri")
          loopHandler.post { publishSnapshot() }
        }
      }.apply { name = "native-media-size"; isDaemon = true }.start()
    }
    metadataChapters = emptyList()
    Log.d(logTag, "prepare begin uri=$preparationUri")
    val mediaSource = when {
      isLocalUri -> directLocalMediaSourceFactory.createMediaSource(mediaItem)
      isHentaiStreamUri -> directNetworkMediaSourceFactory.createMediaSource(mediaItem)
      else -> mediaSourceFactory.createMediaSource(mediaItem)
    }
    activePlayer.setMediaSource(mediaSource, startPositionMs.coerceAtLeast(0L))
    // The PlayerView is attached once during Activity creation. Preparing immediately here is
    // required for local HDR files; deferring this through View.post can leave Media3 in BUFFERING
    // without ever starting the local data pipeline on Xiaomi devices.
    activePlayer.prepare()
    Log.d(logTag, "prepare returned elapsedMs=${SystemClock.elapsedRealtime() - preparationStartedAtMs} uri=$preparationUri")
    activePlayer.playWhenReady = autoplay
    publishSnapshot()
    startTimelineUpdates()
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

  private fun nativeContainerMimeType(uri: Uri): String? =
    when (uri.getQueryParameter("format")?.lowercase() ?: uri.path?.substringAfterLast('.', "")?.lowercase()) {
      "mkv", "mka" -> "video/x-matroska"
      "ts", "m2ts", "mts" -> "video/mp2t"
      "mp4", "m4v" -> "video/mp4"
      "webm" -> "video/webm"
      else -> null
    }

  fun setPlaying(playing: Boolean) {
    if (playing) activePlayer.play() else activePlayer.pause()
    publishSnapshot()
    startTimelineUpdates()
  }

  fun seekTo(positionMs: Long) {
    activePlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
    pendingSeekDisplayPositionMs = pendingSeekPositionMs
    loopHandler.removeCallbacks(settleSeekRunnable)
    loopHandler.removeCallbacks(seekRunnable)
    loopHandler.postDelayed(seekRunnable, 80L)
    // Seek controls must not enumerate every subtitle/audio metadata entry on the UI thread.
    publishPlaybackSnapshot()
    startTimelineUpdates()
  }

  /** Seeks immediately for chapter navigation; drag/tap scrubbing keeps the coalescing path. */
  fun seekToChapter(positionMs: Long) {
    val targetMs = positionMs.coerceAtLeast(0L)
    pendingSeekPositionMs = null
    pendingSeekDisplayPositionMs = targetMs
    loopHandler.removeCallbacks(settleSeekRunnable)
    loopHandler.removeCallbacks(seekRunnable)
    // Chapter boundaries must not resolve to the preceding sync frame, which makes the chapter
    // indicator briefly report the previous chapter while Media3 catches up.
    activePlayer.setSeekParameters(SeekParameters.EXACT)
    activePlayer.seekTo(targetMs)
    publishPlaybackSnapshot()
    startTimelineUpdates()
  }

  fun seekBy(offsetMs: Long) {
    activePlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    val basePositionMs = pendingSeekPositionMs ?: activePlayer.currentPosition
    pendingSeekPositionMs = (basePositionMs + offsetMs).coerceAtLeast(0L)
    pendingSeekDisplayPositionMs = pendingSeekPositionMs
    loopHandler.removeCallbacks(settleSeekRunnable)
    loopHandler.removeCallbacks(seekRunnable)
    loopHandler.postDelayed(seekRunnable, 80L)
    // Keep repeated seek-bar updates lightweight; the track/metadata snapshot is unchanged.
    publishPlaybackSnapshot()
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
    val clampedSpeed = speed.coerceIn(0.25f, 8f)
    activePlayer.setPlaybackParameters(
      PlaybackParameters(clampedSpeed, if (pitchCorrection) 1f else clampedSpeed),
    )
    publishSnapshot()
  }

  fun selectTrack(track: NativeTrack) {
    val group = activePlayer.currentTracks.groups.getOrNull(track.groupIndex) ?: return
    if (group.type != track.type || track.trackIndex !in 0 until group.length) return
    if (track.type == C.TRACK_TYPE_TEXT) lastSelectedSubtitleTrack = track
    activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(track.type, false)
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
      .build()
    publishSnapshot()
  }

  fun selectAudioTrack(group: Tracks.Group, trackIndex: Int) {
    activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
      .build()
    publishSnapshot()
  }

  fun selectSubtitleTrack(group: Tracks.Group, trackIndex: Int) {
    if (trackIndex !in 0 until group.length) return
    val format = group.getTrackFormat(trackIndex)
    lastSelectedSubtitleTrack = NativeTrack(
      groupIndex = activePlayer.currentTracks.groups.indexOf(group),
      trackIndex = trackIndex,
      type = C.TRACK_TYPE_TEXT,
      label = format.label ?: "Subtitle ${trackIndex + 1}",
      language = format.language,
      selected = true,
    )
    activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
      .build()
    publishSnapshot()
  }

  fun disableSubtitles() {
    activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
      .build()
    publishSnapshot()
  }

  fun restoreSubtitles() {
    lastSelectedSubtitleTrack?.let(::selectTrack)
  }

  fun addListener(listener: Player.Listener) = activePlayer.addListener(listener)
  fun removeListener(listener: Player.Listener) = activePlayer.removeListener(listener)

  fun stop() {
    clearLoop()
    loopHandler.removeCallbacks(timelineRunnable)
    loopHandler.removeCallbacks(seekRunnable)
    loopHandler.removeCallbacks(settleSeekRunnable)
    pendingSeekPositionMs = null
    pendingSeekDisplayPositionMs = null
    activePlayer.stop()
    activePlayer.clearMediaItems()
    _subtitleCueText.value = ""
    _hasRenderedFirstFrame.value = false
    publishSnapshot()
  }

  fun release() {
    clearLoop()
    loopHandler.removeCallbacks(timelineRunnable)
    loopHandler.removeCallbacks(seekRunnable)
    loopHandler.removeCallbacks(settleSeekRunnable)
    pendingSeekPositionMs = null
    activePlayer.removeListener(listener)
    attachedView?.player = null
    attachedView = null
    activePlayer.release()
  }

  private fun publishSnapshot() {
    val groups = activePlayer.currentTracks.groups
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
    val durationMs = activePlayer.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
    val declaredVideoBitrate = video?.bitrate?.takeIf { it > 0 } ?: 0
    val estimatedVideoBitrate =
      if (declaredVideoBitrate == 0 && sourceSizeBytes > 0L && durationMs > 0L) {
        ((sourceSizeBytes * 8_000L) / durationMs).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
      } else {
        0
      }
    _snapshot.value = NativePlaybackSnapshot(
      isPlaying = activePlayer.isPlaying,
      isReady = activePlayer.playbackState == Player.STATE_READY,
      isBuffering = activePlayer.playbackState == Player.STATE_BUFFERING,
      positionMs = (pendingSeekDisplayPositionMs ?: activePlayer.currentPosition).coerceAtLeast(0L),
      durationMs = durationMs,
      videoWidth = video?.width ?: 0,
      videoHeight = video?.height ?: 0,
      videoMimeType = video?.sampleMimeType,
      videoCodec = video?.codecs,
      videoBitrate = declaredVideoBitrate.takeIf { it > 0 } ?: estimatedVideoBitrate,
      videoBitrateEstimated = declaredVideoBitrate == 0 && estimatedVideoBitrate > 0,
      audioCodec = audio?.codecs ?: audio?.sampleMimeType,
      audioBitrate = audio?.bitrate?.takeIf { it > 0 } ?: 0,
      audioChannels = audio?.channelCount ?: 0,
      audioSampleRate = audio?.sampleRate ?: 0,
      speed = activePlayer.playbackParameters.speed,
      subtitleTracks = subtitles,
      audioTracks = audioTracks,
      chapters = chapters,
    )
  }

  /** Publishes only rapidly changing playback values; track/metadata enumeration is expensive. */
  private fun publishPlaybackSnapshot() {
    val previous = _snapshot.value
    _snapshot.value = previous.copy(
      isPlaying = activePlayer.isPlaying,
      isReady = activePlayer.playbackState == Player.STATE_READY,
      isBuffering = activePlayer.playbackState == Player.STATE_BUFFERING,
      positionMs = (pendingSeekDisplayPositionMs ?: activePlayer.currentPosition).coerceAtLeast(0L),
      durationMs = activePlayer.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
      speed = activePlayer.playbackParameters.speed,
    )
  }

  /**
   * Some Matroska/MP4 files expose embedded subtitles without a default text-track flag.
   * Media3 then reports the tracks but renders none. Match MPV's default behavior by selecting
   * the first supported embedded text track when the user has not selected one yet.
   */
  private fun ensureEmbeddedSubtitleSelected(tracks: Tracks) {
    if (tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && (0 until it.length).any { i -> it.isTrackSelected(i) } }) return
    val candidate = tracks.groups.firstOrNull { group ->
      group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { i -> group.isTrackSupported(i) }
    } ?: return
    val index = (0 until candidate.length).firstOrNull { candidate.isTrackSupported(it) } ?: return
    activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .setOverrideForType(TrackSelectionOverride(candidate.mediaTrackGroup, index))
      .build()
    lastSelectedSubtitleTrack = NativeTrack(
      groupIndex = tracks.groups.indexOf(candidate),
      trackIndex = index,
      type = C.TRACK_TYPE_TEXT,
      label = candidate.getTrackFormat(index).label ?: candidate.getTrackFormat(index).language ?: "Subtitle ${index + 1}",
      language = candidate.getTrackFormat(index).language,
      selected = true,
    )
    Log.d(logTag, "Auto-selected embedded subtitle group=${tracks.groups.indexOf(candidate)} track=$index")
  }

  private fun metadataEntriesToChapters(metadata: Metadata): List<NativeChapter> =
    (0 until metadata.length()).mapNotNull { index ->
      val entry = metadata.get(index)
      (entry as? Chapter)?.let { chapter ->
        if (chapter.isHidden()) return@mapNotNull null
        val startTimeMs = chapter.getStartTimeMs()
        if (startTimeMs == C.TIME_UNSET || startTimeMs < 0L) return@mapNotNull null
        val title = chapter.getTitle()?.value?.trim().orEmpty()
        return@mapNotNull NativeChapter(
          title.ifBlank { "Chapter ${index + 1}" },
          startTimeMs / 1000f,
        )
      }
      val startTimeMs = runCatching {
        entry.javaClass.methods.firstOrNull {
          it.name == "getStartTimeMs" || it.name == "getChapterTimeStart"
        }?.invoke(entry) as? Number
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
    if (activePlayer.currentMediaItem != null) loopHandler.post(timelineRunnable)
  }
}
