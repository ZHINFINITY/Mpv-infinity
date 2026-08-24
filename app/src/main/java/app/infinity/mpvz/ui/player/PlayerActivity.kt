/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player

import android.Manifest
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import app.infinity.mpvz.presentation.crash.AppDebugLog
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import app.infinity.mpvz.R
import app.infinity.mpvz.database.entities.PlaybackStateEntity
import app.infinity.mpvz.database.entities.PlaylistEntity
import app.infinity.mpvz.database.entities.PlaylistItemEntity
import app.infinity.mpvz.database.repository.NetworkStreamEntryRepository
import app.infinity.mpvz.databinding.PlayerLayoutBinding
import app.infinity.mpvz.domain.anime4k.Anime4KManager
import app.infinity.mpvz.domain.network.NetworkPlaybackUri
import app.infinity.mpvz.domain.playbackstate.repository.PlaybackStateRepository
import app.infinity.mpvz.domain.torrent.TorrentStreamRequest
import app.infinity.mpvz.domain.torrent.TorrentStreamException
import app.infinity.mpvz.domain.torrent.TorrentStreamingEngine
import app.infinity.mpvz.domain.torrent.canonicalInfoHash
import app.infinity.mpvz.domain.torrent.isTorrentSource
import app.infinity.mpvz.network.AndroidCookieJar
import app.infinity.mpvz.network.NetworkUserAgent
import app.infinity.mpvz.preferences.AdvancedPreferences
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.AudioChannels
import app.infinity.mpvz.preferences.AudioPlayerOrientation
import app.infinity.mpvz.preferences.AudioPreferences
import app.infinity.mpvz.preferences.BrowserPreferences
import app.infinity.mpvz.preferences.DecoderPreferences
import app.infinity.mpvz.preferences.MPVDecoderMode
import app.infinity.mpvz.preferences.PlaybackEngineMode
import app.infinity.mpvz.preferences.PlayerPreferences
import app.infinity.mpvz.preferences.SubtitlesPreferences
import app.infinity.mpvz.preferences.VideoSortType
import app.infinity.mpvz.ui.browser.playlist.ALL_VIDEOS_PLAYLIST_ID
import app.infinity.mpvz.ui.browser.playlist.buildAllVideosPlaylistEntity
import app.infinity.mpvz.ui.browser.playlist.isAllVideosPlaylist
import app.infinity.mpvz.ui.cast.CastMediaSnapshot
import app.infinity.mpvz.ui.cast.CastPlaybackController
import app.infinity.mpvz.ui.player.controls.PlayerControls
import app.infinity.mpvz.ui.player.ytdlp.YtdlpManager
import app.infinity.mpvz.ui.theme.MpvrxTheme
import app.infinity.mpvz.ui.torrent.TorrentSelectionActivity
import app.infinity.mpvz.utils.device.VulkanCapabilities
import app.infinity.mpvz.utils.history.RecentlyPlayedOps
import app.infinity.mpvz.utils.media.HttpUtils
import app.infinity.mpvz.utils.media.JellyfinSessionReporter
import app.infinity.mpvz.utils.media.MediaUtils
import app.infinity.mpvz.utils.media.TemporaryPlaybackQueue
import app.infinity.mpvz.utils.media.fileExtension
import app.infinity.mpvz.utils.media.resolveSeekMode
import app.infinity.mpvz.utils.media.M3UParseResult
import app.infinity.mpvz.utils.media.M3UParser
import app.infinity.mpvz.utils.media.PlaybackStateEvents
import app.infinity.mpvz.utils.media.SubtitleOps
import app.infinity.mpvz.utils.media.listTreeFilesSafely
import app.infinity.mpvz.utils.media.openPersistedTreeDocument
import app.infinity.mpvz.utils.storage.FileTypeUtils
import com.github.k1rakishou.fsaf.FileManager
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.koin.android.ext.android.inject
import okhttp3.OkHttpClient
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.LinkedHashMap
import kotlin.math.pow
import kotlin.math.roundToLong

private enum class BackgroundPlaybackStartResult {
  Started,
  PendingPermission,
  Blocked,
}

private enum class PlaybackEngine {
  MPV,
  MEDIA3,
}

/**
 * Main player activity that handles video playback using the MPV library.
 *
 * This activity manages:
 * - Video playback using MPV library
 * - System UI visibility (immersive mode)
 * - Audio focus management
 * - Picture-in-Picture (PiP) mode
 * - Background playback service
 * - MediaSession for external controls (Android Auto, Bluetooth, etc.)
 * - Playback state persistence and restoration
 * - Subtitle and audio track management
 * - Hardware key event handling
 *
 * @see PlayerViewModel for UI state management
 * @see MediaPlaybackService for background playback functionality
 */
