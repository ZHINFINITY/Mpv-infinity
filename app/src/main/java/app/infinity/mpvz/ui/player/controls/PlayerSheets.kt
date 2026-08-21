/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.controls

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.infinity.mpvz.preferences.AudioChannels
import app.infinity.mpvz.preferences.PlaybackEngineMode
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.player.Decoder
import app.infinity.mpvz.ui.player.Panels
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.Sheets
import app.infinity.mpvz.ui.player.TrackNode
import app.infinity.mpvz.ui.player.controls.components.sheets.AmbientSheet
import app.infinity.mpvz.ui.player.controls.components.sheets.AspectRatioSheet
import app.infinity.mpvz.ui.player.controls.components.sheets.AudioTracksSheet
import app.infinity.mpvz.ui.player.controls.components.sheets.ChaptersSheet
import app.infinity.mpvz.ui.player.controls.components.sheets.DecodersSheet
import app.infinity.mpvz.ui.player.controls.components.sheets.FrameNavigationSheet
import app.infinity.mpvz.ui.player.controls.components.sheets.MoreSheet
import app.infinity.mpvz.ui.player.controls.components.sheets.OnlineSubtitleSearchSheet
import app.infinity.mpvz.ui.player.controls.components.sheets.PlaybackSpeedSheet
import app.infinity.mpvz.ui.player.controls.components.sheets.PlaylistSheet
import app.infinity.mpvz.ui.player.controls.components.sheets.SubtitlesSheet
import app.infinity.mpvz.ui.player.controls.components.sheets.VideoZoomSheet
import app.infinity.mpvz.ui.player.controls.components.sheets.VisualizerStyleSheet
import dev.vivvvek.seeker.Segment
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import androidx.compose.runtime.collectAsState as composeCollectAsState