@Suppress("TooManyFunctions", "LargeClass")
class PlayerActivity :
  AppCompatActivity(),
  PlayerHost {
  // ==================== ViewModels and Bindings ====================

  /**
   * View model for managing player UI state.
   */
  private val viewModel: PlayerViewModel by viewModels()

  /**
   * Binding for the player layout.
   */
  private val binding by lazy { PlayerLayoutBinding.inflate(layoutInflater) }

  /** Media3 backend kept behind the existing mpvRx UI for incremental playback migration. */
  private val media3PlaybackController by lazy {
    Media3PlaybackController(
      context = this,
      onStateChanged = { state ->
        lifecycleScope.launch(Dispatchers.Main.immediate) {
          media3State = state
          cachedMedia3State = state
          if (
            playbackEngine == PlaybackEngine.MEDIA3 &&
              state.videoWidth > 0 &&
              state.videoHeight > 0 &&
              playerPreferences.orientation.get() == PlayerOrientation.Video
          ) {
            setOrientation()
          }
        }
      },
      onError = { error ->
        Log.w(TAG, "Media3 playback error; falling back to MPV", error)
        lifecycleScope.launch(Dispatchers.Main.immediate) {
          // Media3 errors are also automatic Native failures. Without recording this state, the
          // MPV Dolby Vision track observer can immediately select Native again after this handoff.
          val failedItem = media3ActiveItem ?: currentPlaybackItem()
          if (failedItem != null && decoderPreferences.playbackEngine.get() == PlaybackEngineMode.Auto) {
            media3AutoFallbackItemId = failedItem.stableId
            AppDebugLog.warn(
              TAG,
              "Media3 error recorded as automatic fallback item=${failedItem.stableId}; " +
                "suppressing Native retry",
            )
          }
          switchToMpvEngine(failedItem)
        }
      },
      onVideoFrameRendered = {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
          if (playbackEngine == PlaybackEngine.MEDIA3) {
            media3VideoFrameRendered = true
            media3VideoWatchdogJob?.cancel()
          }
        }
      },
      onEnded = {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
          if (playbackEngine == PlaybackEngine.MEDIA3) {
            handleEndOfFile(isEof = true)
          }
        }
      },
      onChaptersChanged = { chapters ->
        lifecycleScope.launch(Dispatchers.Main.immediate) {
          if (playbackEngine == PlaybackEngine.MEDIA3) {
            viewModel.setMedia3Chapters(chapters)
          }
        }
      },
    )
  }

  /**
   * Observer for MPV events.
   */
  private val playerObserver by lazy { PlayerObserver(this) }

  /**
   * True when the current playback session was launched from the Secure Folder. Files hidden
   * there should never leave a trail in Recents/playback-history, regardless of how playback
   * later navigates (single file, auto-playlist, etc.).
   *
   * `PlayerActivity` is `singleTask`, so opening a new file while the player is already running
   * goes through `onNewIntent` (not `onCreate`) and reuses this same instance. This is a `var`
   * set explicitly in `onCreate` and recomputed from the current intent in `onNewIntent`
   * whenever genuinely new media is loaded — not a `by lazy` computed once and cached for the
   * activity's whole lifetime — so a stale value from an earlier, non-secure session can't
   * survive into a later secure-folder one (or vice versa). Defaults to `false` here since
   * `intent` isn't safely readable this early (before `onCreate`/`attach`).
   */
  private var isSecureFolderLaunch = false

  /** Original content/file URI from the current file-manager launch, retained for folder refresh. */
  private var externalContentLaunchUri: Uri? = null

  // ==================== Dependency Injection ====================

  /**
   * Repository for managing playback state.
   */
  private val playbackStateRepository: PlaybackStateRepository by inject()

  private val torrentStreamingEngine: TorrentStreamingEngine by inject()

  private val networkStreamEntryRepository: NetworkStreamEntryRepository by inject()

  private val networkHttpClient: OkHttpClient by inject()

  private val androidCookieJar: AndroidCookieJar by inject()

  /**
   * Repository for managing playlists.
   */
  private val playlistRepository: app.infinity.mpvz.database.repository.PlaylistRepository by inject()

  /**
   * Preferences for player settings.
   */
  private val playerPreferences: PlayerPreferences by inject()

  /**
   * Preferences for audio settings.
   */
  private val audioPreferences: AudioPreferences by inject()

  /**
   * Preferences for subtitle settings.
   */
  private val subtitlesPreferences: SubtitlesPreferences by inject()

  /**
   * Preferences for decoder and renderer settings.
   */
  private val decoderPreferences: DecoderPreferences by inject()

  /**
   * Preferences for advanced settings.
   */
  private val advancedPreferences: AdvancedPreferences by inject()

  /**
   * Preferences for browser settings.
   */
  private val browserPreferences: BrowserPreferences by inject()

  /**
   * Preferences for appearance settings.
   */
  private val appearancePreferences: AppearancePreferences by inject()

  /**
   * Manager for file operations.
   */
  private val fileManager: FileManager by inject()

  /**
   * Track selector for automatic audio/subtitle selection
   */
  private val trackSelector: TrackSelector by lazy {
    TrackSelector(audioPreferences, subtitlesPreferences)
  }

  // ==================== Views ====================

  /**
   * The MPV player view.
   */
  val player by lazy { binding.player }

  override fun currentThumbnailSource(): String? = currentPlayableUri

  override fun isCurrentMediaKnownAudio(): Boolean {
    // Explicit audio launches may use a shared container such as MKV; the launch hint must win
    // over the container extension. Video transitions write is_audio=false before loading.
    if (isKnownAudioLaunch(intent)) return true
    val extension =
      sequenceOf(fileName, currentPlayableUri)
        .filterNotNull()
        .map { it.fileExtension() }
        .firstOrNull { it in FileTypeUtils.AUDIO_EXTENSIONS || it in FileTypeUtils.VIDEO_EXTENSIONS }
    return extension in FileTypeUtils.AUDIO_EXTENSIONS
  }

  private fun isAudioPlaybackItem(item: PlaybackItem): Boolean {
    // An explicit audio launch is authoritative even for shared containers such as MKV whose
    // resolver MIME may be video/* despite the requested track being audio-only.
    if (isKnownAudioLaunch(intent)) return true
    val mimeType = item.mimeType.orEmpty()
    if (mimeType.startsWith("video/", ignoreCase = true)) return false
    if (mimeType.startsWith("audio/", ignoreCase = true)) return true
    val candidates = sequenceOf(item.originalUri, item.playableUri, item.title).filterNotNull().toList()
    if (candidates.any { candidate ->
        candidate.substringBefore('?').substringAfterLast('.').lowercase() in
          FileTypeUtils.AUDIO_EXTENSIONS
      }) {
      return true
    }
    // Audio-only containers such as MKV need the explicit launch hint because their extension is
    // also used by video files. A declared video MIME type above always takes precedence.
    return isKnownAudioLaunch(intent)
  }

  // ==================== State Management ====================

  /**
   * Current video file name being played.
   */
  private var fileName by mutableStateOf("")

  /**
   * Unique identifier for the current media, used for saving/loading playback state.
   * For network streams, this includes a hash of the URI to ensure uniqueness.
   */
  private var mediaIdentifier = ""
  private var legacyMediaIdentifier: String? = null
  private var pendingBackgroundPlaybackStart = false

  /**
   * Playlist of URIs for sequential playback
   */
  internal var playlist: List<Uri> = emptyList()

  /**
   * Database metadata for playlist items, if the current playlist was loaded from Room.
   */
  private var playlistItems: List<PlaylistItemEntity> = emptyList()

  /**
   * Original network metadata for intent-backed WebDAV/SMB/FTP playlists.
   */
  private var networkPlaylistPaths: List<String> = emptyList()
  private var networkPlaylistTitles: List<String> = emptyList()
  private var networkPlaylistHeaders: List<Map<String, String>> = emptyList()
  private var networkPlaylistConnectionId: Long = -1L

  /**
   * Playlist metadata for the current Room-backed playlist.
   */
  private var playlistEntity: PlaylistEntity? = null

  /**
   * Current index in the playlist
   */
  internal var playlistIndex: Int = 0

  private data class SavedPlaylistSelection(
    val index: Int,
    val stableId: String?,
    val originalUri: String?,
  )

  private var pendingSavedPlaylistSelection: SavedPlaylistSelection? = null

  /**
   * Playlist ID for tracking play history (optional, only for custom playlists)
   */
  private var playlistId: Int? = null

  /**
   * Tracks the starting offset of the loaded playlist window in the full playlist.
   * Used for windowed loading to prevent ANR with large playlists.
   */
  private var playlistWindowOffset: Int = 0

  /**
   * Total count of items in the full playlist (when using windowed loading).
   * -1 means unknown or not using windowed loading.
   */
  var playlistTotalCount: Int = -1
    private set

  /**
   * Indicates whether the current playlist is an M3U playlist sourced from database.
   * Used to skip thumbnail/metadata extraction for network streams.
   */
  private var isM3uPlaylist: Boolean = false

  /**
   * Helper for managing Picture-in-Picture mode.
   */
  private lateinit var pipHelper: MPVPipHelper
  private lateinit var castPlaybackController: CastPlaybackController

  private var isReady = false // Single flag: true when video loaded and ready
  private var isUserFinishing = false
  private var isBackgroundPlaybackSessionActive = false
  /** True only for the audio player’s top-left minimize action. */
  private var audioMinimizeRequested = false
  private var hardStopRequested = false
  private var wasInPipMode = false
  private var handledPipDismissal = false
  private val mainHandler = Handler(Looper.getMainLooper())
  private val pipDismissalStopRunnable = Runnable {
    if (wasInPipMode && !isInPictureInPictureMode && !isChangingConfigurations && !isFinishing) {
      handlePipDismissed()
    }
  }
  private var pendingBackgroundTransition = false
  private var pendingBackNavigationBackgroundTransition = false
  private var noisyReceiverRegistered = false
  private var lastVid = -1 // Track video track for background playback optimization
  private var isInBackgroundPlayback = false // Track if we are currently in background playback mode
  private var screenStateReceiverRegistered = false
  private var mpvInitialized = false // Track MPV initialization state
  private var playbackEngine by mutableStateOf(PlaybackEngine.MPV)
  private var media3State by mutableStateOf(Media3PlaybackController.State())
  // Media3 exposes player state on the application thread. Playback polling runs on a worker;
  // keep the last published snapshot available there instead of touching ExoPlayer off-thread.
  @Volatile private var cachedMedia3State = Media3PlaybackController.State()
  private var media3ItemId: String? = null
  private var media3PreparedItemId: String? = null
  /** True when MPV was fully stopped so Media3 could exclusively own the current item. */
  private var mpvStoppedForMedia3 = false
  private var media3VideoFrameRendered = false
  private var media3AutoFallbackItemId: String? = null
  private var activePlaybackItem: PlaybackItem? = null
  /** Last item submitted to Media3; retained while MPV is stopped and its queue is transiently empty. */
  private var media3ActiveItem: PlaybackItem? = null
  // A rotation button tap is authoritative for the current item. Video-aspect callbacks must not
  // immediately replace a user's manual Vertical/Landscape choice.
  private var manualOrientationOverride: Int? = null
  private var manualOrientationOverrideItemId: String? = null
  private var manualEngineOverrideItemId: String? = null
  private var manualEngineOverride: PlaybackEngine? = null
  private var media3VideoWatchdogJob: Job? = null
  private var media3Attached = false
  private fun hasPlaybackSessionToPersist(): Boolean =
    mpvInitialized || media3ItemId != null || media3PreparedItemId != null
  private var viewModelHostAttached = false
  private var torrentPickerHandoff = false
  private var savePlaybackStateJob: Job? = null // Track ongoing save job
  private var wasPlayingBeforePause = false // Track if video was playing before pause
  private var resumeAfterUnlockJob: Job? = null
  private var jellyfinSessionReporter: JellyfinSessionReporter? = null
  private var jellyfinProgressJob: Job? = null
  private val screenUnlockPlaybackController = ScreenUnlockPlaybackController()
  private var backgroundServiceSyncJob: Job? = null
  private var backgroundHandoffJob: Job? = null
  private var deferredFontSyncJob: Job? = null
  private var systemBarsAutoHideJob: Job? = null
  private var videoParamRefreshJob: Job? = null
  private var intentSubtitleJob: Job? = null
  private var mediaLoadJob: Job? = null
  @Volatile private var mediaRequestGeneration = 0L
  @Volatile private var folderDiscoveryInFlightGeneration: Long? = null
  private var eofAdvanceJob: Job? = null
  // Keep the old video decoder detached until mpv has completed the replacement load.
  // Reattaching it as part of `loadfile` can make the old and new outputs overlap.
  private var restoreVideoTrackAfterFileLoad = false

  @Volatile private var isAdvancingAtEof = false
  // Queue navigation must start the newly selected item at zero; reopening the same item may resume.
  @Volatile private var pendingQueueTransitionStartAtZero = false
  @Volatile private var pendingQueueTransitionItemId: String? = null
  @Volatile private var playWhenFileLoaded = false

  private var pendingVideoParamRefreshRequiresShaderReload = false
  private var lastBackgroundThumbnailKey: String? = null
  private var lastBackgroundThumbnail: Bitmap? = null
  private var currentPlayableUri: String? = null // Store current URI for notification re-entry
  private val playbackRenderDispatcher = Dispatchers.Main
  private val mediaLoadDispatcher = Dispatchers.Default.limitedParallelism(1)
  // Auto-mode preflight is intentionally bounded and cached: MediaExtractor metadata is useful
  // for Dolby Vision routing, but it must not add repeated startup latency on the same file.
  private val dolbyVisionProbeCache =
    object : LinkedHashMap<String, Boolean>(32, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
        size > 32
    }

  // ==================== Background Playback ====================

  /**
   * Reference to the background playback service.
   */
  private var mediaPlaybackService: MediaPlaybackService? = null

  /**
   * Tracks whether we're currently bound to the background playback service.
   */
  private var serviceBound = false

  // ==================== MediaSession ====================

  /**
   * MediaSession for integration with system media controls, Android Auto, and Wear OS.
   */
  private lateinit var mediaSession: MediaSession

  /**
   * Tracks whether MediaSession has been successfully initialized.
   */
  private var mediaSessionInitialized = false

  /**
   * Builder for MediaSession playback states.
   */
  private lateinit var playbackStateBuilder: PlaybackState.Builder

  // ==================== Audio Focus ====================

  /**
   * Audio focus request for API 26+.
   */
  private var audioFocusRequest: AudioFocusRequest? = null

  private var audioFocusRequestActive = false
  private var holdsAudioFocus = false
  private var resumeOnAudioFocusGain = false
  private var playbackDelayedForAudioFocus = false
  private var volumeBeforeAudioFocusDuck: Double? = null
  private var audioFocusRetryAttempt = 0
  private val audioFocusRetryRunnable: Runnable = Runnable {
    if (isFinishing || isDestroyed || serviceBound || isBackgroundPlaybackSessionActive) return@Runnable
    if (PlaybackSession.state.value.paused) return@Runnable
    if (requestAudioFocus()) {
      audioFocusRetryAttempt = 0
      PlaybackSession.setPropertyBoolean("pause", false)
      Log.d(TAG, "Audio focus recovered; resumed playback")
    } else if (audioFocusRetryAttempt < AUDIO_FOCUS_RETRY_MAX_ATTEMPTS) {
      audioFocusRetryAttempt++
      mainHandler.postDelayed(audioFocusRetryRunnable, AUDIO_FOCUS_RETRY_DELAY_MS)
    } else {
      Log.w(TAG, "Audio focus was not granted after bounded retries")
    }
  }

  // ==================== Broadcast Receivers ====================

  /**
   * Receiver for handling noisy audio events.
   */
  private val noisyReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
          viewModel.pause()
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
      }
    }

  private val screenStateReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        when (intent?.action) {
          Intent.ACTION_SCREEN_OFF -> {
            screenUnlockPlaybackController.onScreenTurnedOff(
              autoplayAfterScreenUnlockEnabled = playerPreferences.autoplayAfterScreenUnlock.get(),
              wasPlayingBeforePause = wasPlayingBeforePause,
              isCurrentlyPaused = viewModel.paused,
              backgroundPlaybackActive = isBackgroundPlaybackEnabled(),
              isUserFinishing = isUserFinishing,
              isFinishing = isFinishing,
            )
          }
          Intent.ACTION_USER_PRESENT -> {
            resumePlaybackAfterScreenUnlockIfNeeded()
          }
          Intent.ACTION_SCREEN_ON -> resumePlaybackAfterScreenUnlockIfNeeded()
        }
      }
    }

  /**
   * Listener for audio focus changes.
   */
  private val audioFocusChangeListener =
    AudioManager.OnAudioFocusChangeListener { focusChange ->
      when (focusChange) {
        AudioManager.AUDIOFOCUS_LOSS -> {
          audioFocusRequestActive = false
          holdsAudioFocus = false
          resumeOnAudioFocusGain = false
          playbackDelayedForAudioFocus = false
          restoreDuckedAudioVolume()
          // Ignore the loss caused by handing off playback to the detached
          // MediaPlaybackService so minimizing into the Mini Player does not pause.
          val handoff = isFinishing || isDestroyed || MediaPlaybackService.isActivityHandoffInProgress()
          if (handoff) return@OnAudioFocusChangeListener
          // Focus callbacks must not use the ordinary pause action: it abandons focus and can
          // erase the resume intent while Android is still dispatching a transient focus cycle.
          PlaybackSession.setPropertyBoolean("pause", true)
        }

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
          holdsAudioFocus = false
          val handoff = isFinishing || isDestroyed || MediaPlaybackService.isActivityHandoffInProgress()
          if (handoff) return@OnAudioFocusChangeListener
          val wasPlaying = PlaybackSession.getPropertyBoolean("pause") == false
          // Android can dispatch the same transient loss more than once during a call. Once a
          // playing session has requested resume, a later callback must not overwrite it merely
          // because the first callback already paused mpv.
          resumeOnAudioFocusGain = resumeOnAudioFocusGain || wasPlaying
          PlaybackSession.setPropertyBoolean("pause", true)
        }

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
          if (volumeBeforeAudioFocusDuck == null) {
            PlaybackSession.getPropertyDouble("volume")?.let { volume ->
              volumeBeforeAudioFocusDuck = volume
              PlaybackSession.setPropertyDouble("volume", volume * 0.5)
            }
          }
        }

        AudioManager.AUDIOFOCUS_GAIN -> {
          if (!audioFocusRequestActive) return@OnAudioFocusChangeListener
          audioFocusRequestActive = true
          holdsAudioFocus = true
          restoreDuckedAudioVolume()
          val shouldResume = resumeOnAudioFocusGain || playbackDelayedForAudioFocus
          resumeOnAudioFocusGain = false
          playbackDelayedForAudioFocus = false
          mainHandler.removeCallbacks(audioFocusRetryRunnable)
          audioFocusRetryAttempt = 0
          if (shouldResume) PlaybackSession.setPropertyBoolean("pause", false)
        }

        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
          Log.d(TAG, "Audio focus request failed")
          scheduleAudioFocusRetry()
        }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    activeInstance = WeakReference(this)
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    if (redirectUnselectedTorrentToPicker(intent, finishCurrent = true)) return
    pendingSavedPlaylistSelection = savedInstanceState?.toSavedPlaylistSelection()
    mediaRequestGeneration++
    // Read from the actual launch intent now that it's safe to (see isSecureFolderLaunch kdoc).
    isSecureFolderLaunch = intent.getStringExtra("launch_source") == "secure_folder"
    setContentView(binding.root)
    // Media3 is attached when its visible PlayerView has completed layout. Attaching while the
    // view is still GONE can leave a TextureView/decoder surface with zero bounds on some OEMs.
    setupSystemBarsAutoHide()
    setupPipHelper()

    // A detached background session belongs to PlaybackSession, not to the old Activity.
    // Notification re-entry attaches this new surface to that live core without reloading it.
    releaseDetachedBackgroundPlaybackBeforeFreshLaunch()
    val setupResult = setupMPV()
    if (setupResult != null) {
      isUserFinishing = true
      Toast.makeText(this, getString(R.string.toast_playback_load_failed) + ": " + setupResult, Toast.LENGTH_LONG).show()
      finish()
      return
    }
    // Construct the Activity-scoped adapter only after the process-wide native core exists;
    // its StateFlow declarations register native properties during ViewModel initialization.
    viewModel.attachHost(this)
    viewModelHostAttached = true
    // Seed the audio-only surface before MPV publishes its first track-list event. Without this
    // hint, the first audio file can briefly render the video-player surface and switch later.
    viewModel.setAudioOnlyLaunchHint(isKnownAudioLaunch(intent))
    viewModel.onMpvCoreInitialized()
    MediaPlaybackService.createNotificationChannel(this)
    setupAudio()
    setupBackPressHandler()
    setupPlayerControls()
    setupVideoTransformObserver()
    setupAudioPlayerViewObserver()
    setupMediaSession()
    observePlaybackSessionQueue()
    observeAutomaticDolbyVisionEngine()
    // Note: screenStateReceiver is now registered in onStart() and
    // unregistered in onStop(), matching the noisyReceiver pattern.
    // Previously it was registered here in onCreate and stayed registered
    // for the entire Activity lifetime — including while paused/stopped —
    // which wasted battery (every ACTION_SCREEN_OFF / ACTION_USER_PRESENT
    // woke the Activity) and risked leaking the receiver if onDestroy was
    // skipped. See issue 2.3 in the leak audit.

    playlistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
    playlistIndex = intent.getIntExtra("playlist_index", 0)
    loadNetworkPlaylistMetadata(intent)

    // Load playlist from intent extras first (fast path - backward compatibility)
    playlist =
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra("playlist") ?: emptyList()
      }

    val preparedPlaybackQueue =
      playlist.isEmpty() && restorePreparedPlaybackQueue(intent)

    var restoredSavedPlaylistItem = false
    if (playlist.isNotEmpty()) {
      playlistIndex = playlistIndex.coerceIn(0, playlist.lastIndex)
      restoredSavedPlaylistItem = applyPendingSavedSelection(playlist)
      playlistWindowOffset = 0
      playlistTotalCount = playlist.size
      viewModel.refreshPlaylistItems()
    }
    val hasReusableSavedPlaybackSession = hasValidSavedPlaybackSession()
    // A file manager's standalone ACTION_VIEW must start fresh. Reusing a prior singleton session
    // can skip folder discovery and leave the playlist sheet with no generated queue.
    val isExternalContentMediaLaunch =
      intent.action == Intent.ACTION_VIEW &&
        intent.data?.scheme in setOf(ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE)
    externalContentLaunchUri =
      if (isExternalContentMediaLaunch) intent.data else null
    val canReuseSavedPlaybackSession = hasReusableSavedPlaybackSession && !isExternalContentMediaLaunch

    // If playlist is empty but playlist_id is provided, load asynchronously from database
    // Load all items - LazyColumn handles pagination/virtualization efficiently
    if (playlist.isEmpty() && playlistId != null && !canReuseSavedPlaybackSession) {
      lifecycleScope.launch(Dispatchers.IO) {
        val pid = playlistId ?: return@launch
        try {
          loadPlaylistById(
            pid = pid,
            sourceIntent = intent,
            logPrefix = "Loaded",
          )
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load playlist from database", e)
        }
      }
    }

    // Auto-generate a folder queue for playlist-mode launches. When scoped storage leaves us
    // with only a content:// URI, use MediaStore metadata instead of passing fd:// to File().
    // A validated temporary queue is already the user’s complete, editable queue; never replace it
    // with siblings from the first item’s folder.
    if (playlist.isEmpty() &&
      playlistId == null &&
      playerPreferences.playlistMode.get() &&
      !canReuseSavedPlaybackSession &&
      !preparedPlaybackQueue
    ) {
      val path = parsePathFromIntent(intent)
      val sourceUri = extractUriFromIntent(intent)
      val localPath =
        path?.takeIf { File(it).isFile }
          ?: sourceUri
            ?.takeIf { it.scheme == "content" }
            ?.resolveLocalFilePath(this)
            ?.takeIf { File(it).isFile }
      if (localPath != null) {
        generatePlaylistFromFolder(localPath)
      } else if (sourceUri?.scheme == "content") {
        generatePlaylistFromMediaStore(sourceUri)
      }
    }

    // Extract fileName early so it's available when video loads
    fileName = getFileName(intent)
    if (fileName.isBlank()) {
      fileName = intent.data?.lastPathSegment ?: "Unknown Video"
    }
    legacyMediaIdentifier = getLegacyMediaIdentifier(intent, fileName)
    mediaIdentifier = getMediaIdentifier(intent, fileName)

    // A validated process-local session still owns its queue. Do not clear or republish it before
    // the saved-state attachment below has a chance to claim that exact current item.
    if (intent.action != MediaPlaybackService.ACTION_OPEN_PLAYER &&
      !preparedPlaybackQueue &&
      !canReuseSavedPlaybackSession
    ) {
      if (playlist.isEmpty()) {
        TemporaryPlaybackQueue.clear()
      } else {
        TemporaryPlaybackQueue.discardSnapshot()
        publishPlaylistToSession()
      }
    }

    // Set HTTP headers (including referer) BEFORE playing the file
    setHttpHeadersFromExtras(intent.extras)

    val attachedToCurrentSession =
      !isExternalContentMediaLaunch &&
        (attachToCurrentPlaybackSessionIfRequested() || attachToSavedPlaybackSessionIfValid())
    if (!attachedToCurrentSession && !restoredSavedPlaylistItem && playlist.isNotEmpty()) {
      pendingSavedPlaylistSelection = null
    }
    val awaitingRoomPlaylistRestore =
      !attachedToCurrentSession && pendingSavedPlaylistSelection != null && playlist.isEmpty() && playlistId != null
    if (!attachedToCurrentSession && restoredSavedPlaylistItem) {
      pendingSavedPlaylistSelection = null
      loadPlaylistItemInternal(playlistIndex, saveCurrentPlaybackState = false)
    } else if (!attachedToCurrentSession && !awaitingRoomPlaylistRestore) {
      getPlayableUri(intent)?.let { playableUri ->
        // Remind user if they forgot to set up yt-dlp
        if (playableUri.startsWith("http") && !playableUri.substringAfterLast('/').contains('.')) {
          val ytdlDir = YtdlpManager.getYtdlDir(this)
          if (!File(ytdlDir, "yt-dlp").exists()) {
            viewModel.showToast(getString(R.string.toast_need_ytdl))
          }
        }

        currentPlayableUri = playableUri
        isReady = false
        viewModel.onVideoLoadStarted()
        val originalUri = extractUriFromIntent(intent)
        val shouldExpandM3u =
          M3uPlaybackPolicy.shouldExpandInApp(
            playableUri = playableUri,
            originalUri = originalUri?.toString(),
            fileName = fileName,
            mimeType = intent.type,
            hasExistingPlaylist = playlist.isNotEmpty(),
            hasPlaylistId = playlistId != null,
          )
        if (shouldExpandM3u) {
          startMediaLoad(playableUri, originalUri?.toString(), expandM3u = true)
        } else {
          startMediaLoad(playableUri, originalUri?.toString())
        }
      }
    }
    setupCastPlayback()

    // Only set orientation immediately if NOT in Video mode
    // For Video mode, wait for video-params/aspect to become available
    if (isKnownAudioLaunch(intent) || playerPreferences.orientation.get() != PlayerOrientation.Video) {
      setOrientation()
    }

    // Apply persisted shuffle state after playlist is loaded
    viewModel.applyPersistedShuffleState()

    // Observe selected Lua scripts for runtime loading
    lifecycleScope.launch {
      var previousScripts = advancedPreferences.selectedLuaScripts.get()
      advancedPreferences.selectedLuaScripts.changes().collect { newScripts ->
        if (!advancedPreferences.enableLuaScripts.get()) {
          previousScripts = newScripts
          return@collect
        }
        val addedScripts = newScripts - previousScripts
        addedScripts.forEach { scriptName ->
          loadScriptAtRuntime(scriptName)
        }
        previousScripts = newScripts
      }
    }

    lifecycleScope.launch {
      advancedPreferences.enableLuaScripts.changes().drop(1).collect { enabled ->
        if (enabled) {
          advancedPreferences.selectedLuaScripts.get().forEach { scriptName ->
            loadScriptAtRuntime(scriptName)
          }
          if (advancedPreferences.selectedLuaScripts.get().isEmpty()) {
            viewModel.showToast("Scripts enabled")
          }
        } else {
          viewModel.showToast("Scripts disabled. Reopen the video if a script stays active.")
        }
      }
    }

    lifecycleScope.launch {
      audioPreferences.audioOrientation.changes().drop(1).collect {
        if (isKnownAudioLaunch(intent) || viewModel.isAudioOnly.value) {
          setOrientation()
        }
      }
    }

    lifecycleScope.launch {
      viewModel.chapters
        .map { chapters -> chapters.map { ChapterNode(time = it.start, title = it.name) } }
        .distinctUntilChanged()
        .collect { chapterNodes ->
          mediaPlaybackService?.setChapters(
            chapterNodes,
          )
        }
    }

    setLayoutInDisplayCutoutModeIfSupported(shortEdges = true)
  }

  override fun attachBaseContext(newBase: Context?) {
    if (newBase == null) {
      super.attachBaseContext(null)
      return
    }

    val originalConfiguration = newBase.resources.configuration
    val contextToUse =
      if (originalConfiguration.fontScale == 1f) {
        newBase
      } else {
        val updatedConfiguration = Configuration(originalConfiguration).apply { fontScale = 1f }
        val configurationContext = newBase.createConfigurationContext(updatedConfiguration)
        configurationContext
      }

    super.attachBaseContext(contextToUse)
  }

  private fun setupBackPressHandler() {
    val callback =
      object : OnBackPressedCallback(shouldInterceptBackPress()) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
          applyPredictiveBackProgress(backEvent)
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
          applyPredictiveBackProgress(backEvent)
        }

        override fun handleOnBackCancelled() {
          resetPredictiveBackProgress()
        }

        override fun handleOnBackPressed() {
          // Do not let the predictive-back transform compete with Android's
          // full-screen-to-PiP surface morph.
          resetPredictiveBackProgress(animate = false)
          handleBackPress()
        }
      }

    onBackPressedDispatcher.addCallback(
      this,
      callback,
    )

    lifecycleScope.launch {
      combine(
        viewModel.sheetShown,
        viewModel.panelShown,
        combine(
          playerPreferences.autoPiPOnNavigation.changes(),
          playerPreferences.enableVideoMiniPlayer.changes(),
          audioPreferences.backgroundPlayback.changes(),
          audioPreferences.audioBackgroundPlayback.changes(),
        ) { autoPip, miniPlayer, videoBg, audioBg ->
          autoPip || miniPlayer || videoBg || audioBg
        },
      ) { sheetShown, panelShown, prefsActive ->
        sheetShown != Sheets.None ||
          panelShown != Panels.None ||
          prefsActive ||
          viewModel.isAudioOnly.value ||
          isCurrentMediaKnownAudio()
      }.distinctUntilChanged()
        .collect { callback.isEnabled = it }
    }
  }

  private fun shouldInterceptBackPress(): Boolean =
    viewModel.sheetShown.value != Sheets.None ||
      viewModel.panelShown.value != Panels.None ||
      playerPreferences.autoPiPOnNavigation.get() ||
      isMiniPlayerEnabled() ||
      isBackgroundPlaybackEnabled()

  private fun applyPredictiveBackProgress(backEvent: BackEventCompat) {
    val root = binding.root
    val width = root.width
    val height = root.height
    if (width == 0 || height == 0) return

    val progress = backEvent.progress.coerceIn(0f, 1f)
    val fromRightEdge = backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT
    val direction = if (fromRightEdge) -1f else 1f
    val scale = 1f - (0.045f * progress)

    root.animate().cancel()
    binding.controls.animate().cancel()
    root.pivotX = if (fromRightEdge) width.toFloat() else 0f
    root.pivotY = backEvent.touchY.coerceIn(0f, height.toFloat())
    root.scaleX = scale
    root.scaleY = scale
    root.translationX = direction * width * 0.04f * progress
    binding.controls.alpha = 1f - (0.2f * progress)
  }

  private fun resetPredictiveBackProgress(animate: Boolean = true) {
    binding.root.animate().cancel()
    binding.controls.animate().cancel()
    if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
      binding.root.scaleX = 1f
      binding.root.scaleY = 1f
      binding.root.translationX = 0f
      binding.controls.alpha = 1f
      return
    }

    binding.root
      .animate()
      .scaleX(1f)
      .scaleY(1f)
      .translationX(0f)
      .setDuration(140L)
      .start()
    binding.controls
      .animate()
      .alpha(1f)
      .setDuration(140L)
      .start()
  }

  /**
   * Minimizes the audio player without stopping the current track. This is intentionally separate
   * from normal Back and the explicit hard-stop action: the top-left audio arrow is a minimize
   * affordance regardless of the user's background-playback preference.
   */
  private fun minimizeAudioPlayer() {
    if (!viewModel.isAudioOnly.value) {
      handleBackPress()
      return
    }

    // A session reopened from the mini-player can still be in BACKGROUND while the Activity is
    // attaching. Treat that as ready audio instead of routing the arrow through normal Back.
    if (!isAudioSessionReady()) {
      viewModel.showToast(getString(R.string.toast_playback_load_failed))
      return
    }

    when (
      startBackgroundPlayback(
        allowUserPrompt = false,
        bindToActivity = false,
      )
    ) {
      BackgroundPlaybackStartResult.Started -> {
        audioMinimizeRequested = true
        isBackgroundPlaybackSessionActive = true
        isUserFinishing = true
        MediaPlaybackService.nativeBackgroundRequested = false
        setActivityMediaSessionActive(false)
        finish()
      }
      BackgroundPlaybackStartResult.PendingPermission,
      BackgroundPlaybackStartResult.Blocked,
      -> {
        // A minimize action must not turn into an accidental hard stop. Keep the Activity open
        // when the detached service cannot be started (for example, notification permission is
        // unavailable), so the user can continue playback and correct the permission/settings.
        viewModel.showToast(getString(R.string.notification_disabled_in_advanced_settings))
      }
    }
  }

  private fun handleBackPress() {
    // Dismiss overlays first
    if (viewModel.sheetShown.value != Sheets.None) {
      viewModel.sheetShown.update { Sheets.None }
      viewModel.showControls()
      return
    }

    if (viewModel.panelShown.value != Panels.None) {
      viewModel.panelShown.update { Panels.None }
      viewModel.showControls()
      return
    }

    // Background playback or Mini Player handoff on Back: return to the browser while
    // handing the live MPV session to the foreground service.
    if (
      isMiniPlayerEnabled() ||
      PlayerLifecyclePolicy.shouldStartBackgroundPlaybackOnBack(
        backgroundPlaybackEnabled = isBackgroundPlaybackEnabled(),
        mediaReady = isReady,
      )
    ) {
      when (startBackgroundPlayback()) {
        BackgroundPlaybackStartResult.Started -> {
          pendingBackNavigationBackgroundTransition = true
          completePendingBackgroundHandoff()
        }
        BackgroundPlaybackStartResult.PendingPermission -> {
          pendingBackNavigationBackgroundTransition = true
        }
        BackgroundPlaybackStartResult.Blocked -> {
          isUserFinishing = true
          finish()
        }
      }
      return
    }

    // Check if auto PIP is enabled - enter PIP mode instead of finishing
    if (playerPreferences.autoPiPOnNavigation.get() && !viewModel.isAudioOnly.value && !isCurrentMediaKnownAudio() && isReady) {
      enterPipModeSmoothly()
      return
    }

    isUserFinishing = true
    finish()
  }

  private fun setupPlayerControls() {
    binding.controls.setContent {
      MpvrxTheme {
        Box(modifier = Modifier.fillMaxSize()) {
          PlayerControls(
            viewModel = viewModel,
            onBackPress = ::handleBackPress,
            onClosePlayer = ::requestExplicitHardStop,
            onMinimizeAudioPlayer = ::minimizeAudioPlayer,
            isMedia3Active = playbackEngine == PlaybackEngine.MEDIA3,
            media3State = media3State,
            engineSelection =
              if (playbackEngine == PlaybackEngine.MEDIA3) {
                PlaybackEngineMode.Media3
              } else {
                PlaybackEngineMode.MPV
              },
            onEngineSelected = ::selectEngineFromDecoderSheet,
            onMedia3AudioChannels = { channels ->
              media3PlaybackController.setAudioChannels(channels)
            },
            onMedia3AudioProcessing = { normalization, drc ->
              media3PlaybackController.setAudioProcessing(normalization, drc)
            },
            onMedia3AudioPitchCorrection = { enabled ->
              media3PlaybackController.setAudioPitchCorrection(enabled)
            },
            onDecoderSelected = { decoder ->
              // MPV decoder choices must never wake or switch to MPV while Media3 owns playback.
              // Keep this guard at the Activity boundary in case a stale Compose callback arrives.
              if (playbackEngine != PlaybackEngine.MEDIA3) {
                decoderPreferences.mpvDecoderMode.set(
                  MPVDecoderMode.entries.firstOrNull {
                    it.value == decoder.value
                  } ?: MPVDecoderMode.Auto,
                )
                PlaybackSession.setPropertyString("hwdec", decoder.value)
              }
            },
            modifier = Modifier,
          )
        }
      }
    }
  }

  private fun setupVideoTransformObserver() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        combine(
          viewModel.videoZoom,
          viewModel.videoPanX,
          viewModel.videoPanY,
          viewModel.videoAspect,
        ) { zoom, panX, panY, aspect ->
          TransformState(zoom, panX, panY, aspect)
        }.collect { transform ->
          val scale = 2f.pow(transform.zoom)
          // Both surfaces receive the same transform; only the active engine's surface is visible.
          binding.player.scaleX = scale
          binding.player.scaleY = scale
          binding.player.translationX = transform.panX
          binding.player.translationY = transform.panY
          binding.media3Player.scaleX = scale
          binding.media3Player.scaleY = scale
          binding.media3Player.translationX = transform.panX
          binding.media3Player.translationY = transform.panY
          binding.media3Player.resizeMode =
            when (transform.aspect) {
              VideoAspect.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
              VideoAspect.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
              VideoAspect.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        }
      }
    }
  }

  private data class TransformState(
    val zoom: Float,
    val panX: Float,
    val panY: Float,
    val aspect: VideoAspect,
  )

  private fun media3SourceUri(item: PlaybackItem): Uri {
    val playableUri = Uri.parse(item.playableUri)
    if (!playableUri.scheme.isNullOrBlank()) return playableUri
    if (item.playableUri.startsWith("/")) return Uri.fromFile(File(item.playableUri))
    val originalUri = Uri.parse(item.originalUri)
    if (!originalUri.scheme.isNullOrBlank()) return originalUri
    return playableUri
  }

  private fun redactedUrlForLog(value: String): String {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return "<invalid-url>"
    val parameterNames = uri.queryParameterNames
    if (parameterNames.isEmpty()) return value
    val base = uri.buildUpon().clearQuery().build().toString()
    return "$base?${parameterNames.joinToString("&") { "$it=<redacted>" }}"
  }

  private fun shouldUseFastMedia3Start(item: PlaybackItem): Boolean {
    val sourceUri = media3SourceUri(item)
    val sizeBytes =
      runCatching {
        when (sourceUri.scheme?.lowercase()) {
          "file", null -> sourceUri.path?.let(::File)?.length() ?: -1L
          "content" -> contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length }
            ?: -1L
          else -> -1L
        }
      }.getOrDefault(-1L)
    // Cues scanning becomes disproportionate on multi-gigabyte local Matroska files. Keep the
    // reliable normal path for smaller files and all network streams.
    return sizeBytes >= 4L * 1024L * 1024L * 1024L &&
      sourceUri.scheme?.lowercase() in setOf("file", "content")
  }

  private fun startMedia3PlaybackWhenReady(
    item: PlaybackItem,
    resumePositionMs: Long,
    onStarted: () -> Unit,
  ) {
    val playerView = binding.media3Player
    val sourceUri = media3SourceUri(item)
    AppDebugLog.info(
      TAG,
      "Media3: source resolved original=${redactedUrlForLog(item.originalUri)} " +
        "playable=${redactedUrlForLog(item.playableUri)} " +
        "resolved=${redactedUrlForLog(sourceUri.toString())} " +
        "scheme=${sourceUri.scheme ?: "none"} " +
        "localExists=${sourceUri.path?.let(::File)?.exists() ?: false}",
    )

    fun startPlayback() {
      if (playbackEngine != PlaybackEngine.MEDIA3 || media3ItemId != item.stableId) return
      if (media3PreparedItemId == item.stableId) {
        AppDebugLog.info(TAG, "Media3: duplicate start ignored item=${item.stableId}")
        return
      }
      AppDebugLog.info(
        TAG,
        "Media3: start attempt layout=${playerView.width}x${playerView.height} " +
          "visibility=${playerView.visibility} attachedToWindow=${playerView.isAttachedToWindow}",
      )
      if (!media3Attached) {
        media3PlaybackController.attach(playerView)
        media3Attached = true
      }
      runCatching {
        media3PlaybackController.setRepeatMode(
          when (viewModel.repeatMode.value) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
          },
        )
        media3PlaybackController.setAudioChannels(audioPreferences.audioChannels.get())
        media3PlaybackController.setAudioProcessing(
          volumeNormalization = audioPreferences.volumeNormalization.get(),
          drcEnabled = audioPreferences.drcEnabled.get(),
        )
        media3PlaybackController.setAudioPitchCorrection(audioPreferences.audioPitchCorrection.get())
        media3PlaybackController.play(
          uri = sourceUri,
          title = item.title,
          headers = item.headers,
          startPositionMs = resumePositionMs,
          playWhenReady = true,
          // The no-Cues path is only used for a fresh large-file start. Handoffs with a nonzero
          // position stay on the Cues-enabled path so the position cannot reset during prepare.
          fastStart = resumePositionMs <= 0L && shouldUseFastMedia3Start(item),
        )
      }.onSuccess {
        media3PreparedItemId = item.stableId
        AppDebugLog.info(TAG, "Media3: playback submitted state=${media3PlaybackController.currentState().playbackState}")
        onStarted()
      }.onFailure { error ->
        AppDebugLog.error(TAG, "Media3 could not start playback; falling back to MPV", error)
        switchToMpvEngine()
      }
    }

    // ExoPlayer can prepare before PlayerView receives its final non-zero size. Waiting for up to
    // 60 layout frames added a visible startup delay on large Matroska files; PlayerView attaches
    // its surface when the next layout pass completes, while demuxing/decoding can begin now.
    playerView.post {
      if (playbackEngine == PlaybackEngine.MEDIA3 && media3ItemId == item.stableId) {
        startPlayback()
      }
    }
  }

  private fun setupAudioPlayerViewObserver() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.isAudioOnly.collect { isAudioOnly ->
          if (isAudioOnly) {
            // Queue-driven video -> audio transitions do not recreate the Activity. Reapply the
            // audio window/orientation contract here so the old landscape video state cannot
            // leave the audio controls clipped or non-interactive.
            setOrientation()
            setupWindowFlags()
            viewModel.showControls()
            binding.player.visibility = View.INVISIBLE

            try {
              WindowCompat.setDecorFitsSystemWindows(window, true)
              windowInsetsController.apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                show(WindowInsetsCompat.Type.statusBars())
                show(WindowInsetsCompat.Type.navigationBars())
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
              }
            } catch (e: Exception) {
              Log.e(TAG, "Failed to show system bars for audio playback", e)
            }
          } else {
            val lp = binding.player.layoutParams as ViewGroup.MarginLayoutParams
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
              lp.height != ViewGroup.LayoutParams.MATCH_PARENT ||
              lp.leftMargin != 0 ||
              lp.topMargin != 0
            ) {
              lp.width = ViewGroup.LayoutParams.MATCH_PARENT
              lp.height = ViewGroup.LayoutParams.MATCH_PARENT
              lp.leftMargin = 0
              lp.topMargin = 0
              binding.player.layoutParams = lp
            }
            binding.player.clipToOutline = false
            binding.player.visibility = View.VISIBLE
          }
        }
      }
    }
  }

  /**
   * Initializes the Picture-in-Picture helper.
   */
  private fun setupPipHelper() {
    pipHelper = MPVPipHelper(
      activity = this,
      videoViewProvider = {
        if (playbackEngine == PlaybackEngine.MEDIA3) binding.media3Player else player
      },
      isAudioPlayer = { viewModel.isAudioOnly.value || isCurrentMediaKnownAudio() },
      isVideoLoaded = {
        if (playbackEngine == PlaybackEngine.MEDIA3) {
          media3State.playbackState != Player.STATE_IDLE
        } else {
          isReady
        }
      },
      isMedia3Active = { playbackEngine == PlaybackEngine.MEDIA3 },
      isMedia3Playing = { media3State.isPlaying },
      media3VideoSize = {
        val width = media3State.videoWidth
        val height = media3State.videoHeight
        if (width > 0 && height > 0) width to height else null
      },
      media3PlayWhenReady = { playWhenReady ->
        if (playbackEngine == PlaybackEngine.MEDIA3) {
          media3PlaybackController.setPlayWhenReady(playWhenReady)
        }
      },
      media3SeekBy = { offsetMs ->
        if (playbackEngine == PlaybackEngine.MEDIA3) {
          media3PlaybackController.seekBy(offsetMs)
        }
      },
    )
  }

  private fun setupCastPlayback() {
    castPlaybackController =
      CastPlaybackController(
        activity = this,
        currentMedia = ::currentCastMediaSnapshot,
        pauseLocal = viewModel::pause,
        restoreLocal = { positionMs, play ->
          if (!isFinishing && !isDestroyed) {
            viewModel.seekTo((positionMs / 1000L).toInt().coerceAtLeast(0))
            if (play) viewModel.unpause() else viewModel.pause()
          }
        },
        notifyUser = viewModel::showToast,
      )
    castPlaybackController.start()
  }

  private fun currentCastMediaSnapshot(): CastMediaSnapshot? {
    if (!isReady || fileName.isBlank()) return null
    val source =
      sequenceOf(
        currentPlayableUri,
        runCatching { PlaybackSession.getPropertyString("path") }.getOrNull(),
        intent?.dataString,
      ).filterNotNull()
        .filter { it.isNotBlank() }
        .map { sourceText ->
          val parsed = Uri.parse(sourceText)
          if (parsed.scheme.isNullOrBlank()) Uri.fromFile(File(sourceText)) else parsed
        }.firstOrNull { uri ->
          when (uri.scheme?.lowercase()) {
            "content", "file" -> true
            "http", "https" -> uri.host !in setOf("127.0.0.1", "localhost", "0.0.0.0")
            else -> false
          }
        } ?: return null
    return CastMediaSnapshot(
      source = source,
      title = getPreferredCurrentTitle().ifBlank { fileName },
      mimeType = intent?.type ?: runCatching { contentResolver.getType(source) }.getOrNull(),
      durationMs = ((PlaybackSession.getPropertyDouble("duration") ?: 0.0) * 1000.0).toLong(),
      positionMs = ((PlaybackSession.getPropertyDouble("time-pos") ?: 0.0) * 1000.0).toLong(),
      isPlaying = PlaybackSession.getPropertyBoolean("pause") == false,
    )
  }

  private fun setupAudio() {
    audioPreferences.audioChannels.get().let {
      runCatching {
        if (it == AudioChannels.ReverseStereo) {
          PlaybackSession.setPropertyString(AudioChannels.AutoSafe.property, AudioChannels.AutoSafe.value)
        } else {
          PlaybackSession.setPropertyString(it.property, it.value)
        }
      }.onFailure { e ->
        Log.e(TAG, "Error setting audio channels: ${it.property}=${it.value}", e)
      }
    }

    if (audioFocusRequest == null) {
      audioFocusRequest =
        AudioFocusRequest
          .Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(
            AudioAttributes
              .Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
              .build(),
          ).setOnAudioFocusChangeListener(audioFocusChangeListener)
          .setAcceptsDelayedFocusGain(true)
          .setWillPauseWhenDucked(true)
          .build()
    }
    // Reopening an existing session: the detached service already owns focus and playback is
    // ongoing. Requesting focus here would steal it from the service and make it pause, so the
    // foreground Activity re-acquires focus from onStart() after the service is torn down.
    val reattachingSession = intent.action == MediaPlaybackService.ACTION_OPEN_PLAYER && PlaybackSession.isInitialized
    if (!serviceBound && !reattachingSession) {
      requestAudioFocus()
    }
  }

  /**
   * @return true if audio focus was granted immediately, false otherwise
   */
  override fun requestAudioFocus(): Boolean {
    if (holdsAudioFocus) return true
    if (audioFocusRequestActive) {
      playbackDelayedForAudioFocus = true
      return false
    }
    val req = audioFocusRequest ?: return false
    val result = audioManager.requestAudioFocus(req)
    return when (result) {
      AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
        audioFocusRequestActive = true
        holdsAudioFocus = true
        playbackDelayedForAudioFocus = false
        resumeOnAudioFocusGain = false
        mainHandler.removeCallbacks(audioFocusRetryRunnable)
        audioFocusRetryAttempt = 0
        true
      }

      AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
        audioFocusRequestActive = true
        holdsAudioFocus = false
        playbackDelayedForAudioFocus = true
        false
      }

      else -> {
        audioFocusRequestActive = false
        holdsAudioFocus = false
        playbackDelayedForAudioFocus = false
        resumeOnAudioFocusGain = false
        scheduleAudioFocusRetry()
        false
      }
    }
  }

  private fun scheduleAudioFocusRetry() {
    if (isFinishing || isDestroyed || serviceBound || isBackgroundPlaybackSessionActive == true || viewModel.paused == true) return
    mainHandler.removeCallbacks(audioFocusRetryRunnable)
    audioFocusRetryAttempt = 0
    mainHandler.postDelayed(audioFocusRetryRunnable, AUDIO_FOCUS_RETRY_DELAY_MS)
  }

  override fun currentMediaLookupHint(): String? = currentPlayableUri ?: intent?.dataString

  override fun currentPlayerLookupHints(): PlayerLookupHints =
    PlayerLookupHints(
      canonicalTitle = intent?.getStringExtra("introdb_title"),
      imdbId = intent?.getStringExtra("introdb_imdb_id"),
      tmdbId =
        intent
          ?.getIntExtra("introdb_tmdb_id", -1)
          ?.takeIf { it > 0 },
      mediaType = intent?.getStringExtra("introdb_media_type"),
      season =
        intent
          ?.getIntExtra("introdb_season", -1)
          ?.takeIf { it > 0 },
      episode =
        intent
          ?.getIntExtra("introdb_episode", -1)
          ?.takeIf { it > 0 },
    )

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    // Enter PIP mode when user presses home button if auto PIP is enabled (disabled for audio)
    if (playerPreferences.autoPiPOnNavigation.get() && !viewModel.isAudioOnly.value && !isCurrentMediaKnownAudio() && isReady && !isFinishing) {
      enterPipModeSmoothly()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus) {
      cancelSystemBarsAutoHide()
      return
    }

    if (shouldAutoHideSystemBars()) {
      scheduleSystemBarsAutoHide(delayMs = 250L)
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    val queueState = PlaybackSession.queue.value
    val currentItem = queueState.currentItem
    val index = queueState.currentIndex.takeIf { currentItem != null && it >= 0 } ?: playlistIndex
    val originalUri = currentItem?.originalUri ?: playlist.getOrNull(index)?.toString() ?: currentPlayableUri
    val stableId = currentItem?.stableId ?: mediaIdentifier.takeIf { it.isNotBlank() }
    if (index >= 0 && (!stableId.isNullOrBlank() || !originalUri.isNullOrBlank())) {
      outState.putInt(STATE_PLAYLIST_INDEX, index)
      outState.putString(STATE_PLAYLIST_STABLE_ID, stableId)
      outState.putString(STATE_PLAYLIST_ORIGINAL_URI, originalUri)
    }
    super.onSaveInstanceState(outState)
  }

  override fun onDestroy() {
    if (wasInPipMode && !isInPictureInPictureMode && !isChangingConfigurations && !hardStopRequested) {
      handlePipDismissed()
    }
    cancelPendingPipDismissalStop()
    mainHandler.removeCallbacks(audioFocusRetryRunnable)
    Log.d(TAG, "PlayerActivity onDestroy")
    val playbackWasInitialized = hasPlaybackSessionToPersist()
    val keepBackgroundPlaybackAlive =
      PlayerLifecyclePolicy.shouldKeepBackgroundPlaybackAliveOnDestroy(
        backgroundPlaybackEnabled = playbackWasInitialized && isBackgroundPlaybackEnabled(),
        backgroundPlaybackSessionActive = isBackgroundPlaybackSessionActive,
        audioOnly = viewModel.isAudioOnly.value,
        audioMinimizeRequested = audioMinimizeRequested,
      )


    runCatching {
      mediaLoadJob?.cancel()
      if (::castPlaybackController.isInitialized) castPlaybackController.release()
      cancelSystemBarsAutoHide()
      if (playbackWasInitialized) saveVideoPlaybackState(fileName, immediate = true)
      if (playbackWasInitialized && !keepBackgroundPlaybackAlive) {
        reportJellyfinStop()
      }

      if ((isUserFinishing || isFinishing) && !keepBackgroundPlaybackAlive) {
        if (serviceBound) {
          runCatching { unbindService(serviceConnection) }
          serviceBound = false
        }
        stopService(Intent(this, MediaPlaybackService::class.java))
        mediaPlaybackService = null
      } else if (keepBackgroundPlaybackAlive && serviceBound) {
        // Unbind but keep the service running for background audio
        runCatching { unbindService(serviceConnection) }
        serviceBound = false
        mediaPlaybackService = null
      }

      // Release the Activity's focus before the service requests it for detached playback.
      // Otherwise the Activity's focus listener would receive LOSS and pause playback just as
      // the user minimizes into the Mini Player.
      cleanupAudio()
      if (keepBackgroundPlaybackAlive) {
        media3PlaybackController.detachUiCallbacks()
        detachedMedia3Controller = media3PlaybackController
        MediaPlaybackService.takeAudioOwnershipForDetachedPlayback()
      }
      cleanupReceivers()
      releaseMediaSession()
      if (!keepBackgroundPlaybackAlive && !torrentPickerHandoff) torrentStreamingEngine.stopStream()
    }.onFailure { e ->
      Log.e(TAG, "Error during onDestroy", e)
    }

    media3VideoWatchdogJob?.cancel()
    media3VideoWatchdogJob = null
    media3PlaybackController.detachUiCallbacks()
    if (!keepBackgroundPlaybackAlive) {
      media3PlaybackController.release()
      if (detachedMedia3Controller === media3PlaybackController) detachedMedia3Controller = null
    }
    if (activeInstance?.get() === this) activeInstance = null
    super.onDestroy()
    // The core remains alive throughout Android/ViewModel/window cleanup. Only after super returns

    // do we detach the renderer and enqueue native destruction on the dedicated worker.
    runCatching { cleanupMPV(keepBackgroundPlaybackAlive) }
      .onFailure { e -> Log.e(TAG, "Error during MPV teardown", e) }
    if (viewModelHostAttached) {
      viewModel.detachHost(this)
      viewModelHostAttached = false
    }
  }

  private fun cleanupMPV(keepBackgroundPlaybackAlive: Boolean) {
    if (!mpvInitialized) return

    player.isExiting = true
    mpvInitialized = false
    player.onSurfaceReady = null
    intentSubtitleJob?.cancel()
    videoParamRefreshJob?.cancel()
    backgroundServiceSyncJob?.cancel()
    backgroundHandoffJob?.cancel()
    deferredFontSyncJob?.cancel()
    mediaLoadJob?.cancel()
    eofAdvanceJob?.cancel()
    resumeAfterUnlockJob?.cancel()
    runCatching { PlaybackSession.removeObserver(playerObserver) }
      .onFailure { e -> Log.e(TAG, "Error removing MPV observer", e) }

    runCatching { player.releaseSurface() }
      .onFailure { e -> Log.e(TAG, "Error releasing MPV surface", e) }

    if (!keepBackgroundPlaybackAlive) {
      viewModel.onMpvCoreStopping()
      MediaPlaybackService.prepareForMpvShutdown()
      endBackgroundPlayback()
      PlaybackSession.stop(clearQueue = true)
    } else {
      PlaybackSession.markBackground()
    }
  }

  private fun isDolbyVisionSourceHint(vararg values: String?): Boolean {
    val searchable = values
      .filterNotNull()
      .joinToString(" ")
      .lowercase()
    return searchable.contains("dolby vision") ||
      searchable.contains("dolby-vision") ||
      searchable.contains("dovi") ||
      Regex("\\bdv(?:he|h1)\\.?(?:[0-9]{2})?").containsMatchIn(searchable)
  }

  private fun isDolbyVisionItem(item: PlaybackItem): Boolean =
    isDolbyVisionSourceHint(item.mimeType, item.title, item.originalUri, item.playableUri)

  /**
   * Probes local/content sources before MPV opens them so Auto mode can route Dolby Vision directly
   * to Media3. Network streams are intentionally left to their declared MIME/extension because a
   * blocking extractor probe would delay IPTV startup.
   */
  private suspend fun probeDolbyVisionMimeCached(source: String): String? {
    val cacheKey = source.trim()
    if (cacheKey.isBlank()) return null
    synchronized(dolbyVisionProbeCache) {
      dolbyVisionProbeCache[cacheKey]?.let { isDolbyVision ->
        return if (isDolbyVision) "video/dolby-vision" else null
      }
    }
    val isDolbyVision =
      withTimeoutOrNull(750L) {
        // Keep a slow content provider off the single media-load dispatcher. The timeout prevents
        // an extractor stall from delaying the actual player startup indefinitely.
        withContext(Dispatchers.IO) {
          probeDolbyVisionMime(cacheKey) == "video/dolby-vision"
        }
      } ?: false
    synchronized(dolbyVisionProbeCache) {
      dolbyVisionProbeCache[cacheKey] = isDolbyVision
    }
    return if (isDolbyVision) "video/dolby-vision" else null
  }

  private fun probeDolbyVisionMime(source: String): String? {
    val uri = Uri.parse(source)
    val scheme = uri.scheme?.lowercase()
    if (scheme != null && scheme !in setOf("file", "content")) return null
    val extractor = MediaExtractor()
    return try {
      if (scheme == "content") {
        extractor.setDataSource(this, uri, emptyMap())
      } else {
        val path = if (scheme == "file") uri.path else source
        if (path.isNullOrBlank()) return null
        extractor.setDataSource(path)
      }
      (0 until extractor.trackCount)
        .asSequence()
        .map { index -> extractor.getTrackFormat(index) }
        .filter { format -> format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        .mapNotNull { format ->
          val mime = format.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
          val codec =
            listOf("codecs", "codec", "codec-string")
              .firstNotNullOfOrNull { key -> format.getString(key) }
              .orEmpty()
              .lowercase()
          if (mime == "video/dolby-vision" || codec.startsWith("dvhe") || codec.startsWith("dvh1")) {
            "video/dolby-vision"
          } else {
            null
          }
        }
        .firstOrNull()
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (error: Exception) {
      AppDebugLog.info(TAG, "Media3: Dolby Vision preflight unavailable source=${redactedUrlForLog(source)} error=${error.message}")
      null
    } finally {
      runCatching { extractor.release() }
    }
  }

  private fun isDolbyVisionTrack(track: TrackNode): Boolean {
    val searchable = listOf(track.codec, track.codecDesc, track.codecProfile, track.formatName)
      .filterNotNull()
      .joinToString(" ")
      .lowercase()
    val metadata = track.metadata?.values?.joinToString(" ").orEmpty().lowercase()
    return track.dolbyVisionProfile != null ||
      searchable.contains("dolby vision") ||
      searchable.contains("dolby-vision") ||
      searchable.contains("dovi") ||
      Regex("\\bdv(?:he|h1)\\.?(?:[0-9]{2})?").containsMatchIn("$searchable $metadata")
  }

  private fun currentPlaybackItem(): PlaybackItem? =
    PlaybackSession.state.value.currentItem ?: PlaybackSession.queue.value.currentItem ?: activePlaybackItem

  private fun isMedia3Stream(item: PlaybackItem): Boolean {
    val mime = item.mimeType.orEmpty().lowercase()
    val extension = item.playableUri.substringBefore('?').substringAfterLast('.', "").lowercase()
    return mime.contains("mpegurl") || mime.contains("dash+xml") || extension in setOf("m3u8", "mpd")
  }

  private fun shouldUseMedia3(item: PlaybackItem): Boolean {
    // Audio-only playback is an MPV contract. This guard must run before the global engine
    // preference and before Auto/Dolby detection, otherwise a stale video/Media3 state can take
    // ownership of a song and leave MPV controls pointed at an inactive generation.
    if (isAudioPlaybackItem(item)) {
      AppDebugLog.info(TAG, "Forcing MPV for audio item=${item.stableId} title=${item.title.orEmpty()}")
      return false
    }
    if (item.originalUri.startsWith("magnet:", ignoreCase = true)) return false
    // A failed Native handoff owns the current item until the user explicitly selects Native
    // again. This prevents queue/state emissions during the handoff from immediately starting a
    // second Native controller while MPV is already resuming the same file.
    if (media3AutoFallbackItemId == item.stableId) {
      AppDebugLog.info(
        TAG,
        "Suppressing Native retry after fallback item=${item.stableId} " +
          "configuredMode=${decoderPreferences.playbackEngine.get().name}",
      )
      return false
    }
    if (manualEngineOverrideItemId == item.stableId) {
      return manualEngineOverride == PlaybackEngine.MEDIA3
    }
    return when (decoderPreferences.playbackEngine.get()) {
      PlaybackEngineMode.MPV -> false
      PlaybackEngineMode.Media3 -> true
      // MPV remains the default for ordinary video; Media3 is retained for Dolby Vision and
      // adaptive IPTV streams, which require the Media3 HLS/DASH modules added earlier.
      PlaybackEngineMode.Auto ->
        (isDolbyVisionItem(item) && media3AutoFallbackItemId != item.stableId) || isMedia3Stream(item)
    }
  }

  private fun switchToMedia3Engine(item: PlaybackItem, force: Boolean = false) {
    // Keep this defensive guard even though shouldUseMedia3() rejects audio. Manual engine
    // selection and delayed observers can call this method directly after a queue transition.
    if (isAudioPlaybackItem(item)) {
      AppDebugLog.warn(TAG, "Rejected Media3 handoff for audio item=${item.stableId}; restoring MPV")
      switchToMpvEngine(itemOverride = item, force = true)
      return
    }
    activePlaybackItem = item
    media3ActiveItem = item
    if (decoderPreferences.playbackEngine.get() != PlaybackEngineMode.Auto) {
      media3AutoFallbackItemId = null
    }
    if (!force && playbackEngine == PlaybackEngine.MEDIA3 && media3ItemId == item.stableId) {
      val currentState = media3PlaybackController.currentState()
      if (currentState.playbackState != Player.STATE_IDLE && currentState.mediaItemIndex >= 0) return
      AppDebugLog.info(TAG, "Media3 session is idle for current item; rebuilding item=${item.stableId}")
    }
    AppDebugLog.info(
      TAG,
      "Playback engine selected engine=MEDIA3 uri=${redactedUrlForLog(item.playableUri)} " +
        "originalUri=${redactedUrlForLog(item.originalUri)} " +
        "title=${item.title.orEmpty().ifBlank { "<untitled>" }} " +
        "configuredMode=${decoderPreferences.playbackEngine.get().name}",
    )
    val startsAtZero =
      pendingQueueTransitionStartAtZero &&
        (pendingQueueTransitionItemId == null || pendingQueueTransitionItemId == item.stableId)
    val resumePositionMs =
      if (startsAtZero) {
        0L
      } else {
        when (playbackEngine) {
          PlaybackEngine.MPV ->
            ((PlaybackSession.getPropertyDouble("time-pos") ?: 0.0) * 1000.0).toLong()
          PlaybackEngine.MEDIA3 -> media3PlaybackController.positionForEngineHandoffMs()
        }
      }
    // Persist the outgoing MPV position before stopping libmpv. The handoff can otherwise expose
    // a transient zero/unknown snapshot to onPause or the next engine callback.
    if (
      playbackEngine == PlaybackEngine.MPV &&
        isReady &&
        currentPlaybackItem()?.stableId == item.stableId
    ) {
      saveVideoPlaybackState(
        mediaTitle = item.title?.takeIf { it.isNotBlank() } ?: fileName,
        immediate = true,
        identifierOverride = item.stableId,
      )
    }

    // Keep the queue-transition guard active until loadVideoPlaybackState() finishes. Clearing it
    // here lets a later asynchronous state callback reapply the previous item's saved position.
    if (playbackEngine == PlaybackEngine.MPV) {
      // Stop libmpv rather than only pausing it. This releases its demuxer/decoder/audio queues so
      // Media3 is the only active engine and the device does not spend battery on a hidden player.
      runCatching { PlaybackSession.stop(clearQueue = false) }
      mpvStoppedForMedia3 = true
      AppDebugLog.info(TAG, "MPV stopped for exclusive Media3 playback item=${item.stableId}")
    }
    playbackEngine = PlaybackEngine.MEDIA3
    // Media3 owns chapter visibility for this session. The controller will replace this empty
    // state only if the extractor emits supported Chapter metadata.
    viewModel.setMedia3Chapters(emptyList())
    // Dolby Vision items routed here are known widescreen assets in the current Auto/Media3 path.
    // Request landscape before asynchronous Media3 VideoSize arrives so the activity does not
    // remain portrait during decoder initialization. The normal setOrientation() callback still
    // applies the exact aspect once Media3 reports dimensions.
    if (
      isDolbyVisionItem(item) &&
        playerPreferences.orientation.get() == PlayerOrientation.Video &&
        !(manualOrientationOverride != null &&
          (manualOrientationOverrideItemId == null || manualOrientationOverrideItemId == item.stableId))
    ) {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      AppDebugLog.info(TAG, "Media3: provisional landscape requested for Dolby Vision item=${item.stableId}")
    }
    media3State = Media3PlaybackController.State()
    cachedMedia3State = Media3PlaybackController.State()
    media3PreparedItemId = null
    media3ItemId = item.stableId
    media3VideoFrameRendered = false
    media3VideoWatchdogJob?.cancel()
    // MPVView is backed by a native SurfaceView. It must be GONE, not merely INVISIBLE, while
    // Media3 owns its separate video surface; otherwise the old MPV SurfaceView can keep a black
    // surface above Media3 on OEM compositors. MPV's surface callbacks unbind it and rebind it when
    // visibility is restored.
    binding.player.visibility = View.GONE
    binding.media3Player.visibility = View.VISIBLE
    AppDebugLog.info(
      TAG,
      "Media3: surfaces switched media3=${binding.media3Player.width}x${binding.media3Player.height} " +
        "mpvVisibility=${binding.player.visibility} media3Visibility=${binding.media3Player.visibility}",
    )
    // MPV has already been stopped above; keep the native core initialized but idle for fast
    // switching back. Only the active Media3 controller owns this item now.
    fun startMedia3(positionMs: Long) {
      startMedia3PlaybackWhenReady(item, positionMs) {
        // Dolby Vision routing must not leave the user with audio and a permanently blank video
        // surface. Some profiles are accepted by Media3’s audio pipeline but never produce a
        // rendered video frame on a particular device decoder. Give the renderer time to initialize,
        // then return to MPV, which is known to render this device’s file correctly.
        if (isDolbyVisionItem(item)) {
          media3VideoWatchdogJob = lifecycleScope.launch {
            delay(10_000L)
            if (
              playbackEngine == PlaybackEngine.MEDIA3 &&
                media3ItemId == item.stableId &&
                !media3VideoFrameRendered
            ) {
              media3AutoFallbackItemId = item.stableId
              AppDebugLog.warn(
                TAG,
                "Media3 produced no video frame after watchdog; falling back to MPV " +
                  "and suppressing Media3 retry for item=${item.stableId}",
              )
              switchToMpvEngine()
            }
          }
        }
      }
    }

    if (startsAtZero || resumePositionMs > 0L || !playerPreferences.savePositionOnQuit.get()) {
      startMedia3(resumePositionMs)
    } else {
      lifecycleScope.launch(Dispatchers.IO) {
        val savedPositionMs = persistedPlaybackPositionMs(item)
        withContext(Dispatchers.Main.immediate) {
          if (playbackEngine == PlaybackEngine.MEDIA3 && media3ItemId == item.stableId) {
            startMedia3(savedPositionMs)
          }
        }
      }
    }
  }

  private suspend fun persistedPlaybackPositionMs(item: PlaybackItem): Long {
    val identifiers =
      linkedSetOf(
        item.stableId,
        PlaybackIdentity.forUri(item.originalUri),
        PlaybackIdentity.forUri(item.playableUri),
      ).filter { it.isNotBlank() }

    for (identifier in identifiers) {
      val lastPositionSeconds = playbackStateRepository.getVideoDataByTitle(identifier)?.lastPosition?.toLong() ?: 0L
      if (lastPositionSeconds > 0L) return lastPositionSeconds * 1000L
    }
    return 0L
  }

  private fun selectEngineFromDecoderSheet(selectedEngine: PlaybackEngineMode) {
    if (selectedEngine == PlaybackEngineMode.Auto) return
    // The decoder sheet is rendered above the active player surface. During Media3 ownership MPV's
    // queue can briefly be empty, so prefer the item that owns the visible Media3 session and then
    // fall back to the normal PlaybackSession lookup.
    AppDebugLog.info(
      TAG,
      "Manual engine selection requested engine=$selectedEngine playbackEngine=$playbackEngine " +
        "media3ItemId=$media3ItemId activeItem=${activePlaybackItem?.stableId} " +
        "media3ActiveItem=${media3ActiveItem?.stableId}",
    )
    val resolvedItem =
      media3ActiveItem?.takeIf { media3ItemId == it.stableId }
        ?: activePlaybackItem?.takeIf { media3ItemId == null || it.stableId == media3ItemId }
        ?: currentPlaybackItem()
    if (resolvedItem == null) {
      AppDebugLog.warn(
        TAG,
        "Manual engine selection ignored: no active item selectedEngine=$selectedEngine " +
          "media3ItemId=$media3ItemId",
      )
      return
    }
    manualEngineOverrideItemId = resolvedItem.stableId
    media3AutoFallbackItemId = null
    val targetEngine =
      when (selectedEngine) {
        PlaybackEngineMode.MPV -> PlaybackEngine.MPV
        PlaybackEngineMode.Media3 -> PlaybackEngine.MEDIA3
        PlaybackEngineMode.Auto -> return
      }
    decoderPreferences.playbackEngine.set(selectedEngine)
    manualEngineOverride = targetEngine
    AppDebugLog.info(
      TAG,
      "Manual engine override scheduled item=${resolvedItem.stableId} engine=$targetEngine",
    )
    // The decoder sheet invokes this callback immediately before its dismiss callback. Defer the
    // heavy handoff one main-loop turn so the sheet can finish closing and the Compose engine state
    // can recompose instead of competing with a synchronous MPV stop/load operation.
    lifecycleScope.launch(Dispatchers.Main.immediate) {
      yield()
      if (manualEngineOverrideItemId != resolvedItem.stableId) return@launch
      when (targetEngine) {
        PlaybackEngine.MPV -> switchToMpvEngine(resolvedItem, force = true)
        PlaybackEngine.MEDIA3 -> switchToMedia3Engine(resolvedItem, force = true)
      }
    }
  }

  private fun switchToMedia3ForCurrentItem() {
    lifecycleScope.launch(Dispatchers.Main.immediate) {
      repeat(40) { attempt ->
        val item = currentPlaybackItem()
        if (item != null) {
          AppDebugLog.info(TAG, "Manual Media3 selection resolved item=${item.stableId} attempt=$attempt")
          switchToMedia3Engine(item)
          return@launch
        }
        delay(50L)
      }
      AppDebugLog.warn(TAG, "Manual Media3 selection timed out: no active PlaybackSession item")
    }
  }

  private fun switchToMpvEngine(itemOverride: PlaybackItem? = null, force: Boolean = false) {
    media3VideoWatchdogJob?.cancel()
    media3VideoWatchdogJob = null
    media3VideoFrameRendered = false
    val currentItem = itemOverride ?: currentPlaybackItem()
    if (!force && playbackEngine == PlaybackEngine.MPV && !mpvStoppedForMedia3) {
      binding.media3Player.visibility = View.GONE
      binding.player.visibility = View.VISIBLE
      return
    }
    // Persist the outgoing Media3 position before clearing its controller state. This is needed
    // for an engine switch followed immediately by app close, where the lifecycle callback may
    // observe the newly created MPV session rather than the Media3 session that was playing.
    if (
      playbackEngine == PlaybackEngine.MEDIA3 &&
        currentItem != null &&
        media3ItemId == currentItem.stableId &&
        media3PreparedItemId == currentItem.stableId
    ) {
      saveVideoPlaybackState(
        mediaTitle = currentItem.title?.takeIf { it.isNotBlank() } ?: fileName,
        immediate = true,
        identifierOverride = currentItem.stableId,
      )
    }

    AppDebugLog.info(
      TAG,
      "Playback engine selected engine=MPV " +
        "uri=${redactedUrlForLog(currentItem?.playableUri.orEmpty())} " +
        "originalUri=${redactedUrlForLog(currentItem?.originalUri.orEmpty())} " +
        "title=${currentItem?.title.orEmpty().ifBlank { "<untitled>" }} " +
        "configuredMode=${decoderPreferences.playbackEngine.get().name}",
    )
    // Prefer the controller's requested target because the Compose state callback may lag behind
    // a seek, especially when an unsupported audio switch causes Media3 to fail immediately after it.
    val startsAtZero =
      pendingQueueTransitionStartAtZero &&
        (pendingQueueTransitionItemId == null || pendingQueueTransitionItemId == currentItem?.stableId)
    val resumePositionMs =
      if (startsAtZero) {
        0L
      } else if (playbackEngine == PlaybackEngine.MEDIA3) {
        media3PlaybackController.positionForEngineHandoffMs()
      } else {
        ((PlaybackSession.getPropertyDouble("time-pos") ?: 0.0) * 1000.0).toLong()
      }
    // Keep the queue-transition guard active until loadVideoPlaybackState() finishes. Clearing it
    // here lets a later asynchronous state callback reapply the previous item's saved position.
    playbackEngine = PlaybackEngine.MPV
    viewModel.setMedia3Chapters(null)
    media3State = Media3PlaybackController.State()
    cachedMedia3State = Media3PlaybackController.State()
    media3PreparedItemId = null
    media3ItemId = null
    media3ActiveItem = null
    media3PlaybackController.stop()
    binding.media3Player.visibility = View.GONE
    binding.player.visibility = View.VISIBLE
    if (currentItem != null) {
      // Media3 playback stops MPV for exclusive ownership. A manual switch back must explicitly
      // reload the same item into MPV; otherwise the visible MPV surface is shown while libmpv
      // remains stopped and no video can appear.
      mpvStoppedForMedia3 = false
      runCatching { PlaybackSession.load(currentItem) }
      lifecycleScope.launch {
        // libmpv may be reloaded in a paused state after Media3 owned the item. Resume it on the
        // same delayed boundary used for the handoff seek so the visible MPV surface is live.
        delay(300L)
        if (playbackEngine == PlaybackEngine.MPV) {
          runCatching { PlaybackSession.setPropertyBoolean("pause", false) }
          if (resumePositionMs > 0L) {
            runCatching {
              PlaybackSession.command(
                "seek",
                (resumePositionMs / 1000.0).toString(),
                "absolute+exact",
              )
            }
          }
          // A seek started during the handoff can nest a second mute guard inside the replacement
          // guard. Complete both in one place so MPV does not remain silent after Media3 stops.
          PlaybackSession.restorePlaybackAudioAfterTransition()
          AppDebugLog.info(
            TAG,
            "MPV handoff resumed item=${currentItem.stableId} positionMs=$resumePositionMs audioRestored=true",
          )
        }
      }
    } else {
      runCatching {
        PlaybackSession.setPropertyString("vid", "auto")
        PlaybackSession.setPropertyBoolean("pause", false)
      }
    }
  }

  private fun syncPlaybackEngine(item: PlaybackItem) {
    if (shouldUseMedia3(item)) {
      switchToMedia3Engine(item)
    } else {
      switchToMpvEngine()
    }
  }

  private fun observeAutomaticDolbyVisionEngine() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.videoTracks.collect { tracks ->
          if (decoderPreferences.playbackEngine.get() != PlaybackEngineMode.Auto) return@collect
          val currentItem = PlaybackSession.queue.value.currentItem ?: activePlaybackItem ?: return@collect
          // Audio has no video-engine route. Stale video-track emissions from the previous item
          // must never trigger an automatic Media3 handoff for the current song.
          if (isAudioPlaybackItem(currentItem)) return@collect
          // The MPV track observer emits Dolby Vision again after a Native watchdog fallback.
          // Do not let that emission restart Native for the same item; the fallback guard in
          // shouldUseMedia3() does not cover this direct observer path.
          if (media3AutoFallbackItemId == currentItem.stableId) {
            AppDebugLog.info(
              TAG,
              "Suppressing automatic Native retry from MPV Dolby Vision track observer " +
                "item=${currentItem.stableId}",
            )
            return@collect
          }
          if (playbackEngine == PlaybackEngine.MPV && tracks.any(::isDolbyVisionTrack)) {
            AppDebugLog.info(TAG, "Auto engine detected Dolby Vision track; switching to Media3 item=${currentItem.stableId}")
            switchToMedia3Engine(currentItem)
          }
        }
      }
    }
  }

  private fun observePlaybackSessionQueue() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        PlaybackSession.queue
          .map { queueState -> queueState.currentIndex to queueState.currentItem }
          .distinctUntilChanged()
          .collect { (index, item) ->
            if (item == null || index < 0) return@collect
            val previousItemId = activePlaybackItem?.stableId
            if (previousItemId != null && previousItemId != item.stableId) {
              // Queue-driven transitions are new selections, not engine handoffs for the same item.
              // Keep the zero-position guard active until the item’s saved-state load completes.
              pendingQueueTransitionStartAtZero = true
              pendingQueueTransitionItemId = item.stableId
            }
            activePlaybackItem = item
            // Publish the new identity before engine synchronization. Media3 startup can invoke
            // lifecycle/save callbacks immediately, so leaving the old mediaIdentifier here can
            // store the Native position under the previous queue item.
            legacyMediaIdentifier = null
            mediaIdentifier = item.stableId
            if (previousItemId != null && previousItemId != item.stableId &&
              manualOrientationOverrideItemId != item.stableId
            ) {
              manualOrientationOverride = null
              manualOrientationOverrideItemId = null
            }
            // A decoder-page selection is authoritative for this item. The queue emits again
            // during the handoff, so automatic synchronization must not immediately switch back.
            if (manualEngineOverrideItemId != item.stableId) {
              syncPlaybackEngine(item)
            }
            val queueItems = PlaybackSession.queue.value.items
            playlist = queueItems.map { queued -> Uri.parse(queued.originalUri) }
            playlistIndex = index
            playlistWindowOffset = 0
            playlistTotalCount = playlist.size
            networkPlaylistPaths = queueItems.map { queued -> queued.networkSource?.relativePath.orEmpty() }
            networkPlaylistTitles = queueItems.map { queued -> queued.title.orEmpty() }
            networkPlaylistHeaders = queueItems.map(PlaybackItem::headers)
            networkPlaylistConnectionId = item.networkSource?.connectionId ?: -1L
            fileName = item.title?.takeIf { it.isNotBlank() } ?: getFileNameFromUri(Uri.parse(item.originalUri))
            currentPlayableUri = item.playableUri
            isReady = false
            val isAudioItem = isAudioPlaybackItem(item)
            // Keep Activity and service metadata aligned with the item that is actually active.
            // The original launch intent may describe the first video in a mixed queue.
            intent.putExtra("is_audio", isAudioItem)
            intent.putExtra("media_library_audio", isAudioItem)
            intent.setDataAndType(Uri.parse(item.originalUri), item.mimeType)
            viewModel.setAudioOnlyLaunchHint(isAudioItem)
            if (isAudioItem) {
              viewModel.onAudioLoadStarted()
              setOrientation()
              setupWindowFlags()
            } else {
              viewModel.onVideoLoadStarted()
            }
            if (serviceBound || mediaPlaybackService != null) {
              syncBackgroundPlaybackService(updateThumbnail = true)
            }
            viewModel.calculateVideoHash(Uri.parse(item.originalUri))
            viewModel.refreshPlaylistItems()
          }
      }
    }
  }

  override fun abandonAudioFocus() {
    resumeOnAudioFocusGain = false
    playbackDelayedForAudioFocus = false
    restoreDuckedAudioVolume()
    if (audioFocusRequestActive) {
      audioFocusRequest?.let { req -> runCatching { audioManager.abandonAudioFocusRequest(req) } }
    }
    audioFocusRequestActive = false
    holdsAudioFocus = false
  }

  private fun restoreDuckedAudioVolume() {
    volumeBeforeAudioFocusDuck?.let { volume -> PlaybackSession.setPropertyDouble("volume", volume) }
    volumeBeforeAudioFocusDuck = null
  }

  private fun cleanupAudio() {
    abandonAudioFocus()
  }

  private fun cleanupReceivers() {
    if (noisyReceiverRegistered) {
      runCatching {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      }
    }

    if (screenStateReceiverRegistered) {
      runCatching {
        unregisterReceiver(screenStateReceiver)
        screenStateReceiverRegistered = false
      }
    }
  }

  private fun registerScreenStateReceiver() {
    if (screenStateReceiverRegistered) return

    runCatching {
      val filter =
        IntentFilter().apply {
          addAction(Intent.ACTION_SCREEN_OFF)
          addAction(Intent.ACTION_SCREEN_ON)
          addAction(Intent.ACTION_USER_PRESENT)
        }
      ContextCompat.registerReceiver(this, screenStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
      screenStateReceiverRegistered = true
    }.onFailure { e ->
      Log.e(TAG, "Error registering screen state receiver", e)
    }
  }

  override fun onPause() {
    if (!hasPlaybackSessionToPersist()) {
      super.onPause()
      return
    }

    runCatching {
      // Permission/system dialogs and orientation changes pause the Activity without actually
      // backgrounding it. Playback ownership changes in onStop, where that distinction is known.
      if (isUserFinishing && !isInPictureInPictureMode && !isBackgroundPlaybackSessionActive) {
        restoreSystemUI()
      }

      saveVideoPlaybackState(fileName, immediate = true)
    }.onFailure { e ->
      Log.e(TAG, "Error during onPause", e)
    }

    super.onPause()
  }

  private fun requestExplicitHardStop() {
    if (hardStopRequested) return
    // The explicit player/PiP X action stops Media3 before onDestroy can reliably observe it.
    // Capture the live controller position first so this close path cannot discard the timestamp.
    if (hasPlaybackSessionToPersist()) {
      saveVideoPlaybackState(fileName, immediate = true)
    }
    hardStopRequested = true
    isUserFinishing = true
    isBackgroundPlaybackSessionActive = false
    isInBackgroundPlayback = false
    pendingBackgroundTransition = false
    pendingBackNavigationBackgroundTransition = false
    runCatching {
      media3PlaybackController.detachUiCallbacks()
      media3PlaybackController.stop()
      media3PlaybackController.release()
      if (detachedMedia3Controller === media3PlaybackController) detachedMedia3Controller = null
    }.onFailure { e -> Log.e(TAG, "Error during explicit Media3 hard stop", e) }
    // Explicit X/PiP dismissal must also stop the process-wide native session. The normal finish
    // handoff intentionally preserves that session for background playback, which is not desired
    // for an explicit close.
    runCatching { PlaybackSession.stop(clearQueue = true) }
      .onFailure { e -> Log.e(TAG, "Error during explicit native hard stop", e) }
    // PiP dismissal must also terminate the service that can keep a detached Media3/native
    // session alive after the Activity task has been removed.
    MediaPlaybackService.nativeBackgroundRequested = false
    runCatching { stopService(Intent(this, MediaPlaybackService::class.java)) }
      .onFailure { e -> Log.e(TAG, "Error stopping playback service after explicit close", e) }
    if (!isFinishing) finishAndRemoveTask()
  }

  override fun finish() {
    if (!mpvInitialized) {
      super.finish()
      return
    }
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false

      // Clean up service when finishing
      if (!isBackgroundPlaybackSessionActive) {
        endBackgroundPlayback()
      }

      if (!isBackgroundPlaybackSessionActive) {
        reportJellyfinStop()
      }
      setReturnIntent()
    }.onFailure { e ->
      Log.e(TAG, "Error during finish", e)
    }

    super.finish()

    // Minimizing into the Mini Player: slide the full player down toward the bottom
    // bar. The browser tab stays in place; the Mini Player slides up to meet it.
    if (isMiniPlayerEnabled()) {
      overridePendingTransition(0, R.anim.slide_out_down)
    }
  }

  override fun finishAndRemoveTask() {
    if (!mpvInitialized) {
      super.finishAndRemoveTask()
      return
    }
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      isUserFinishing = true

      // Clean up service when finishing
      if (!isBackgroundPlaybackSessionActive) {
        endBackgroundPlayback()
      }

      reportJellyfinStop()
      setReturnIntent()
    }.onFailure { e ->
      Log.e(TAG, "Error during finishAndRemoveTask", e)
    }

    super.finishAndRemoveTask()
  }

  override fun onStop() {
    MediaPlaybackService.activityForeground = false
    runCatching {
      // onStop is the last reliable foreground callback before background ownership or task
      // removal can stop Media3. Save here as a second shutdown boundary, before any handoff.
      if (hasPlaybackSessionToPersist()) {
        saveVideoPlaybackState(fileName, immediate = true)
      }
      pipHelper.onStop()
      if (!mpvInitialized) return@runCatching

      if (noisyReceiverRegistered) {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      }

      // Unregister the screen-state receiver while stopped so the Activity
      // is not woken by ACTION_SCREEN_OFF / ACTION_USER_PRESENT while in
      // the background. It is re-registered in onStart(). See issue 2.3.
      if (screenStateReceiverRegistered) {
        unregisterReceiver(screenStateReceiver)
        screenStateReceiverRegistered = false
      }

      if (
        PlayerLifecyclePolicy.shouldTreatStopAsPipDismissal(
          wasInPictureInPictureMode = wasInPipMode,
          isInPictureInPictureMode = isInPictureInPictureMode,
          isChangingConfigurations = isChangingConfigurations,
          backgroundPlaybackEnabled = isBackgroundPlaybackEnabled(),
          isScreenOffOrLocked = isDeviceScreenOffOrLocked(),
          alreadyHandled = handledPipDismissal,
        )
      ) {
        schedulePipDismissalStop()
        return@runCatching
      }

      val shouldKeepNativePlayingInBackground =
        playbackEngine == PlaybackEngine.MEDIA3 &&
          PlayerLifecyclePolicy.shouldStartBackgroundPlaybackOnStop(
            backgroundPlaybackEnabled = isBackgroundPlaybackEnabled(),
            backgroundPlaybackSessionActive = isBackgroundPlaybackSessionActive,
            isUserFinishing = isUserFinishing,
            isFinishing = isFinishing,
            isInPictureInPictureMode = isInPictureInPictureMode,
            isScreenOffOrLocked = isDeviceScreenOffOrLocked(),
            audioOnly = viewModel.isAudioOnly.value,
          )
      if (shouldKeepNativePlayingInBackground) {
        // Media3 owns playback in Native mode; the MPV-backed service handoff cannot recreate or
        // control this ExoPlayer instance. Keep the existing Media3 player alive and retain its
        // play/pause state instead of calling viewModel.pause(), which only pauses MPV.
        isBackgroundPlaybackSessionActive = true
        MediaPlaybackService.nativeBackgroundRequested = true
        val nativeBackgroundIntent =
          Intent(this, MediaPlaybackService::class.java).apply {
            putExtra(MediaPlaybackService.EXTRA_NATIVE_BACKGROUND_PLAYBACK, true)
            putExtra("media_title", fileName)
          }
        runCatching { startForegroundService(nativeBackgroundIntent) }
          .onFailure { error ->
            MediaPlaybackService.nativeBackgroundRequested = false
            Log.e(TAG, "Unable to start Native background keep-alive service", error)
          }
        AppDebugLog.info(
          TAG,
          "Keeping Native Media3 playback alive while Activity is stopped " +
            "positionMs=${media3State.positionMs} isPlaying=${media3State.isPlaying}",
        )
        return@runCatching
      }

      if (
        PlayerLifecyclePolicy.shouldStartBackgroundPlaybackOnStop(
          backgroundPlaybackEnabled = isBackgroundPlaybackEnabled(),
          backgroundPlaybackSessionActive = isBackgroundPlaybackSessionActive,
          isUserFinishing = isUserFinishing,
          isFinishing = isFinishing,
          isInPictureInPictureMode = isInPictureInPictureMode,
          isScreenOffOrLocked = isDeviceScreenOffOrLocked(),
          audioOnly = viewModel.isAudioOnly.value,
        )
      ) {
        if (
          startBackgroundPlayback(
            allowUserPrompt = false,
            bindToActivity = !viewModel.isAudioOnly.value,
          ) == BackgroundPlaybackStartResult.Started
        ) {
          isBackgroundPlaybackSessionActive = true
          disableVideoForBackground()
        } else {
          rememberResumeAfterUnlockBeforeForcedPause()
          viewModel.pause()
        }
        return@runCatching
      }

      if (isDeviceScreenOffOrLocked() && !isBackgroundPlaybackEnabled()) {
        rememberResumeAfterUnlockBeforeForcedPause()
        viewModel.pause()
      } else if (
        !isBackgroundPlaybackSessionActive &&
        !isBackgroundPlaybackEnabled() &&
        !isInPictureInPictureMode &&
        !isChangingConfigurations
      ) {
        // onStop is also delivered when the user presses Home. The old code only paused on
        // finish/screen-off, so a normal video continued audibly behind the launcher even though
        // video background playback was disabled.
        AppDebugLog.info(TAG, "Pausing playback because the player entered background")
        viewModel.pause()
      } else if (isBackgroundPlaybackSessionActive && !isInBackgroundPlayback) {
        disableVideoForBackground()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onStop", e)
    }

    super.onStop()
  }

  private fun schedulePipDismissalStop() {
    mainHandler.removeCallbacks(pipDismissalStopRunnable)
    mainHandler.postDelayed(pipDismissalStopRunnable, 750L)
  }

  private fun cancelPendingPipDismissalStop() {
    mainHandler.removeCallbacks(pipDismissalStopRunnable)
  }

  private fun handlePipDismissed() {
    Log.d(TAG, "PiP dismissed; closing playback instead of continuing in background")
    handledPipDismissal = true
    requestExplicitHardStop()
  }

  fun getCurrentPlayableUriForLookup(): String? = currentPlayableUri ?: intent?.dataString

  override fun onStart() {
    cancelPendingPipDismissalStop()
    super.onStart()
    if (!mpvInitialized) return
    MediaPlaybackService.activityForeground = true

    runCatching {
      setupWindowFlags()
      setupSystemUI()
      val deviceScreenOffOrLocked = isDeviceScreenOffOrLocked()

      if (!deviceScreenOffOrLocked) {
        // Foreground playback owns the session again after unlock or app return.
        enableVideoAfterBackground()
        if (MediaPlaybackService.isNativeBackgroundPlaybackActive()) endBackgroundPlayback()
        else if (MediaPlaybackService.isRunning()) endBackgroundPlayback()
        isBackgroundPlaybackSessionActive = false
        // The detached service released focus during the handoff; take it back over so a
        // future focus loss (e.g. a phone call) pauses the now-foreground playback.
        if (viewModel.paused != true) requestAudioFocus()
      }

      if (!noisyReceiverRegistered) {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        ContextCompat.registerReceiver(this, noisyReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyReceiverRegistered = true
      }

      // Re-register the screen-state receiver when returning to the
      // foreground. It is unregistered in onStop(). See issue 2.3.
      registerScreenStateReceiver()

      if (playerPreferences.rememberBrightness.get()) {
        val brightness = playerPreferences.defaultBrightness.get()
        if (brightness != BRIGHTNESS_NOT_SET) {
          viewModel.changeBrightnessTo(brightness)
        }
      } else {
        // Adhere to the system brightness (including auto-brightness). Do not force the
        // manual SCREEN_BRIGHTNESS value onto the window, which dims the screen when
        // auto-brightness is active.
        viewModel.resetBrightnessToSystem()
      }

      if (!isInPictureInPictureMode) {
        wasInPipMode = false
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onStart", e)
    }
  }

  private fun setupWindowFlags() {
    pipHelper.updatePictureInPictureParams()
    val isAudio = viewModel.isAudioOnly.value || isKnownAudioLaunch(intent) || isCurrentMediaKnownAudio()
    if (isAudio) {
      WindowCompat.setDecorFitsSystemWindows(window, true)
      window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
      return
    }
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setFlags(
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    )
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
  }

  private fun setLayoutInDisplayCutoutModeIfSupported(shortEdges: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    val mode =
      if (shortEdges) {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      } else {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
      }
    val attributes = window.attributes
    attributes.layoutInDisplayCutoutMode = mode
    window.attributes = attributes
  }

  private fun setupSystemBarsAutoHide() {
    ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
      handleSystemBarsVisibility(insets)
      binding.player.applyOsdSafeAreaMargins(insets)
      insets
    }
    lifecycleScope.launch {
      playerPreferences.safeAreaWindow.changes().drop(1).collect {
        binding.player.applyOsdSafeAreaMargins(ViewCompat.getRootWindowInsets(binding.root))
      }
    }
    binding.root.post { ViewCompat.requestApplyInsets(binding.root) }
  }

  private fun handleSystemBarsVisibility(insets: WindowInsetsCompat) {
    val isAudio = viewModel.isAudioOnly.value || isKnownAudioLaunch(intent) || isCurrentMediaKnownAudio()
    if (isAudio) {
      cancelSystemBarsAutoHide()
      try {
        windowInsetsController.apply {
          systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
          show(WindowInsetsCompat.Type.statusBars())
          show(WindowInsetsCompat.Type.navigationBars())
          isAppearanceLightStatusBars = false
          isAppearanceLightNavigationBars = false
        }
      } catch (_: Exception) {
      }
      return
    }

    val systemBarsVisible =
      insets.isVisible(WindowInsetsCompat.Type.statusBars()) ||
        insets.isVisible(WindowInsetsCompat.Type.navigationBars())

    if (systemBarsVisible) {
      scheduleSystemBarsAutoHide()
    } else {
      cancelSystemBarsAutoHide()
    }
  }

  private fun shouldAutoHideSystemBars(): Boolean {
    val isAudio = viewModel.isAudioOnly.value || isKnownAudioLaunch(intent) || isCurrentMediaKnownAudio()
    return !isInPictureInPictureMode &&
      !isAudio &&
      !viewModel.controlsShown.value &&
      viewModel.sheetShown.value == Sheets.None &&
      viewModel.panelShown.value == Panels.None
  }

  private fun scheduleSystemBarsAutoHide(delayMs: Long = 1500L) {
    if (!shouldAutoHideSystemBars()) {
      cancelSystemBarsAutoHide()
      return
    }

    systemBarsAutoHideJob?.cancel()
    systemBarsAutoHideJob =
      lifecycleScope.launch {
        delay(delayMs)
        if (shouldAutoHideSystemBars()) {
          hideSystemBarsForPlayback()
        }
      }
  }

  private fun cancelSystemBarsAutoHide() {
    systemBarsAutoHideJob?.cancel()
    systemBarsAutoHideJob = null
  }

  @Suppress("DEPRECATION")
  private fun hideSystemBarsForPlayback() {
    cancelSystemBarsAutoHide()
    val isAudio = viewModel.isAudioOnly.value || isKnownAudioLaunch(intent) || isCurrentMediaKnownAudio()
    if (isAudio) {
      try {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        binding.root.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        windowInsetsController.apply {
          systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
          show(WindowInsetsCompat.Type.statusBars())
          show(WindowInsetsCompat.Type.navigationBars())
          isAppearanceLightStatusBars = false
          isAppearanceLightNavigationBars = false
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to show system bars for audio playback", e)
      }
      return
    }
    try {
      windowInsetsController.apply {
        hide(WindowInsetsCompat.Type.statusBars())
        hide(WindowInsetsCompat.Type.navigationBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to hide system bars for playback", e)
    }

    binding.root.systemUiVisibility =
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
      View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
      View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
      View.SYSTEM_UI_FLAG_FULLSCREEN or
      View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
      if (playerPreferences.showSystemStatusBar.get()) 0 else View.SYSTEM_UI_FLAG_LOW_PROFILE
  }

  private fun setupSystemUI() {
    val isAudio = viewModel.isAudioOnly.value || isKnownAudioLaunch(intent) || isCurrentMediaKnownAudio()
    setLayoutInDisplayCutoutModeIfSupported(shortEdges = !isAudio)

    // Set status bar color for when it will be shown (with controls)
    applyStatusBarColorIfNeeded()

    // Always start with status bar hidden - it will show when controls are shown
    hideSystemBarsForPlayback()
  }

  @Suppress("DEPRECATION")
  private fun applyStatusBarColorIfNeeded() {
    if (playerPreferences.showSystemStatusBar.get()) {
      window.statusBarColor = android.graphics.Color.parseColor("#80000000")
    }
  }

  private fun restoreSystemUI() {
    cancelSystemBarsAutoHide()

    // Clear flags first for immediate effect
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // Set cutout mode before showing bars for smoother transition
    setLayoutInDisplayCutoutModeIfSupported(shortEdges = false)

    // Update window insets configuration
    WindowCompat.setDecorFitsSystemWindows(window, true)

    // Restore default behavior and show bars in one go
    try {
      windowInsetsController.apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to restore system UI insets", e)
    }
  }

  private fun releaseDetachedBackgroundPlaybackBeforeFreshLaunch() {
    if ((intent.action == MediaPlaybackService.ACTION_OPEN_PLAYER || hasValidSavedPlaybackSession()) &&
      PlaybackSession.isInitialized
    ) {
      MediaPlaybackService.prepareForActivityHandoff()
      PlaybackSession.markForeground()
      return
    }

    if (MediaPlaybackService.isRunning()) {
      Log.d(TAG, "Stopping detached service before replacing its media")
      MediaPlaybackService.relinquishMediaSessionToActivity()
      stopService(Intent(this, MediaPlaybackService::class.java))
    }
  }

  private fun attachToCurrentPlaybackSessionIfRequested(sourceIntent: Intent = intent): Boolean {
    if (sourceIntent.action != MediaPlaybackService.ACTION_OPEN_PLAYER) return false
    return attachToPlaybackSession(sourceIntent)
  }

  private fun attachToSavedPlaybackSessionIfValid(sourceIntent: Intent = intent): Boolean {
    if (!hasValidSavedPlaybackSession()) return false
    val attached = attachToPlaybackSession(sourceIntent)
    if (attached) pendingSavedPlaylistSelection = null
    return attached
  }

  private fun hasValidSavedPlaybackSession(): Boolean {
    val saved = pendingSavedPlaylistSelection ?: return false
    val state = PlaybackSession.state.value
    if (state.phase !in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) return false
    val queue = PlaybackSession.queue.value
    if (queue.currentIndex != saved.index) return false
    val item = queue.currentItem ?: return false
    return saved.stableId == item.stableId || saved.originalUri == item.originalUri
  }

  private fun attachToPlaybackSession(sourceIntent: Intent): Boolean {
    val sessionState = PlaybackSession.state.value
    val currentItem = sessionState.currentItem ?: PlaybackSession.queue.value.currentItem ?: return false
    if (sessionState.phase !in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) return false
    // A recreated Activity has a new Media3 controller. Attaching the old process session here
    // would skip the normal persisted-position lookup and leave the new controller at 00:00. Only
    // attach an existing Media3 session when this Activity already owns its prepared controller
    // (for example, an ACTION_OPEN_PLAYER intent delivered to the same Activity instance).
    if (shouldUseMedia3(currentItem) && media3PreparedItemId != currentItem.stableId) return false

    val queueState = PlaybackSession.queue.value
    playlist = queueState.items.map { item -> Uri.parse(item.originalUri) }
    playlistIndex = queueState.currentIndex.coerceAtLeast(0)
    playlistWindowOffset = 0
    playlistTotalCount = playlist.size
    networkPlaylistPaths = queueState.items.map { item -> item.networkSource?.relativePath.orEmpty() }
    networkPlaylistTitles = queueState.items.map { item -> item.title.orEmpty() }
    networkPlaylistHeaders = queueState.items.map(PlaybackItem::headers)
    networkPlaylistConnectionId = currentItem.networkSource?.connectionId ?: -1L

    fileName = currentItem.title?.takeIf { it.isNotBlank() } ?: getFileNameFromUri(Uri.parse(currentItem.originalUri))
    mediaIdentifier = currentItem.stableId
    currentPlayableUri = currentItem.playableUri
    isReady = sessionState.phase == PlaybackPhase.READY || sessionState.phase == PlaybackPhase.BACKGROUND
    player.isExiting = false
    PlaybackSession.markForeground()

    val mediaIntent =
      Intent(sourceIntent).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(currentItem.originalUri)
        type = currentItem.mimeType
        putExtra("title", currentItem.title)
        putExtra("media_identifier", currentItem.stableId)
        putExtra("playlist_index", queueState.currentIndex)
        playlistId?.let { id -> putExtra("playlist_id", id) }
        if (sourceIntent.action == MediaPlaybackService.ACTION_OPEN_PLAYER) {
          putExtra("launch_source", "notification")
        }
        putExtra("internal_launch", true)
        val isAudio =
          sourceIntent.getBooleanExtra("is_audio", false) ||
            sourceIntent.getBooleanExtra("media_library_audio", false) ||
            currentItem.mimeType?.startsWith("audio/") == true
        putExtra("is_audio", isAudio)
        putExtra("media_library_audio", isAudio)
        currentItem.networkSource?.let { source ->
          putExtra("network_connection_id", source.connectionId)
          putExtra("network_file_path", source.relativePath)
        }
      }
    setIntent(mediaIntent)

    if (mediaIntent.getBooleanExtra("is_audio", false)) {
      viewModel.onAudioLoadStarted()
    } else if (isReady) {
      viewModel.onVideoLoadCompleted()
    } else {
      viewModel.onVideoLoadStarted()
    }
    viewModel.refreshPlaylistItems()
    syncBackgroundPlaybackService(updateThumbnail = false)
    return true
  }

  /** Restores a process-local queue prepared by an internal browser without Binder-sized arrays. */
  private fun restorePreparedPlaybackQueue(sourceIntent: Intent): Boolean {
    if (!sourceIntent.getBooleanExtra(EXTRA_PREPARED_PLAYBACK_QUEUE, false) ||
      !sourceIntent.getBooleanExtra("internal_launch", false)
    ) {
      return false
    }

    val queueState = PlaybackSession.queue.value
    if (queueState.items.isEmpty()) return false
    val requestedIndex = sourceIntent.getIntExtra("playlist_index", queueState.currentIndex)
    val currentItem = queueState.items.getOrNull(requestedIndex) ?: return false
    if (sourceIntent.data?.toString() != currentItem.originalUri) return false

    playlistId = null
    playlistItems = emptyList()
    playlistEntity = null
    isM3uPlaylist = queueState.isM3u
    playlist = queueState.items.map { item -> Uri.parse(item.originalUri) }
    playlistIndex = requestedIndex
    playlistWindowOffset = 0
    playlistTotalCount = playlist.size
    networkPlaylistPaths = queueState.items.map { item -> item.networkSource?.relativePath.orEmpty() }
    networkPlaylistTitles = queueState.items.map { item -> item.title.orEmpty() }
    networkPlaylistHeaders = queueState.items.map(PlaybackItem::headers)
    networkPlaylistConnectionId = currentItem.networkSource?.connectionId ?: -1L
    return true
  }

  /**
   * Initializes the MPV player with the necessary paths and observers.
   * CRITICAL: Must copy config and scripts BEFORE initializing MPV, as MPV loads scripts during init.
   */
  private fun setupMPV(): String? {
    // Prepare config and user MPV assets before initializing MPV.
    runCatching {
      syncBundledAssetsIfNeeded()
      syncFromUserMpvDirectory()
      sanitizeInternalFontsDirectory()
      Log.d(TAG, "MPV config and assets prepared successfully")
    }.onFailure { e ->
      Log.e(TAG, "Error copying MPV config and assets", e)
    }

    player.onSurfaceReady = {
      if (!isDeviceScreenOffOrLocked() && (isInBackgroundPlayback || lastVid > 0)) {
        enableVideoAfterBackground()
      }
    }

    // NOW initialize MPV - it will find and load the scripts we just copied
    val initError = initializePlayerWithRendererFallback()
    if (initError != null) return initError
    runCatching { PlaybackSession.setThumbnailJavaVM(applicationContext) }
    mpvInitialized = true
    Log.d(TAG, "MPV initialized")

    // Add observer after initialization
    PlaybackSession.addObserver(playerObserver)

    scheduleDeferredSubtitleFontsSync()
    return null
  }

  private fun initializePlayerWithRendererFallback(): String? {
    player.forceOpenGlFallback = false
    val firstAttempt = player.initializeSession(filesDir.path, cacheDir.path)
    if (firstAttempt.isSuccess) return null

    val firstError = firstAttempt.exceptionOrNull()
    if (!decoderPreferences.useVulkan.get() || !VulkanCapabilities.isAvailable(this)) {
      Log.e(TAG, "Failed to initialize MPV", firstError)
      return firstError?.message ?: firstError?.toString() ?: "Unknown error"
    }

    Log.w(TAG, "MPV Vulkan init failed, retrying with OpenGL fallback for this session", firstError)
    player.forceOpenGlFallback = true
    val fallbackAttempt = player.initializeSession(filesDir.path, cacheDir.path)
    fallbackAttempt.exceptionOrNull()?.let { error -> Log.e(TAG, "Failed to initialize MPV", error) }
    return if (fallbackAttempt.isSuccess) null else fallbackAttempt.exceptionOrNull()?.message ?: fallbackAttempt.exceptionOrNull()?.toString() ?: "Unknown fallback error"
  }

  /**
   * Syncs MPV assets from the user's configured MPV directory to internal storage.
   * Handles: mpv.conf, input.conf, selected scripts/, script helper folders, script-opts/,
   * shaders/, and fonts/.
   */
  private fun syncFromUserMpvDirectory() {
    val mpvConfStorageUri = advancedPreferences.mpvConfStorageUri.get()

    // Try to open the user's MPV directory
    val tree =
      if (mpvConfStorageUri.isNotBlank()) {
        openPersistedTreeDocument(this, mpvConfStorageUri)
      } else {
        null
      }

    if (tree != null) {
      Log.d(TAG, "Syncing from user MPV directory: ${tree.uri}")
      val rootChildren = listTreeFilesSafely(tree)
      syncConfigFiles(tree, rootChildren)
      syncScripts(tree, rootChildren)
      syncScriptOpts(tree, rootChildren)
      syncShaders(tree, rootChildren)
      syncFonts(tree, rootChildren)
      Log.d(TAG, "Full MPV directory sync completed")
    } else {
      // Fallback: use preferences-based config (no user directory set)
      Log.d(TAG, "No MPV directory configured, using preferences fallback")
      copyMPVConfigFromPreferences()
    }
  }

  // ==================== Config Files Sync ====================

  /**
   * Syncs mpv.conf and input.conf from the user's MPV directory.
   * Also caches the content in preferences for the config editor.
   */
  private fun syncConfigFiles(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    for (configName in listOf("mpv.conf", "input.conf")) {
      runCatching {
        val configFile = findFileCaseInsensitive(tree, configName, rootChildren)
        if (configFile != null && configFile.exists() && configFile.canRead()) {
          contentResolver.openInputStream(configFile.uri)?.use { input ->
            val content = input.bufferedReader().readText()
            writeTextFileIfChanged(File(filesDir, configName), content)
            // Cache in preferences for the config editor
            when (configName) {
              "mpv.conf" -> advancedPreferences.mpvConf.set(content)
              "input.conf" -> advancedPreferences.inputConf.set(content)
            }
            Log.d(TAG, "Synced config: $configName (${content.length} chars)")
          }
        } else {
          // Config not in directory, fall back to preferences
          val prefContent =
            when (configName) {
              "mpv.conf" -> advancedPreferences.mpvConf.get()
              "input.conf" -> advancedPreferences.inputConf.get()
              else -> ""
            }
          File(filesDir, configName).apply {
            if (!exists()) createNewFile()
            if (prefContent.isNotBlank()) writeText(prefContent)
          }
          Log.d(TAG, "Config not found in directory, used preferences: $configName")
        }
      }.onFailure { e ->
        Log.e(TAG, "Error syncing config: $configName", e)
      }
    }
  }

  // ==================== Scripts Sync ====================

  /**
   * Syncs all script files (.lua, .js) from the user's MPV directory.
   * Looks in scripts/ subfolder first (case-insensitive), falls back to root.
   */
  private fun syncScripts(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    val internalScriptsDir = File(filesDir, "scripts")
    internalScriptsDir.mkdirs()

    if (!advancedPreferences.enableLuaScripts.get()) {
      clearDirectoryContents(internalScriptsDir)
      Log.d(TAG, "Scripts disabled, skipping")
      return
    }

    val scriptsSubdir = findSubdirCaseInsensitive(tree, "scripts", rootChildren)
    val sourceDir = scriptsSubdir ?: tree
    val scriptExtensions = setOf("lua", "js")
    val selectedScripts = advancedPreferences.selectedLuaScripts.get()
    val count =
      syncFlatDocumentDirectory(
        sourceDir = sourceDir,
        destinationDir = internalScriptsDir,
        includeFile = { name -> name.substringAfterLast('.', "").lowercase() in scriptExtensions },
        allowedNames = selectedScripts,
        deleteMissing = true,
      )
    val supportCount = syncScriptSupportDirectories(scriptsSubdir)

    Log.d(
      TAG,
      "Scripts sync: $count file(s), $supportCount helper file(s) from ${if (scriptsSubdir != null) "scripts/" else "root"}",
    )
  }

  /**
   * Syncs helper folders from scripts/ and mirrors Lua modules into mpv's internal
   * script-modules path so require() works without exposing a separate user folder.
   */
  private fun syncScriptSupportDirectories(scriptsSubdir: DocumentFile?): Int {
    val internalScriptsDir = File(filesDir, "scripts")
    val internalModulesDir = File(filesDir, "script-modules")
    internalModulesDir.mkdirs()

    if (!advancedPreferences.enableLuaScripts.get()) {
      clearDirectoryContents(internalModulesDir)
      return 0
    }

    clearDirectoryContents(internalModulesDir)

    var copiedCount = 0

    if (scriptsSubdir != null) {
      listTreeFilesSafely(scriptsSubdir).forEach { document ->
        val name = document.name?.takeIf { isSafeDocumentFileName(it) } ?: return@forEach
        if (!document.isDirectory) return@forEach

        copiedCount +=
          syncRecursiveDocumentDirectory(
            sourceDir = document,
            destinationDir = File(internalScriptsDir, name),
            includeFile = { true },
            deleteMissing = true,
          )

        copiedCount +=
          syncRecursiveDocumentDirectory(
            sourceDir = document,
            destinationDir = File(internalModulesDir, name),
            includeFile = { fileName -> fileName.endsWith(".lua", ignoreCase = true) },
            deleteMissing = true,
          )
      }
    }

    return copiedCount
  }

  // ==================== Script Options Sync ====================

  /**
   * Syncs all files from script-opts/ subfolder (case-insensitive).
   */
  private fun syncScriptOpts(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    val internalScriptOptsDir = File(filesDir, "script-opts")
    internalScriptOptsDir.mkdirs()

    val scriptOptsSubdir = findSubdirCaseInsensitive(tree, "script-opts", rootChildren)
    if (scriptOptsSubdir == null) {
      Log.d(TAG, "No script-opts/ subfolder found, skipping")
      return
    }

    val count =
      syncFlatDocumentDirectory(
        sourceDir = scriptOptsSubdir,
        destinationDir = internalScriptOptsDir,
        includeFile = { true },
        deleteMissing = true,
      )

    Log.d(TAG, "Script-opts sync: $count file(s)")
  }

  // ==================== Shaders Sync ====================

  /**
   * Syncs shader files (.glsl, .hook, .comp) from the user's MPV directory.
   * Looks in shaders/ subfolder first (case-insensitive), falls back to root.
   */
  private fun syncShaders(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    val shadersDir = File(filesDir, "shaders")
    shadersDir.mkdirs()

    val shadersSubdir = findSubdirCaseInsensitive(tree, "shaders", rootChildren)
    val sourceDir = shadersSubdir ?: tree
    val shaderExtensions = setOf("glsl", "hook", "comp")
    val count =
      syncFlatDocumentDirectory(
        sourceDir = sourceDir,
        destinationDir = shadersDir,
        includeFile = { name -> name.substringAfterLast('.', "").lowercase() in shaderExtensions },
        protectedNames = Anime4KManager.BUILT_IN_SHADER_FILES,
        deleteMissing = true,
      )

    Log.d(TAG, "Shaders sync: $count file(s)")
  }

  // ==================== Fonts Sync ====================

  /**
   * Syncs font files (.ttf, .otf, .ttc, .woff, .woff2) from the user's MPV directory.
   * Looks in fonts/ subfolder first (case-insensitive), falls back to root.
   */
  private fun syncFonts(
    tree: DocumentFile,
    rootChildren: Array<DocumentFile>,
  ) {
    val internalFontsDir = File(filesDir, "fonts")
    internalFontsDir.mkdirs()
    internalFontsDir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }

    val fontsSubdir = findSubdirCaseInsensitive(tree, "fonts", rootChildren)
    val sourceDir = fontsSubdir ?: tree
    val fontExtensions = setOf("ttf", "otf", "ttc", "woff", "woff2")
    val count =
      syncFlatDocumentDirectory(
        sourceDir = sourceDir,
        destinationDir = internalFontsDir,
        includeFile = { name -> name.substringAfterLast('.', "").lowercase() in fontExtensions },
        deleteMissing = false,
      )

    Log.d(TAG, "Fonts sync: $count file(s) from MPV directory")
  }

  private fun syncBundledAssetsIfNeeded() {
    val syncPrefs = getSharedPreferences("mpv_asset_sync", MODE_PRIVATE)
    val currentVersion =
      runCatching {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
      }.getOrDefault(-1L)

    val assetsAlreadyPrepared =
      File(filesDir, "mpv.conf").exists() &&
        File(filesDir, "input.conf").exists() &&
        File(filesDir, "scripts").exists()

    if (assetsAlreadyPrepared && syncPrefs.getLong("bundled_assets_version", -1L) == currentVersion) {
      return
    }

    Utils.copyAssets(this@PlayerActivity)
    syncPrefs.edit().putLong("bundled_assets_version", currentVersion).apply()
  }

  private fun scheduleDeferredSubtitleFontsSync() {
    deferredFontSyncJob?.cancel()
    deferredFontSyncJob =
      lifecycleScope.launch(Dispatchers.IO) {
        delay(750)
        runCatching { syncSubtitleFontsFromPreferenceFolder() }
          .onFailure { e -> Log.e(TAG, "Deferred subtitle font sync failed", e) }
      }
  }

  private fun syncSubtitleFontsFromPreferenceFolder() {
    val sourceDir = resolveSubtitleFontSourceDirectory() ?: return

    val destinationDir = File(filesDir, "fonts")
    destinationDir.mkdirs()
    destinationDir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }
    syncFontDirectory(sourceDir, destinationDir)
  }

  private fun resolveSubtitleFontSourceDirectory(): DocumentFile? {
    val fontsFolderUri = subtitlesPreferences.fontsFolder.get()
    if (fontsFolderUri.isBlank()) return null

    val sourceDir = openPersistedTreeDocument(this, fontsFolderUri) ?: return null

    // Older builds auto-pointed the subtitle font folder at the whole storage/config root.
    // Use its fonts/ child instead so playback never recursively scans a large media folder.
    if (fontsFolderUri == advancedPreferences.mpvConfStorageUri.get()) {
      return findSubdirCaseInsensitive(sourceDir, "fonts")
    }

    return sourceDir
  }

  private fun syncFontDirectory(
    sourceDir: DocumentFile,
    destinationDir: File,
  ): Int {
    destinationDir.mkdirs()
    var copiedCount = 0

    listTreeFilesSafely(sourceDir).forEach { document ->
      val name = document.name ?: return@forEach
      when {
        document.isDirectory -> {
          copiedCount += syncFontDirectory(document, destinationDir)
        }
        document.isFile -> {
          val extension = name.substringAfterLast('.', "").lowercase()
          if (extension !in setOf("ttf", "otf", "ttc", "woff", "woff2")) {
            return@forEach
          }

          if (copyDocumentToFileIfNeeded(document, File(destinationDir, name))) {
            copiedCount++
          }
        }
      }
    }

    return copiedCount
  }

  private fun syncRecursiveDocumentDirectory(
    sourceDir: DocumentFile,
    destinationDir: File,
    includeFile: (name: String) -> Boolean,
    deleteMissing: Boolean,
  ): Int {
    destinationDir.mkdirs()
    val expectedFiles = mutableSetOf<String>()
    val expectedDirs = mutableSetOf<String>()
    var copiedCount = 0

    fun syncDirectory(
      currentSourceDir: DocumentFile,
      currentDestinationDir: File,
      relativeDir: String,
    ) {
      currentDestinationDir.mkdirs()
      listTreeFilesSafely(currentSourceDir).forEach { document ->
        val name = document.name?.takeIf { isSafeDocumentFileName(it) } ?: return@forEach
        val relativePath = if (relativeDir.isBlank()) name else "$relativeDir/$name"

        when {
          document.isDirectory -> {
            expectedDirs += relativePath
            syncDirectory(
              currentSourceDir = document,
              currentDestinationDir = File(currentDestinationDir, name),
              relativeDir = relativePath,
            )
          }
          document.isFile && includeFile(name) -> {
            expectedFiles += relativePath
            if (copyDocumentToFileIfNeeded(document, File(currentDestinationDir, name))) {
              copiedCount++
            }
          }
        }
      }
    }

    syncDirectory(sourceDir, destinationDir, relativeDir = "")

    if (deleteMissing) {
      pruneDirectoryToExpected(destinationDir, expectedFiles, expectedDirs, relativeDir = "")
    }

    return copiedCount
  }

  private fun pruneDirectoryToExpected(
    directory: File,
    expectedFiles: Set<String>,
    expectedDirs: Set<String>,
    relativeDir: String,
  ) {
    directory.listFiles()?.forEach { existingFile ->
      val relativePath =
        if (relativeDir.isBlank()) {
          existingFile.name
        } else {
          "$relativeDir/${existingFile.name}"
        }

      when {
        existingFile.isDirectory -> {
          pruneDirectoryToExpected(existingFile, expectedFiles, expectedDirs, relativePath)
          val isExpected = relativePath in expectedDirs
          val isEmpty = existingFile.listFiles()?.isEmpty() != false
          if (!isExpected || isEmpty) {
            existingFile.deleteRecursively()
          }
        }
        existingFile.isFile && relativePath !in expectedFiles -> existingFile.delete()
      }
    }
  }

  private fun syncFlatDocumentDirectory(
    sourceDir: DocumentFile,
    destinationDir: File,
    includeFile: (name: String) -> Boolean,
    allowedNames: Set<String>? = null,
    protectedNames: Set<String> = emptySet(),
    deleteMissing: Boolean,
  ): Int {
    destinationDir.mkdirs()
    val expectedNames = mutableSetOf<String>()
    var copiedCount = 0

    listTreeFilesSafely(sourceDir).forEach { document ->
      if (!document.isFile) return@forEach
      val name = document.name ?: return@forEach
      if (!includeFile(name)) return@forEach
      if (allowedNames != null && name !in allowedNames) return@forEach

      expectedNames += name
      if (copyDocumentToFileIfNeeded(document, File(destinationDir, name))) {
        copiedCount++
      }
    }

    if (deleteMissing) {
      destinationDir.listFiles()?.forEach { existingFile ->
        if (existingFile.isFile &&
          existingFile.name !in expectedNames &&
          existingFile.name !in protectedNames
        ) {
          existingFile.delete()
        }
      }
    }

    return copiedCount
  }

  private fun copyDocumentToFileIfNeeded(
    source: DocumentFile,
    target: File,
  ): Boolean {
    val sourceLength = source.length()
    val sourceLastModified = source.lastModified()

    if (target.exists() &&
      sourceLength >= 0L &&
      target.length() == sourceLength &&
      sourceLastModified > 0L &&
      target.lastModified() == sourceLastModified
    ) {
      return false
    }

    target.parentFile?.mkdirs()
    contentResolver.openInputStream(source.uri)?.use { input ->
      target.outputStream().use { output ->
        input.copyTo(output)
      }
    } ?: return false

    if (sourceLastModified > 0L) {
      target.setLastModified(sourceLastModified)
    }
    return true
  }

  private fun writeTextFileIfChanged(
    target: File,
    content: String,
  ) {
    if (target.exists() && runCatching { target.readText() }.getOrNull() == content) {
      return
    }

    target.parentFile?.mkdirs()
    target.writeText(content)
  }

  /**
   * Loads a specific Lua script at runtime without restarting the player.
   * Finds the script in the user's MPV directory, copies it to internal storage,
   * and commands MPV to load it.
   */
  private fun loadScriptAtRuntime(scriptName: String) {
    if (!mpvInitialized || isFinishing) return

    val mpvConfStorageUri = advancedPreferences.mpvConfStorageUri.get()
    if (mpvConfStorageUri.isBlank()) return

    lifecycleScope.launch(Dispatchers.IO) {
      runCatching {
        val tree = openPersistedTreeDocument(this@PlayerActivity, mpvConfStorageUri)
        if (tree != null) {
          val rootChildren = listTreeFilesSafely(tree)
          // Look for scripts/ subfolder first (case-insensitive), fall back to root
          val scriptsSubdir = findSubdirCaseInsensitive(tree, "scripts", rootChildren)
          val scriptsDir = scriptsSubdir ?: tree
          syncScriptSupportDirectories(scriptsSubdir)
          syncScriptOpts(tree, rootChildren)

          val scriptFile =
            listTreeFilesSafely(scriptsDir).firstOrNull {
              it.name == scriptName
            }

          if (scriptFile != null) {
            val internalScriptsDir = File(filesDir, "scripts")
            if (!internalScriptsDir.exists()) internalScriptsDir.mkdirs()

            val targetFile = File(internalScriptsDir, scriptName)

            contentResolver.openInputStream(scriptFile.uri)?.use { input ->
              targetFile.outputStream().use { output ->
                input.copyTo(output)
              }
            }

            withContext(Dispatchers.Main) {
              if (!canIssueMpvCommands()) return@withContext
              PlaybackSession.command("load-script", targetFile.absolutePath)
              viewModel.showToast("Loaded script: $scriptName")
            }
          }
        }
      }.onFailure { e ->
        Log.e(TAG, "Error loading script at runtime: $scriptName", e)
        withContext(Dispatchers.Main) {
          android.widget.Toast
            .makeText(
              this@PlayerActivity,
              "Failed to load script: ${e.message}",
              android.widget.Toast.LENGTH_LONG,
            ).show()
        }
      }
    }
  }

  // ==================== Helpers ====================

  /**
   * Fallback: copies config from preferences when no user MPV directory is set.
   */
  private fun copyMPVConfigFromPreferences() {
    runCatching {
      File(filesDir, "mpv.conf").apply {
        if (!exists()) createNewFile()
        val content = advancedPreferences.mpvConf.get()
        if (content.isNotBlank()) writeText(content)
      }
      File(filesDir, "input.conf").apply {
        if (!exists()) createNewFile()
        val content = advancedPreferences.inputConf.get()
        if (content.isNotBlank()) writeText(content)
      }
      // Ensure scripts directory exists even without user dir
      File(filesDir, "scripts").mkdirs()
      File(filesDir, "script-modules").mkdirs()
      File(filesDir, "fonts").mkdirs()
      File(filesDir, "shaders").mkdirs()
    }.onFailure { e ->
      Log.e(TAG, "Error creating fallback config files", e)
    }
  }

  private fun sanitizeInternalFontsDirectory() {
    val fontsDir = File(filesDir, "fonts")
    if (!fontsDir.exists()) {
      return
    }

    fontsDir.listFiles()?.filter { it.isDirectory }?.forEach { nestedDir ->
      nestedDir.deleteRecursively()
    }
  }

  private fun clearDirectoryContents(directory: File) {
    directory.listFiles()?.forEach { child ->
      if (child.isDirectory) {
        child.deleteRecursively()
      } else {
        child.delete()
      }
    }
  }

  private fun isSafeDocumentFileName(name: String): Boolean =
    name.isNotBlank() && !name.contains('/') && !name.contains('\\')

  /**
   * Finds a subdirectory by name (case-insensitive) within a DocumentFile.
   */
  private fun findSubdirCaseInsensitive(
    parent: DocumentFile,
    name: String,
    children: Array<DocumentFile> = listTreeFilesSafely(parent),
  ): DocumentFile? =
    children.firstOrNull {
      it.isDirectory && it.name?.equals(name, ignoreCase = true) == true
    }

  /**
   * Finds a file by name (case-insensitive) within a DocumentFile.
   */
  private fun findFileCaseInsensitive(
    parent: DocumentFile,
    name: String,
    children: Array<DocumentFile> = listTreeFilesSafely(parent),
  ): DocumentFile? =
    children.firstOrNull {
      it.isFile && it.name?.equals(name, ignoreCase = true) == true
    }

  override fun onResume() {
    super.onResume()
    if (!mpvInitialized) return
    if (!isDeviceScreenOffOrLocked()) enableVideoAfterBackground()
    updateVolume()
    resumePlaybackAfterScreenUnlockIfNeeded()
    if (!screenUnlockPlaybackController.hasPendingResume()) wasPlayingBeforePause = false
  }

  /**
   * Updates the volume level to match the system volume.
   *
   * This method updates the current volume level by getting the current system volume
   * and adjusting the MPV volume accordingly. It ensures that the MPV volume is set
   * to the maximum allowed value if the system volume is lower than the maximum.
   */
  private fun updateVolume() {
    val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    viewModel.syncCurrentVolumeState()
    if (volume < viewModel.maxVolume) {
      viewModel.changeMPVVolumeTo(MAX_MPV_VOLUME)
    }
  }

  private fun isMiniPlayerEnabled(): Boolean {
    val isAudio = viewModel.isAudioOnly.value || isKnownAudioLaunch(intent) || isCurrentMediaKnownAudio()
    return if (isAudio) {
      true
    } else {
      playerPreferences.enableVideoMiniPlayer.get()
    }
  }

  private fun isBackgroundPlaybackEnabled(): Boolean =
    if (viewModel.isAudioOnly.value || isKnownAudioLaunch(intent)) audioPreferences.audioBackgroundPlayback.get()
    else audioPreferences.backgroundPlayback.get()

  private fun isDeviceScreenOffOrLocked(): Boolean {
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    return keyguardManager.isDeviceLocked || !powerManager.isInteractive
  }

  private fun rememberResumeAfterUnlockBeforeForcedPause() {
    screenUnlockPlaybackController.onScreenTurnedOff(
      autoplayAfterScreenUnlockEnabled = playerPreferences.autoplayAfterScreenUnlock.get(),
      wasPlayingBeforePause = wasPlayingBeforePause,
      isCurrentlyPaused = viewModel.paused,
      backgroundPlaybackActive = false,
      isUserFinishing = isUserFinishing,
      isFinishing = isFinishing,
    )
    wasPlayingBeforePause = viewModel.paused == false || wasPlayingBeforePause
  }

  private fun resumePlaybackAfterScreenUnlockIfNeeded() {
    resumeAfterUnlockJob?.cancel()
    if (!screenUnlockPlaybackController.hasPendingResume()) return

    resumeAfterUnlockJob =
      lifecycleScope.launch {
        repeat(50) {
          val deviceLocked = isDeviceScreenOffOrLocked()
          if (screenUnlockPlaybackController.consumeResumeAfterUnlockIfReady(deviceLocked)) {
            wasPlayingBeforePause = false
            if (viewModel.paused == true && !isFinishing && !isUserFinishing) {
              viewModel.unpause()
            }
            return@launch
          }
          if (!screenUnlockPlaybackController.hasPendingResume()) return@launch
          delay(100)
        }
      }
  }

  /**
   * Processes intent extras to set initial playback position, subtitles, and HTTP headers.
   *
   * This method checks the intent extras for the following keys:
   * - "position": The initial playback position in seconds.
   * - "subs": A list of subtitle URIs to add.
   * - "subs.enable": A list of subtitle URIs to enable.
   * - "headers": A list of HTTP headers to set for network playback.
   *
   * @param extras Bundle containing intent extras
   */
  private fun setIntentExtras(extras: Bundle?) {
    if (extras == null) return

    extras.getInt("position", POSITION_NOT_SET).takeIf { it != POSITION_NOT_SET }?.let {
      PlaybackSession.setPropertyInt("time-pos", it / MILLISECONDS_TO_SECONDS)
    }

    addSubtitlesFromExtras(extras)
    setHttpHeadersFromExtras(extras)
  }

  /**
   * Adds subtitle tracks from intent extras.
   *
   * This method checks the intent extras for the "subs" key, which contains a list
   * of subtitle URIs to add. It also checks for the "subs.enable" key, which contains
   * a list of subtitle URIs to enable.
   *
   * @param extras Bundle containing subtitle URIs
   */
  private fun addSubtitlesFromExtras(extras: Bundle) {
    if (!extras.containsKey("subs") && !extras.containsKey("subs.enable")) return

    val subList = extractSubtitleUriList(extras, "subs")
    val subsToEnable = extractSubtitleUriList(extras, "subs.enable")
    val hasSubsToEnable = extras.containsKey("subs.enable")
    val subtitleTitles = extractSubtitleStringArray(extras, "subs.name", "subs.titles", "subs.filename")
    val subtitleLanguages = extractSubtitleStringArray(extras, "subs.langs", "subs.languages")
    val subtitleEntries =
      IntentSubtitleLoadPolicy.entriesToLoad(
        subtitles = subList,
        enabledSubtitles = subsToEnable,
        hasEnabledSubtitleExtra = hasSubsToEnable,
      )

    intentSubtitleJob?.cancel()
    intentSubtitleJob =
      lifecycleScope.launch(Dispatchers.IO) {
        for (entry in subtitleEntries) {
          if (!isActive || !canIssueMpvCommands()) break
          val suburi = entry.value
          val subfile = suburi.resolveUri(this@PlayerActivity) ?: continue
          val flag = if (entry.select) "select" else "auto"
          val title =
            if (entry.metadataIndex >= 0) {
              subtitleTitles
                .getOrNull(entry.metadataIndex)
                ?.trim()
                .orEmpty()
                .ifBlank { null }
            } else {
              null
            }
          val language =
            if (entry.metadataIndex >= 0) {
              subtitleLanguages
                .getOrNull(entry.metadataIndex)
                ?.trim()
                .orEmpty()
                .ifBlank { null }
            } else {
              null
            }
          val displayTitle = title ?: language

          withContext(Dispatchers.Main.immediate) {
            if (!canIssueMpvCommands()) return@withContext

            Log.v(TAG, "Adding subtitles from intent extras: $subfile")
            val trackCountBefore = PlaybackSession.getPropertyInt("track-list/count") ?: 0
            runCatching {
              when {
                displayTitle != null -> PlaybackSession.command("sub-add", subfile, flag, displayTitle)
                else -> PlaybackSession.command("sub-add", subfile, flag)
              }
            }.onSuccess {
              val trackCountAfter = PlaybackSession.getPropertyInt("track-list/count") ?: 0
              if (trackCountAfter > trackCountBefore) {
                val newTrackIndex = trackCountAfter - 1
                if (displayTitle != null) {
                  runCatching {
                    PlaybackSession.setPropertyString("track-list/$newTrackIndex/title", displayTitle)
                  }
                }
                if (language != null) {
                  runCatching {
                    PlaybackSession.setPropertyString("track-list/$newTrackIndex/lang", language)
                  }
                }
              }
            }.onFailure { error ->
              Log.w(TAG, "Failed to add subtitle from intent extras: $subfile", error)
            }
          }
        }
      }
  }

  private fun extractSubtitleUriList(extras: Bundle, key: String): List<Uri> {
    val fromParcelableArray = runCatching { Utils.getParcelableArray<Uri>(extras, key)?.toList() }.getOrNull()
    if (!fromParcelableArray.isNullOrEmpty()) return fromParcelableArray

    val fromParcelableList = runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        extras.getParcelableArrayList(key, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        extras.getParcelableArrayList<Uri>(key)
      }
    }.getOrNull()
    if (!fromParcelableList.isNullOrEmpty()) return fromParcelableList

    val fromStringArray = extras.getStringArray(key)?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
    if (!fromStringArray.isNullOrEmpty()) return fromStringArray

    val fromStringList = extras.getStringArrayList(key)?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
    if (!fromStringList.isNullOrEmpty()) return fromStringList

    return emptyList()
  }

  private fun extractSubtitleStringArray(extras: Bundle, vararg keys: String): Array<String> {
    for (key in keys) {
      extras.getStringArray(key)?.let { return it }
      extras.getStringArrayList(key)?.let { return it.toTypedArray() }
    }
    return emptyArray()
  }

  /**
   * Sets HTTP headers from intent extras for network playback.
   *
   * This method checks the intent extras for the "headers" key, which contains a list
   * of HTTP headers to set. It sets the User-Agent header and any additional headers
   * specified in the list.
   *
   * Also automatically adds Referer header based on the URL origin if not already provided.
   *
   * @param extras Bundle containing HTTP headers
   */
  private fun setHttpHeadersFromExtras(extras: Bundle?) {
    val uri = extractUriFromIntent(intent)
    val headers = buildPlaybackHeaders(uri, PlaybackHttpHeaders.fromFlatPairs(extras?.getStringArray("headers")))
    applyHttpHeaders(headers)
  }

  /**
   * Sets HTTP headers for a specific URI (used for playlist items).
   * Automatically extracts and sets the Referer header based on the URI origin.
   *
   * @param uri The URI to extract referer from and set headers for
   */
  private fun setHttpHeadersForUri(uri: Uri) {
    if (!HttpUtils.isNetworkStream(uri)) {
      applyHttpHeaders(emptyMap())
      return
    }

    val playlistItem = getPlaylistItemByUri(uri)
    val itemHeaders =
      PlaybackSession.queue.value.items
        .getOrNull(playlistIndex)
        ?.headers
        .orEmpty()
    val storedHeaders =
      getEffectiveUserAgent(playlistItem)
        ?.let { userAgent -> mapOf("User-Agent" to userAgent) }
        .orEmpty()
    applyHttpHeaders(buildPlaybackHeaders(uri, itemHeaders, storedHeaders))
  }

  /**
   * Parses the file path from the intent.
   *
   * This method checks the intent action and data to determine the file path.
   * It supports the following actions:
   * - ACTION_VIEW: The file path is contained in the intent data.
   * - ACTION_SEND: The file path is contained in the intent extras.
   *
   * @param intent The intent containing the file URI
   * @return The resolved file path, or null if not found
   */
  private fun parsePathFromIntent(intent: Intent): String? =
    intent
      .getStringExtra("local_media_path")
      ?.takeIf { path -> File(path).isFile }
      ?: when (intent.action) {
        Intent.ACTION_VIEW -> intent.data?.resolveUri(this)
        Intent.ACTION_SEND -> parsePathFromSendIntent(intent)
        else -> intent.getStringExtra("uri")
      }

  /**
   * Parses the file path from a SEND intent.
   *
   * This method checks the intent extras for the file path.
   *
   * @param intent The SEND intent
   * @return The resolved file path, or null if not found
   */
  private fun parsePathFromSendIntent(intent: Intent): String? =
    if (intent.hasExtra(Intent.EXTRA_STREAM)) {
      val uri =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
          intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
          @Suppress("DEPRECATION")
          intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
      uri?.resolveUri(this@PlayerActivity)
    } else {
      intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
        val uri = text.trim().toUri()
        if (uri.isHierarchical && !uri.isRelative) {
          uri.resolveUri(this)
        } else {
          null
        }
      }
    }

  /**
   * Extracts and resolves the file name from the intent.
   *
   * @param intent The intent containing the file URI
   * @return The display name of the file, or empty string if not found
   */
  private fun getFileName(intent: Intent): String {
    // First check if a custom title/filename was provided via intent extras
    intent.getStringExtra("title")?.let { return it }
    intent.getStringExtra("filename")?.let { return it }

    val uri = extractUriFromIntent(intent) ?: return ""

    // Try content resolver first for content:// URIs
    getDisplayNameFromUri(uri)?.let { return it }

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  /**
   * Extracts filename from URI, handling URL encoding and network URLs properly.
   * For network streams, returns a temporary name that will be updated async via HTTP headers.
   *
   * @param uri The URI to extract filename from
   * @return The extracted filename
   */
  private fun extractFileNameFromUri(uri: Uri): String {
    // For HTTP/HTTPS URLs, extract from path (will be updated async via HTTP headers)
    if (HttpUtils.isNetworkStream(uri)) {
      // Get the last path segment and decode URL encoding
      val path = uri.path ?: return uri.host ?: "Network Stream"
      val lastSegment = path.substringAfterLast("/")

      if (lastSegment.isNotBlank()) {
        // Decode URL encoding (e.g., %20 -> space)
        return try {
          java.net.URLDecoder
            .decode(lastSegment, "UTF-8")
            .substringBefore("?") // Remove query parameters
            .substringBefore("#") // Remove fragments (only for network streams)
            .takeIf { it.isNotBlank() } ?: uri.host ?: "Network Stream"
        } catch (e: Exception) {
          lastSegment
            .substringBefore("?")
            .substringBefore("#")
        }
      }

      // If no filename in path, use hostname
      return uri.host ?: "Network Stream"
    }

    // For file:// and content:// URIs - preserve # characters as they're part of the filename
    val lastSegment = uri.lastPathSegment?.substringAfterLast("/") ?: uri.path ?: "Unknown Video"

    // For local files, only decode URL encoding but preserve # characters
    return try {
      java.net.URLDecoder.decode(lastSegment, "UTF-8")
    } catch (e: Exception) {
      lastSegment
    }
  }

  /**
   * Gets the display title for a playlist item URI.
   * If Room metadata exists for the current playlist, the stored playlist item title wins.
   *
   * @param uri The URI to get the title for
   * @return The display name/title of the file
   */
  internal fun getPlaylistItemTitle(uri: Uri): String {
    getPlaylistItemByUri(uri)?.fileName?.takeIf { it.isNotBlank() }?.let { return it }

    val idx = playlist.indexOf(uri)
    if (idx != -1 && idx < networkPlaylistTitles.size) {
      networkPlaylistTitles[idx].takeIf { it.isNotBlank() }?.let { return it }
    }

    // Try content resolver first for content:// URIs
    getDisplayNameFromUri(uri)?.let { return it }

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  internal fun getPlaylistItemTvgLogo(index: Int): String? = playlistItems.getOrNull(index)?.tvgLogo

  private fun getPlaylistItemByIndex(index: Int): PlaylistItemEntity? = playlistItems.getOrNull(index)

  private fun getPlaylistItemByUri(uri: Uri): PlaylistItemEntity? {
    val currentItem = getPlaylistItemByIndex(playlistIndex)
    if (currentItem != null && isSameUriOrLocalPath(currentItem.filePath, uri)) {
      return currentItem
    }
    return playlistItems.firstOrNull { isSameUriOrLocalPath(it.filePath, uri) }
  }

  private fun isSameUriOrLocalPath(
    filePath: String,
    uri: Uri,
  ): Boolean {
    if (filePath == uri.toString()) return true
    val path1 =
      if (filePath.startsWith("content://") || filePath.startsWith("file://")) {
        Uri.parse(filePath).extractLocalPath()
      } else {
        filePath
      }
    val path2 =
      if (uri.scheme == "content" || uri.scheme == "file") {
        uri.extractLocalPath()
      } else {
        uri.toString()
      }
    return path1 != null && path2 != null && path1 == path2
  }

  private fun getEffectiveUserAgent(item: PlaylistItemEntity?): String? =
    item?.userAgent?.takeIf { it.isNotBlank() }
      ?: playlistEntity?.userAgent?.takeIf { it.isNotBlank() }

  private fun configuredMpvHeaders(): Map<String, String> =
    runCatching {
      File(filesDir, "mpv.conf")
        .takeIf { it.isFile }
        ?.readText()
        ?.let(PlaybackHttpHeaders::fromMpvConf)
        .orEmpty()
    }.onFailure { error ->
      Log.w(TAG, "Unable to read mpv.conf network headers", error)
    }.getOrDefault(emptyMap())

  private fun buildPlaybackHeaders(
    uri: Uri?,
    vararg sources: Map<String, String>,
  ): Map<String, String> {
    if (!HttpUtils.isNetworkStream(uri)) return emptyMap()
    // mpv.conf is the user’s default network policy. Explicit per-item/intent headers remain
    // authoritative, while the URL origin is only a final fallback when no Referer was configured.
    var headers = PlaybackHttpHeaders.merge(configuredMpvHeaders(), *sources)
    headers = PlaybackHttpHeaders.withDefault(headers, "Referer", HttpUtils.extractRefererDomain(uri))
    headers = PlaybackHttpHeaders.withDefault(headers, "User-Agent", NetworkUserAgent.resolve(this))
    return headers
  }

  private fun applyHttpHeaders(headers: Map<String, String>) {
    PlaybackSession.setPropertyString("user-agent", PlaybackHttpHeaders.userAgent(headers).orEmpty())
    PlaybackSession.setPropertyString("http-header-fields", PlaybackHttpHeaders.toMpvHeaderFields(headers))

    if (headers.isNotEmpty()) {
      Log.d(TAG, "Applied HTTP headers (ua=${PlaybackHttpHeaders.userAgent(headers) != null}, count=${headers.size})")
    }
  }

  private fun getPreferredCurrentTitle(): String =
    getPlaylistItemByIndex(playlistIndex)?.fileName?.takeIf { it.isNotBlank() } ?: fileName

  private fun getPreferredCurrentArtist(): String {
    currentPlaybackItem()?.artist
      ?.trim()
      ?.takeIf { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
      ?.let { return it }
    val propertyKeys =
      listOf(
        "metadata/artist",
        "metadata/by-key/artist",
        "metadata/by-key/Artist",
        "metadata/by-key/album_artist",
        "metadata/by-key/albumartist",
        "metadata/by-key/ALBUMARTIST",
        "metadata/by-key/performer",
        "metadata/by-key/PERFORMER",
        "metadata/by-key/author",
        "metadata/by-key/composer",
      )
    return propertyKeys.firstNotNullOfOrNull { key ->
      runCatching { PlaybackSession.getPropertyString(key) }.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
    } ?: getPreferredCurrentTitle()
      .split(" - ", " – ", " — ", limit = 2)
      .firstOrNull()
      ?.trim()
      ?.takeIf { it.length in 1..80 && !it.startsWith("[") }
      .orEmpty()
  }

  private fun shouldForceCurrentMediaTitle(): Boolean =
    getPlaylistItemByIndex(playlistIndex)?.fileName?.isNotBlank() == true ||
      getExplicitIntentTitle() != null ||
      (!isCurrentStreamM3U() && !HttpUtils.shouldPreferResolvedMediaTitle(extractUriFromIntent(intent), fileName))

  private fun getExplicitIntentTitle(): String? =
    intent.getStringExtra("title")?.takeIf { !HttpUtils.isLikelyJunkTitle(it) }
      ?: intent.getStringExtra("filename")?.takeIf { !HttpUtils.isLikelyJunkTitle(it) }

  /**
   * Re-run folder discovery when a standalone file-manager launch still has a singleton queue.
   * This is triggered by the playlist-sheet action rather than every Compose recomposition.
   */
  override fun refreshCurrentFolderQueue() {
    val queueState = PlaybackSession.queue.value
    if (queueState.isTemporaryQueue) {
      Log.d(TAG, "Skipping folder queue refresh for temporary queue: ${queueState.items.size} items")
      return
    }
    // The Activity playlist can be stale or singleton after an external file-manager launch.
    // Always perform a fresh discovery when the playlist sheet requests it.
    val sourceUri = externalContentLaunchUri ?: extractUriFromIntent(intent)
    val localPath =
      parsePathFromIntent(intent)
        ?.takeIf { File(it).isFile }
        ?: sourceUri
          ?.takeIf {
            it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE
          }
          ?.let { uri ->
            if (uri.scheme == ContentResolver.SCHEME_FILE) uri.path else uri.resolveLocalFilePath(this)
          }
          ?.takeIf { File(it).isFile }

    Log.d(
      TAG,
      "Playlist sheet refresh requested: queueSize=${playlist.size}, sourceUri=$sourceUri, localPath=$localPath",
    )
    if (localPath != null) {
      generatePlaylistFromFolder(localPath)
    } else if (sourceUri?.scheme == ContentResolver.SCHEME_CONTENT) {
      generatePlaylistFromMediaStore(sourceUri)
    }
  }

  /**
   * Plays a playlist item by index.
   *
   * @param index The index of the playlist item to play
   */
  override fun playQueueItem(index: Int) {
    if (index in playlist.indices) {
      loadPlaylistItem(index)
    }
  }

  /**
   * Extracts the URI from the intent based on intent type.
   *
   * @param intent The intent to extract URI from
   * @return The extracted URI, or null if not found
   */
  private fun extractUriFromIntent(intent: Intent): Uri? {
    if (intent.type == "text/plain") {
      return intent.getStringExtra(Intent.EXTRA_TEXT)?.toUri()
    }

    val streamUri =
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
      }

    return intent.data ?: streamUri ?: intent.getStringExtra("uri")?.toUri()
  }

  /**
   * Queries the content resolver to get the display name for a URI.
   *
   * @param uri The URI to query
   * @return The display name, or null if not found
   */
  private fun getDisplayNameFromUri(uri: Uri): String? =
    runCatching {
      contentResolver
        .query(
          uri,
          arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
          null,
          null,
          null,
        )?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.onFailure { e ->
      Log.e(TAG, "Error getting display name from URI", e)
    }.getOrNull()

  /**
   * Converts the intent URI to a playable URI string for MPV.
   *
   * @param intent The intent containing the file URI
   * @return A playable URI string, or null if unable to resolve
   */
  private fun getPlayableUri(intent: Intent): String? {
    extractUriFromIntent(intent)
      ?.toString()
      ?.takeIf { source -> isTorrentSource(source, intent.type) }
      ?.let { return it }

    val uri = parsePathFromIntent(intent)
    if (uri == null) {
      Log.e(TAG, "Unable to resolve playable media URI: ${redactedUrlForLog(extractUriFromIntent(intent).toString())}")
      viewModel.onVideoLoadCompleted()
      viewModel.showToast(getString(R.string.toast_playback_load_failed))
      return null
    }
    return if (uri.startsWith("content://")) {
      uri.toUri().openContentFd(this)
    } else {
      uri
    }
  }

  /**
   * Handles device configuration changes.
   *
   * @param newConfig The new configuration
   */
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    val isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
    viewModel.onOrientationChanged(isPortrait)
    if (isReady) {
      handleConfigurationChange()
    }
  }

  /**
   * Handles configuration changes by updating video aspect ratio.
   */
  private fun handleConfigurationChange() {
    if (!isInPictureInPictureMode) {
      // Configuration changes don't affect aspect ratio
    } else {
      viewModel.hideControls()
    }
  }

  // ==================== MPV Event Observers ====================

  /**
   * Observer callback for MPV property changes (Long values).
   * Handles video width and height changes.
   *
   * @param property The property name that changed
   * @param value The new Long value
   */
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(
    property: String,
    value: Long,
  ) {
    when (property) {
      "video-params/w",
      "video-params/h",
      -> {
        // Safety check: don't access MPV during cleanup
        if (!mpvInitialized || player.isExiting || isFinishing) return
        scheduleVideoParamRefresh(reloadShaders = true)
      }
    }
  }

  /**
   * Observer callback for MPV property changes (Boolean values).
   * Handles pause state and end-of-file events.
   *
   * @param property The property name that changed
   * @param value The new Boolean value
   */
  internal fun onObserverEvent(
    property: String,
    value: Boolean,
  ) {
    when (property) {
      "pause" -> {
        handlePauseStateChange(value)
      }
      "eof-reached" -> handleEndOfFile(value)
      "user-data/mpv/console/open" -> {
        if (!value) {
          if (advancedPreferences.enabledStatisticsPage.get() == 7) {
            advancedPreferences.enabledStatisticsPage.set(0)
          }
        } else {
          if (advancedPreferences.enabledStatisticsPage.get() != 7) {
            advancedPreferences.enabledStatisticsPage.set(7)
          }
        }
      }
    }
  }

  /**
   * Handles pause state changes by managing screen-on flag and MediaSession state.
   *
   * @param isPaused true if playback is paused, false if playing
   */
  private fun handlePauseStateChange(isPaused: Boolean) {
    if (isPaused) {
      // Only clear keep-screen-on if the preference is NOT enabled
      if (!playerPreferences.keepScreenOnWhenPaused.get()) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    } else {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    updateMediaSessionPlaybackState(!isPaused)
    runCatching {
      if (isInPictureInPictureMode) {
        pipHelper.updatePictureInPictureParams()
      }
    }.onFailure { /* Silently ignore PiP update failures */ }

    jellyfinSessionReporter?.let { reporter ->
      val currentPosMs = (viewModel.pos ?: 0).toLong() * 1000L
      reporter.reportPlaybackProgress(currentPosMs, isPaused)
    }
  }

  /**
   * Handles end-of-file event by playing next in playlist if available, otherwise finishing activity if configured.
   *
   * @param isEof true if end of file reached
   */
  private fun handleEndOfFile(isEof: Boolean) {
    if (!isEof) {
      eofAdvanceJob?.cancel()
      eofAdvanceJob = null
      isAdvancingAtEof = false
      return
    }
    if (isAdvancingAtEof) return

    val repeatMode = viewModel.repeatMode.value
    if (repeatMode == RepeatMode.ONE) {
      restartCurrentAtEof()
      return
    }

    val isAudio = viewModel.isAudioOnly.value || isKnownAudioLaunch(intent) || isCurrentMediaKnownAudio()
    val autoplay = if (isAudio) playerPreferences.autoplayNextAudio.get() else playerPreferences.autoplayNextVideo.get()
    val repeatAll = repeatMode == RepeatMode.ALL

    if (playlist.isNotEmpty()) {
      val hasNext = PlaybackSession.hasNext()
      if ((autoplay && hasNext) || repeatAll) {
        isAdvancingAtEof = true
        playNextQueueItem()
      } else {
        finishAtEofIfRequested()
      }
      return
    }

    if (playerPreferences.playlistMode.get() && (autoplay || repeatAll)) {
      val path = parsePathFromIntent(intent)
      if (path != null) {
        isAdvancingAtEof = true
        eofAdvanceJob =
          lifecycleScope.launch(Dispatchers.IO) {
            generatePlaylistFromFolderInternal(path)
            withContext(Dispatchers.Main) {
              val hasNext = PlaybackSession.hasNext()
              when {
                (autoplay && hasNext) || (repeatAll && playlist.isNotEmpty()) -> playNextQueueItem()
                repeatAll -> restartCurrentAtEof()
                else -> finishAtEofIfRequested()
              }
            }
          }
        return
      }
    }

    if (repeatAll) restartCurrentAtEof() else finishAtEofIfRequested()
  }

  private fun restartCurrentAtEof() {
    isAdvancingAtEof = false
    if (playbackEngine == PlaybackEngine.MEDIA3) {
      AppDebugLog.info(TAG, "Media3 repeat replay positionMs=0")
      media3PlaybackController.seekTo(0L, fast = false)
      media3PlaybackController.setPlayWhenReady(true)
      return
    }
    PlaybackSession.command("seek", "0", "absolute")
    viewModel.unpause()
  }

  private fun finishAtEofIfRequested() {
    isAdvancingAtEof = false
    if (playerPreferences.closeAfterReachingEndOfVideo.get()) {
      finishAndRemoveTask()
    }
  }

  /**
   * Observer callback for MPV property changes (String values).
   * Handles Lua script invocations.
   *
   * @param property The property name that changed
   * @param value The new String value
   */
  internal fun onObserverEvent(
    property: String,
    value: String,
  ) {
    when (property) {
      "sub-text" -> {
        if (isSecondarySubtitleActive()) {
          val primaryPosition = subtitlesPreferences.subPos.get()
          val width = player.width.takeIf { it > 0 }?.toFloat()
          val height = player.height.takeIf { it > 0 }?.toFloat()
          applySubtitlePositions(primaryPosition, width, height)
        }
      }
      else -> {
        when (property.substringBeforeLast("/")) {
          "user-data/mpvrx" -> viewModel.handleLuaInvocation(property, value)
        }
      }
    }
  }

  /**
   * Observer callback for MPV property changes (MPVNode values).
   *
   * This method is called when an MPV property (with MPVNode value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new MPVNode value
   */
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(
    property: String,
    value: MPVNode,
  ) {
    // Currently no MPVNode properties are handled
  }

  /**
   * Observer callback for MPV property changes (Double values).
   *
   * This method is called when an MPV property (with Double value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new Double value
   */
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(
    property: String,
    value: Double,
  ) {
    // Handle Double properties
    when (property) {
      "video-params/aspect" -> {
        // Safety check: don't access MPV during cleanup
        if (!mpvInitialized || player.isExiting || isFinishing) return
        scheduleVideoParamRefresh(reloadShaders = false)
      }
      "container-fps" -> {
        if (!mpvInitialized || player.isExiting || isFinishing) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && value > 0.0) {
          try {
            val surface = player.holder?.surface
            if (surface != null && surface.isValid) {
              surface.setFrameRate(
                value.toFloat(),
                android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
              )
              android.util.Log.i(TAG, "Set display refresh rate to ${value}Hz")
            }
          } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to set frame rate", e)
          }
        }
      }
      "sub-scale" -> {
        if (isSecondarySubtitleActive()) {
          val primaryPosition = subtitlesPreferences.subPos.get()
          val width = player.width.takeIf { it > 0 }?.toFloat()
          val height = player.height.takeIf { it > 0 }?.toFloat()
          applySubtitlePositions(primaryPosition, width, height)
        }
      }
    }
  }

  @Synchronized
  private fun scheduleVideoParamRefresh(reloadShaders: Boolean) {
    pendingVideoParamRefreshRequiresShaderReload =
      pendingVideoParamRefreshRequiresShaderReload ||
      reloadShaders

    videoParamRefreshJob?.cancel()
    videoParamRefreshJob =
      lifecycleScope.launch {
        delay(100)
        if (!mpvInitialized || player.isExiting || isFinishing) return@launch

        val aspect =
          withContext(playbackRenderDispatcher) {
            player.getVideoOutAspect()
          }
        Log.d(TAG, "Coalesced video params refresh, aspect: $aspect")
        pipHelper.updatePictureInPictureParams()

        val aspectOverride =
          withContext(playbackRenderDispatcher) {
            PlaybackSession.getPropertyDouble("video-aspect-override") ?: -1.0
          }
        if (playerPreferences.orientation.get() == PlayerOrientation.Video &&
          aspect != null &&
          aspectOverride <= 0.0
        ) {
          setOrientation()
        }

        if (pendingVideoParamRefreshRequiresShaderReload) {
          pendingVideoParamRefreshRequiresShaderReload = false
          withContext(playbackRenderDispatcher) {
            player.applyAnime4KShaders()
            viewModel.restartHdrScreenOutputAndAmbientIfActive()
          }
        }
      }
  }

  /**
   * Observer callback for MPV property changes (no value parameter).
   * Handles properties with no value parameter.
   *
   * @param property The property name that changed
   */
  internal fun onObserverEvent(property: String) {
    // Currently no properties use this signature
  }

  /**
   * Handles MPV core events such as file loaded and playback restart.
   *
   * Called by the player when critical playback events occur.
   *
   * @param eventId The MPV event ID
   */
  internal fun event(eventId: Int) {
    when (eventId) {
      MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
        val loadGeneration = PlaybackSession.state.value.activeGeneration
        if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return
        eofAdvanceJob?.cancel()
        eofAdvanceJob = null
        isAdvancingAtEof = false
        isReady = true
        if (restoreVideoTrackAfterFileLoad) {
          restoreVideoTrackAfterFileLoad = false
          PlaybackSession.setPropertyString("vid", "auto")
        }
        if (playWhenFileLoaded) {
          playWhenFileLoaded = false
        }
        viewModel.onVideoLoadCompleted()
        handleFileLoaded(loadGeneration)
        // Background service ownership is established by onStop()/Back handoff. Starting or
        // resyncing it for every foreground FILE_LOADED event makes rapid audio transitions
        // compete with the live Activity session and can cause stutter.
      }

      MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
        isAdvancingAtEof = false
        player.isExiting = false
        if (!isReady) {
          isReady = true
        }
        viewModel.onVideoLoadCompleted()
      }
    }
  }

  /**
   * Handles the file loaded event from MPV.
   * Initializes playback state, loads saved playback data, restores custom settings,
   * applies user preferences, and sets up metadata and media session.
   */
  private fun handleFileLoaded(loadGeneration: Long) {
    if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return
    // Extract fileName from intent only if not already set
    // This preserves fileName set in onNewIntent or onCreate
    if (fileName.isBlank()) {
      fileName = getFileName(intent)
      // Ensure fileName is not blank - use a fallback if necessary
      if (fileName.isBlank()) {
        fileName = intent.data?.lastPathSegment ?: "Unknown Video"
      }
      legacyMediaIdentifier = getLegacyMediaIdentifier(intent, fileName)
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    } else if (mediaIdentifier.isBlank()) {
      // If fileName was already set, but mediaIdentifier is missing, set it for safety
      legacyMediaIdentifier = getLegacyMediaIdentifier(intent, fileName)
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    }

    if (serviceBound || mediaPlaybackService != null) {
      syncBackgroundPlaybackService(updateThumbnail = true)
    }

    val currentUri =
      if (playlist.isNotEmpty() && playlistIndex in playlist.indices) {
        playlist[playlistIndex]
      } else {
        extractUriFromIntent(intent)
      }
    val loadedFileName = fileName
    val loadedMediaIdentifier = mediaIdentifier
    val loadedLegacyIdentifier = legacyMediaIdentifier
    val loadedIntent = Intent(intent)
    val loadedPlaylistIndex = playlistIndex
    val loadedPlaylist = playlist.toList()
    currentUri?.let { viewModel.calculateVideoHash(it) }

    reportJellyfinStop()
    currentUri?.toString()?.let { url ->
      jellyfinSessionReporter = JellyfinSessionReporter.create(url, lifecycleScope)
      jellyfinSessionReporter?.reportPlaybackStart((viewModel.pos ?: 0).toLong() * 1000L)
      startJellyfinProgressLoop()
    }

    // Reset AB loop values when video changes
    viewModel.clearABLoop()

    // Drop the old ambient shader file, but keep the user's ambient preference/style.
    viewModel.prepareAmbientForNewVideo()

    setIntentExtras(intent.extras)

    lifecycleScope.launch(Dispatchers.IO) {
      // Load playback state (will skip track restoration if preferred language configured)
      val hasState =
        loadVideoPlaybackState(
          identifier = loadedMediaIdentifier,
          legacyIdentifier = loadedLegacyIdentifier,
          loadGeneration = loadGeneration,
        )
      if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@launch

      // Apply track selection logic (defaults only apply when no saved state)
      trackSelector.onFileLoaded(hasState)

      // Apply default zoom only if there's no saved state
      if (!hasState) {
        withContext(Dispatchers.Main) {
          if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@withContext
          val zoomPreference = playerPreferences.defaultVideoZoom.get()
          PlaybackSession.setPropertyDouble("video-zoom", zoomPreference.toDouble())
          viewModel.setVideoZoom(zoomPreference)
        }
      }
    }

    // Save to recently played when video actually loads and plays
    lifecycleScope.launch(Dispatchers.IO) {
      if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@launch
      if (loadedPlaylist.isNotEmpty()) {
        // For playlist items, save using the current URI
        // All items are loaded, so playlistIndex is the direct index
        if (loadedPlaylistIndex in loadedPlaylist.indices) {
          saveRecentlyPlayedForUri(loadedPlaylist[loadedPlaylistIndex], loadedFileName)
        } else {
          Log.w(
            TAG,
            "Cannot save recently played: invalid playlist index $loadedPlaylistIndex (playlist size: ${loadedPlaylist.size})",
          )
        }
      } else {
        // For non-playlist videos, use the original saveRecentlyPlayed
        saveRecentlyPlayed()
      }
    }

    // Only set orientation immediately if NOT in Video mode
    // For Video mode, wait for video-params/aspect to become available
    if (playerPreferences.orientation.get() != PlayerOrientation.Video) {
      setOrientation()
    } else {
      // For Video mode, try to set orientation after a short delay to ensure
      // video dimensions are available
      lifecycleScope.launch {
        kotlinx.coroutines.delay(100)
        if (PlaybackSession.isCurrentGeneration(loadGeneration) && mpvInitialized && !player.isExiting && !isFinishing) {
          val aspect = player.getVideoOutAspect()
          Log.d(TAG, "handleFileLoaded - Video mode, aspect after delay: $aspect")
          if (aspect != null && aspect > 0) {
            setOrientation()
          }
        }
      }
    }

    // Audio track information becomes available only after FILE_LOADED. Re-apply
    // orientation once the track list settles so album art is not treated as video.
    lifecycleScope.launch {
      delay(100)
      if (PlaybackSession.isCurrentGeneration(loadGeneration) && mpvInitialized && !player.isExiting && !isFinishing) {
        setOrientation()
      }
    }

    applySubtitlePreferences()
    applyVideoFilterPreferences()
    viewModel.restoreSavedVideoAspect(showUpdate = false)

    if (shouldForceCurrentMediaTitle()) {
      val preferredTitle = getPreferredCurrentTitle()
      PlaybackSession.setPropertyString("force-media-title", preferredTitle)
      viewModel.setMediaTitle(preferredTitle)
    }

    viewModel.unpause()
    if (!holdsAudioFocus) scheduleAudioFocusRetry()
    lifecycleScope.launch {

      withContext(playbackRenderDispatcher) {
        if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@withContext
        player.applyAnime4KShaders()
        viewModel.restartHdrScreenOutputAndAmbientIfActive()
      }
    }

    if (
      subtitlesPreferences.autoEnableSubtitles.get() &&
      subtitlesPreferences.autoloadMatchingSubtitles.get()
    ) {
      lifecycleScope.launch {
        if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@launch
        // For network files played via proxy (SMB/WebDAV/FTP), use the original network file path
        val networkFilePath = loadedIntent.getStringExtra("network_file_path")
        val networkConnectionId = loadedIntent.getLongExtra("network_connection_id", -1L)

        if (networkFilePath != null && networkConnectionId != -1L) {
          // Pass network file path and connection ID for subtitle discovery
          SubtitleOps.autoloadSubtitles(
            videoFilePath = networkFilePath,
            videoFileName = loadedFileName,
            networkConnectionId = networkConnectionId,
            expectedGeneration = loadGeneration,
          )
        } else {
          // Regular file or direct network stream
          val filePath = parsePathFromIntent(loadedIntent)
          if (filePath != null) {
            SubtitleOps.autoloadSubtitles(
              videoFilePath = filePath,
              videoFileName = loadedFileName,
              expectedGeneration = loadGeneration,
            )
          }
        }
      }
    }

    updateMediaSessionMetadata(
      title = fileName,
      durationMs = (PlaybackSession.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L,
    )
    updateMediaSessionPlaybackState(isPlaying = true)
    syncBackgroundPlaybackService(updateThumbnail = true)

    // Asynchronously fetch better filename from HTTP headers for network streams
    fetchNetworkStreamTitle(loadGeneration, loadedIntent, loadedFileName)
  }

  /**
   * Fetches a better title from HTTP headers for network streams asynchronously.
   * Updates the title in UI, MPV, and media session if a better name is found.
   */
  private fun fetchNetworkStreamTitle(
    loadGeneration: Long,
    sourceIntent: Intent,
    originalFileName: String,
  ) {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@launch
        val uri = extractUriFromIntent(sourceIntent)
        if (uri == null || !HttpUtils.isNetworkStream(uri)) {
          return@launch
        }

        // Skip fetching for m3u/m3u8 streams - let MPV provide the title
        if (isCurrentStreamM3U()) {
          Log.d(TAG, "Skipping title fetch for m3u/m3u8 stream: $uri")
          return@launch
        }

        // Skip fetching if title was provided in intent extras (e.g. from Jellyfin or other external launchers)
        // This prevents overwriting the correct title with a generic filename from the URL (like "stream")
        if (sourceIntent.hasExtra("title") || sourceIntent.hasExtra("filename")) {
          Log.d(TAG, "Skipping title fetch because title was explicitly provided in intent")
          return@launch
        }

        // Skip fetching for local proxy URLs (SMB/WebDAV/FTP files)
        // These already have correct filename from intent extras
        val host = uri.host?.lowercase()
        if (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") {
          Log.d(TAG, "Skipping title fetch for local proxy URL: $uri")
          return@launch
        }

        val url = uri.toString()
        Log.d(TAG, "Fetching title from network stream: $url")

        val betterFilename = HttpUtils.extractFilenameFromUrl(url)
        if (betterFilename != null &&
          betterFilename.isNotBlank() &&
          betterFilename != originalFileName &&
          betterFilename != uri.host &&
          betterFilename != "Network Stream" &&
          !HttpUtils.isLikelyJunkTitle(betterFilename)
        ) {
          Log.d(TAG, "Found better filename from HTTP headers: $betterFilename")

          if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@launch
          fileName = betterFilename

          // DO NOT update mediaIdentifier - keep the original identifier for playback state consistency
          // The URI hash in mediaIdentifier ensures position is saved/loaded correctly even if filename changes

          // Update MPV title
          withContext(Dispatchers.Main) {
            if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@withContext
            PlaybackSession.setPropertyString("force-media-title", betterFilename)
            viewModel.setMediaTitle(betterFilename)

            // Update media session
            val durationMs = (PlaybackSession.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
            updateMediaSessionMetadata(
              title = betterFilename,
              durationMs = durationMs,
            )

            syncBackgroundPlaybackService(updateThumbnail = true)
          }

          // Update recently played with the parsed video title, duration, and file size
          val filePath =
            when (uri.scheme) {
              "file" -> uri.path ?: uri.toString()
              "content" -> {
                contentResolver
                  .query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    null,
                    null,
                    null,
                  )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                      val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                      if (columnIndex != -1) cursor.getString(columnIndex) else null
                    } else {
                      null
                    }
                  } ?: uri.toString()
              }

              else -> uri.toString()
            }

          // Get duration and file size from MPV on Main thread
          var updatedDuration = 0L
          var updatedFileSize = 0L
          var updatedWidth = 0
          var updatedHeight = 0
          withContext(Dispatchers.Main) {
            if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@withContext
            updatedDuration =
              runCatching {
                (PlaybackSession.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
              }.getOrDefault(0L)

            updatedFileSize =
              runCatching {
                PlaybackSession.getPropertyDouble("file-size")?.toLong()
                  ?: PlaybackSession.getPropertyDouble("stream-end")?.toLong()
                  ?: 0L
              }.getOrDefault(0L)

            updatedWidth =
              runCatching {
                PlaybackSession.getPropertyInt("width") ?: PlaybackSession.getPropertyInt("video-params/w") ?: 0
              }.getOrDefault(0)

            updatedHeight =
              runCatching {
                PlaybackSession.getPropertyInt("height") ?: PlaybackSession.getPropertyInt("video-params/h") ?: 0
              }.getOrDefault(0)
          }

          // Update metadata without thumbnail
          if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@launch
          runCatching {
            RecentlyPlayedOps.updateVideoMetadata(
              filePath,
              betterFilename,
              updatedDuration,
              updatedFileSize,
              updatedWidth,
              updatedHeight,
            )
            Log.d(
              TAG,
              "Updated recently played metadata for current network item",
            )
          }.onFailure { e ->
            Log.e(TAG, "Error updating video metadata in recently played", e)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error fetching network stream title", e)
      }
    }
  }

  /**
   * Applies all saved subtitle preferences when a file is loaded.
   * This ensures subtitle customizations (font, colors, position, etc.) persist across videos.
   */
  private fun applySubtitlePreferences() {
    val font = subtitlesPreferences.font.get()
    val fontSize = subtitlesPreferences.fontSize.get()
    val bold = subtitlesPreferences.bold.get()
    val italic = subtitlesPreferences.italic.get()
    val justify = subtitlesPreferences.justification.get().value
    val borderStyle = subtitlesPreferences.borderStyle.get().value
    val borderSize = subtitlesPreferences.borderSize.get()
    val shadowOffset = subtitlesPreferences.shadowOffset.get()

    // Color settings
    val textColor = subtitlesPreferences.textColor.get().toColorHexString()
    val borderColor = subtitlesPreferences.borderColor.get().toColorHexString()
    val backgroundColor = subtitlesPreferences.backgroundColor.get().toColorHexString()
    val shadowColor = subtitlesPreferences.shadowColor.get().toColorHexString()

    // Miscellaneous settings
    val scaleByWindow = subtitlesPreferences.scaleByWindow.get()
    val scaleValue = if (scaleByWindow) "yes" else "no"
    val subScale = subtitlesPreferences.subScale.get()
    val blendMode =
      if (subtitlesPreferences.blendSubtitlesWithVideo.get() &&
        playerPreferences.isAmbientEnabled.get()
      ) {
        "video"
      } else {
        "no"
      }

    PlaybackSession.setPropertyString("blend-subtitles", blendMode)

    for (prefix in listOf("sub-", "secondary-sub-")) {
      PlaybackSession.setPropertyString("${prefix}font", font)
      PlaybackSession.setPropertyInt("${prefix}font-size", fontSize)
      PlaybackSession.setPropertyBoolean("${prefix}bold", bold)
      PlaybackSession.setPropertyBoolean("${prefix}italic", italic)
      PlaybackSession.setPropertyString("${prefix}justify", justify)
      PlaybackSession.setPropertyString("${prefix}border-style", borderStyle)
      PlaybackSession.setPropertyInt("${prefix}border-size", borderSize)
      PlaybackSession.setPropertyInt("${prefix}outline-size", borderSize)
      PlaybackSession.setPropertyInt("${prefix}shadow-offset", shadowOffset)
      PlaybackSession.setPropertyString("${prefix}color", textColor)
      PlaybackSession.setPropertyString("${prefix}border-color", borderColor)
      PlaybackSession.setPropertyString("${prefix}back-color", backgroundColor)
      PlaybackSession.setPropertyString("${prefix}shadow-color", shadowColor)
      PlaybackSession.setPropertyString("${prefix}scale-by-window", scaleValue)
      PlaybackSession.setPropertyString("${prefix}use-margins", scaleValue)
      PlaybackSession.setPropertyFloat("${prefix}scale", subScale)
    }

    applySubtitleLayout(
      primaryPosition = subtitlesPreferences.subPos.get(),
      forceAssOverride = subtitlesPreferences.overrideAssSubs.get(),
      screenWidth = player.width.takeIf { it > 0 }?.toFloat(),
      screenHeight = player.height.takeIf { it > 0 }?.toFloat(),
    )

    Log.d(TAG, "Applied subtitle preferences")
  }

  /**
   * Applies saved video filter preferences (brightness, contrast, etc.) when a file is loaded.
   */
  private fun applyVideoFilterPreferences() {
    if (viewModel.isAudioOnly.value || isCurrentMediaKnownAudio()) return
    VideoFilters.entries.forEach {
      PlaybackSession.setPropertyInt(it.mpvProperty, it.preference(decoderPreferences).get())
    }
    Log.d(TAG, "Applied video filter preferences")
  }

  /**
   * Helper extension function to convert Int color to hex string for MPV
   */
  private fun Int.toColorHexString(): String {
    val a = (this shr 24 and 0xFF).toString(16).padStart(2, '0')
    val r = (this shr 16 and 0xFF).toString(16).padStart(2, '0')
    val g = (this shr 8 and 0xFF).toString(16).padStart(2, '0')
    val b = (this and 0xFF).toString(16).padStart(2, '0')
    return "#$a$r$g$b".uppercase()
  }

  private fun canIssueMpvCommands(): Boolean = mpvInitialized && !player.isExiting && !isDestroyed

  /**
   * Saves the current playback state to the database.
   *
   * Captures MPV state synchronously, then persists on a background dispatcher.
   * This avoids shutdown races with MPV destruction and collapses duplicate writes.
   *
   * @param mediaTitle The title of the media being played
   */
  private fun saveVideoPlaybackState(
    mediaTitle: String,
    immediate: Boolean = false,
    identifierOverride: String? = null,
  ) {
    val snapshot = capturePlaybackStateSnapshot(mediaTitle, identifierOverride) ?: return

    // Cancel any previous pending save operation
    savePlaybackStateJob?.cancel()

    val saveBlock: suspend kotlinx.coroutines.CoroutineScope.() -> Unit = {
      runCatching {
        if (!immediate) {
          delay(250)
        }

        val oldState = playbackStateRepository.getVideoDataByTitle(snapshot.mediaIdentifier)
        Log.d(TAG, "Saving playback state for: ${snapshot.mediaTitle} (identifier: ${snapshot.mediaIdentifier})")

        val playbackState =
          PlaybackStatePersistence.buildEntity(
            oldState = oldState,
            snapshot = snapshot,
            savePositionOnQuit = playerPreferences.savePositionOnQuit.get(),
            watchedThreshold = browserPreferences.watchedThreshold.get(),
          )
        playbackStateRepository.upsert(playbackState)
        PlaybackStateEvents.notifyChanged(snapshot.mediaIdentifier)
      }.onFailure { e ->
        Log.e(TAG, "Error saving playback state", e)
      }
    }

    if (immediate) {
      // Activity lifecycle scopes can be cancelled as the window is destroyed. Complete the small
      // Room write before returning from onPause/onDestroy so process death cannot drop the final
      // timestamp. This follows the service's existing blocking-save path.
      runBlocking(Dispatchers.IO) { saveBlock() }
    } else {
      // Launch new save job and track it
      savePlaybackStateJob = lifecycleScope.launch(Dispatchers.IO, block = saveBlock)
    }
  }

  private fun startJellyfinProgressLoop() {
    jellyfinProgressJob?.cancel()
    jellyfinProgressJob =
      lifecycleScope.launch {
        while (isActive) {
          delay(10000) // Report progress every 10 seconds
          val reporter = jellyfinSessionReporter ?: continue
          val currentPosMs = (viewModel.pos ?: 0).toLong() * 1000L
          val isPaused = viewModel.paused ?: false
          reporter.reportPlaybackProgress(currentPosMs, isPaused)
        }
      }
  }

  private fun reportJellyfinStop() {
    jellyfinProgressJob?.cancel()
    jellyfinProgressJob = null
    jellyfinSessionReporter?.let { reporter ->
      val currentPosMs = (viewModel.pos ?: 0).toLong() * 1000L
      reporter.reportPlaybackStop(currentPosMs)
      jellyfinSessionReporter = null
    }
  }

  private fun capturePlaybackStateSnapshot(
    mediaTitle: String,
    identifierOverride: String? = null,
  ): PlaybackStateSnapshot? {
    val media3Active = playbackEngine == PlaybackEngine.MEDIA3
    // Native saves must use the item that owns the active Media3 controller. During queue startup,
    // PlaybackSession can briefly still expose the previous item, and mediaIdentifier can still
    // describe the launch intent. media3ActiveItem is assigned at the handoff boundary and is the
    // authoritative identity for the visible Native player.
    val activeItemIdentifier =
      (media3ActiveItem ?: activePlaybackItem ?: currentPlaybackItem())?.stableId
        ?.takeIf { it.isNotBlank() }
    val identifier =
      identifierOverride?.takeIf { it.isNotBlank() }
        ?: if (media3Active) activeItemIdentifier ?: mediaIdentifier else mediaIdentifier
    if (identifier.isBlank()) return null

    val currentPositionSeconds =
      if (media3Active) {
        (readMedia3PositionMs() / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      } else {
        readMpvIntSeconds("time-pos", viewModel.pos ?: 0)
      }
    val durationSeconds =
      if (media3Active) {
        (readMedia3DurationMs() / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      } else {
        readMpvIntSeconds("duration", viewModel.duration ?: 0)
      }

    return PlaybackStateSnapshot(
      mediaIdentifier = identifier,
      mediaTitle = mediaTitle,
      currentPosition = currentPositionSeconds,
      duration = durationSeconds,
      playbackSpeed = PlaybackSession.getPropertyDouble("speed") ?: DEFAULT_PLAYBACK_SPEED,
      videoZoom = PlaybackSession.getPropertyDouble("video-zoom")?.toFloat() ?: viewModel.videoZoom.value,
      sid = player.sid,
      secondarySid = player.secondarySid,
      subDelayMs = ((PlaybackSession.getPropertyDouble("sub-delay") ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt(),
      subSpeed = PlaybackSession.getPropertyDouble("sub-speed") ?: DEFAULT_SUB_SPEED,
      aid = player.aid,
      audioDelayMs = ((PlaybackSession.getPropertyDouble("audio-delay") ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt(),
      externalSubtitles = viewModel.externalSubtitles.joinToString("|"),
    )
  }

  private fun readMpvIntSeconds(
    property: String,
    fallback: Int,
  ): Int =
    runCatching {
      PlaybackSession.getPropertyDouble(property)?.toInt()
        ?: PlaybackSession.getPropertyInt(property)
        ?: fallback
    }.getOrDefault(fallback)

  private fun readMedia3PositionMs(): Long {
    val livePositionMs =
      runCatching { media3PlaybackController.positionForEngineHandoffMs() }
        .getOrDefault(0L)
        .coerceAtLeast(0L)
    val cachedPositionMs = cachedMedia3State.positionMs.coerceAtLeast(0L)
    return if (livePositionMs > 0L || cachedPositionMs <= 0L) livePositionMs else cachedPositionMs
  }

  private fun readMedia3DurationMs(): Long {
    val liveDurationMs = runCatching { media3PlaybackController.currentState().durationMs }
      .getOrDefault(0L)
      .coerceAtLeast(0L)
    val cachedDurationMs = cachedMedia3State.durationMs.coerceAtLeast(0L)
    return maxOf(liveDurationMs, cachedDurationMs)
  }

  /**
   * Loads and applies saved playback state from the database.
   *
   * @param mediaTitle The title of the media being played
   * @return true if saved state was found and applied, false otherwise
   */
  private suspend fun loadVideoPlaybackState(
    identifier: String,
    legacyIdentifier: String?,
    loadGeneration: Long,
  ): Boolean {
    if (identifier.isBlank() || !PlaybackSession.isCurrentGeneration(loadGeneration)) return false

    return runCatching {
      var state = playbackStateRepository.getVideoDataByTitle(identifier)
      if (state == null) {
        val legacyKey = legacyIdentifier?.takeIf { it.isNotBlank() && it != identifier }
        // Only migrate legacy records whose key is collision-resistant (e.g. contains a
        // URI hash like "name_123456" for remote files). Bare filenames used by older
        // versions for local files are ambiguous — two files in different directories
        // share the same display name, so migrating would steal one file's state.
        val isCollisionResistant = legacyKey != null && legacyKey.contains('_')
        val legacyState = legacyKey
          ?.takeIf { isCollisionResistant }
          ?.let { playbackStateRepository.getVideoDataByTitle(it) }
        if (legacyState != null) {
          val migratedState = legacyState.copy(mediaTitle = identifier)
          state = migratedState
          playbackStateRepository.upsert(migratedState)
          Log.d(TAG, "Migrated playback state to collision-resistant media identifier")
        }
      }

      if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@runCatching false

      applyPlaybackState(state)

      if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@runCatching false

      withContext(Dispatchers.Main) {
        if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@withContext
        applyDefaultSettings(state)
      }
      if (pendingQueueTransitionStartAtZero &&
        (pendingQueueTransitionItemId == null || pendingQueueTransitionItemId == mediaIdentifier)
      ) {
        pendingQueueTransitionStartAtZero = false
        pendingQueueTransitionItemId = null
      }
      state != null

    }.onFailure { e ->
      Log.e(TAG, "Error loading playback state", e)
    }.getOrDefault(false)
  }

  /**
   * Applies saved playback state to MPV.
   *
   * Restores subtitle delay, audio delay, audio and track selections, and playback speed.
   * Also restores saved time position if enabled. Explicit player state remains authoritative.
   *
   * @param state The saved playback state entity
   */
  private suspend fun applyPlaybackState(state: PlaybackStateEntity?) {
    if (state == null) return

    val subDelay = state.subDelay / DELAY_DIVISOR
    val audioDelay = state.audioDelay / DELAY_DIVISOR

    // Restore external subtitles first
    if (state.externalSubtitles.isNotBlank()) {
      val externalSubUris = state.externalSubtitles.split("|").filter { it.isNotBlank() }
      Log.d(TAG, "Restoring ${externalSubUris.size} external subtitle(s)")

      for (subUri in externalSubUris) {
        viewModel.addSubtitleSuspend(Uri.parse(subUri), select = false, silent = true)
      }
    }

    // Always restore subtitle and audio tracks from saved state
    // User's manual selection has highest priority
    if (state.sid > 0) {
      player.sid = state.sid
      Log.d(TAG, "Restored primary subtitle track: ${state.sid} (user selection)")
    }

    if (state.secondarySid > 0) {
      player.secondarySid = state.secondarySid
      Log.d(TAG, "Restored secondary subtitle track: ${state.secondarySid} (user selection)")
    }

    applySubtitleLayout(
      primaryPosition = subtitlesPreferences.subPos.get(),
      forceAssOverride = subtitlesPreferences.overrideAssSubs.get(),
      screenWidth = player.width.takeIf { it > 0 }?.toFloat(),
      screenHeight = player.height.takeIf { it > 0 }?.toFloat(),
    )

    if (state.aid > 0) {
      player.aid = state.aid
      Log.d(TAG, "Restored audio track: ${state.aid} (user selection)")
    }

    PlaybackSession.setPropertyDouble("sub-delay", subDelay)
    PlaybackSession.setPropertyDouble("speed", state.playbackSpeed)
    // Re-apply audio-pitch-correction after speed change, as mpv resets it to default
    PlaybackSession.setPropertyBoolean("audio-pitch-correction", audioPreferences.audioPitchCorrection.get())
    PlaybackSession.setPropertyDouble("audio-delay", audioDelay)
    PlaybackSession.setPropertyDouble("sub-speed", state.subSpeed)

    // Restore video zoom from saved state
    PlaybackSession.setPropertyDouble("video-zoom", state.videoZoom.toDouble())
    viewModel.setVideoZoom(state.videoZoom)

    if (!pendingQueueTransitionStartAtZero &&
      playerPreferences.savePositionOnQuit.get() &&
      state.lastPosition != 0 &&
      !viewModel.isAudioOnly.value &&
      !isCurrentMediaKnownAudio()
    ) {
      if (playbackEngine == PlaybackEngine.MEDIA3 && cachedMedia3State.playbackState != Player.STATE_IDLE) {
        withContext(Dispatchers.Main.immediate) {
          if (playbackEngine == PlaybackEngine.MEDIA3) {
            media3PlaybackController.seekTo(state.lastPosition * 1000L, fast = false)
          }
        }
      } else {
        PlaybackSession.setPropertyInt("time-pos", state.lastPosition)
      }
    }
  }

  /**
   * Applies default settings when no saved state exists.
   *
   * Sets subtitle speed to user default if not present in saved state.
   *
   * @param state The saved playback state entity (null if no saved state)
   */
  private fun applyDefaultSettings(state: PlaybackStateEntity?) {
    if (state == null) {
      val defaultSubSpeed = subtitlesPreferences.defaultSubSpeed.get().toDouble()
      PlaybackSession.setPropertyDouble("sub-speed", defaultSubSpeed)
    }
  }

  /**
   * Saves the currently playing file to recently played history.
   *
   * Handles various URI schemes and infers launch source.
   */
  private suspend fun saveRecentlyPlayed() {
    runCatching {
      val uri = extractUriFromIntent(intent)

      if (uri == null) {
        Log.w(TAG, "Cannot save recently played: URI is null")
        return@runCatching
      }

      if (uri.scheme == null) {
        Log.w(TAG, "Cannot save recently played: URI has null scheme: $uri")
        return@runCatching
      }

      if (isTorrentSource(uri.toString(), intent.type)) {
        // Torrent files have their own durable, per-file catalog in the Network tab.
        return@runCatching
      }

      val filePath =
        when (uri.scheme) {
          "file" -> {
            uri.path ?: uri.toString()
          }

          "content" -> {
            contentResolver
              .query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else {
                  null
                }
              } ?: uri.toString()
          }

          else -> {
            uri.toString()
          }
        }

      val launchSource =
        when {
          intent.getStringExtra("launch_source") != null -> intent.getStringExtra("launch_source")
          intent.action == Intent.ACTION_SEND -> "share"
          else -> "normal"
        }

      // Prioritize intent title first if provided and valid
      val intentTitle = intent.getStringExtra("title")

      // Get parsed video title from MPV
      val mpvTitle =
        runCatching {
          PlaybackSession.getPropertyString("media-title")
        }.getOrNull()

      val videoTitle =
        when {
          !HttpUtils.isLikelyJunkTitle(intentTitle) -> intentTitle
          !HttpUtils.isLikelyJunkTitle(mpvTitle) && mpvTitle != fileName -> mpvTitle
          else -> null
        }

      // Get duration and file size from MPV
      val duration =
        runCatching {
          (PlaybackSession.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
        }.getOrDefault(0L)

      val fileSize =
        runCatching {
          // Try multiple properties to get file size
          PlaybackSession.getPropertyDouble("file-size")?.toLong()
            ?: PlaybackSession.getPropertyDouble("stream-end")?.toLong()
            ?: 0L
        }.getOrDefault(0L)

      // Get video resolution from MPV
      val width =
        runCatching {
          PlaybackSession.getPropertyInt("width") ?: PlaybackSession.getPropertyInt("video-params/w") ?: 0
        }.getOrDefault(0)

      val height =
        runCatching {
          PlaybackSession.getPropertyInt("height") ?: PlaybackSession.getPropertyInt("video-params/h") ?: 0
        }.getOrDefault(0)

      // Secure Folder playback should never surface in Recents/playback-history — that would
      // defeat the point of hiding the file in the first place.
      if (isSecureFolderLaunch) {
        Log.d(TAG, "Skipping recently-played save for secure_folder launch: $filePath")
        return@runCatching
      }

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = fileName,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = launchSource,
      )

      Log.d(TAG, "Saved recently played: $filePath")
      Log.d(TAG, "  - fileName: $fileName")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x$height")
      Log.d(TAG, "  - source: $launchSource")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played", e)
    }
  }

  // ==================== Intent and Result Management ====================

  /**
   * Sets the result intent with current playback position and duration.
   * Called when activity is finishing to return data to caller.
   */
  private fun setReturnIntent() {
    Log.d(TAG, "Setting return intent")

    val action =
      if ((callingPackage != null && callingPackage != packageName) ||
        intent.getBooleanExtra("return_result", false)
      ) {
        "is.xyz.mpv.MPVActivity.result"
      } else {
        RESULT_INTENT
      }

    val resultIntent =
      Intent(action).apply {
        viewModel.pos?.let { putExtra("position", it * MILLISECONDS_TO_SECONDS) }
        viewModel.duration?.let { putExtra("duration", it * MILLISECONDS_TO_SECONDS) }
      }

    setResult(RESULT_OK, resultIntent)
  }

  /**
   * Handles new intents to load a different file without recreating the activity.
   *
   * @param intent The new intent
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)

    // Transport intents control the existing session and must not replace its media/source intent.
    when (intent.action) {
      MediaPlaybackService.ACTION_NOTIFICATION_PREVIOUS -> {
        playPreviousQueueItem()
        return
      }
      MediaPlaybackService.ACTION_NOTIFICATION_NEXT -> {
        playNextQueueItem()
        return
      }
      MediaPlaybackService.ACTION_OPEN_PLAYER -> {
        isBackgroundPlaybackSessionActive = false
        pendingBackgroundTransition = false
        attachToCurrentPlaybackSessionIfRequested(intent)
        PlaybackSession.markForeground()
        val reopenedItem = PlaybackSession.queue.value.currentItem
        val reopenedAudio =
          isKnownAudioLaunch(intent) ||
            viewModel.isAudioOnly.value ||
            reopenedItem?.let(::isAudioPlaybackItem) == true
        isReady = PlaybackSession.state.value.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
        if (isReady && !reopenedAudio) viewModel.onVideoLoadCompleted()
        if (isBackgroundPlaybackEnabled() || reopenedAudio) {
          if (!serviceBound || mediaPlaybackService == null) {
            startBackgroundPlaybackInternal(bindToActivity = true)
          }
          syncBackgroundPlaybackService(updateThumbnail = true)
        } else if (!reopenedAudio) {
          // Audio opened from the compact player must keep its existing foreground service alive
          // regardless of the saved background-playback preference. The arrow is a minimize
          // action; the preference controls automatic background entry, not this explicit handoff.
          endBackgroundPlayback()
        } else {
          MediaPlaybackService.prepareForActivityHandoff()
          MediaPlaybackService.relinquishMediaSessionToActivity()
          setActivityMediaSessionActive(true)
        }
        return
      }
    }

    if (redirectUnselectedTorrentToPicker(intent, finishCurrent = false)) return

    // A browser may replace the process queue before this singleTask Activity receives its Intent.
    // Snapshot what this Activity actually has loaded before installing any incoming metadata.
    val previouslyLoadedIdentifier = mediaIdentifier
    val previouslyLoadedUri =
      playlist.getOrNull(playlistIndex)?.toString()
        ?: extractUriFromIntent(this.intent)?.toString()
    val previouslyLoadedTorrentFileIndex = this.intent.getIntExtra("torrent_file_index", -1)
    val previousItemWasReady = isReady

    setIntent(intent)
    viewModel.setAudioOnlyLaunchHint(isKnownAudioLaunch(intent))
    // A direct/new-item intent is not queue navigation, so it may use that item's own saved state.
    pendingQueueTransitionStartAtZero = false
    pendingQueueTransitionItemId = null
    mediaRequestGeneration++
    pendingSavedPlaylistSelection = null
    if (isKnownAudioLaunch(intent)) setOrientation()

    isBackgroundPlaybackSessionActive = false
    pendingBackgroundTransition = false
    handledPipDismissal = false
    if (!isBackgroundPlaybackEnabled() && (serviceBound || mediaPlaybackService != null || MediaPlaybackService.isRunning())) {
      endBackgroundPlayback()
    }

    // Recompute from the new intent — this activity is singleTask, so opening a different file
    // reuses this same instance via onNewIntent instead of a fresh onCreate. Doing this here
    // (after the notification prev/next and background-resume branches already returned above)
    // means it only changes when genuinely new media is being loaded, not on every onNewIntent
    // call, so a stale true/false from the previous file never leaks into the next one.
    isSecureFolderLaunch = intent.getStringExtra("launch_source") == "secure_folder"

    // Check if this intent has playlist information
    val hasPlaylistExtras =
      intent.hasExtra("playlist_id") ||
        intent.hasExtra("playlist")

    // Load playlist from intent extras first (fast path)
    val playlistFromIntent =
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra("playlist") ?: emptyList()
      }

    val preparedPlaybackQueue =
      playlistFromIntent.isEmpty() && restorePreparedPlaybackQueue(intent)

    if (preparedPlaybackQueue) {
      viewModel.refreshPlaylistItems()
    } else if (hasPlaylistExtras || playlistFromIntent.isNotEmpty()) {
      val newPlaylistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
      playlistId = newPlaylistId
      playlistIndex = intent.getIntExtra("playlist_index", 0)
      playlistWindowOffset = 0
      playlistTotalCount = playlistFromIntent.size.takeIf { it > 0 } ?: -1
      playlist = playlistFromIntent
      playlistItems = emptyList()
      playlistEntity = null
      isM3uPlaylist = false
      loadNetworkPlaylistMetadata(intent)
      if (playlist.isNotEmpty()) {
        playlistIndex = playlistIndex.coerceIn(0, playlist.lastIndex)
        publishPlaylistToSession()
        viewModel.refreshPlaylistItems()
      }
    } else {
      // A genuine standalone media intent replaces the old queue. Notification actions returned
      // above, so they can never accidentally clear it.
      playlistId = null
      playlistIndex = 0
      playlistWindowOffset = 0
      playlistTotalCount = -1
      playlist = emptyList()
      playlistItems = emptyList()
      playlistEntity = null
      isM3uPlaylist = false
      networkPlaylistPaths = emptyList()
      networkPlaylistTitles = emptyList()
      networkPlaylistHeaders = emptyList()
      networkPlaylistConnectionId = -1L
      TemporaryPlaybackQueue.clear()
    }

    // If playlist is empty but playlist_id is provided, load from database
    if (playlist.isEmpty() && playlistId != null) {
      lifecycleScope.launch(Dispatchers.IO) {
        val pid = playlistId ?: return@launch
        try {
          loadPlaylistById(
            pid = pid,
            sourceIntent = intent,
            logPrefix = "onNewIntent: Loaded",
          )
        } catch (e: Exception) {
          Log.e(TAG, "onNewIntent: Failed to load playlist from database", e)
        }
      }
    }

    // Auto-generate a folder queue for playlist-mode launches. When scoped storage leaves us
    // with only a content:// URI, use MediaStore metadata instead of passing fd:// to File().
    // A validated temporary queue is already the user’s complete, editable queue; never replace it
    // with siblings from the first item’s folder.
    if (playlist.isEmpty() && playlistId == null && playerPreferences.playlistMode.get() && !preparedPlaybackQueue) {
      val isExternalContentMediaLaunch =
        intent.action == Intent.ACTION_VIEW &&
          intent.data?.scheme in setOf(ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE)
      externalContentLaunchUri =
        if (isExternalContentMediaLaunch) intent.data else null
      val path = parsePathFromIntent(intent)
      val sourceUri = externalContentLaunchUri ?: extractUriFromIntent(intent)
      val localPath =
        path?.takeIf { File(it).isFile }
          ?: sourceUri
            ?.takeIf { it.scheme == "content" }
            ?.resolveLocalFilePath(this)
            ?.takeIf { File(it).isFile }
      if (localPath != null) {
        generatePlaylistFromFolder(localPath)
      } else if (sourceUri?.scheme == "content") {
        generatePlaylistFromMediaStore(sourceUri)
      }
    }

    // Extract the new fileName before loading the file
    fileName = getFileName(intent)
    if (fileName.isBlank()) {
      fileName = intent.data?.lastPathSegment ?: "Unknown Video"
    }
    legacyMediaIdentifier = getLegacyMediaIdentifier(intent, fileName)
    mediaIdentifier = getMediaIdentifier(intent, fileName)

    // Set HTTP headers (including referer) BEFORE loading the new file
    setHttpHeadersFromExtras(intent.extras)

    // Load the new file — but skip reload if the same item is already playing
    getPlayableUri(intent)?.let { uri ->
      // If the requested song is the same URI that's already loaded (e.g. user tapped the
      // currently-playing song from the Songs tab), don't restart from position 0.
      val incomingOriginalUri = extractUriFromIntent(intent)?.toString()
      val incomingTorrentFileIndex = intent.getIntExtra("torrent_file_index", -1)
      val incomingIsTorrent = isTorrentSource(incomingOriginalUri ?: uri, intent.type)
      val alreadyPlayingThisItem =
        previousItemWasReady &&
          if (incomingIsTorrent) {
            incomingOriginalUri != null &&
              incomingOriginalUri == previouslyLoadedUri &&
              incomingTorrentFileIndex == previouslyLoadedTorrentFileIndex
          } else {
            previouslyLoadedIdentifier.isNotBlank() && previouslyLoadedIdentifier == mediaIdentifier ||
              (incomingOriginalUri != null && incomingOriginalUri == previouslyLoadedUri)
          }

      if (alreadyPlayingThisItem) {
        Log.d(TAG, "onNewIntent: same item already playing, skipping reload")
        // Just ensure the player is visible
        if (isBackgroundPlaybackEnabled()) {
          syncBackgroundPlaybackService(updateThumbnail = false)
        }
        return@let
      }

      // Remind user if they forgot to set up yt-dlp
      if (uri.startsWith("http") && !uri.substringAfterLast('/').contains('.')) {
        val ytdlDir = YtdlpManager.getYtdlDir(this)
        if (!File(ytdlDir, "yt-dlp").exists()) {
          viewModel.showToast(getString(R.string.toast_need_ytdl))
        }
      }

      currentPlayableUri = uri
      isReady = false
      viewModel.onVideoLoadStarted()
      val originalUri = extractUriFromIntent(intent)
      val shouldExpandM3u =
        M3uPlaybackPolicy.shouldExpandInApp(
          playableUri = uri,
          originalUri = originalUri?.toString(),
          fileName = fileName,
          mimeType = intent.type,
          hasExistingPlaylist = playlist.isNotEmpty(),
          hasPlaylistId = playlistId != null,
        )
      if (shouldExpandM3u) {
        startMediaLoad(
          playableUri = uri,
          originalUri = originalUri?.toString(),
          expandM3u = true,
          disableVideoOnFallback = true,
        )
      } else {
        startMediaLoad(uri, originalUri?.toString())
      }
    }
  }

  private fun startMediaLoad(
    playableUri: String,
    originalUri: String? = null,
    expandM3u: Boolean = false,
    disableVideoOnFallback: Boolean = false,
  ) {
    mediaLoadJob?.cancel()
    playWhenFileLoaded = true
    val sourceIntent = Intent(intent)
    val requestedFileName = fileName
    val requestedMediaIdentifier = mediaIdentifier
    val requestedPlaylistIndex = playlistIndex
    val requestedQueueItem = PlaybackSession.queue.value.items.getOrNull(requestedPlaylistIndex)
    val requestGeneration = mediaRequestGeneration
    val requestedSource = originalUri ?: extractUriFromIntent(sourceIntent)?.toString() ?: playableUri
    val requestedHeaders =
      buildPlaybackHeaders(
        Uri.parse(requestedSource),
        PlaybackHttpHeaders.fromFlatPairs(sourceIntent.extras?.getStringArray("headers")),
        requestedQueueItem?.headers.orEmpty(),
      )
    val requestedTorrentFileIndex = sourceIntent.getIntExtra("torrent_file_index", -1).takeIf { it >= 0 }
    val isTorrentRequest =
      isTorrentSource(requestedSource, sourceIntent.type) || isTorrentSource(playableUri, sourceIntent.type)
    mediaLoadJob =
      lifecycleScope.launch(mediaLoadDispatcher) {
        try {
          if (!isTorrentRequest) torrentStreamingEngine.stopStream()
          if (isTorrentRequest && !advancedPreferences.enableP2pStreaming.get()) {
            torrentStreamingEngine.stopStream()
            playWhenFileLoaded = false
            withContext(Dispatchers.Main) {
              viewModel.onVideoLoadCompleted()
              viewModel.showToast(getString(R.string.toast_torrent_streaming_disabled))
            }
            return@launch
          }

          if (expandM3u && loadDynamicM3uPlaylist(originalUri ?: playableUri, sourceIntent)) {
            withContext(Dispatchers.Main) {
              if (playlist.isNotEmpty()) {
                loadPlaylistItem(playlistIndex.coerceIn(0, playlist.lastIndex))
              }
            }
            return@launch
          }

          var resolvedPlayableUri = playableUri
          var resolvedOriginalUri = requestedSource
          var resolvedFileName = requestedFileName
          var resolvedMediaIdentifier = requestedMediaIdentifier
          var resolvedMimeType = sourceIntent.type

          if (isTorrentRequest) {
            val result =
              torrentStreamingEngine.startStream(
                TorrentStreamRequest(
                  source = requestedSource,
                  fileIndex = requestedTorrentFileIndex,
                  preparationId = sourceIntent.getStringExtra("torrent_preparation_id"),
                ),
              )
            coroutineContext.ensureActive()
            if (requestGeneration != mediaRequestGeneration) {
              throw CancellationException("Torrent request was replaced")
            }
            resolvedPlayableUri = result.localUrl
            resolvedOriginalUri = result.source
            resolvedFileName = result.selectedFile.name
            resolvedMimeType = result.selectedFile.mimeType
            resolvedMediaIdentifier = PlaybackIdentity.forTorrent(result.infoHash, result.selectedFile.index)

            try {
              networkStreamEntryRepository.replaceTorrentFiles(
                canonicalSourceUri = result.source,
                infoHash = result.infoHash,
                files =
                  result.playableFiles.map { file ->
                    NetworkStreamEntryRepository.TorrentFile(
                      index = file.index,
                      path = file.path,
                      name = file.name,
                      size = file.size,
                    )
                  },
              )
            } catch (cancellation: CancellationException) {
              throw cancellation
            } catch (error: Exception) {
              Log.e(TAG, "Failed to persist torrent file catalog", error)
            }
            coroutineContext.ensureActive()
            if (requestGeneration != mediaRequestGeneration) {
              throw CancellationException("Torrent request was replaced")
            }

            withContext(Dispatchers.Main) {
              fileName = resolvedFileName
              legacyMediaIdentifier = null
              mediaIdentifier = resolvedMediaIdentifier
              currentPlayableUri = resolvedPlayableUri
              intent.setDataAndType(Uri.parse(result.source), result.selectedFile.mimeType)
              intent.putExtra("title", result.selectedFile.name)
              intent.putExtra("torrent_file_index", result.selectedFile.index)
              intent.putExtra("is_audio", result.selectedFile.mimeType.startsWith("audio/"))
            }
          }

          // Tear down the outgoing video track before replacing the file.
          restoreVideoTrackAfterFileLoad = !disableVideoOnFallback
          PlaybackSession.setPropertyString("vid", "no")
          val networkPath = sourceIntent.getStringExtra("network_file_path")
          val networkConnectionId = sourceIntent.getLongExtra("network_connection_id", -1L)
          val networkSource =
            if (!networkPath.isNullOrBlank() && networkConnectionId != -1L) {
              NetworkPlaybackSource(networkConnectionId, networkPath)
            } else {
              null
            }
          // Do not open a large local Matroska file with MediaExtractor before playback. That
          // preflight was on the critical path and made ordinary Media3/MPV startup take seconds.
          // Explicit DV markers are cheap to recognize here; unlabelled DV is detected after
          // playback begins by observeAutomaticDolbyVisionEngine() from the actual MPV track.
          val probedDolbyVisionMime =
            if (
              decoderPreferences.playbackEngine.get() == PlaybackEngineMode.Auto &&
                !resolvedMimeType.orEmpty().equals("video/dolby-vision", ignoreCase = true) &&
                isDolbyVisionSourceHint(resolvedFileName, resolvedOriginalUri, resolvedPlayableUri)
            ) {
              // Prefer the resolved playable path. Probe the original URI only when the resolved
              // path did not identify Dolby Vision, avoiding two extractor opens for normal files.
              var detectedDolbyVisionMime: String? = null
              for (source in
                listOf(resolvedPlayableUri)
                  .filter { candidate ->
                    val scheme = Uri.parse(candidate).scheme?.lowercase()
                    scheme == null || scheme == "file" || scheme == "content"
                  }
                  .distinct()) {
                detectedDolbyVisionMime = probeDolbyVisionMimeCached(source)
                if (detectedDolbyVisionMime != null) break
              }
              detectedDolbyVisionMime
            } else {
              null
            }
          if (probedDolbyVisionMime != null) {
            resolvedMimeType = probedDolbyVisionMime
            AppDebugLog.info(
              TAG,
              "Auto engine preflight detected Dolby Vision source=$resolvedOriginalUri",
            )
          }
          val item =
            if (!isTorrentRequest) {
              requestedQueueItem?.copy(
                playableUri = resolvedPlayableUri,
                mimeType = resolvedMimeType ?: requestedQueueItem.mimeType,
                headers = requestedHeaders,
              )
            } else {
              null
            }
              ?: PlaybackItem(
                stableId = resolvedMediaIdentifier.ifBlank { PlaybackIdentity.forUri(resolvedOriginalUri) },
                originalUri = resolvedOriginalUri,
                playableUri = resolvedPlayableUri,
                title = resolvedFileName,
                mimeType = resolvedMimeType,
                headers = requestedHeaders,
                networkSource = networkSource,
              )
          val isAudioItem = isAudioPlaybackItem(item)
          val cookieSource =
            sequenceOf(resolvedPlayableUri, resolvedOriginalUri)
              .firstOrNull { value -> value.startsWith("http://", true) || value.startsWith("https://", true) }
          if (cookieSource != null) {
            androidCookieJar
              .exportForPlayback(cookieSource, AndroidCookieJar.playbackCookieFile(this@PlayerActivity))
              .onFailure { error -> Log.w(TAG, "Failed to prepare playback cookies", error) }
          }
          withContext(Dispatchers.Main) {
            if (requestGeneration == mediaRequestGeneration) {
              fileName = item.title.orEmpty().ifBlank { getFileNameFromUri(Uri.parse(item.originalUri)) }
              legacyMediaIdentifier = null
              mediaIdentifier = item.stableId
              currentPlayableUri = item.playableUri
              intent.putExtra("title", fileName)
              intent.putExtra("media_identifier", item.stableId)
              intent.putExtra("is_audio", isAudioItem)
              intent.putExtra("media_library_audio", isAudioItem)
              intent.setDataAndType(Uri.parse(item.originalUri), item.mimeType)
              viewModel.setMediaTitle(fileName)
              if (isAudioItem) {
                viewModel.onAudioLoadStarted()
              } else {
                viewModel.onVideoLoadStarted()
              }
            }
          }
          // Only collapse to a singleton if the asynchronous folder generator has not already
          // published a multi-item queue or is still discovering the folder. Torrent requests
          // intentionally remain singleton.
          val folderDiscoveryPending = folderDiscoveryInFlightGeneration == requestGeneration
          if (((requestedQueueItem == null && PlaybackSession.queue.value.items.size <= 1) && !folderDiscoveryPending) || isTorrentRequest) {
            PlaybackSession.replaceQueue(listOf(item), 0)
          }
          if (shouldUseMedia3(item)) {
            withContext(Dispatchers.Main) {
              if (requestGeneration == mediaRequestGeneration) syncPlaybackEngine(item)
            }
          } else {
            PlaybackSession.load(item)
          }
        } catch (error: CancellationException) {
          throw error
        } catch (error: Exception) {
          playWhenFileLoaded = false
          isAdvancingAtEof = false
          Log.e(TAG, "Failed to load media URL", error)
          withContext(Dispatchers.Main) {
            viewModel.onVideoLoadCompleted()
            val message =
              if (isTorrentRequest && error is TorrentStreamException) {
                error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.toast_playback_load_failed)
              } else {
                getString(R.string.toast_playback_load_failed)
              }
            viewModel.showToast(message)
          }
        }
      }
  }

  private fun redirectUnselectedTorrentToPicker(
    sourceIntent: Intent,
    finishCurrent: Boolean,
  ): Boolean {
    if (
      sourceIntent.getIntExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, -1) >= 0 ||
      !sourceIntent.getStringExtra(MediaUtils.EXTRA_TORRENT_PREPARATION_ID).isNullOrBlank()
    ) {
      return false
    }
    val source = extractUriFromIntent(sourceIntent)?.toString()?.trim().orEmpty()
    if (!isTorrentSource(source, sourceIntent.type)) return false

    val pickerIntent =
      Intent(sourceIntent).apply {
        setClass(this@PlayerActivity, TorrentSelectionActivity::class.java)
        putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, source)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    torrentPickerHandoff = finishCurrent
    startActivity(pickerIntent)
    if (finishCurrent) finish()
    return true
  }

  // ==================== Picture-in-Picture Management ====================

  /**
   * Called when Picture-in-Picture mode changes.
   * Updates UI visibility and window configuration.
   *
   * @param isInPictureInPictureMode true if entering PiP, false if exiting
   * @param newConfig The new configuration
   */
  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

    pipHelper.onPictureInPictureModeChanged(isInPictureInPictureMode)
    if (isInPictureInPictureMode) {
      cancelPendingPipDismissalStop()
      wasInPipMode = true
      handledPipDismissal = false
    } else if (wasInPipMode) {
      // Android invokes this transition both when the user expands PiP and when the
      // system X removes the PiP window. Delay the stop briefly so onStart() can cancel
      // it for expansion, while the X path still reaches the hard stop.
      schedulePipDismissalStop()
    }

    binding.controls.animate().cancel()
    if (isInPictureInPictureMode) {
      binding.controls.alpha = 0f
    }

    runCatching {
      if (isInPictureInPictureMode) {
        enterPipUIMode()
      } else {
        exitPipUIMode()
        if (ValueAnimator.areAnimatorsEnabled()) {
          binding.controls.alpha = 0f
          binding.controls
            .animate()
            .alpha(1f)
            .setDuration(180L)
            .setInterpolator(PathInterpolator(0.25f, 1f, 0.5f, 1f))
            .start()
        } else {
          binding.controls.alpha = 1f
        }
      }
    }.onFailure { e ->
      Log.e(TAG, "Error handling PiP mode change", e)
    }
  }

  /**
   * Configures window for Picture-in-Picture mode.
   * Shows system UI and navigation bars.
   */
  private fun enterPipUIMode() {
    cancelSystemBarsAutoHide()
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    WindowCompat.setDecorFitsSystemWindows(window, true)
    try {
      windowInsetsController.apply {
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to show system bars for PiP mode", e)
    }
  }

  /**
   * Restores window configuration when exiting Picture-in-Picture mode.
   * Hides system UI for immersive playback.
   */
  private fun exitPipUIMode() {
    setupWindowFlags()
    setupSystemUI()
  }

  /**
   * Enters Picture-in-Picture mode and hides all overlay controls.
   */
  fun enterPipModeHidingOverlay() {
    if (viewModel.isAudioOnly.value || isCurrentMediaKnownAudio()) return
    runCatching {
      enterPipUIMode()
    }.onFailure { e ->
      Log.e(TAG, "Error entering PiP mode with hidden overlay", e)
    }

    enterPipModeSmoothly()
  }

  private fun enterPipModeSmoothly() {
    if (viewModel.isAudioOnly.value || isCurrentMediaKnownAudio()) return
    binding.root.animate().cancel()
    binding.controls.animate().cancel()
    binding.root.scaleX = 1f
    binding.root.scaleY = 1f
    binding.root.translationX = 0f
    binding.controls.alpha = 0f
    pipHelper.updatePictureInPictureParams()
    pipHelper.enterPipMode()
  }

  // ==================== Orientation Management ====================

  /**
   * Sets the screen orientation based on user preferences.
   *
   * IMPORTANT: Preferences are the single source of truth for orientation.
   * This method applies the preference value when videos load.
   * The rotation button temporarily overrides this without changing preferences.
   *
   * For "Video" orientation mode, this will wait for video-params/aspect to update
   * to the correct orientation, starting with landscape as fallback.
   */
  private fun setOrientation() {
    val currentItemId = currentPlaybackItem()?.stableId ?: activePlaybackItem?.stableId
    if (manualOrientationOverride != null &&
      (manualOrientationOverrideItemId == null || manualOrientationOverrideItemId == currentItemId)
    ) {
      requestOrientationIfChanged(manualOrientationOverride!!)
      return
    }
    if (isKnownAudioLaunch(intent) || viewModel.isAudioOnly.value) {
      val audioOrient =
        when (audioPreferences.audioOrientation.get()) {
          AudioPlayerOrientation.Auto -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
          AudioPlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
          AudioPlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
      requestOrientationIfChanged(audioOrient)
      return
    }
    val orientationPref = playerPreferences.orientation.get()

    val targetOrientation =
      when (orientationPref) {
        PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        PlayerOrientation.Video -> {
          // For video orientation, check if aspect is available
          val aspect =
            if (playbackEngine == PlaybackEngine.MEDIA3) {
              val width = media3State.videoWidth
              val height = media3State.videoHeight
              if (width > 0 && height > 0) width.toDouble() / height.toDouble() else null
            } else {
              runCatching { player.getVideoOutAspect() }.getOrNull()
            }
          Log.d(TAG, "setOrientation - Video mode: aspect=$aspect")
          if (aspect == null || aspect <= 0.0) {
            // Media3 dimensions arrive asynchronously. Do not force an orientation using stale
            // MPV metadata or the previous screen state before Media3 reports VideoSize.
            Log.d(TAG, "setOrientation - Aspect not available yet; leaving current orientation")
            return
          } else {
            // Aspect available - set correct orientation now
            val orientation =
              if (aspect > 1.0) {
                Log.d(TAG, "setOrientation - Aspect $aspect > 1.0, setting landscape")
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
              } else {
                Log.d(TAG, "setOrientation - Aspect $aspect <= 1.0, setting portrait")
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
              }
            orientation
          }
        }
        PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        PlayerOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        PlayerOrientation.SensorPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        PlayerOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        PlayerOrientation.SensorLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      }
    requestOrientationIfChanged(targetOrientation)
  }

  private fun requestOrientationIfChanged(orientation: Int) {
    if (requestedOrientation != orientation) {
      requestedOrientation = orientation
    }
  }

  private fun isKnownAudioLaunch(sourceIntent: Intent): Boolean =
    sourceIntent.getBooleanExtra("is_audio", false) || sourceIntent.type?.startsWith("audio/") == true

  // ==================== Key Event Handling ====================

  /**
   * Handles hardware key down events for player control.
   * Supports D-pad navigation, media keys, and volume controls.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
  override fun onKeyDown(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    val isTrackSheetOpen =
      viewModel.sheetShown.value == Sheets.SubtitleTracks ||
        viewModel.sheetShown.value == Sheets.AudioTracks
    val isNoSheetOpen = viewModel.sheetShown.value == Sheets.None

    // If any modifier keys are pressed, delegate to MPVView for proper modifier handling
    val modifierEvent =
      event?.takeIf {
        it.isShiftPressed || it.isCtrlPressed || it.isAltPressed || it.isMetaPressed
      }
    val hasModifiers = modifierEvent != null

    when (keyCode) {
      KeyEvent.KEYCODE_DPAD_UP -> {
        if (hasModifiers) {
          player.onKey(modifierEvent)
          return true
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_LEFT,
      -> {
        // If modifiers are pressed, delegate to MPVView for proper handling (e.g. sub-step)
        if (hasModifiers) {
          player.onKey(modifierEvent)
          return true
        }

        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }

        if (isNoSheetOpen) {
          when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
              viewModel.handleRightDoubleTap()
              return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
              viewModel.handleLeftDoubleTap()
              return true
            }
          }
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
        if (hasModifiers) {
          player.onKey(modifierEvent)
          return true
        }
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_SPACE -> {
        if (hasModifiers) {
          player.onKey(modifierEvent)
          return true
        }
        viewModel.pauseUnpause()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_UP -> {
        if (viewModel.isAudioOnly.value) {
          viewModel.changeVolumeBy(1, showUi = true)
          return true
        }
        viewModel.changeVolumeBy(1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        if (viewModel.isAudioOnly.value) {
          viewModel.changeVolumeBy(-1, showUi = true)
          return true
        }
        viewModel.changeVolumeBy(-1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_STOP -> {
        finishAndRemoveTask()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_REWIND -> {
        viewModel.handleLeftDoubleTap()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
        viewModel.handleRightDoubleTap()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
        viewModel.handleMediaPrevious()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      KeyEvent.KEYCODE_HEADSETHOOK,
      -> {
        viewModel.handleMediaPlayPause()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_NEXT -> {
        viewModel.handleMediaNext()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_PLAY -> {
        viewModel.unpause()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_PAUSE -> {
        viewModel.pause()
        return true
      }

      else -> {
        event?.let { player.onKey(it) }
        return super.onKeyDown(keyCode, event)
      }
    }
  }

  /**
   * Handles hardware key up events for player control.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  override fun onKeyUp(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    event?.let {
      if (player.onKey(it)) return true
    }
    return super.onKeyUp(keyCode, event)
  }

  // ==================== System UI Management ====================

  /**
   * Restores system UI to normal state (shows status and navigation bars).
   * Called when finishing the activity to return to normal Android UI.
   */

  // ==================== MediaSession ====================

  /**
   * Initializes MediaSession for integration with system media controls.
   * Supports Android Auto, Wear OS, Bluetooth controls, and notification controls.
   */
  private fun setupMediaSession() {
    runCatching {
      mediaSession =
        MediaSession(this, TAG).apply {
          setCallback(
            object : MediaSession.Callback() {
              override fun onPlay() {
                viewModel.unpause()
                updateMediaSessionPlaybackState(isPlaying = true)
              }

              override fun onPause() {
                viewModel.pause()
                updateMediaSessionPlaybackState(isPlaying = false)
              }

              override fun onSeekTo(pos: Long) {
                viewModel.seekTo((pos / 1000).toInt())
                updateMediaSessionPlaybackState(isPlaying = viewModel.paused == false)
              }

              override fun onSkipToNext() {
                playNextQueueItem()
              }

              override fun onSkipToPrevious() {
                playPreviousQueueItem()
              }

              override fun onStop() {
                if (fileName.isNotBlank()) saveVideoPlaybackState(fileName, immediate = true)
                torrentStreamingEngine.stopStream()
                PlaybackSession.stop(clearQueue = false)
                mediaSession.setPlaybackState(
                  PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0L, 0f).build(),
                )
              }
            },
          )
          isActive = !MediaPlaybackService.isForegroundActive()
        }
      playbackStateBuilder = PlaybackState.Builder()
      mediaSessionInitialized = true
      updateMediaSessionPlaybackState(isPlaying = PlaybackSession.getPropertyBoolean("pause") == false)
    }.onFailure { e ->
      Log.e(TAG, "Failed to initialize MediaSession", e)
      mediaSessionInitialized = false
    }
  }

  /**
   * Updates MediaSession playback state (playing/paused).
   *
   * @param isPlaying true if currently playing, false if paused
   */
  private fun updateMediaSessionPlaybackState(isPlaying: Boolean) {
    if (!mediaSessionInitialized) return
    if (Looper.myLooper() != Looper.getMainLooper()) {
      runOnUiThread { updateMediaSessionPlaybackState(isPlaying) }
      return
    }
    runCatching {
      val phase = PlaybackSession.state.value.phase
      val state =
        when (phase) {
          PlaybackPhase.LOADING, PlaybackPhase.INITIALIZING -> PlaybackState.STATE_BUFFERING
          PlaybackPhase.IDLE, PlaybackPhase.STOPPING -> PlaybackState.STATE_STOPPED
          PlaybackPhase.ERROR -> PlaybackState.STATE_ERROR
          PlaybackPhase.UNINITIALIZED -> PlaybackState.STATE_NONE
          PlaybackPhase.READY, PlaybackPhase.BACKGROUND ->
            if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        }
      val positionMs = (viewModel.pos ?: 0) * 1000L
      var actions = 0L
      if (state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_NONE && state != PlaybackState.STATE_ERROR) {
        actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO
        actions = actions or if (isPlaying) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
        if (PlaybackSession.hasPrevious()) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (PlaybackSession.hasNext()) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
      }
      mediaSession.setPlaybackState(
        playbackStateBuilder
          .setActions(actions)
          .setState(state, positionMs, if (state == PlaybackState.STATE_PLAYING) 1.0f else 0f)
          .build(),
      )
    }.onFailure { e -> Log.e(TAG, "Error updating playback state", e) }
  }

  /**
   * Updates MediaSession metadata (title, duration, etc.).
   *
   * @param title The media title
   * @param durationMs The media duration in milliseconds
   */
  private fun updateMediaSessionMetadata(
    title: String,
    durationMs: Long,
  ) {
    if (!mediaSessionInitialized) return
    if (Looper.myLooper() != Looper.getMainLooper()) {
      runOnUiThread { updateMediaSessionMetadata(title, durationMs) }
      return
    }
    runCatching {
      val metadata =
        MediaMetadata
          .Builder()
          .putString(MediaMetadata.METADATA_KEY_TITLE, title)
          .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
          .build()
      mediaSession.setMetadata(metadata)
    }.onFailure { e -> Log.e(TAG, "Error updating metadata", e) }
  }

  /**
   * Releases MediaSession resources.
   * Called during activity cleanup.
   */
  private fun releaseMediaSession() {
    if (!mediaSessionInitialized) return
    runCatching {
      mediaSession.isActive = false
      mediaSession.release()
    }.onFailure { e -> Log.e(TAG, "Error releasing MediaSession", e) }
    mediaSessionInitialized = false
  }

  private fun setActivityMediaSessionActive(active: Boolean) {
    if (!mediaSessionInitialized || mediaSession.isActive == active) return
    runCatching {
      mediaSession.isActive = active
      if (active) updateMediaSessionPlaybackState(isPlaying = PlaybackSession.getPropertyBoolean("pause") == false)
    }.onFailure { error -> Log.e(TAG, "Error changing Activity MediaSession ownership", error) }
  }

  // ==================== Background Playback Service ====================

  /**
   * Service connection for binding to background playback service.
   */
  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(
        name: ComponentName?,
        service: IBinder?,
      ) {
        val binder = service as? MediaPlaybackService.MediaPlaybackBinder ?: return
        val connectedService = binder.getService() ?: return
        mediaPlaybackService = connectedService
        serviceBound = true
        Log.d(TAG, "Service connected")
        syncBackgroundPlaybackService(updateThumbnail = true)
        awaitServiceMediaSessionOwnership()
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        Log.d(TAG, "Service disconnected")
        backgroundHandoffJob?.cancel()
        mediaPlaybackService = null
        serviceBound = false
        if (!isFinishing && !isDestroyed) setActivityMediaSessionActive(true)
      }
    }

  /**
   * Starts the background playback service and binds to it.
   *
   * This should only be called if a video is loaded and playback is initialized.
   * Responsible for starting and binding to the MediaPlaybackService, which
   * handles background playback.
   */
  private fun startBackgroundPlayback(
    allowUserPrompt: Boolean = true,
    bindToActivity: Boolean = true,
  ): BackgroundPlaybackStartResult {
    pendingBackgroundPlaybackStart = true

    if (!shouldShowPlaybackNotification()) {
      pendingBackgroundPlaybackStart = false
      Log.d(TAG, "Playback notification disabled, skipping background playback service")
      if (allowUserPrompt) {
        Toast
          .makeText(
            this,
            getString(R.string.notification_disabled_in_advanced_settings),
            Toast.LENGTH_LONG,
          ).show()
      }
      return BackgroundPlaybackStartResult.Blocked
    }

    when (ensureNotificationAccessForPlayback(allowUserPrompt)) {
      BackgroundPlaybackStartResult.Started -> Unit
      BackgroundPlaybackStartResult.PendingPermission -> return BackgroundPlaybackStartResult.PendingPermission
      BackgroundPlaybackStartResult.Blocked -> {
        pendingBackgroundPlaybackStart = false
        return BackgroundPlaybackStartResult.Blocked
      }
    }

    pendingBackgroundPlaybackStart = false
    return if (startBackgroundPlaybackInternal(bindToActivity = bindToActivity)) {
      BackgroundPlaybackStartResult.Started
    } else {
      BackgroundPlaybackStartResult.Blocked
    }
  }

  private fun startBackgroundPlaybackInternal(bindToActivity: Boolean): Boolean {
    val audioReady = isAudioSessionReady()
    if (fileName.isBlank() || (!isReady && !audioReady)) {
      Log.w(TAG, "Cannot start background playback: media not ready")
      return false
    }

    // Reusing the existing foreground service avoids a second startForeground/onStartCommand cycle
    // when the user repeatedly opens and minimizes the same audio session. That cycle can briefly
    // steal audio focus and, on some devices, stop the shared native session.
    if (!bindToActivity && viewModel.isAudioOnly.value && MediaPlaybackService.isForegroundActive()) {
      setActivityMediaSessionActive(false)
      Log.d(TAG, "Audio background service already active; reusing it for minimize")
      return true
    }

    // Prevent starting service multiple times
    if (bindToActivity && serviceBound && mediaPlaybackService?.isForegroundReady() == true) {
      setActivityMediaSessionActive(false)
      Log.d(TAG, "Service already bound, skipping start")
      return true
    }

    Log.d(TAG, "Starting background playback for: $fileName")

    // Ensure notification channel exists
    MediaPlaybackService.createNotificationChannel(this)

    // Get media info before starting service
    val artist = getPreferredCurrentArtist()

    // Pass media info via intent extras
    val intent =
      Intent(this, MediaPlaybackService::class.java).apply {
        putExtra("media_title", FileTypeUtils.stripExtension(fileName))
        putExtra("media_artist", artist)
        putExtra("media_uri", currentDurableMediaUri())
        putExtra("media_identifier", mediaIdentifier)
        putExtra("audio_background_playback", viewModel.isAudioOnly.value)
      }

    try {
      startForegroundService(intent)
      if (bindToActivity && !serviceBound) {
        if (!bindService(intent, serviceConnection, BIND_AUTO_CREATE)) {
          stopService(intent)
          setActivityMediaSessionActive(true)
          Log.e(TAG, "Playback service rejected the bind request")
          return false
        }
        Log.d(TAG, "Service start and bind initiated")
      } else {
        Log.d(TAG, "Service start initiated")
      }
      if (serviceBound) awaitServiceMediaSessionOwnership()
      return true
    } catch (e: Exception) {
      setActivityMediaSessionActive(true)
      Log.e(TAG, "Error starting/binding service", e)
      return false
    }
  }

  private fun isAudioSessionReady(): Boolean {
    if (!viewModel.isAudioOnly.value || !PlaybackSession.isInitialized) return false
    val phase = PlaybackSession.state.value.phase
    return phase == PlaybackPhase.READY || phase == PlaybackPhase.BACKGROUND
  }

  private fun ensureNotificationAccessForPlayback(allowUserPrompt: Boolean): BackgroundPlaybackStartResult {
    if (!shouldShowPlaybackNotification()) {
      if (allowUserPrompt) {
        Toast
          .makeText(
            this,
            getString(R.string.notification_disabled_in_advanced_settings),
            Toast.LENGTH_LONG,
          ).show()
      }
      return BackgroundPlaybackStartResult.Blocked
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      if (!allowUserPrompt) return BackgroundPlaybackStartResult.Blocked
      Toast
        .makeText(
          this,
          getString(R.string.notification_permission_denied),
          Toast.LENGTH_LONG,
        ).show()
      return BackgroundPlaybackStartResult.Blocked
    }

    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
      if (!allowUserPrompt) return BackgroundPlaybackStartResult.Blocked
      Toast
        .makeText(
          this,
          getString(R.string.notification_permission_disabled),
          Toast.LENGTH_LONG,
        ).show()
      openNotificationSettings()
      return BackgroundPlaybackStartResult.Blocked
    }

    return BackgroundPlaybackStartResult.Started
  }

  private fun openNotificationSettings() {
    val intent =
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
      }
    runCatching { startActivity(intent) }
      .onFailure { Log.e(TAG, "Failed to open notification settings", it) }
  }

  /**
   * Stops the background playback service and unbinds from it.
   *
   * Called when the activity is destroyed to remove the notification.
   */
  private fun endBackgroundPlayback() {
    Log.d(TAG, "Ending background playback service")
    backgroundHandoffJob?.cancel()
    backgroundHandoffJob = null
    isBackgroundPlaybackSessionActive = false
    pendingBackgroundTransition = false
    pendingBackNavigationBackgroundTransition = false

    // Tell the service this destruction is a handoff back to the Activity so it neither
    // pauses on focus loss nor stops the shared PlaybackSession media during teardown.
    MediaPlaybackService.prepareForActivityHandoff()

    if (serviceBound) {
      try {
        unbindService(serviceConnection)
        Log.d(TAG, "Service unbound successfully")
      } catch (e: Exception) {
        Log.e(TAG, "Error unbinding service", e)
      }
      serviceBound = false
    }

    // Stop the service which will trigger its onDestroy and cleanup
    try {
      stopService(Intent(this, MediaPlaybackService::class.java))
      Log.d(TAG, "Stop service command sent")
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping service", e)
    }

    mediaPlaybackService = null
    MediaPlaybackService.relinquishMediaSessionToActivity()
    if (!isFinishing && !isDestroyed) setActivityMediaSessionActive(true)
  }

  /** Toggles video background playback without changing the audio-player setting. */
  fun toggleBackgroundPlayback() {
    val enabled = !audioPreferences.backgroundPlayback.get()

    if (enabled && !shouldShowPlaybackNotification()) {
      Toast
        .makeText(
          this,
          getString(R.string.notification_disabled_in_advanced_settings),
          Toast.LENGTH_LONG,
        ).show()
      return
    }

    audioPreferences.backgroundPlayback.set(enabled)

    if (enabled) {
      ensureNotificationAccessForPlayback(allowUserPrompt = true)
    } else {
      pendingBackgroundTransition = false
      isBackgroundPlaybackSessionActive = false
      endBackgroundPlayback()
      enableVideoAfterBackground()
      viewModel.showToast("Background playback off")
      return
    }

    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot start background playback: media not ready")
      viewModel.showToast("Background playback on")
      return
    }

    Log.d(TAG, "Background playback enabled from player controls")
    when (startBackgroundPlayback()) {
      BackgroundPlaybackStartResult.Started -> {
        isBackgroundPlaybackSessionActive = true
        viewModel.showToast("Background playback on")
      }
      BackgroundPlaybackStartResult.PendingPermission -> pendingBackgroundTransition = true
      BackgroundPlaybackStartResult.Blocked -> {
        audioPreferences.backgroundPlayback.set(false)
        isBackgroundPlaybackSessionActive = false
        pendingBackgroundTransition = false
      }
    }
  }

  /** Toggles the audio-player-specific background playback setting. */
  fun toggleAudioBackgroundPlayback() {
    val enabled = !audioPreferences.audioBackgroundPlayback.get()

    if (enabled && !shouldShowPlaybackNotification()) {
      Toast
        .makeText(
          this,
          getString(R.string.notification_disabled_in_advanced_settings),
          Toast.LENGTH_LONG,
        ).show()
      return
    }

    audioPreferences.audioBackgroundPlayback.set(enabled)

    if (enabled) ensureNotificationAccessForPlayback(allowUserPrompt = true)
    if (!enabled) {
      pendingBackgroundTransition = false
      isBackgroundPlaybackSessionActive = false
      endBackgroundPlayback()
      enableVideoAfterBackground()
      if (!isFinishing && !isDestroyed && viewModel.paused != true) {
        requestAudioFocus()
        if (PlaybackSession.getPropertyBoolean("pause") == true) viewModel.unpause()
      }
      viewModel.showToast("Audio background playback off")
      return
    }
    if (fileName.isBlank() || !isReady) {
      viewModel.showToast("Audio background playback on")
      return
    }
    when (startBackgroundPlayback()) {
      BackgroundPlaybackStartResult.Started -> {
        isBackgroundPlaybackSessionActive = true
        viewModel.showToast("Audio background playback on")
      }
      BackgroundPlaybackStartResult.PendingPermission -> pendingBackgroundTransition = true
      BackgroundPlaybackStartResult.Blocked -> {
        audioPreferences.audioBackgroundPlayback.set(false)
        isBackgroundPlaybackSessionActive = false
        pendingBackgroundTransition = false
      }
    }
  }

  private fun finishIntoBackgroundPlayback() {
    isBackgroundPlaybackSessionActive = true
    pendingBackNavigationBackgroundTransition = false
    disableVideoForBackground()
    isUserFinishing = true
    finish()
  }

  private fun completePendingBackgroundHandoff() {
    if (!pendingBackNavigationBackgroundTransition) return
    awaitServiceMediaSessionOwnership()
  }

  private fun awaitServiceMediaSessionOwnership() {
    backgroundHandoffJob?.cancel()
    backgroundHandoffJob =
      lifecycleScope.launch {
        repeat(30) {
          if (isFinishing || isDestroyed) return@launch
          val service = mediaPlaybackService
          if (service != null && service.isForegroundReady()) {
            setActivityMediaSessionActive(false)
            if (pendingBackNavigationBackgroundTransition) finishIntoBackgroundPlayback()
            return@launch
          }
          delay(100)
        }

        val failedBackgroundHandoff = pendingBackNavigationBackgroundTransition
        pendingBackNavigationBackgroundTransition = false
        setActivityMediaSessionActive(true)
        if (failedBackgroundHandoff) {
          Toast.makeText(this@PlayerActivity, R.string.toast_playback_load_failed, Toast.LENGTH_LONG).show()
        }
        endBackgroundPlayback()
      }
  }

  // ==================== PlayerHost ====================
  override val context: Context
    get() = this
  override val windowInsetsController: WindowInsetsControllerCompat
    get() = WindowCompat.getInsetsController(window, window.decorView)
  override val hostWindow: android.view.Window
    get() = window
  override val hostWindowManager: WindowManager
    get() = windowManager
  override val hostContentResolver: android.content.ContentResolver
    get() = contentResolver
  override val audioManager: AudioManager
    get() = getSystemService(AUDIO_SERVICE) as AudioManager

  override fun isMedia3Active(): Boolean = playbackEngine == PlaybackEngine.MEDIA3

  override fun media3IsPlaying(): Boolean =
    playbackEngine == PlaybackEngine.MEDIA3 && cachedMedia3State.isPlaying

  override fun media3SetPlayWhenReady(value: Boolean): Boolean {
    if (!isMedia3Active()) return false
    media3PlaybackController.setPlayWhenReady(value)
    return true
  }

    override fun media3SeekBy(offsetMs: Long): Boolean {
    if (!isMedia3Active()) return false
    media3PlaybackController.seekBy(offsetMs)
    return true
  }
  override fun media3SeekFrameBy(offsetMs: Long): Boolean {
    if (!isMedia3Active()) return false
    media3PlaybackController.seekFrameBy(offsetMs)
    return true
  }
  override fun media3SeekTo(positionMs: Long, fast: Boolean): Boolean {
    if (!isMedia3Active()) return false
    media3PlaybackController.seekTo(positionMs, fast = fast)
    return true
  }

  override fun media3SetPlaybackSpeed(speed: Float): Boolean {
    if (!isMedia3Active()) return false
    media3PlaybackController.setPlaybackSpeed(speed)
    return true
  }

  override fun media3PlaybackSpeed(): Float =
    if (isMedia3Active()) cachedMedia3State.playbackSpeed else 1f

  override fun media3SetAudioPitchCorrection(enabled: Boolean): Boolean {
    if (!isMedia3Active()) return false
    media3PlaybackController.setAudioPitchCorrection(enabled)
    return true
  }

  override fun media3SetRepeatMode(mode: RepeatMode): Boolean {
    if (!isMedia3Active()) return false
    media3PlaybackController.setRepeatMode(
      when (mode) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
      },
    )
    return true
  }

  override fun media3SetAudioChannels(channels: AudioChannels): Boolean {
    if (!isMedia3Active()) return false
    media3PlaybackController.setAudioChannels(channels)
    return true
  }

  override fun media3SetAudioProcessing(volumeNormalization: Boolean, drcEnabled: Boolean): Boolean {
    if (!isMedia3Active()) return false
    media3PlaybackController.setAudioProcessing(volumeNormalization, drcEnabled)
    return true
  }

  override fun media3SelectAudioTrack(trackId: Int): Boolean {
    if (!isMedia3Active()) return false
    return media3PlaybackController.selectAudioTrack(trackId)
  }

  override fun media3SelectSubtitleTrack(trackId: Int): Boolean {
    if (!isMedia3Active()) return false
    return media3PlaybackController.selectSubtitleTrack(trackId)
  }

  override fun media3UnselectSubtitleTrack(trackId: Int): Boolean {
    if (!isMedia3Active()) return false
    return media3PlaybackController.unselectSubtitleTrack(trackId)
  }

  override fun media3DisableSubtitles(): Boolean {
    if (!isMedia3Active()) return false
    return media3PlaybackController.disableSubtitles()
  }

  override fun media3IsSubtitleSelected(trackId: Int): Boolean {
    if (!isMedia3Active()) return false
    return media3PlaybackController.isSubtitleSelected(trackId)
  }

  override fun media3SetSubtitleScale(scale: Float): Boolean {
    if (!isMedia3Active()) return false
    return media3PlaybackController.setSubtitleScale(scale)
  }

  override fun media3ApplySubtitleStyle(
    textColor: Int,
    backgroundColor: Int,
    edgeType: Int,
    edgeColor: Int,
  ): Boolean {
    if (!isMedia3Active()) return false
    return media3PlaybackController.setSubtitleStyle(textColor, backgroundColor, edgeType, edgeColor)
  }

  override fun media3HasSelectedSubtitle(): Boolean {
    if (!isMedia3Active()) return false
    return media3PlaybackController.hasSelectedSubtitle()
  }

  override fun media3CurrentPositionMs(): Long {
    if (!isMedia3Active()) return 0L
    return cachedMedia3State.positionMs
  }

  override fun media3DurationMs(): Long {
    if (!isMedia3Active()) return 0L
    return cachedMedia3State.durationMs
  }

  override fun media3FrameDurationMs(): Long? {
    if (!isMedia3Active()) return null
    val frameRate = media3PlaybackController.currentState().videoFrameRate
    // Some Matroska/VFR files omit frame-rate metadata. Keep frame navigation available with the
    // same conservative fallback used by the stepping command instead of disabling the sheet.
    return frameRate.takeIf { it > 0f && it.isFinite() }?.let {
      (1000f / it).roundToLong().coerceIn(1L, 1000L)
    } ?: 40L
  }

  override fun media3LoopA(): Long? =
    if (isMedia3Active()) media3PlaybackController.media3LoopA() else null

  override fun media3LoopB(): Long? =
    if (isMedia3Active()) media3PlaybackController.media3LoopB() else null

  override fun media3SetLoopA(positionMs: Long): Boolean {
    if (!isMedia3Active()) return false
    media3PlaybackController.setLoopA(positionMs)
    return true
  }

  override fun media3SetLoopB(positionMs: Long): Boolean {
    if (!isMedia3Active()) return false
    media3PlaybackController.setLoopB(positionMs)
    return true
  }

  override fun media3ClearABLoop(): Boolean {
    if (!isMedia3Active()) return false
    media3PlaybackController.clearABLoop()
    return true
  }

  private val keyguardManager: KeyguardManager
    get() = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
  override var hostRequestedOrientation: Int
    get() = requestedOrientation
    set(value) {
      manualOrientationOverride = value
      manualOrientationOverrideItemId = currentPlaybackItem()?.stableId ?: activePlaybackItem?.stableId
      requestedOrientation = value
      AppDebugLog.info(
        TAG,
        "Manual orientation override value=$value item=${manualOrientationOverrideItemId ?: "unknown"}",
      )
    }

  // ==================== Playlist Management ====================

  /**
   * Check if there's a next video in the playlist
   */
  override fun hasNextQueueItem(): Boolean {
    return PlaybackSession.hasNext()
  }

  /**
   * Check if there's a previous video in the playlist
   */
  override fun hasPreviousQueueItem(): Boolean {
    return PlaybackSession.hasPrevious()
  }

  /**
   * Called when shuffle is toggled on/off
   */
  override fun onQueueShuffleChanged(enabled: Boolean) {
    PlaybackSession.setShuffleEnabled(enabled)
  }

  override fun reorderQueueItem(
    from: Int,
    to: Int,
  ) {
    if (from == to) return
    if (from !in playlist.indices || to !in playlist.indices) return
    if (isM3uPlaylist) return

    val mutablePlaylist = playlist.toMutableList()
    val movedUri = mutablePlaylist.removeAt(from)
    mutablePlaylist.add(to, movedUri)
    playlist = mutablePlaylist

    if (networkPlaylistPaths.size == playlist.size) {
      networkPlaylistPaths = networkPlaylistPaths.toMutableList().apply { add(to, removeAt(from)) }
    }
    if (networkPlaylistTitles.size == playlist.size) {
      networkPlaylistTitles = networkPlaylistTitles.toMutableList().apply { add(to, removeAt(from)) }
    }
    if (networkPlaylistHeaders.size == playlist.size) {
      networkPlaylistHeaders = networkPlaylistHeaders.toMutableList().apply { add(to, removeAt(from)) }
    }

    if (playlistItems.isNotEmpty() && from in playlistItems.indices && to in playlistItems.indices) {
      val mutableItems = playlistItems.toMutableList()
      val movedItem = mutableItems.removeAt(from)
      mutableItems.add(to, movedItem)
      playlistItems = mutableItems

      playlistEntity?.let { entity ->
        if (!isM3uPlaylist) {
          val newOrder = playlistItems.map { it.id }
          lifecycleScope.launch(Dispatchers.IO) {
            playlistRepository.reorderPlaylistItems(entity.id, newOrder)
          }
        }
      }
    }

    playlistIndex =
      if (from == playlistIndex) {
        to
      } else {
        if (from < playlistIndex && to >= playlistIndex) {
          playlistIndex - 1
        } else if (from > playlistIndex && to <= playlistIndex) {
          playlistIndex + 1
        } else {
          playlistIndex
        }
      }

    if (!PlaybackSession.moveQueueItem(from, to)) {
      publishPlaylistToSession()
    } else {
      TemporaryPlaybackQueue.syncFromSession()
    }
    viewModel.refreshPlaylistItems()
  }

  override fun removeQueueItem(index: Int) {
    if (!PlaybackSession.queue.value.isTemporaryQueue || isM3uPlaylist) return
    val oldPlaylistSize = playlist.size
    if (index !in 0 until oldPlaylistSize || index == playlistIndex) return
    if (!PlaybackSession.removeQueueItem(index)) return

    playlist = playlist.toMutableList().apply { removeAt(index) }
    if (networkPlaylistPaths.size == oldPlaylistSize) {
      networkPlaylistPaths = networkPlaylistPaths.toMutableList().apply { removeAt(index) }
    }
    if (networkPlaylistTitles.size == oldPlaylistSize) {
      networkPlaylistTitles = networkPlaylistTitles.toMutableList().apply { removeAt(index) }
    }
    if (networkPlaylistHeaders.size == oldPlaylistSize) {
      networkPlaylistHeaders = networkPlaylistHeaders.toMutableList().apply { removeAt(index) }
    }
    if (playlistItems.isNotEmpty() && index in playlistItems.indices) {
      playlistItems = playlistItems.toMutableList().apply { removeAt(index) }
        .mapIndexed { itemIndex, item -> item.copy(position = itemIndex) }
    }
    if (index < playlistIndex) playlistIndex -= 1
    TemporaryPlaybackQueue.syncFromSession()
    viewModel.refreshPlaylistItems()
  }

  /**
   * Play the next video in the playlist
   */
  override fun playNextQueueItem() {
    PlaybackSession.selectNext() ?: return
    TemporaryPlaybackQueue.syncFromSession()
    loadPlaylistItem(PlaybackSession.queue.value.currentIndex)
  }

  /**
   * Play the previous video in the playlist
   */
  override fun playPreviousQueueItem() {
    PlaybackSession.selectPrevious() ?: return
    TemporaryPlaybackQueue.syncFromSession()
    loadPlaylistItem(PlaybackSession.queue.value.currentIndex)
  }

  /**
   * Load a playlist item by index
   */
  private fun loadPlaylistItem(index: Int) {
    // All items are loaded - just validate index and load directly
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }
    loadPlaylistItemInternal(index)
  }

  /**
   * Internal method to load a playlist item
   */
  private fun loadPlaylistItemInternal(
    index: Int,
    saveCurrentPlaybackState: Boolean = true,
  ) {
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }

    // Mark the target before selecting it so the queue observer cannot reuse the old item's
    // Media3/MPV position during asynchronous engine synchronization.
    pendingQueueTransitionStartAtZero = true
    pendingQueueTransitionItemId = PlaybackSession.queue.value.items.getOrNull(index)?.stableId

    // Save current video's playback state before switching
    if (saveCurrentPlaybackState && fileName.isNotBlank()) {
      saveVideoPlaybackState(fileName)
      reportJellyfinStop()
    }

    val uri = playlist[index]
    val playableUri = uri.openContentFd(this) ?: uri.toString()
    currentPlayableUri = uri.toString()
    val persistedNetworkReference = NetworkPlaybackUri.parse(uri.toString())
    val networkFilePath =
      networkPlaylistPaths.getOrNull(index)?.takeIf { it.isNotBlank() }
        ?: persistedNetworkReference?.path?.value
    val resolvedNetworkConnectionId =
      networkPlaylistConnectionId.takeIf { it != -1L }
        ?: persistedNetworkReference?.connectionId
    val networkTitle = networkPlaylistTitles.getOrNull(index)?.takeIf { it.isNotBlank() }

    // Update playlist index
    playlistIndex = index
    PlaybackSession.selectQueueItem(index)
    TemporaryPlaybackQueue.syncFromSession()
    viewModel.calculateVideoHash(uri)

    // Extract and set the new file name
    fileName = getPlaylistItemByIndex(index)?.fileName?.takeIf { it.isNotBlank() }
      ?: networkTitle
      ?: getFileNameFromUri(uri)
    // Generate new media identifier for playback state
    legacyMediaIdentifier =
      if (networkFilePath != null && resolvedNetworkConnectionId != null) {
        "network_${resolvedNetworkConnectionId}_${networkFilePath.hashCode()}"
      } else if (isRemotePlaybackUri(uri)) {
        "${fileName}_${uri.toString().hashCode()}"
      } else {
        null
      }
    mediaIdentifier =
      if (networkFilePath != null && resolvedNetworkConnectionId != null) {
        buildNetworkMediaIdentifier(resolvedNetworkConnectionId, networkFilePath)
      } else {
        getMediaIdentifierFromUri(uri, fileName)
      }

    // Set HTTP headers (including referer) for network streams
    setHttpHeadersForUri(uri)

    // Update playlist play history if this is a custom playlist
    playlistId?.takeUnless(::isAllVideosPlaylist)?.let { id ->
      lifecycleScope.launch(Dispatchers.IO) {
        val playlistItem = getPlaylistItemByUri(uri)
        val filePath =
          playlistItem?.filePath ?: when (uri.scheme) {
            "file" -> uri.path ?: uri.toString()
            "content" -> {
              contentResolver
                .query(
                  uri,
                  arrayOf(MediaStore.MediaColumns.DATA),
                  null,
                  null,
                  null,
                )?.use { cursor ->
                  if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (columnIndex != -1) cursor.getString(columnIndex) else null
                  } else {
                    null
                  }
                } ?: uri.toString()
            }

            else -> uri.toString()
          }

        runCatching {
          playlistRepository.updatePlayHistory(id, filePath)
          Log.d(TAG, "Updated playlist history for: $filePath in playlist $id")
        }.onFailure { e ->
          Log.e(TAG, "Error updating playlist history", e)
        }
      }
    }

    // Load the new queue item. The resolved media type is applied inside startMediaLoad so audio
    // containers such as MKV are not prematurely reset through the video lifecycle.
    isAdvancingAtEof = true
    isReady = false

    startMediaLoad(playableUri)

    // Update media title (this will trigger UI update)
    val shouldForceTitle =
      getPlaylistItemByIndex(index)?.fileName?.isNotBlank() == true ||
        !(uri.toString().lowercase().contains(".m3u8") || uri.toString().lowercase().contains(".m3u"))
    if (shouldForceTitle) {
      PlaybackSession.setPropertyString("force-media-title", fileName)
      viewModel.setMediaTitle(fileName)
    }

    // Update media session metadata
    lifecycleScope.launch {
      kotlinx.coroutines.delay(100) // Wait for MPV to load the file
      val durationMs = (PlaybackSession.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
      updateMediaSessionMetadata(
        title = fileName,
        durationMs = durationMs,
      )
      syncBackgroundPlaybackService(updateThumbnail = true)
      // Refresh playlist items to update the currently playing indicator
      viewModel.refreshPlaylistItems()
    }
  }

  private fun syncBackgroundPlaybackService(updateThumbnail: Boolean) {
    // Service ownership is created only by an explicit background handoff. Do not start a
    // foreground service as a side effect of every queue transition or metadata refresh.
    val service = mediaPlaybackService ?: return
    val rawTitle = getPreferredCurrentTitle().ifBlank { fileName.ifBlank { getString(R.string.player_unknown_video) } }
    val title = FileTypeUtils.stripExtension(rawTitle)
    val artist = getPreferredCurrentArtist()
    val thumbnailKey = buildBackgroundThumbnailKey()
    val cachedThumbnail =
      if (thumbnailKey == lastBackgroundThumbnailKey) {
        lastBackgroundThumbnail
      } else {
        null
      }

    service.setMediaInfo(
      title = title,
      artist = artist,
      thumbnail = cachedThumbnail,
      uri = currentDurableMediaUri(),
      identifier = mediaIdentifier,
    )
    // Mirror playlist state into the service so the notification tap-intent can restore it
    service.setPlaylistInfo(
      isAudio =
        viewModel.isAudioOnly.value ||
          intent.getBooleanExtra("is_audio", false) ||
          currentPlaybackItem()?.mimeType?.startsWith("audio/", ignoreCase = true) == true,
    )
    service.setChapters(viewModel.chapters.value.map { ChapterNode(time = it.start, title = it.name) })

    if (!updateThumbnail || thumbnailKey.isBlank()) return
    if (thumbnailKey == lastBackgroundThumbnailKey && cachedThumbnail != null) return

    backgroundServiceSyncJob?.cancel()
    backgroundServiceSyncJob =
      lifecycleScope.launch {
        delay(150)
        val generatedThumbnail =
          withContext(Dispatchers.IO) {
            runCatching { PlaybackSession.grabThumbnail(480) }.getOrNull() ?: runCatching {
              val uriStr = currentPlayableUri
              if (!uriStr.isNullOrBlank()) {
                val parsedUri = Uri.parse(uriStr)
                val cleanPath =
                  when {
                    parsedUri.scheme == "file" -> parsedUri.path
                    parsedUri.scheme == "content" -> null
                    else -> uriStr
                  }
                val retriever = android.media.MediaMetadataRetriever()
                try {
                  if (cleanPath != null) {
                    retriever.setDataSource(cleanPath)
                  } else {
                    retriever.setDataSource(this@PlayerActivity, parsedUri)
                  }
                  app.infinity.mpvz.domain.thumbnail.EmbeddedArtworkResolver.decodeEmbeddedArtwork(cleanPath, retriever)
                } finally {
                  retriever.release()
                }
              } else {
                null
              }
            }.getOrNull()
          }

        if (!mpvInitialized || player.isExiting || isFinishing) return@launch
        if (thumbnailKey != buildBackgroundThumbnailKey()) return@launch

        lastBackgroundThumbnailKey = thumbnailKey
        lastBackgroundThumbnail = generatedThumbnail
        mediaPlaybackService?.setMediaInfo(
          title = title,
          artist = artist,
          thumbnail = generatedThumbnail,
          uri = currentDurableMediaUri(),
          identifier = mediaIdentifier,
        )
      }
  }

  private fun buildBackgroundThumbnailKey(): String {
    if (mediaIdentifier.isBlank()) return ""
    return "$mediaIdentifier|$playlistIndex"
  }

  /**
   * Get file name from URI (used for playlist items)
   */
  private fun getFileNameFromUri(uri: Uri): String {
    getDisplayNameFromUri(uri)?.let { return it }
    return extractFileNameFromUri(uri)
  }

  /**
   * Get the current video title for controls display.
   * Prefer an explicit intent title when one was supplied by the launcher.
   * For m3u/m3u8 streams, only uses MPV's media-title when it looks valid.
   */
  fun getTitleForControls(): String {
    PlaybackSession.state.value.currentItem?.title
      ?.takeIf { it.isNotBlank() && !HttpUtils.isLikelyJunkTitle(it) }
      ?.let { return it }
    activePlaybackItem?.title
      ?.takeIf { it.isNotBlank() && !HttpUtils.isLikelyJunkTitle(it) }
      ?.let { return it }
    getExplicitIntentTitle()?.let { return it }

    if (HttpUtils.shouldPreferResolvedMediaTitle(extractUriFromIntent(intent), fileName)) {
      PlaybackSession
        .getPropertyString("media-title")
        ?.takeIf { !HttpUtils.isLikelyJunkTitle(it) }
        ?.let { return it }
    }

    // For m3u/m3u8 streams, only trust MPV if it produced a real title.
    if (isCurrentStreamM3U()) {
      PlaybackSession
        .getPropertyString("media-title")
        ?.takeIf { !HttpUtils.isLikelyJunkTitle(it) }
        ?.let { return it }
    }
    return fileName.ifBlank { "Unknown Video" }
  }

  /**
   * Check if the currently playing media is an m3u or m3u8 stream.
   * Checks both the intent URI and the current playlist item if playing from a playlist.
   */
  private fun isCurrentStreamM3U(): Boolean {
    // First check the intent URI
    val uri = extractUriFromIntent(intent)
    if (uri != null && isUriM3U(uri)) {
      return true
    }

    // Also check the current playlist item if playing from a playlist
    if (playlist.isNotEmpty() && playlistIndex >= 0 && playlistIndex < playlist.size) {
      return isUriM3U(playlist[playlistIndex])
    }

    return false
  }

  /**
   * Check if a specific URI is an m3u or m3u8 file/stream.
   */
  private fun isUriM3U(uri: Uri): Boolean {
    val lowerUrl = uri.toString().lowercase()
    return lowerUrl.contains(".m3u8") ||
      lowerUrl.contains(".m3u") ||
      lowerUrl.endsWith(".m3u8") ||
      lowerUrl.endsWith(".m3u")
  }

  /**
   * Save recently played for a specific URI
   */
  private suspend fun saveRecentlyPlayedForUri(
    uri: Uri,
    name: String,
  ) {
    runCatching {
      val filePath =
        when (uri.scheme) {
          "file" -> {
            uri.path ?: uri.toString()
          }

          "content" -> {
            contentResolver
              .query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else {
                  null
                }
              } ?: uri.toString()
          }

          else -> {
            uri.toString()
          }
        }

      // Get parsed video title from MPV
      val videoTitle =
        runCatching {
          PlaybackSession.getPropertyString("media-title")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != name }

      // Get duration and file size from MPV
      val duration =
        runCatching {
          (PlaybackSession.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
        }.getOrDefault(0L)

      val fileSize =
        runCatching {
          // Try multiple properties to get file size
          PlaybackSession.getPropertyDouble("file-size")?.toLong()
            ?: PlaybackSession.getPropertyDouble("stream-end")?.toLong()
            ?: 0L
        }.getOrDefault(0L)

      // Get video resolution from MPV
      val width =
        runCatching {
          PlaybackSession.getPropertyInt("width") ?: PlaybackSession.getPropertyInt("video-params/w") ?: 0
        }.getOrDefault(0)

      val height =
        runCatching {
          PlaybackSession.getPropertyInt("height") ?: PlaybackSession.getPropertyInt("video-params/h") ?: 0
        }.getOrDefault(0)

      val historyPlaylistId = playlistId?.takeUnless(::isAllVideosPlaylist)

      if (isSecureFolderLaunch) {
        Log.d(TAG, "Skipping recently-played save (playlist nav) for secure_folder launch: $filePath")
        return@runCatching
      }

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = name,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = "playlist",
        playlistId = historyPlaylistId,
      )

      Log.d(TAG, "Saved recently played (playlist): $filePath")
      Log.d(TAG, "  - fileName: $name")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x$height")
      Log.d(TAG, "  - playlistId: $historyPlaylistId")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played for playlist item", e)
    }
  }

  /** Generates one collision-resistant identifier without including network credentials. */
  private fun getMediaIdentifier(
    intent: Intent,
    fileName: String,
  ): String {
    intent.getStringExtra("media_identifier")?.takeIf { it.isNotBlank() }?.let { return it }

    // Check if this is a network file played via proxy (SMB/WebDAV/FTP)
    // Use the stable network file path instead of the temporary proxy URL
    val networkFilePath = intent.getStringExtra("network_file_path")
    val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

    if (networkFilePath != null && networkConnectionId != -1L) {
      val identifier = buildNetworkMediaIdentifier(networkConnectionId, networkFilePath)
      return identifier
    }

    val source = extractUriFromIntent(intent)?.toString() ?: parsePathFromIntent(intent) ?: fileName
    if (isTorrentSource(source, intent.type)) {
      val fileIndex = intent.getIntExtra("torrent_file_index", -1)
      return canonicalInfoHash(source)
        ?.let { infoHash -> PlaybackIdentity.forTorrent(infoHash, fileIndex) }
        ?: PlaybackIdentity.forUri("$source\u0000torrent-file:$fileIndex")
    }
    return NetworkPlaybackUri.parse(source)
      ?.let { reference -> PlaybackIdentity.forNetwork(reference.connectionId, reference.path.value) }
      ?: PlaybackIdentity.forUri(source)
  }

  private fun currentDurableMediaUri(): String? =
    PlaybackSession.queue.value.currentItem?.originalUri ?: currentPlayableUri

  /** Old keys remain readable once, then are copied to the v2 collision-resistant key. */
  private fun getLegacyMediaIdentifier(
    intent: Intent,
    fileName: String,
  ): String? {
    if (intent.getStringExtra("media_identifier")?.startsWith("media:v2:") == true) return null
    val networkFilePath = intent.getStringExtra("network_file_path")
    val connectionId = intent.getLongExtra("network_connection_id", -1L)
    if (!networkFilePath.isNullOrBlank() && connectionId != -1L) {
      return "network_${connectionId}_${networkFilePath.hashCode()}"
    }
    val uri = extractUriFromIntent(intent)
    if (uri != null && NetworkPlaybackUri.parse(uri.toString()) != null) return null
    // Local files must not use the bare filename as a legacy key — it is ambiguous when
    // multiple directories contain files with the same display name (issue #382).
    return if (uri != null && isRemotePlaybackUri(uri)) "${fileName}_${uri.toString().hashCode()}" else null
  }

  private fun loadNetworkPlaylistMetadata(intent: Intent) {
    networkPlaylistPaths = intent.getStringArrayListExtra("network_playlist_paths") ?: emptyList()
    networkPlaylistTitles = intent.getStringArrayListExtra("network_playlist_titles") ?: emptyList()
    networkPlaylistHeaders = emptyList()
    networkPlaylistConnectionId = intent.getLongExtra("network_playlist_connection_id", -1L)
  }

  private fun Bundle.toSavedPlaylistSelection(): SavedPlaylistSelection? {
    if (!containsKey(STATE_PLAYLIST_INDEX)) return null
    val index = getInt(STATE_PLAYLIST_INDEX, -1)
    val stableId = getString(STATE_PLAYLIST_STABLE_ID)?.takeIf { it.isNotBlank() }
    val originalUri = getString(STATE_PLAYLIST_ORIGINAL_URI)?.takeIf { it.isNotBlank() }
    return if (index >= 0 && (stableId != null || originalUri != null)) {
      SavedPlaylistSelection(index, stableId, originalUri)
    } else {
      null
    }
  }

  /** Resolves by identity first, with the saved numeric cursor as the final compatibility fallback. */
  private fun applyPendingSavedSelection(materializedPlaylist: List<Uri>): Boolean {
    val saved = pendingSavedPlaylistSelection ?: return false
    val matchingIndex =
      saved.index
        .takeIf { index -> materializedPlaylist.getOrNull(index)?.matches(saved) == true }
        ?: materializedPlaylist.indexOfFirst { uri -> uri.matches(saved) }.takeIf { it >= 0 }
        // A playlist can legitimately refresh URI spellings or stable-ID inputs between process
        // instances. The saved numeric cursor is the final fallback, never the stale launch index.
        ?: saved.index.takeIf { index -> index in materializedPlaylist.indices }
        ?: return false
    playlistIndex = matchingIndex
    Log.d(TAG, "Restored playlist item $matchingIndex from saved Activity state")
    return true
  }

  private fun Uri.matches(saved: SavedPlaylistSelection): Boolean {
    val uri = toString()
    return saved.originalUri == uri || saved.stableId == PlaybackIdentity.forUri(uri)
  }

  private fun publishPlaylistToSession() {
    val items =
      playlist.mapIndexed { index, uri ->
        val databaseItem = playlistItems.getOrNull(index)
        val persistedNetworkReference = NetworkPlaybackUri.parse(uri.toString())
        val networkPath =
          networkPlaylistPaths.getOrNull(index)?.takeIf { it.isNotBlank() }
            ?: persistedNetworkReference?.path?.value
        val networkConnectionId =
          networkPlaylistConnectionId.takeIf { it != -1L }
            ?: persistedNetworkReference?.connectionId
        val networkSource =
          if (networkPath != null && networkConnectionId != null) {
            NetworkPlaybackSource(networkConnectionId, networkPath)
          } else {
            null
          }
        val title =
          databaseItem?.fileName?.takeIf { it.isNotBlank() }
            ?: networkPlaylistTitles.getOrNull(index)?.takeIf { it.isNotBlank() }
            ?: getFileNameFromUri(uri)
        val storedHeaders =
          databaseItem
            ?.userAgent
            ?.takeIf { it.isNotBlank() }
            ?.let { userAgent -> mapOf("User-Agent" to userAgent) }
            .orEmpty()
        val headers = buildPlaybackHeaders(uri, networkPlaylistHeaders.getOrNull(index).orEmpty(), storedHeaders)

        PlaybackItem.fromUri(
          uri = uri.toString(),
          title = title,
          headers = headers,
          networkSource = networkSource,
          playlistItemId = databaseItem?.id,
          artworkUri = databaseItem?.tvgLogo,
        )
      }

    PlaybackSession.replaceQueue(
      items = items,
      currentIndex = playlistIndex,
      isExplicitQueue = true,
      isM3u = isM3uPlaylist,
    )
    PlaybackSession.setRepeatMode(viewModel.repeatMode.value)
    PlaybackSession.setShuffleEnabled(viewModel.shuffleEnabled.value)
  }

  private fun buildNetworkMediaIdentifier(
    connectionId: Long,
    filePath: String,
  ): String = PlaybackIdentity.forNetwork(connectionId, filePath)

  /**
   * Generate a unique identifier for this media from a URI and name.
   *
   * For local/offline files, uses fileName (display name or path).
   * For network URIs (http/https/rtmp/etc.), uses a hash of the URI string to distinguish different streams.
   */
  private fun getMediaIdentifierFromUri(
    uri: Uri,
    @Suppress("UNUSED_PARAMETER") fileName: String,
  ): String =
    NetworkPlaybackUri.parse(uri.toString())
      ?.let { reference -> PlaybackIdentity.forNetwork(reference.connectionId, reference.path.value) }
      ?: PlaybackIdentity.forUri(uri.toString())

  private fun isRemotePlaybackUri(uri: Uri): Boolean =
    uri.scheme?.lowercase() in setOf("http", "https", "rtmp", "rtmps", "ftp", "rtsp", "mms")

  private fun shouldShowPlaybackNotification(): Boolean =
    advancedPreferences.notificationStyle
      .get()
      .takeIf { it.isSupportedOn(Build.VERSION.SDK_INT) }
      ?.let { it != NotificationStyle.None }
      ?: true

  private fun normalizePlaylistFilePath(path: String): String = path.replace("\\", "/")

  private fun naturalSortFiles(files: List<File>): List<File> =
    files.sortedWith { first, second ->
      app.infinity.mpvz.utils.sort.SortUtils.NaturalOrderComparator.DEFAULT
        .compare(first.name, second.name)
    }

  private suspend fun sortSiblingFilesForVideoList(files: List<File>): List<File> {
    val sortType = browserPreferences.videoSortType.get()
    val sortOrder = browserPreferences.videoSortOrder.get()

    val sortedFiles =
      when (sortType) {
        VideoSortType.Title -> naturalSortFiles(files)
        VideoSortType.Date -> files.sortedBy { it.lastModified() }
        VideoSortType.Size -> files.sortedBy { it.length() }
        VideoSortType.Duration -> {
          val fileByPath = files.associateBy { normalizePlaylistFilePath(it.absolutePath) }
          val sortedVideos =
            app.infinity.mpvz.repository.MediaFileRepository
              .getVideosFromFiles(this@PlayerActivity, files)
              .let { videos ->
                app.infinity.mpvz.utils.sort.SortUtils
                  .sortVideos(videos, sortType, sortOrder)
              }
          val resolvedFiles = sortedVideos.mapNotNull { video -> fileByPath[normalizePlaylistFilePath(video.path)] }
          if (resolvedFiles.isEmpty()) {
            naturalSortFiles(files)
          } else {
            val seenPaths = resolvedFiles.mapTo(mutableSetOf()) { normalizePlaylistFilePath(it.absolutePath) }
            resolvedFiles + naturalSortFiles(files.filter { normalizePlaylistFilePath(it.absolutePath) !in seenPaths })
          }
        }
      }

    return if (sortType == VideoSortType.Duration || sortOrder.isAscending) {
      sortedFiles
    } else {
      sortedFiles.reversed()
    }
  }

  private suspend fun resolveAutoPlaylistSiblingFiles(
    currentFile: File,
    launchSource: String,
  ): List<File> {
    val parentFolder = currentFile.parentFile ?: return emptyList()
    val isAudioTarget = isKnownAudioLaunch(intent) || FileTypeUtils.isAudioFile(currentFile)
    val includeAudio = browserPreferences.includeAudioBrowser.get()
    val minimumAudioDurationMs = browserPreferences.minimumAudioDurationSeconds.get() * 1000L
    val isEligibleMediaFile: (File) -> Boolean = { file ->
      file.isFile &&
        !file.name.startsWith(".") &&
        if (isAudioTarget) {
          FileTypeUtils.isAudioFile(file) &&
            (
              minimumAudioDurationMs == 0L ||
                FileTypeUtils.getDurationMs(file) >= minimumAudioDurationMs
            )
        } else {
          FileTypeUtils.isVideoFile(file)
        }
    }
    val directMediaFiles = parentFolder.listFiles { file -> isEligibleMediaFile(file) }?.toList().orEmpty()
    Log.d(
      TAG,
      "resolveAutoPlaylistSiblingFiles: current=${currentFile.absolutePath} parent=${parentFolder.absolutePath} " +
        "launchSource=$launchSource directFiles=${directMediaFiles.size} isAudioTarget=$isAudioTarget",
    )

    // Keep the immediate season folder as the default. If it has no usable siblings, walk upward
    // and search descendants so an Anime/Show/Season/Episode layout can still form a queue for
    // file-manager and other launches too. The previous launch-source gate returned a singleton
    // immediately for those launches, preventing nested MKV folders from reaching this fallback.
    var playlistMediaFiles = directMediaFiles
    if (playlistMediaFiles.size <= 1) {
      var ancestorFolder: File? = parentFolder
      while (ancestorFolder != null && playlistMediaFiles.size <= 1) {
        val recursiveMediaFiles =
          ancestorFolder
            .walkTopDown()
            .filter(isEligibleMediaFile)
            .toList()
        if (recursiveMediaFiles.size > playlistMediaFiles.size) {
          playlistMediaFiles = recursiveMediaFiles
        }
        ancestorFolder = ancestorFolder.parentFile
      }
    }
    Log.d(
      TAG,
      "resolveAutoPlaylistSiblingFiles: playlistMediaFiles=${playlistMediaFiles.size} " +
        "after ancestor walk current=${currentFile.absolutePath}",
    )

    val currentFilePath = normalizePlaylistFilePath(currentFile.absolutePath)
    val fileByPath = playlistMediaFiles.associateBy { normalizePlaylistFilePath(it.absolutePath) }
    val sortedFromLibrary =
      app.infinity.mpvz.repository.MediaFileRepository
        .getVideosInFolder(context, normalizePlaylistFilePath(parentFolder.absolutePath))
        .let { videos ->
          app.infinity.mpvz.utils.sort.SortUtils.sortVideos(
            videos,
            browserPreferences.videoSortType.get(),
            browserPreferences.videoSortOrder.get(),
          )
        }.mapNotNull { video -> fileByPath[normalizePlaylistFilePath(video.path)] }
    Log.d(
      TAG,
      "resolveAutoPlaylistSiblingFiles: sortedFromLibrary=${sortedFromLibrary.size} " +
        "currentMatched=${sortedFromLibrary.any { normalizePlaylistFilePath(it.absolutePath) == currentFilePath }}",
    )

    return if (
      sortedFromLibrary.size > 1 &&
        sortedFromLibrary.any { normalizePlaylistFilePath(it.absolutePath) == currentFilePath }
    ) {
      sortedFromLibrary
    } else {
      sortSiblingFilesForVideoList(playlistMediaFiles)
    }
  }

  private suspend fun loadPlaylistById(
    pid: Int,
    sourceIntent: Intent,
    logPrefix: String,
    expectedGeneration: Long = mediaRequestGeneration,
  ) {
    if (isAllVideosPlaylist(pid)) {
      val isAudioTarget = sourceIntent.getBooleanExtra("media_library_audio", false) || isKnownAudioLaunch(sourceIntent)
      val mediaLibraryAudio = sourceIntent.getBooleanExtra("media_library_audio", false) || isAudioTarget
      val isMediaLibraryLaunch = sourceIntent.getStringExtra("launch_source") == "media_library" || isAudioTarget
      val allVideos =
        app.infinity.mpvz.utils.sort.SortUtils.sortVideos(
          app.infinity.mpvz.repository.MediaFileRepository
            .getAllVideos(
              context = this@PlayerActivity,
              includeAudioOverride = if (isMediaLibraryLaunch || isAudioTarget) true else null,
            ).let { media ->
              if (isMediaLibraryLaunch || isAudioTarget) {
                media.filter { it.isAudio == mediaLibraryAudio }
              } else {
                media.filter { !it.isAudio }
              }
            },
          browserPreferences.videoSortType.get(),
          browserPreferences.videoSortOrder.get(),
        )
      val playlistUris = allVideos.map { it.uri }
      val resolvedPath = parsePathFromIntent(sourceIntent)
      val resolvedUri = sourceIntent.dataString
      val derivedIndex =
        allVideos.indexOfFirst { video ->
          video.path == resolvedPath || video.uri.toString() == resolvedUri
        }
      val syntheticItems =
        allVideos.mapIndexed { index, video ->
          PlaylistItemEntity(
            id = index + 1,
            playlistId = ALL_VIDEOS_PLAYLIST_ID,
            filePath = video.path,
            fileName = video.displayName,
            position = index,
            addedAt = video.dateAdded * 1000L,
          )
        }
      val updatedAt =
        allVideos.maxOfOrNull { it.dateModified * 1000L } ?: System.currentTimeMillis()
      if (expectedGeneration != mediaRequestGeneration) return

      withContext(Dispatchers.Main) {
        if (expectedGeneration != mediaRequestGeneration) return@withContext
        playlistEntity = buildAllVideosPlaylistEntity(updatedAt = updatedAt)
        playlistItems = syntheticItems
        isM3uPlaylist = false
        playlist = playlistUris
        networkPlaylistHeaders = emptyList()
        playlistWindowOffset = 0
        playlistTotalCount = playlistUris.size
        playlistIndex =
          derivedIndex.takeIf { it >= 0 }
            ?: playlistIndex.coerceIn(0, (playlistUris.lastIndex).coerceAtLeast(0))
        Log.d(TAG, "$logPrefix ${playlistUris.size} items from all-videos playlist")
        val restoringSavedSelection = pendingSavedPlaylistSelection != null
        if (restoringSavedSelection) {
          applyPendingSavedSelection(playlist)
          pendingSavedPlaylistSelection = null
        }
        publishPlaylistToSession()
        viewModel.refreshPlaylistItems()
        if (restoringSavedSelection && playlist.isNotEmpty()) {
          loadPlaylistItemInternal(playlistIndex, saveCurrentPlaybackState = false)
        }
      }
      return
    }

    val loadedPlaylist = playlistRepository.getPlaylistById(pid)
    val loadedItems = playlistRepository.getPlaylistItems(pid)
    val items = loadedItems.map { Uri.parse(it.filePath) }
    val totalCount = loadedItems.size
    if (expectedGeneration != mediaRequestGeneration) return

    withContext(Dispatchers.Main) {
      if (expectedGeneration != mediaRequestGeneration) return@withContext
      playlistEntity = loadedPlaylist
      playlistItems = loadedItems
      isM3uPlaylist = loadedPlaylist?.isM3uPlaylist == true
      playlist = items
      networkPlaylistHeaders = emptyList()
      playlistIndex = if (items.isEmpty()) 0 else playlistIndex.coerceIn(items.indices)
      playlistWindowOffset = 0
      playlistTotalCount = totalCount
      Log.d(TAG, "$logPrefix all $totalCount items from playlist $pid (isM3U: $isM3uPlaylist)")
      val restoringSavedSelection = pendingSavedPlaylistSelection != null
      if (restoringSavedSelection) {
        applyPendingSavedSelection(playlist)
        pendingSavedPlaylistSelection = null
      }
      publishPlaylistToSession()
      viewModel.refreshPlaylistItems()
      if (restoringSavedSelection && playlist.isNotEmpty()) {
        loadPlaylistItemInternal(playlistIndex, saveCurrentPlaybackState = false)
      }
    }
  }

  private fun generatePlaylistFromMediaStore(currentUri: Uri) {
    val expectedGeneration = mediaRequestGeneration
    folderDiscoveryInFlightGeneration = expectedGeneration
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        generatePlaylistFromMediaStoreInternal(currentUri, expectedGeneration)
      } finally {
        if (folderDiscoveryInFlightGeneration == expectedGeneration) {
          folderDiscoveryInFlightGeneration = null
        }
      }
    }
  }

  private suspend fun generatePlaylistFromMediaStoreInternal(
    currentUri: Uri,
    expectedGeneration: Long,
  ): Boolean =
    runCatching {
      data class MediaStoreVideo(
        val id: Long,
        val uri: Uri,
        val name: String,
        val dateModified: Long,
        val size: Long,
        val duration: Long,
      )

      val filesUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
      var currentMediaId: Long? = null
      var currentDisplayName = ""
      var currentSize = 0L
      var relativePath =
        contentResolver
          .query(
            currentUri,
            arrayOf(
              MediaStore.MediaColumns.RELATIVE_PATH,
              MediaStore.MediaColumns._ID,
              MediaStore.MediaColumns.DISPLAY_NAME,
              MediaStore.MediaColumns.SIZE,
            ),
            null,
            null,
            null,
          )?.use { cursor ->
            if (cursor.moveToFirst()) {
              val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
              val pathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
              val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
              val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
              if (idColumn != -1) currentMediaId = cursor.getLong(idColumn)
              if (nameColumn != -1) currentDisplayName = cursor.getString(nameColumn).orEmpty()
              if (sizeColumn != -1) currentSize = cursor.getLong(sizeColumn)
              if (pathColumn != -1) cursor.getString(pathColumn).orEmpty() else ""
            } else {
              ""
            }
          }.orEmpty()

      // Some document-provider URIs expose the MediaStore ID but omit RELATIVE_PATH. Resolve the
      // same row through MediaStore.Video.Media before giving up on folder discovery.
      if (relativePath.isBlank()) {
        val documentId =
          currentUri.lastPathSegment
            ?.substringAfterLast(':')
            ?.toLongOrNull()
        if (documentId != null) {
          contentResolver
            .query(
              filesUri,
              arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns._ID),
              "${MediaStore.MediaColumns._ID}=?",
              arrayOf(documentId.toString()),
              null,
            )?.use { cursor ->
              if (cursor.moveToFirst()) {
                currentMediaId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                relativePath =
                  cursor
                    .getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                    .orEmpty()
              }
            }
        }
      }
      // A file manager may provide a document URI whose last segment is a provider-specific token,
      // not the numeric MediaStore ID. If the URI still exposes the filename, use filename plus
      // size to resolve the corresponding indexed video row and recover its parent folder.
      if (relativePath.isBlank() && currentDisplayName.isNotBlank()) {
        val selection =
          if (currentSize > 0L) {
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?"
          } else {
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
          }
        val selectionArgs =
          if (currentSize > 0L) arrayOf(currentDisplayName, currentSize.toString())
          else arrayOf(currentDisplayName)
        contentResolver
          .query(
            filesUri,
            arrayOf(
              MediaStore.MediaColumns.RELATIVE_PATH,
              MediaStore.MediaColumns._ID,
            ),
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
          )?.use { cursor ->
            if (cursor.moveToFirst()) {
              currentMediaId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
              relativePath =
                cursor
                  .getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                  .orEmpty()
            }
          }
      }
      if (relativePath.isBlank()) {
        Log.d(TAG, "MediaStore folder queue skipped: no relative path for $currentUri name=$currentDisplayName")
        return@runCatching false
      }
      Log.d(TAG, "MediaStore folder probe: uri=$currentUri relativePath=$relativePath currentId=$currentMediaId")

      fun queryVideos(selection: String, selectionArgs: Array<String>): List<MediaStoreVideo> =
        buildList {
          contentResolver
            .query(
              filesUri,
              arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.RELATIVE_PATH,
              ),
              selection,
              selectionArgs,
              null,
            )?.use { cursor ->
              val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
              val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
              val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
              val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
              val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
              if (cursor.moveToFirst()) {
                do {
                  val name = cursor.getString(nameColumn).orEmpty()
                  if (
                    name.startsWith(".") ||
                      !FileTypeUtils.VIDEO_EXTENSIONS.contains(
                        name.substringAfterLast('.', "").lowercase(),
                      )
                  ) {
                    continue
                  }
                  val id = cursor.getLong(idColumn)
                  add(
                    MediaStoreVideo(
                      id = id,
                      uri = ContentUris.withAppendedId(filesUri, id),
                      name = name,
                      dateModified = cursor.getLong(dateColumn),
                      size = cursor.getLong(sizeColumn),
                      duration = cursor.getLong(durationColumn),
                    ),
                  )
                } while (cursor.moveToNext())
              }
            }
        }

      // Prefer the immediate season folder. If it contains only one item, broaden the query to
      // ancestor folders so layouts such as Anime/Show/Season 1/Episode.mkv can still produce a
      // usable queue when the user opened the series rather than the season directory.
      var videos =
        queryVideos(
          "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
          arrayOf(relativePath),
        )
      if (videos.size <= 1) {
        val pathParts = relativePath.trimEnd('/').split('/').filter { it.isNotBlank() }
        for (partCount in (pathParts.size - 1) downTo 1) {
          val ancestorPath = pathParts.take(partCount).joinToString("/") + "/"
          val ancestorVideos =
            queryVideos(
              "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
              arrayOf("$ancestorPath%"),
            )
          if (ancestorVideos.size > videos.size) videos = ancestorVideos
          if (videos.size > 1) break
        }
      }
      if (videos.size <= 1) {
        Log.d(TAG, "MediaStore folder queue skipped: ${videos.size} video(s) in $relativePath")
        return@runCatching false
      }

      val sortedVideos =
        when (browserPreferences.videoSortType.get()) {
          VideoSortType.Date -> videos.sortedBy { it.dateModified }
          VideoSortType.Size -> videos.sortedBy { it.size }
          VideoSortType.Duration -> videos.sortedBy { it.duration }
          VideoSortType.Title -> videos.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }.let { sorted ->
          if (browserPreferences.videoSortOrder.get().isAscending) sorted else sorted.reversed()
        }
      val resolvedCurrentId =
        currentMediaId
          ?: currentUri.lastPathSegment
            ?.substringAfterLast(':')
            ?.toLongOrNull()
      val newIndex = sortedVideos.indexOfFirst { it.id == resolvedCurrentId }
      if (newIndex < 0 || expectedGeneration != mediaRequestGeneration) return@runCatching false

      withContext(Dispatchers.Main) {
        if (expectedGeneration != mediaRequestGeneration) return@withContext
        playlistEntity = null
        playlistItems = emptyList()
        isM3uPlaylist = false
        playlist = sortedVideos.map { it.uri }
        networkPlaylistHeaders = emptyList()
        playlistIndex = newIndex
        val restoredSavedSelection = applyPendingSavedSelection(playlist)
        if (pendingSavedPlaylistSelection != null) pendingSavedPlaylistSelection = null
        publishPlaylistToSession()
        viewModel.refreshPlaylistItems()
        Log.d(TAG, "MediaStore auto-playlist generated: ${playlist.size} items in $relativePath")
        if (restoredSavedSelection) {
          loadPlaylistItemInternal(playlistIndex, saveCurrentPlaybackState = false)
        }
      }
      true
    }.onFailure { error ->
      Log.e(TAG, "Failed to generate MediaStore folder playlist", error)
    }.getOrDefault(false)

  private fun generatePlaylistFromFolder(currentPath: String) {
    val expectedGeneration = mediaRequestGeneration
    val sourceUri =
      (externalContentLaunchUri ?: extractUriFromIntent(intent))
        ?.takeIf { it.scheme == "content" }
    folderDiscoveryInFlightGeneration = expectedGeneration

    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val generated = generatePlaylistFromFolderInternal(currentPath, expectedGeneration)
        if (!generated && sourceUri != null && expectedGeneration == mediaRequestGeneration) {
          Log.d(TAG, "Filesystem folder queue unavailable; retrying MediaStore for $sourceUri")
          generatePlaylistFromMediaStoreInternal(sourceUri, expectedGeneration)
        }
      } finally {
        if (folderDiscoveryInFlightGeneration == expectedGeneration) {
          folderDiscoveryInFlightGeneration = null
        }
      }
    }
  }

  private suspend fun generatePlaylistFromFolderInternal(
    currentPath: String,
    expectedGeneration: Long = mediaRequestGeneration,
  ): Boolean =
    runCatching {
      val currentFile = File(currentPath)
      Log.d(
        TAG,
        "generatePlaylistFromFolderInternal: start currentPath=$currentPath " +
          "absolute=${currentFile.absolutePath} expectedGeneration=$expectedGeneration " +
          "actualGeneration=$mediaRequestGeneration",
      )
      if (!currentFile.exists()) {
        Log.d(TAG, "Filesystem folder queue skipped: current file missing $currentPath")
        return@runCatching false
      }

      val launchSource = intent.getStringExtra("launch_source") ?: ""
      val siblingFiles = resolveAutoPlaylistSiblingFiles(currentFile, launchSource)
      Log.d(
        TAG,
        "generatePlaylistFromFolderInternal: siblingFiles=${siblingFiles.size} " +
          "current=${currentFile.absolutePath}",
      )
      if (siblingFiles.size <= 1) {
        Log.d(TAG, "Filesystem folder queue skipped: ${siblingFiles.size} video(s) for $currentPath")
        return@runCatching false
      }

      val currentFilePath = normalizePlaylistFilePath(currentFile.absolutePath)
      val currentFileName = currentFile.name
      val newIndex =
        siblingFiles.indexOfFirst {
          normalizePlaylistFilePath(it.absolutePath) == currentFilePath
        }.takeIf { it >= 0 }
          ?: siblingFiles.indexOfFirst { it.name == currentFileName }
      Log.d(
        TAG,
        "generatePlaylistFromFolderInternal: newIndex=$newIndex " +
          "currentName=${currentFile.name} candidates=${siblingFiles.joinToString { it.name }}",
      )
      if (newIndex < 0) {
        Log.d(TAG, "Filesystem folder queue skipped: current file not found in ${siblingFiles.size} candidates")
        return@runCatching false
      }
      if (expectedGeneration != mediaRequestGeneration) {
        Log.d(TAG, "Filesystem folder queue skipped: stale generation expected=$expectedGeneration actual=$mediaRequestGeneration")
        return@runCatching false
      }

      withContext(Dispatchers.Main) {
        if (expectedGeneration != mediaRequestGeneration) return@withContext
        playlistEntity = null
        playlistItems = emptyList()
        isM3uPlaylist = false
        playlist = siblingFiles.map { it.toUri() }
        networkPlaylistHeaders = emptyList()
        playlistIndex = newIndex
        val restoredSavedSelection = applyPendingSavedSelection(playlist)
        if (pendingSavedPlaylistSelection != null) pendingSavedPlaylistSelection = null
        publishPlaylistToSession()
        viewModel.refreshPlaylistItems()
        Log.d(TAG, "Auto-playlist generated: ${playlist.size} items")
        if (restoredSavedSelection) {
          loadPlaylistItemInternal(playlistIndex, saveCurrentPlaybackState = false)
        }
      }
      true
    }.onFailure { error ->
      Log.e(TAG, "Failed to auto-generate playlist", error)
    }.getOrDefault(false)

  /**
   * Check if the current playlist is an M3U playlist (sourced from database).
   */
  fun isCurrentPlaylistM3U(): Boolean = isM3uPlaylist

  private suspend fun loadDynamicM3uPlaylist(
    uriString: String,
    sourceIntent: Intent,
  ): Boolean {
    val requestHeaders =
      buildPlaybackHeaders(
        Uri.parse(uriString),
        PlaybackHttpHeaders.fromFlatPairs(sourceIntent.extras?.getStringArray("headers")),
      )
    val userAgent = PlaybackHttpHeaders.userAgent(requestHeaders)
    val parseResult =
      when {
        uriString.startsWith("http://") || uriString.startsWith("https://") ->
          M3UParser.parseFromUrl(
            url = uriString,
            userAgent = userAgent,
            headers = requestHeaders,
            httpClient = networkHttpClient,
          )
        uriString.startsWith("content://") || uriString.startsWith("file://") ->
          M3UParser.parseFromUri(this, Uri.parse(uriString))
        else -> {
          val file = File(uriString)
          if (!file.isFile) return false
          M3UParser.parseFromStream(file.inputStream(), sourceUrl = file.toURI().toString())
        }
      }

    if (M3UParser.shouldPlayHlsDirectly(parseResult)) {
      Log.d(TAG, "M3U source is an HLS media manifest; handing it directly to mpv")
      return false
    }

    if (parseResult is M3UParseResult.Success) {
      val items = parseResult.items
      if (items.isNotEmpty()) {
        withContext(Dispatchers.Main) {
          isM3uPlaylist = true
          playlist = items.map { Uri.parse(it.url) }
          networkPlaylistTitles = items.map { it.title ?: extractFileNameFromUri(Uri.parse(it.url)) }
          networkPlaylistPaths = items.map { it.url }
          networkPlaylistHeaders =
            items.map { item ->
              buildPlaybackHeaders(
                Uri.parse(item.url),
                requestHeaders,
                item.userAgent?.let { mapOf("User-Agent" to it) }.orEmpty(),
              )
            }
          playlistWindowOffset = 0
          playlistTotalCount = items.size

          Log.d(TAG, "Dynamically loaded M3U playlist with ${items.size} items")
          applyPendingSavedSelection(playlist)
          if (pendingSavedPlaylistSelection != null) pendingSavedPlaylistSelection = null
          publishPlaylistToSession()
          viewModel.refreshPlaylistItems()
        }
        return true
      }
    }
    return false
  }

  /**
   * Disables video decoding to save battery when moving to background playback.
   */
  private fun disableVideoForBackground() {
    if (!isReady || fileName.isBlank()) return
    if (isMiniPlayerEnabled()) return

    val currentVid = PlaybackSession.getPropertyInt("vid") ?: -1
    if (currentVid > 0) {
      lastVid = currentVid
      PlaybackSession.setPropertyString("vid", "no")
      isInBackgroundPlayback = true
      Log.d(TAG, "Video disabled for background playback (saved vid: $lastVid)")
    }
  }

  /**
   * Restores video decoding when returning from background playback.
   */
  private fun enableVideoAfterBackground() {
    if ((isInBackgroundPlayback || lastVid > 0) && !player.isSurfaceReady) {
      Log.d(TAG, "Deferring video restoration until the playback surface is ready")
      return
    }

    val wereInBackground = isInBackgroundPlayback
    isInBackgroundPlayback = false

    if (wereInBackground && lastVid > 0) {
      if (!viewModel.isAudioOnly.value && !isCurrentMediaKnownAudio()) {
        Log.d(TAG, "Restoring video after background playback (vid: $lastVid)")
        PlaybackSession.setPropertyInt("vid", lastVid)
      } else {
        Log.d(TAG, "Skipping video track restoration because media is in audio-only mode")
      }
      lastVid = -1
    }

    // The SurfaceView can survive task switching without a surfaceChanged callback. Post the
    // refresh until layout has settled so libmpv receives the actual foreground dimensions.
    player.post {
      if (mpvInitialized && !isFinishing && !isDestroyed) {
        player.refreshSurfaceSize()
      }
    }
  }

  companion object {
    @Volatile
    private var activeInstance: WeakReference<PlayerActivity>? = null

    @Volatile
    private var detachedMedia3Controller: Media3PlaybackController? = null

    /** Stop native Media3 playback when the mini-player/service Stop action is explicit. */
    fun requestHardStopFromService() {
      val activity = activeInstance?.get()
      if (activity != null) {
        activity.runOnUiThread { activity.requestExplicitHardStop() }
      } else {
        detachedMedia3Controller?.let { controller ->
          detachedMedia3Controller = null
          runCatching {
            controller.detachUiCallbacks()
            controller.release()
          }
        }
      }
    }

    /**
     * Intent action used to return playback result data to the calling activity.
     */
    private const val RESULT_INTENT = "app.infinity.mpvz.ui.player.PlayerActivity.result"

    /**
     * Constant for "brightness not set".
     */
    private const val BRIGHTNESS_NOT_SET = -1f

    /**
     * Constant used when playback position is not set.
     */
    private const val POSITION_NOT_SET = 0

    /**
     * Maximum volume for MPV in percent.
     */
    private const val MAX_MPV_VOLUME = 100

    private const val AUDIO_FOCUS_RETRY_DELAY_MS = 1000L
    private const val AUDIO_FOCUS_RETRY_MAX_ATTEMPTS = 5

    /**
     * Milliseconds-to-seconds conversion factor.
     */
    private const val MILLISECONDS_TO_SECONDS = 1000

    /**
     * Factor to divide subtitle and audio delays to convert from ms to seconds.
     */
    private const val DELAY_DIVISOR = 1000.0

    /**
     * Default playback speed (1.0 = normal).
     */
    private const val DEFAULT_PLAYBACK_SPEED = 1.0

    /**
     * Default subtitle speed (1.0 = normal).
     */
    private const val DEFAULT_SUB_SPEED = 1.0

    /**
     * General tag for logging from PlayerActivity.
     */
    const val TAG = "MpvInfinity"

    const val EXTRA_PREPARED_PLAYBACK_QUEUE = "prepared_playback_queue"
    private const val STATE_PLAYLIST_INDEX = "player_state_playlist_index"
    private const val STATE_PLAYLIST_STABLE_ID = "player_state_playlist_stable_id"
    private const val STATE_PLAYLIST_ORIGINAL_URI = "player_state_playlist_original_uri"
  }
}