@Composable
fun PlayerSheets(
  sheetShown: Sheets,
  viewModel: app.infinity.mpvz.ui.player.PlayerViewModel,
  // subtitles sheet
  subtitles: ImmutableList<TrackNode>,
  onAddSubtitle: (Uri) -> Unit,
  onToggleSubtitle: (Int) -> Unit,
  isSubtitleSelected: (Int) -> Boolean,
  subtitleSelectionIndicator: (Int) -> String?,
  onRemoveSubtitle: (Int) -> Unit,
  // audio sheet
  audioTracks: ImmutableList<TrackNode>,
  onAddAudio: (Uri) -> Unit,
  onSelectAudio: (TrackNode) -> Unit,
  onMedia3AudioChannels: ((AudioChannels) -> Unit)? = null,
  onMedia3AudioProcessing: ((Boolean, Boolean) -> Unit)? = null,
  onMedia3AudioPitchCorrection: ((Boolean) -> Unit)? = null,
  // chapters sheet
  chapter: Segment?,
  chapters: ImmutableList<Segment>,
  onSeekToChapter: (Int) -> Unit,
  // Decoders sheet
  decoder: Decoder,
  isMedia3Active: Boolean = false,
  engineSelection: PlaybackEngineMode = if (isMedia3Active) PlaybackEngineMode.Media3 else PlaybackEngineMode.MPV,
  onEngineSelected: (PlaybackEngineMode) -> Unit = {},
  media3DecoderName: String? = null,
  onUpdateDecoder: (Decoder) -> Unit,
  // Speed sheet
  speed: Float,
  speedPresets: List<Float>,
  onSpeedChange: (Float) -> Unit,
  onAddSpeedPreset: (Float) -> Unit,
  onRemoveSpeedPreset: (Float) -> Unit,
  onResetSpeedPresets: () -> Unit,
  onMakeDefaultSpeed: (Float) -> Unit,
  onResetDefaultSpeed: () -> Unit,
  // More sheet
  sleepTimerTimeRemaining: Int,
  onStartSleepTimer: (Int) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  onShowSheet: (Sheets) -> Unit,
  videoFormatStatus: app.infinity.mpvz.ui.player.VideoFormatStatus? = null,
  onDismissRequest: () -> Unit,
) {
  when (sheetShown) {
    Sheets.None -> {}
    Sheets.SubtitleTracks -> {
      val subtitlesPicker =
        rememberLauncherForActivityResult(
          ActivityResultContracts.OpenDocument(),
        ) {
          if (it == null) return@rememberLauncherForActivityResult
          onAddSubtitle(it)
        }

      val subtitlesPreferences = koinInject<app.infinity.mpvz.preferences.SubtitlesPreferences>()
      val savedPickerPath = subtitlesPreferences.pickerPath.get()

      val currentMediaTitle = viewModel.currentMediaTitle
      val matchToName =
        if (currentMediaTitle.isNotBlank()) {
          // Remove extension if present to improve matching
          currentMediaTitle.substringBeforeLast(".")
        } else {
          null
        }

      var showFilePicker by remember { mutableStateOf(false) }

      if (showFilePicker) {
        app.infinity.mpvz.ui.browser.dialogs.FilePickerDialog(
          isOpen = true,
          currentPath = savedPickerPath,
          onDismiss = { showFilePicker = false },
          onPathChanged = { path ->
            if (path != null) {
              subtitlesPreferences.pickerPath.set(path)
            }
          },
          onFileSelected = { path ->
            showFilePicker = false
            onAddSubtitle(Uri.parse("file://$path"))
          },
          onSystemPickerRequest = {
            showFilePicker = false
            subtitlesPicker.launch(
              arrayOf(
                "text/plain",
                "text/srt",
                "text/vtt",
                "application/x-subrip",
                "application/x-subtitle",
                "text/x-ssa",
                "*/*",
              ),
            )
          },
          matchToName = matchToName,
        )
      }

      val isTranslating by viewModel.isTranslatingSub.composeCollectAsState()
      val translationProgress by viewModel.translationProgress.composeCollectAsState()
      val translationStatus by viewModel.translationStatus.composeCollectAsState()
      val translatingTrackId by viewModel.translatingTrackId.composeCollectAsState()
      val translatingTrackName by viewModel.translatingTrackName.composeCollectAsState()
      val isGeneratingSubtitles by viewModel.isGeneratingSubtitles.composeCollectAsState()
      val subtitleGenerationProgress by viewModel.subtitleGenerationProgress.composeCollectAsState()
      val subtitleGenerationStatus by viewModel.subtitleGenerationStatus.composeCollectAsState()
      val aiPreferences = koinInject<app.infinity.mpvz.preferences.AiPreferences>()
      val aiEnabled by aiPreferences.enabled.collectAsState()
      val realtimeSubsEnabled by aiPreferences.realtimeSubsEnabled.collectAsState()
      val translationEnabled by aiPreferences.subtitleTranslationEnabled.collectAsState()
      val autoTranslateLanguages by aiPreferences.autoTranslateLanguages.collectAsState()

      val subtitlesOff = subtitles.none { isSubtitleSelected(it.id) }

      SubtitlesSheet(
        tracks = subtitles.toImmutableList(),
        onToggleSubtitle = onToggleSubtitle,
        isSubtitleSelected = isSubtitleSelected,
        subtitleSelectionIndicator = subtitleSelectionIndicator,
        onAddSubtitle = { showFilePicker = true },
        onRemoveSubtitle = onRemoveSubtitle,
        onOpenSubtitleSettings = { onOpenPanel(Panels.SubtitleSettings) },
        onOpenSubtitleDelay = { onOpenPanel(Panels.SubtitleDelay) },
        onOpenOnlineSearch = { onShowSheet(Sheets.OnlineSubtitleSearch) },
        onDismissRequest = onDismissRequest,
        onTranslateSubtitle = { track, lang -> viewModel.translateSubtitle(track, lang) },
        onGenerateSubtitle = { viewModel.generateSubtitles("", "") },
        onCancelTranslation = { viewModel.cancelTranslation() },
        isTranslating = isTranslating,
        translationProgress = translationProgress,
        translationStatus = translationStatus,
        translationEnabled = aiEnabled && translationEnabled,
        isGeneratingSubtitles = isGeneratingSubtitles,
        subtitleGenerationProgress = subtitleGenerationProgress,
        subtitleGenerationStatus = subtitleGenerationStatus,
        translatingTrackId = translatingTrackId,
        translatingTrackName = translatingTrackName,
        autoTranslateLanguages = autoTranslateLanguages,
        aiEnabled = aiEnabled,
        realtimeSubsEnabled = realtimeSubsEnabled,
        subtitlesOff = subtitlesOff,
        onDisableSubtitles = { viewModel.disableSubtitles() },
      )
    }

    Sheets.OnlineSubtitleSearch -> {
      val isSearching by viewModel.isSearchingSub.composeCollectAsState()
      val isDownloading by viewModel.isDownloadingSub.composeCollectAsState()
      val results by viewModel.onlineSubtitleSearchResults.composeCollectAsState()
      val isOnlineSectionExpanded by viewModel.isOnlineSectionExpanded.composeCollectAsState()
      val subtitlesPreferences = koinInject<app.infinity.mpvz.preferences.SubtitlesPreferences>()
      val subtitleSearchMode by subtitlesPreferences.onlineSubtitleSearchMode.collectAsState()

      // Media Search / Autocomplete
      val mediaResults by viewModel.mediaSearchResults.composeCollectAsState()
      val isSearchingMedia by viewModel.isSearchingMedia.composeCollectAsState()

      // TV Show / Seasons / Episodes
      val selectedTvShow by viewModel.selectedTvShow.composeCollectAsState()
      val isFetchingTvDetails by viewModel.isFetchingTvDetails.composeCollectAsState()
      val selectedSeason by viewModel.selectedSeason.composeCollectAsState()
      val seasonEpisodes by viewModel.seasonEpisodes.composeCollectAsState()
      val isFetchingEpisodes by viewModel.isFetchingEpisodes.composeCollectAsState()
      val selectedEpisode by viewModel.selectedEpisode.composeCollectAsState()

      OnlineSubtitleSearchSheet(
        onDismissRequest = onDismissRequest,
        onDownloadOnline = { viewModel.downloadSubtitle(it) },
        isSearching = isSearching,
        isDownloading = isDownloading,
        searchResults = results.toImmutableList(),
        isOnlineSectionExpanded = isOnlineSectionExpanded,
        onToggleOnlineSection = { viewModel.toggleOnlineSection() },
        mediaTitle = viewModel.currentMediaTitle,
        showWyzieSelection = subtitleSearchMode != app.infinity.mpvz.repository.subtitle.OnlineSubtitleSearchMode.SUBHUB,
        // Autocomplete & Series Selection
        mediaSearchResults = mediaResults.toImmutableList(),
        isSearchingMedia = isSearchingMedia,
        onSearchMedia = viewModel::searchOnlineSubtitles,
        onSelectMedia = { viewModel.selectMedia(it) },
        selectedTvShow = selectedTvShow,
        isFetchingTvDetails = isFetchingTvDetails,
        selectedSeason = selectedSeason,
        onSelectSeason = { viewModel.selectSeason(it) },
        seasonEpisodes = seasonEpisodes.toImmutableList(),
        isFetchingEpisodes = isFetchingEpisodes,
        selectedEpisode = selectedEpisode,
        onSelectEpisode = { viewModel.selectEpisode(it) },
        onClearMediaSelection = { viewModel.clearMediaSelection() },
      )
    }

    Sheets.AudioTracks -> {
      val audioPicker =
        rememberLauncherForActivityResult(
          ActivityResultContracts.OpenDocument(),
        ) {
          if (it == null) return@rememberLauncherForActivityResult
          onAddAudio(it)
        }

      val audioPreferences = koinInject<app.infinity.mpvz.preferences.AudioPreferences>()
      val savedPickerPath = audioPreferences.pickerPath.get()

      val currentMediaTitle = viewModel.currentMediaTitle
      val matchToName =
        if (currentMediaTitle.isNotBlank()) {
          currentMediaTitle.substringBeforeLast(".")
        } else {
          null
        }

      var showAudioFilePicker by remember { mutableStateOf(false) }

      val audioAllowedExtensions =
        remember {
          listOf(
            // Common compressed audio
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "wma",
            // Lossless audio
            "flac", "alac", "wav", "wave", "ape", "tta", "tak", "aif", "aiff", "aifc",
            // Multichannel & surround / cinema formats
            "ac3", "eac3", "dts", "dtshd", "dts-hd", "thd", "truehd", "mlp",
            // Audio containers & video files with audio
            "mka", "mkv", "mp4", "webm", "caf", "weba",
            // Voice / telephony
            "amr", "awb", "spx", "3ga",
            // High-resolution DSD
            "dsf", "dff",
            // Legacy / Tracker / Misc
            "au", "snd", "ra", "mp1", "mp2", "mpa", "mpc", "mid", "midi",
          )
        }

      if (showAudioFilePicker) {
        app.infinity.mpvz.ui.browser.dialogs.FilePickerDialog(
          isOpen = true,
          currentPath = savedPickerPath,
          onDismiss = { showAudioFilePicker = false },
          onPathChanged = { path ->
            if (path != null) {
              audioPreferences.pickerPath.set(path)
            }
          },
          onFileSelected = { path ->
            showAudioFilePicker = false
            onAddAudio(Uri.parse("file://$path"))
          },
          onSystemPickerRequest = {
            showAudioFilePicker = false
            audioPicker.launch(
              arrayOf(
                "audio/*",
                "application/ogg",
                "application/x-flac",
                "video/x-matroska",
                "video/*",
                "*/*",
              ),
            )
          },
          matchToName = matchToName,
          allowedExtensions = audioAllowedExtensions,
        )
      }

      AudioTracksSheet(
        tracks = audioTracks,
        onSelect = onSelectAudio,
        onAddAudioTrack = { showAudioFilePicker = true },
        onOpenDelayPanel = { onOpenPanel(Panels.AudioDelay) },
        onOpenEqualizerSheet = { onShowSheet(Sheets.Equalizer) },
        isMedia3Active = isMedia3Active,
        onMedia3AudioChannels = onMedia3AudioChannels,
        onMedia3AudioProcessing = onMedia3AudioProcessing,
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.Chapters -> {
      ChaptersSheet(
        chapters,
        currentChapter = chapter,
        onClick = { onSeekToChapter(chapters.indexOf(it)) },
        onDismissRequest,
      )
    }

    Sheets.Decoders -> {
      DecodersSheet(
        selectedDecoder = decoder,
        isMedia3Active = isMedia3Active,
        engineSelection = engineSelection,
        onEngineSelected = onEngineSelected,
        media3DecoderName = media3DecoderName,
        onSelect = onUpdateDecoder,
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.More -> {
      val anime4KUiState by viewModel.anime4KUiState.composeCollectAsState()
      MoreSheet(
        remainingTime = sleepTimerTimeRemaining,
        onStartTimer = onStartSleepTimer,
        onDismissRequest = onDismissRequest,
        onEnterFiltersPanel = { onOpenPanel(Panels.VideoFilters) },
        onEnterLuaScriptsPanel = { onOpenPanel(Panels.LuaScripts) },
        onEnterEqualizerSheet = { onShowSheet(Sheets.Equalizer) },
        anime4KUiState = anime4KUiState,
        onAnime4KModeSelected = viewModel::selectAnime4KMode,
        videoFormatStatus = videoFormatStatus,
        isMedia3Active = isMedia3Active,
        modifier = Modifier,
      )
    }

    Sheets.PlaybackSpeed -> {
      PlaybackSpeedSheet(
        speed,
        onSpeedChange = onSpeedChange,
        speedPresets = speedPresets,
        onAddSpeedPreset = onAddSpeedPreset,
        onRemoveSpeedPreset = onRemoveSpeedPreset,
        onResetPresets = onResetSpeedPresets,
        onMakeDefault = onMakeDefaultSpeed,
        onResetDefault = onResetDefaultSpeed,
        isMedia3Active = isMedia3Active,
        onMedia3AudioPitchCorrection = onMedia3AudioPitchCorrection,
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.VideoZoom -> {
      val videoZoom by viewModel.videoZoom.composeCollectAsState()
      VideoZoomSheet(
        videoZoom = videoZoom,
        isMedia3Active = isMedia3Active,
        onSetVideoZoom = viewModel::setVideoZoom,
        onResetVideoPan = viewModel::resetVideoPan,
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.AspectRatios -> {
      val playerPreferences = koinInject<app.infinity.mpvz.preferences.PlayerPreferences>()
      val customRatiosSet by playerPreferences.customAspectRatios.collectAsState()
      val currentRatio by viewModel.currentAspectRatio.composeCollectAsState()
      val customRatios =
        customRatiosSet.mapNotNull { str ->
          val parts = str.split("|")
          if (parts.size == 2) {
            app.infinity.mpvz.ui.player.controls.components.sheets.AspectRatio(
              label = parts[0],
              ratio = parts[1].toDoubleOrNull() ?: return@mapNotNull null,
              isCustom = true,
            )
          } else {
            null
          }
        }

      AspectRatioSheet(
        currentRatio = currentRatio,
        customRatios = customRatios,
        onSelectRatio = { ratio ->
          if (ratio < 0) {
            // Default selected - apply Fit mode
            viewModel.changeVideoAspect(app.infinity.mpvz.ui.player.VideoAspect.Fit)
          } else {
            // Custom ratio selected
            viewModel.setCustomAspectRatio(ratio)
          }
        },
        onAddCustomRatio = { label, ratio ->
          playerPreferences.customAspectRatios.set(customRatiosSet + "$label|$ratio")
          viewModel.setCustomAspectRatio(ratio)
        },
        onDeleteCustomRatio = { ratio ->
          val toRemove = "${ratio.label}|${ratio.ratio}"
          playerPreferences.customAspectRatios.set(customRatiosSet - toRemove)
          // If the deleted ratio is currently active, reset to default (Fit)
          if (kotlin.math.abs(currentRatio - ratio.ratio) < 0.01) {
            viewModel.changeVideoAspect(app.infinity.mpvz.ui.player.VideoAspect.Fit)
          }
        },
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.FrameNavigation -> {
      val currentFrame by viewModel.currentFrame.composeCollectAsState()
      val totalFrames by viewModel.totalFrames.composeCollectAsState()
      FrameNavigationSheet(
        currentFrame = currentFrame,
        totalFrames = totalFrames,
        onUpdateFrameInfo = viewModel::updateFrameInfo,
        onPause = viewModel::pause,
        onUnpause = viewModel::unpause,
        onPauseUnpause = viewModel::pauseUnpause,
        onPreviousFrame = viewModel::frameStepBackward,
        onNextFrame = viewModel::frameStepForward,
        onSeekTo = { position, _ -> viewModel.seekTo(position) },
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.Playlist -> {
      // Refresh once on open and again whenever the asynchronous folder queue publishes siblings.
      val queueState by PlaybackSession.queue.collectAsState()
      LaunchedEffect(queueState.items.size, queueState.currentIndex) {
        // Some controls, including gesture and compact portrait variants, update sheetShown
        // directly instead of passing through PlayerControls.onOpenSheet. Trigger discovery here
        // as the authoritative sheet-open path so a singleton external launch can materialize its
        // sibling MKV queue regardless of which playlist control opened the sheet.
        viewModel.refreshCurrentFolderQueue()
        viewModel.refreshPlaylistItems(forceMetadata = true)
      }

      // Observe playlist updates
      val playlist by viewModel.playlistItems.collectAsState()
      val isAudioOnly by viewModel.isAudioOnly.collectAsState()
      val playerPreferences = koinInject<app.infinity.mpvz.preferences.PlayerPreferences>()
      val isPlaylistSwipeActive by viewModel.isPlaylistSwipeActive.collectAsState()
      val playlistSwipeOffset by viewModel.playlistSwipeOffset.collectAsState()

      val filteredPlaylist =
        remember(playlist, isAudioOnly, queueState.items.size, queueState.currentIndex) {
          // The sheet can be composed before the IO refresh publishes playlistItems. Use the
          // already-loaded explicit queue for the first frame, then switch to the refreshed list.
          val sourcePlaylist = playlist.ifEmpty { viewModel.getPlaylistData().orEmpty() }
          if (isAudioOnly) {
            sourcePlaylist.filter { it.isAudio }
          } else {
            sourcePlaylist.filter { !it.isAudio }
          }
        }

      LaunchedEffect(queueState.items.size, queueState.currentIndex, filteredPlaylist.isEmpty()) {
        if (filteredPlaylist.isEmpty()) {
          // A folder queue can take a moment to be discovered. Give it time to publish; if it
          // remains a singleton, close the empty sheet so the player cannot be left in a blocked
          // Playlist state where Back is the only way to restore controls.
          delay(5000L)
          if (
            PlaybackSession.queue.value.items.size <= 1 &&
              viewModel.sheetShown.value == Sheets.Playlist
          ) {
            onDismissRequest()
          }
        }
      }

      if (filteredPlaylist.isNotEmpty()) {
        val playlistImmutable = filteredPlaylist.toImmutableList()
        val totalCount = filteredPlaylist.size
        val isM3U = viewModel.isPlaylistM3U()
        PlaylistSheet(
          playlist = playlistImmutable,
          onDismissRequest = onDismissRequest,
          onItemClick = { item ->
            viewModel.playPlaylistItem(item.index)
          },
          onReorder = { from, to ->
            viewModel.reorderPlaylistItem(from, to)
          },
          onRemove = if (PlaybackSession.queue.value.isTemporaryQueue) {
            { item -> viewModel.removePlaylistItem(item.index) }
          } else {
            null
          },
          totalCount = totalCount,
          isM3UPlaylist = isM3U,
          playerPreferences = playerPreferences,
          isSwipeActive = isPlaylistSwipeActive,
          swipeOffset = playlistSwipeOffset,
          isAudioOnly = isAudioOnly,
        )
      }
    }

    Sheets.AmbientConfig -> {
      AmbientSheet(
        viewModel = viewModel,
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.Equalizer -> {
      val equalizerState by viewModel.equalizerState.collectAsState()
      app.infinity.mpvz.ui.player.controls.components.sheets.EqualizerSheet(
        state = equalizerState,
        onEnabledChanged = viewModel::setEqualizerEnabled,
        onPresetSelected = viewModel::applyEqualizerPreset,
        onBandChanged = viewModel::setEqualizerBandGain,
        onVolumeBoostChanged = viewModel::setEqualizerVolumeBoost,
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.AudioProperties -> {
      // The MPV properties may arrive shortly after the sheet is opened. Observe the active
      // audio session so the panel refreshes instead of keeping an empty first snapshot.
      val audioPath by PlaybackSession.propString["path"].collectAsState()
      val audioStreamFilename by PlaybackSession.propString["stream-open-filename"].collectAsState()
      val audioTitle by PlaybackSession.propString["metadata/by-key/Title"].collectAsState()
      val audioArtist by PlaybackSession.propString["metadata/by-key/Artist"].collectAsState()
      val audioAlbum by PlaybackSession.propString["metadata/by-key/Album"].collectAsState()
      val audioCodec by PlaybackSession.propString["audio-codec-name"].collectAsState()
      val audioSampleRate by PlaybackSession.propInt["audio-params/samplerate"].collectAsState()
      val audioChannels by PlaybackSession.propInt["audio-params/channel-count"].collectAsState()
      val audioBitrate by PlaybackSession.propInt["audio-bitrate"].collectAsState()
      val properties =
        remember(
          audioPath,
          audioStreamFilename,
          audioTitle,
          audioArtist,
          audioAlbum,
          audioCodec,
          audioSampleRate,
          audioChannels,
          audioBitrate,
        ) {
          viewModel.getAudioPropertiesData()
        }
      app.infinity.mpvz.ui.player.controls.components.sheets.AudioPropertiesSheet(
        properties = properties,
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.VisualizerStyle -> {
      val audioPreferences = koinInject<app.infinity.mpvz.preferences.AudioPreferences>()
      val audioVisualizerStyle by audioPreferences.audioVisualizerStyle.collectAsState()
      VisualizerStyleSheet(
        selectedStyle = audioVisualizerStyle,
        onSelectStyle = { audioPreferences.audioVisualizerStyle.set(it) },
        onDismissRequest = onDismissRequest,
      )
    }

    Sheets.Lyrics -> {
      app.infinity.mpvz.ui.player.controls.components.sheets.LyricsSheet(
        viewModel = viewModel,
        onDismiss = onDismissRequest,
      )
    }
  }
}

