/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.playlist

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import app.infinity.mpvz.ui.player.controls.components.GlassSlider as Slider
import app.infinity.mpvz.ui.player.controls.components.GlassSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.infinity.mpvz.R
import app.infinity.mpvz.database.entities.PlaylistEntity
import app.infinity.mpvz.database.entities.PlaylistItemEntity
import app.infinity.mpvz.database.repository.PlaylistRepository
import app.infinity.mpvz.domain.media.model.Video
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.BrowserPreferences
import app.infinity.mpvz.preferences.MediaLayoutMode
import app.infinity.mpvz.preferences.PlayerPreferences
import app.infinity.mpvz.preferences.GesturePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.presentation.components.pullrefresh.PullRefreshBox
import app.infinity.mpvz.ui.browser.cards.M3UVideoCard
import app.infinity.mpvz.ui.browser.cards.VideoCard
import app.infinity.mpvz.ui.browser.cards.VideoCardUiConfig
import app.infinity.mpvz.ui.browser.components.BrowserTopBar
import app.infinity.mpvz.ui.browser.components.ExpressiveScrollBar
import app.infinity.mpvz.ui.browser.components.fastScrollGlyph
import app.infinity.mpvz.ui.browser.selection.rememberSelectionManager
import app.infinity.mpvz.ui.preferences.components.settingsGlassContainerColor
import app.infinity.mpvz.ui.preferences.components.settingsGlassSurface
import app.infinity.mpvz.ui.components.InlineSearchBar
import app.infinity.mpvz.ui.player.controls.components.GlassSwitch
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.popSafely
import app.infinity.mpvz.utils.clipboard.SafeClipboard
import app.infinity.mpvz.utils.media.MediaInfoOps
import app.infinity.mpvz.utils.media.MediaUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.charset.StandardCharsets
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Playlist detail screen showing videos in a playlist.
 *
 * **M3U Playlist Behavior:**
 * M3U playlists are treated as channel lists:
 * - Stored stream metadata is surfaced in the UI
 * - Playlist playback keeps channel titles instead of raw URLs
 * - Per-stream headers can be passed to the player for supported sources
 * - Categories and saved streams make large IPTV lists easier to browse
 *
 * **Regular Playlist Behavior:**
 * Local file playlists support full playlist navigation:
 * - Next/previous buttons available during playback
 * - Playlist continuation and shuffle modes
 * - Full playlist loaded into PlayerActivity
 */
@Serializable
data class PlaylistDetailScreen(
  val playlistId: Int,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val coroutineScope = rememberCoroutineScope()
    val browserPreferences = koinInject<BrowserPreferences>()
    val playerPreferences = koinInject<PlayerPreferences>()
    val playlistRepository = koinInject<PlaylistRepository>()
    val iptvLayoutMode by browserPreferences.iptvLayoutMode.collectAsState()
    val iptvManualGridColumns by browserPreferences.iptvManualGridColumnsEnabled.collectAsState()
    val iptvGridColumnsPortrait by browserPreferences.iptvGridColumnsPortrait.collectAsState()

    // ViewModel
    val viewModel: PlaylistDetailViewModel =
      viewModel(
        key = "PlaylistDetailViewModel_$playlistId",
        factory =
          PlaylistDetailViewModel.factory(
            context.applicationContext as android.app.Application,
            playlistId,
          ),
      )

    val playlist by viewModel.playlist.collectAsState()
    val videoItems by viewModel.videoItems.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val videos = remember(videoItems) { videoItems.map { it.video } }
    val isRadioM3uPlaylist = playlist?.let {
      it.isM3uPlaylist && app.infinity.mpvz.utils.media.RadioM3uPlaylistPolicy.isStoredRadioName(it.name)
    } == true
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing = remember { mutableStateOf(false) }
    val playlistTitle =
      when {
        playlist?.name?.equals(PlaylistRepository.FAVORITES_PLAYLIST_NAME, ignoreCase = true) != true ->
          playlist?.name?.removePrefix(app.infinity.mpvz.utils.media.RadioM3uPlaylistPolicy.RADIO_PREFIX) ?: stringResource(R.string.ui_playlist)
        playlist?.isAudio == true -> stringResource(R.string.playlist_favorite_songs)
        else -> stringResource(R.string.playlist_favorite_videos)
      }

    // Search state
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var selectedM3UFilter by rememberSaveable { mutableStateOf(M3U_FILTER_ALL) }
    val isM3UGrid = iptvLayoutMode == MediaLayoutMode.GRID
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val favoriteStreamCount = remember(videoItems) { videoItems.count { it.playlistItem.isFavorite } }
    val hasFavoriteStreams = favoriteStreamCount > 0

    LaunchedEffect(categories, hasFavoriteStreams, playlist?.isM3uPlaylist) {
      if (playlist?.isM3uPlaylist != true) {
        selectedM3UFilter = M3U_FILTER_ALL
      } else if (
        selectedM3UFilter != M3U_FILTER_ALL &&
        selectedM3UFilter != M3U_FILTER_FAVORITES &&
        selectedM3UFilter !in categories
      ) {
        selectedM3UFilter = M3U_FILTER_ALL
      } else if (selectedM3UFilter == M3U_FILTER_FAVORITES && !hasFavoriteStreams) {
        selectedM3UFilter = M3U_FILTER_ALL
      }
    }

    val m3uFilteredItems = remember(videoItems, selectedM3UFilter, playlist?.isM3uPlaylist) {
      if (playlist?.isM3uPlaylist == true) {
        PlaylistPresentationPolicy.filterM3uItems(videoItems, selectedM3UFilter, { it.playlistItem.groupTitle }, { it.playlistItem.isFavorite })
      } else videoItems
    }

    // Filter video items based on category and search query
    val filteredVideoItems = remember(m3uFilteredItems, isSearching, searchQuery) {
      if (isSearching && searchQuery.isNotBlank()) {
        m3uFilteredItems.filter { item ->
          item.video.displayName.contains(searchQuery, ignoreCase = true) ||
            item.video.path.contains(searchQuery, ignoreCase = true) ||
            item.playlistItem.groupTitle
              .orEmpty()
              .contains(searchQuery, ignoreCase = true) ||
            item.playlistItem.tvgId
              .orEmpty()
              .contains(searchQuery, ignoreCase = true)
        }
      } else {
        m3uFilteredItems
      }
    }

    // Request focus when search is activated
    LaunchedEffect(isSearching) {
      if (isSearching) {
        focusRequester.requestFocus()
        keyboardController?.show()
      }
    }

    // Selection manager - use playlist item ID as unique key, work with filtered items
    val selectionManager =
      rememberSelectionManager(
        items = filteredVideoItems,
        getId = { it.playlistItem.id },
        onDeleteItems = { itemsToDelete, _ ->
          val videosToRemove = itemsToDelete.map { it.video }
          viewModel.removeVideosFromPlaylist(videosToRemove)
          Pair(itemsToDelete.size, 0)
        },
        onOperationComplete = { viewModel.refresh() },
      )

    // UI State
    val listState = rememberLazyListState()
    val deleteDialogOpen = rememberSaveable { mutableStateOf(false) }
    val mediaInfoDialogOpen = rememberSaveable { mutableStateOf(false) }
    val selectedVideo = remember { mutableStateOf<Video?>(null) }
    val mediaInfoData = remember { mutableStateOf<MediaInfoOps.MediaInfoData?>(null) }
    val mediaInfoLoading = remember { mutableStateOf(false) }
    val mediaInfoError = remember { mutableStateOf<String?>(null) }
    var showUrlDialog by rememberSaveable { mutableStateOf(false) }
    var urlDialogContent by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    var exportMenuOpen by rememberSaveable { mutableStateOf(false) }
    val createDocumentLauncher = rememberLauncherForActivityResult(
      ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri ->
      val export = pendingExport
      pendingExport = null
      if (uri != null && export != null) {
        coroutineScope.launch {
          try {
            withContext(Dispatchers.IO) {
              context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(export.second.toByteArray(StandardCharsets.UTF_8))
              } ?: error("Unable to open destination")
            }
            Toast.makeText(context, R.string.playlist_export_success, Toast.LENGTH_SHORT).show()
          } catch (error: Exception) {
            Toast.makeText(context, context.getString(R.string.playlist_export_error, error.message.orEmpty()), Toast.LENGTH_LONG).show()
          }
        }
      }
    }

    fun exportPlaylist() {
      if (isLoading || playlist == null) {
        Toast.makeText(context, R.string.playlist_export_unavailable, Toast.LENGTH_SHORT).show()
        return
      }
      coroutineScope.launch {
        try {
          val export = viewModel.exportM3uContent()
          pendingExport = export
          createDocumentLauncher.launch(export.first)
        } catch (error: Exception) {
          Toast.makeText(context, context.getString(R.string.playlist_export_error, error.message.orEmpty()), Toast.LENGTH_LONG).show()
        }
      }
    }

    fun sharePlaylist() {
      if (isLoading || playlist == null) {
        Toast.makeText(context, R.string.playlist_export_unavailable, Toast.LENGTH_SHORT).show()
        return
      }
      coroutineScope.launch {
        try {
          val (fileName, content) = viewModel.exportM3uContent()
          val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, fileName).apply { writeText(content, StandardCharsets.UTF_8) }
          }
          val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
          val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/x-mpegurl"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
          context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.playlist_share_m3u)))
        } catch (error: Exception) {
          Toast.makeText(context, context.getString(R.string.playlist_share_error, error.message.orEmpty()), Toast.LENGTH_LONG).show()
        }
      }
    }

    // Reorder mode state
    var isReorderMode by rememberSaveable { mutableStateOf(false) }
    var iptvSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var createChannelPlaylistOpen by rememberSaveable { mutableStateOf(false) }

    // Predictive back: Intercept when in selection mode, reorder mode, or searching
    BackHandler(enabled = selectionManager.isInSelectionMode || isReorderMode || isSearching) {
      when {
        isReorderMode -> isReorderMode = false
        isSearching -> {
          isSearching = false
          searchQuery = ""
        }
        selectionManager.isInSelectionMode -> selectionManager.clear()
      }
    }

    var preparingPlayback by remember { mutableStateOf(false) }
    fun launchPlaylistPlayback(
      item: PlaylistVideoItem,
      startIndex: Int,
    ) {
      val fallbackUri = Uri.parse(item.video.path)
      val playUri =
        item.video.uri.takeIf { it != Uri.EMPTY && it.toString().isNotBlank() }
          ?: fallbackUri.takeIf { !it.scheme.isNullOrBlank() }
          ?: Uri.fromFile(java.io.File(item.video.path))

      if (preparingPlayback) return
      preparingPlayback = true
      coroutineScope.launch {
        try {
          val headersExtra = buildM3UHeadersExtra(playlist, item.playlistItem)
          val headers = headersExtra?.toList()?.chunked(2)?.associate { it[0] to it[1] }
            ?: mapOf("User-Agent" to app.infinity.mpvz.network.NetworkUserAgent.resolve(context))
          // Reclassify saved entries too: the owner must not re-import an existing radio M3U.
          val settingEnabled = playerPreferences.openRadioM3uInMusic.get()
          val playlistName = playlist?.name.orEmpty()
          val declaredAudio = playlist?.isAudio == true
          val savedEntries = videoItems
          val recognizedMusic = if (settingEnabled && playlist?.isM3uPlaylist == true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
              app.infinity.mpvz.utils.media.M3URoutingPolicy.isMusicPlaylist(
                app.infinity.mpvz.utils.media.M3UParseResult.Success(
                  playlistName,
                  savedEntries.map { saved ->
                    app.infinity.mpvz.utils.media.M3UPlaylistItem(
                      url = saved.playlistItem.filePath,
                      title = saved.playlistItem.fileName,
                      groupTitle = saved.playlistItem.groupTitle,
                    )
                  },
                ),
                declaredAudio = declaredAudio,
              )
            }
          } else false
          val radioMimeType =
            if (recognizedMusic) "audio/*" else if (settingEnabled && playlist?.isM3uPlaylist == true) {
              app.infinity.mpvz.utils.media.RadioStreamProbe.detectAudioMimeType(playUri.toString(), headers)
            } else null
          // Audio mode is declared before Activity creation; opaque audio URLs can use the
          // bounded header probe, but video/unknown responses never force the Music UI.
          val musicRoute = settingEnabled && playlist?.isM3uPlaylist == true && radioMimeType != null
          val intent =
            Intent(context, PlayerActivity::class.java).apply {
              action = Intent.ACTION_VIEW
              data = playUri
              if (radioMimeType != null) setDataAndType(playUri, radioMimeType)
              putExtra("internal_launch", true)
              putExtra("local_media_path", item.video.path)
              putExtra("playlist_index", startIndex)
              putExtra("playlist_id", playlistId)
              putExtra("launch_source", if (musicRoute) "music_m3u_playlist" else if (playlist?.isM3uPlaylist == true) "m3u_playlist" else "playlist")
              putExtra("media_library_audio", musicRoute || item.video.isAudio)
              putExtra("is_audio", musicRoute || radioMimeType != null || item.video.isAudio)
              putExtra("radio_stream", musicRoute || radioMimeType != null)
              putExtra("title", item.playlistItem.fileName)
              headersExtra?.let { putExtra("headers", it) }
            }
          context.startActivity(intent)
        } finally {
          preparingPlayback = false
        }
      }
    }

    val navigationBarHeight = app.infinity.mpvz.ui.browser.LocalNavigationBarHeight.current
    val miniPlayerClearance = app.infinity.mpvz.ui.browser.NavigationBarState.miniPlayerClearance

    Scaffold(
      containerColor = app.infinity.mpvz.ui.preferences.components.settingsGlassContainerColor(MaterialTheme.colorScheme.background),
      topBar = {
        if (isSearching) {
          // Search mode - show search bar
          InlineSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearch = { },
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            inputFieldModifier = Modifier.focusRequester(focusRequester),
            placeholder = {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.infinity.mpvz.R.string.ui_search_videos),
              )
            },
            leadingIcon = {
              Icon(
                imageVector = Icons.RoundedFilled.Search,
                contentDescription =
                  androidx.compose.ui.res.stringResource(
                    app.infinity.mpvz.R.string.settings_search_title,
                  ),
              )
            },
            trailingIcon = {
              IconButton(
                onClick = {
                  isSearching = false
                  searchQuery = ""
                },
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Close,
                  contentDescription =
                    androidx.compose.ui.res.stringResource(
                      app.infinity.mpvz.R.string.generic_cancel,
                    ),
                )
              }
            },
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
          )
        } else {
          BrowserTopBar(
            title = playlistTitle,
            isInSelectionMode = selectionManager.isInSelectionMode,
            selectedCount = selectionManager.selectedCount,
            totalCount = videos.size,
            onBackClick = {
              when {
                isReorderMode -> isReorderMode = false
                selectionManager.isInSelectionMode -> selectionManager.clear()
                else -> backStack.popSafely()
              }
            },
            onCancelSelection = { selectionManager.clear() },
            isSingleSelection = selectionManager.isSingleSelection,
            useRemoveIcon = true, // Show remove icon instead of delete for playlist
            onInfoClick =
              if (selectionManager.isSingleSelection) {
                {
                  val item = selectionManager.getSelectedItems().firstOrNull()
                  if (item != null) {
                    if (playlist?.isM3uPlaylist == true) {
                      // For M3U playlists, show URL dialog
                      urlDialogContent = item.video.path
                      showUrlDialog = true
                      selectionManager.clear()
                    } else {
                      // For regular playlists, show MediaInfo activity
                      val intent = Intent(context, app.infinity.mpvz.ui.mediainfo.MediaInfoActivity::class.java)
                      intent.action = Intent.ACTION_VIEW
                      intent.data = item.video.uri
                      context.startActivity(intent)
                      selectionManager.clear()
                    }
                  }
                }
              } else {
                null
              },
            onSettingsClick =
              if (playlist?.isM3uPlaylist == true) {
                { iptvSettingsOpen = true }
              } else {
                null
              },
            onShareClick =
              if (playlist?.isM3uPlaylist != true) {
                // Hide share button for M3U playlists
                {
                  val videosToShare = selectionManager.getSelectedItems().map { it.video }
                  MediaUtils.shareVideos(context, videosToShare)
                }
              } else {
                null
              },
            onAddToPlaylistClick =
              if (playlist?.isM3uPlaylist == true && selectionManager.selectedCount > 0) {
                { createChannelPlaylistOpen = true }
              } else {
                null
              },
            onPlayClick = null, // Don't show play icon in selection mode for playlist
            onSelectAll = { selectionManager.selectAll() },
            onInvertSelection = { selectionManager.invertSelection() },
            onDeselectAll = { selectionManager.clear() },
            onDeleteClick = { deleteDialogOpen.value = true },
            additionalActions = {
              when {
                // Show done button when in reorder mode
                isReorderMode -> {
                  IconButton(
                    onClick = { isReorderMode = false },
                  ) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Check,
                      contentDescription =
                        androidx.compose.ui.res.stringResource(
                          app.infinity.mpvz.R.string.ui_done_reordering,
                        ),
                      tint = MaterialTheme.colorScheme.primary,
                    )
                  }
                }
                // Show reorder button and play button when not in selection mode
                !selectionManager.isInSelectionMode -> {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    // Search button
                    IconButton(
                      onClick = { isSearching = true },
                    ) {
                      Icon(
                        imageVector = Icons.RoundedFilled.Search,
                        contentDescription =
                          androidx.compose.ui.res.stringResource(
                            app.infinity.mpvz.R.string.ui_search_videos_2,
                          ),
                        tint = MaterialTheme.colorScheme.onSurface,
                      )
                    }
                    Spacer(modifier = Modifier.width(4.dp))

                    Box {
                      IconButton(onClick = { exportMenuOpen = true }) {
                        Icon(
                          imageVector = Icons.RoundedFilled.MoreVert,
                          contentDescription = stringResource(R.string.playlist_actions),
                          tint = MaterialTheme.colorScheme.onSurface,
                        )
                      }
                      DropdownMenu(
                        expanded = exportMenuOpen,
                        onDismissRequest = { exportMenuOpen = false },
                      ) {
                        DropdownMenuItem(
                          text = { Text(stringResource(R.string.playlist_export_m3u)) },
                          onClick = { exportMenuOpen = false; exportPlaylist() },
                        )
                        DropdownMenuItem(
                          text = { Text(stringResource(R.string.playlist_share_m3u)) },
                          onClick = { exportMenuOpen = false; sharePlaylist() },
                        )
                      }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    // Reorder button (hide for M3U playlists)
                    if (playlist?.isM3uPlaylist != true) {
                      IconButton(
                        onClick = { isReorderMode = true },
                      ) {
                        Icon(
                          imageVector = Icons.RoundedFilled.SwapVert,
                          contentDescription =
                            androidx.compose.ui.res.stringResource(
                              app.infinity.mpvz.R.string.ui_reorder_playlist,
                            ),
                          tint = MaterialTheme.colorScheme.onSurface,
                        )
                      }
                      Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Play button
                    Button(
                      onClick = {
                        val mostRecentlyPlayedItem =
                          videoItems
                            .filter { it.playlistItem.lastPlayedAt > 0 }
                            .maxByOrNull { it.playlistItem.lastPlayedAt }

                        val itemToPlay = mostRecentlyPlayedItem ?: videoItems.firstOrNull()
                        val startIndex =
                          itemToPlay?.let { candidate ->
                            videoItems.indexOfFirst { it.playlistItem.id == candidate.playlistItem.id }
                          } ?: -1

                        if (itemToPlay != null && startIndex >= 0) {
                          coroutineScope.launch {
                            viewModel.updatePlayHistory(itemToPlay.video.path)
                          }
                          launchPlaylistPlayback(itemToPlay, startIndex)
                        }
                      },
                      colors =
                        ButtonDefaults.buttonColors(
                          containerColor = MaterialTheme.colorScheme.primaryContainer,
                          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                      shape = MaterialTheme.shapes.large,
                      modifier = Modifier.padding(end = 20.dp),
                    ) {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Icon(
                          imageVector = Icons.RoundedFilled.PlayArrow,
                          contentDescription = null,
                          modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                          text =
                            androidx.compose.ui.res
                              .stringResource(app.infinity.mpvz.R.string.ui_play),
                          style = MaterialTheme.typography.labelLarge,
                          fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                      }
                    }
                  }
                }
              }
            },
          )
        }
      },
      floatingActionButton = {
        if (!isSearching && !isReorderMode && !selectionManager.isInSelectionMode && playlist?.isM3uPlaylist != true) {
          val isAudioPlaylist = playlist?.isAudio == true || videoItems.any { it.video.isAudio }
          ExtendedFloatingActionButton(
            modifier =
              Modifier.padding(
                bottom = (navigationBarHeight - 16.dp).coerceAtLeast(0.dp) + miniPlayerClearance,
              ),
            onClick = { backStack.add(PlaylistAddVideosScreen(playlistId, isAudio = isAudioPlaylist)) },
            icon = { Icon(Icons.RoundedFilled.Add, contentDescription = null) },
            text = { Text(if (isAudioPlaylist) "Add Songs" else stringResource(R.string.playlist_add_videos)) },
          )
        }
      },
    ) { padding ->
      // Show "no results" message when searching with no results
      if (isSearching && filteredVideoItems.isEmpty() && searchQuery.isNotBlank()) {
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Search,
              contentDescription = null,
              modifier = Modifier.size(64.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text =
                androidx.compose.ui.res
                  .stringResource(app.infinity.mpvz.R.string.ui_no_videos_found),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text =
                androidx.compose.ui.res
                  .stringResource(app.infinity.mpvz.R.string.ui_try_a_different_search_term),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        val pullToRefreshEnabled =
          !selectionManager.isInSelectionMode && !isReorderMode && !isSearching

        PullRefreshBox(
          isRefreshing = isRefreshing,
          enabled = pullToRefreshEnabled,
          listState = listState,
          modifier = Modifier.fillMaxSize().padding(padding),
          onRefresh = {
            val isM3uPlaylist = playlist?.isM3uPlaylist == true
            if (isM3uPlaylist) {
              val result = viewModel.refreshM3UPlaylist()
              result
                .onSuccess {
                  Toast
                    .makeText(
                      context,
                      context.getString(app.infinity.mpvz.R.string.playlist_refresh_success),
                      Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure { error ->
                  Toast
                    .makeText(
                      context,
                      context.getString(
                        R.string.toast_failed_to_refresh,
                        error.message ?: context.getString(R.string.generic_unknown_error),
                      ),
                      Toast.LENGTH_LONG,
                    ).show()
                }
            } else {
              viewModel.refreshNow()
            }
          },
        ) {
          Column(modifier = Modifier.fillMaxSize()) {
            if (playlist?.isM3uPlaylist == true && (categories.isNotEmpty() || hasFavoriteStreams)) {
              M3UPlaylistFilterRow(
                categories = categories,
                categoryCounts = remember(videoItems, categories) { PlaylistPresentationPolicy.categoryCounts(videoItems, categories) { it.playlistItem.groupTitle } },
                totalCount = videoItems.size,
                favoriteCount = favoriteStreamCount,
                hasFavorites = hasFavoriteStreams,
                selectedFilter = selectedM3UFilter,
                onFilterSelected = { selectedM3UFilter = it },
              )
            }

            val emptyKind = PlaylistPresentationPolicy.emptyKind(
              hasUnfilteredItems = videoItems.isNotEmpty(),
              hasActiveSearch = isSearching && searchQuery.isNotBlank(),
              hasCategoryFilter = selectedM3UFilter != M3U_FILTER_ALL,
              isRadio = isRadioM3uPlaylist,
              isM3u = playlist?.isM3uPlaylist == true,
              isAudio = playlist?.isAudio == true,
            )
            PlaylistVideoListContent(
              videoItems = filteredVideoItems,
              isLoading = isLoading && videoItems.isEmpty(),
              emptyTitle = when (emptyKind) {
                PlaylistPresentationPolicy.EmptyKind.FILTERED -> stringResource(R.string.playlist_ui_no_filter_matches)
                PlaylistPresentationPolicy.EmptyKind.RADIO -> stringResource(R.string.playlist_ui_no_radio_stations)
                PlaylistPresentationPolicy.EmptyKind.TV -> stringResource(R.string.playlist_ui_no_tv_channels)
                PlaylistPresentationPolicy.EmptyKind.SONGS -> stringResource(R.string.playlist_ui_no_songs)
                PlaylistPresentationPolicy.EmptyKind.VIDEOS -> stringResource(R.string.ui_no_videos_in_playlist)
              },
              emptyHint = when (emptyKind) {
                PlaylistPresentationPolicy.EmptyKind.FILTERED -> stringResource(R.string.playlist_ui_no_filter_matches_hint)
                PlaylistPresentationPolicy.EmptyKind.RADIO -> stringResource(R.string.playlist_ui_radio_empty_hint)
                PlaylistPresentationPolicy.EmptyKind.TV -> stringResource(R.string.playlist_ui_tv_empty_hint)
                PlaylistPresentationPolicy.EmptyKind.SONGS -> stringResource(R.string.playlist_ui_songs_empty_hint)
                PlaylistPresentationPolicy.EmptyKind.VIDEOS -> stringResource(R.string.ui_add_videos_to_get_started)
              },
              onClearFilters = if (emptyKind == PlaylistPresentationPolicy.EmptyKind.FILTERED) {
                { selectedM3UFilter = M3U_FILTER_ALL; searchQuery = ""; isSearching = false }
              } else null,
              selectionManager = selectionManager,
              isM3uPlaylist = playlist?.isM3uPlaylist == true,
              isRadio = isRadioM3uPlaylist,
              isGridMode = isM3UGrid,
              isAudio = playlist?.isAudio == true || videoItems.any { it.video.isAudio },
              isReorderMode = isReorderMode,
              onReorder = { fromIndex, toIndex ->
                coroutineScope.launch {
                  viewModel.reorderPlaylistItems(fromIndex, toIndex)
                }
              },
              onToggleFavorite =
                if (playlist?.isM3uPlaylist == true && !selectionManager.isInSelectionMode) {
                  { item ->
                    coroutineScope.launch {
                      viewModel.toggleFavorite(item.playlistItem.id)
                    }
                  }
                } else {
                  null
                },
              onVideoItemClick = { item ->
                if (selectionManager.isInSelectionMode) {
                  selectionManager.toggle(item)
                } else {
                  coroutineScope.launch {
                    viewModel.updatePlayHistory(item.video.path)
                  }

                  val startIndex = videoItems.indexOfFirst { it.playlistItem.id == item.playlistItem.id }
                  if (startIndex >= 0) {
                    launchPlaylistPlayback(item, startIndex)
                  } else {
                    MediaUtils.playFile(item.video, context, "playlist_detail", title = item.playlistItem.fileName)
                  }
                }
              },
              onVideoItemLongClick = { item ->
                selectionManager.handleLongClick(item)
              },
              listState = listState,
              modifier = Modifier.weight(1f).fillMaxWidth(),
            )
          }
        }
      }

      // Dialogs
      RemoveFromPlaylistDialog(
        isOpen = deleteDialogOpen.value,
        onDismiss = { deleteDialogOpen.value = false },
        onConfirm = { selectionManager.deleteSelected() },
        itemCount = selectionManager.selectedCount,
      )

      // URL Dialog for M3U streams
      if (showUrlDialog) {
        StreamUrlDialog(
          url = urlDialogContent,
          onDismiss = { showUrlDialog = false },
          onCopy = {
            SafeClipboard.copyPlainText(context, "Stream URL", urlDialogContent)
          },
        )
      }
      if (iptvSettingsOpen && playlist?.isM3uPlaylist == true) {
        IptvSettingsDialog(
          layoutMode = iptvLayoutMode,
          manualGridColumns = iptvManualGridColumns,
          gridColumns = iptvGridColumnsPortrait,
          onLayoutModeChange = { browserPreferences.iptvLayoutMode.set(it) },
          onManualGridColumnsChange = { browserPreferences.iptvManualGridColumnsEnabled.set(it) },
          onGridColumnsChange = { browserPreferences.iptvGridColumnsPortrait.set(it) },
          onDismiss = { iptvSettingsOpen = false },
        )
      }
      if (createChannelPlaylistOpen && playlist?.isM3uPlaylist == true) {
        CreateIptvPlaylistDialog(
          selectedCount = selectionManager.selectedCount,
          onDismiss = { createChannelPlaylistOpen = false },
          onCreate = { name ->
            coroutineScope.launch {
              val selected = selectionManager.getSelectedItems()
              val playlistId = playlistRepository.createPlaylist(name.trim())
              playlistRepository.addItemsToPlaylist(
                playlistId.toInt(),
                selected.map { it.video.path to (it.playlistItem.fileName.ifBlank { it.video.displayName }) },
              )
              createChannelPlaylistOpen = false
              selectionManager.clear()
              Toast.makeText(context, "Playlist created", Toast.LENGTH_SHORT).show()
            }
          },
        )
      }
    }
  }
}

@Composable
private fun IptvSettingsDialog(
  layoutMode: MediaLayoutMode,
  manualGridColumns: Boolean,
  gridColumns: Int,
  onLayoutModeChange: (MediaLayoutMode) -> Unit,
  onManualGridColumnsChange: (Boolean) -> Unit,
  onGridColumnsChange: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  val glass by koinInject<AppearancePreferences>().liquidGlassSurfaces.collectAsState()
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.settingsGlassSurface(cornerRadius = 28.dp, independent = true),
    containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
    title = { Text("IPTV list settings") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Layout", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            modifier = Modifier.settingsGlassSurface(cornerRadius = 18.dp),
            colors = FilterChipDefaults.filterChipColors(
              containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
              selectedContainerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.secondaryContainer),
            ),
            selected = layoutMode == MediaLayoutMode.LIST,
            border = if (glass) null else FilterChipDefaults.filterChipBorder(enabled = true, selected = layoutMode == MediaLayoutMode.LIST),
            onClick = { onLayoutModeChange(MediaLayoutMode.LIST) },
            label = { Text("List") },
          )
          FilterChip(
            modifier = Modifier.settingsGlassSurface(cornerRadius = 18.dp),
            colors = FilterChipDefaults.filterChipColors(
              containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
              selectedContainerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.secondaryContainer),
            ),
            selected = layoutMode == MediaLayoutMode.GRID,
            border = if (glass) null else FilterChipDefaults.filterChipBorder(enabled = true, selected = layoutMode == MediaLayoutMode.GRID),
            onClick = { onLayoutModeChange(MediaLayoutMode.GRID) },
            label = { Text("Grid") },
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text("Manual grid columns")
          GlassSwitch(
            checked = manualGridColumns,
            onCheckedChange = onManualGridColumnsChange,
          )
        }
        if (layoutMode == MediaLayoutMode.GRID && manualGridColumns) {
          Text("Columns: $gridColumns", style = MaterialTheme.typography.bodyMedium)
          Slider(
            value = gridColumns.toFloat(),
            onValueChange = { onGridColumnsChange(it.toInt().coerceIn(1, 4)) },
            valueRange = 1f..4f,
            steps = 2,
          )
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
  )
}

@Composable
private fun CreateIptvPlaylistDialog(
  selectedCount: Int,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  var name by rememberSaveable { mutableStateOf("My IPTV channels") }
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.settingsGlassSurface(cornerRadius = 28.dp, independent = true),
    containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
    title = { Text("Create channel playlist") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Save $selectedCount selected channels as a playlist.")
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Playlist name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    confirmButton = {
      TextButton(
        enabled = name.isNotBlank(),
        onClick = { onCreate(name) },
      ) { Text("Create") }
    },
  )
}

@Composable
private fun PlaylistVideoListContent(
  videoItems: List<PlaylistVideoItem>,
  isLoading: Boolean,
  emptyTitle: String,
  emptyHint: String,
  onClearFilters: (() -> Unit)?,
  selectionManager: app.infinity.mpvz.ui.browser.selection.SelectionManager<PlaylistVideoItem, Int>,
  isReorderMode: Boolean,
  onReorder: (Int, Int) -> Unit,
  onToggleFavorite: ((PlaylistVideoItem) -> Unit)?,
  onVideoItemClick: (PlaylistVideoItem) -> Unit,
  onVideoItemLongClick: (PlaylistVideoItem) -> Unit,
  listState: androidx.compose.foundation.lazy.LazyListState,
  modifier: Modifier = Modifier,
  isM3uPlaylist: Boolean = false,
  isRadio: Boolean = false,
  isAudio: Boolean = false,
  isGridMode: Boolean = false,
) {
  val gesturePreferences = koinInject<GesturePreferences>()
  val browserPreferences = koinInject<app.infinity.mpvz.preferences.BrowserPreferences>()
  val manualGridColumnsEnabled by browserPreferences.iptvManualGridColumnsEnabled.collectAsState()
  val videoGridColumnsPortrait by browserPreferences.iptvGridColumnsPortrait.collectAsState()
  val videoGridColumnsLandscape by browserPreferences.iptvGridColumnsLandscape.collectAsState()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
  val showSubtitleIndicator by browserPreferences.showSubtitleIndicator.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showResolutionChip by browserPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by browserPreferences.showFramerateInResolution.collectAsState()
  val showProgressBar by browserPreferences.showProgressBar.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showCodecSupportIndicator by browserPreferences.showCodecSupportIndicator.collectAsState()
  val showUnplayedOldVideoLabel by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
  val unplayedOldVideoDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showDurationField by browserPreferences.showDurationField.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val musicCoverArtSize by browserPreferences.musicCoverArtSize.collectAsState()
  val thumbnailQuality by browserPreferences.thumbnailQuality.collectAsState()
  val density = LocalDensity.current
  val audioThumbnailSizePx = with(density) { musicCoverArtSize.dp.roundToPx() }
  val videoCardUiConfig =
    remember(
      unlimitedNameLines,
      showVideoThumbnails,
      showSizeChip,
      showResolutionChip,
      showFramerateInResolution,
      showCodecSupportIndicator,
      showProgressBar,
      showDateChip,
      showUnplayedOldVideoLabel,
      unplayedOldVideoDays,
      showExtensionField,
      showDurationField,
      centerGridTitles,
      thumbnailQuality,
    ) {
      VideoCardUiConfig(
        unlimitedNameLines = unlimitedNameLines,
        showThumbnails = showVideoThumbnails,
        showSizeChip = showSizeChip,
        showResolutionChip = showResolutionChip,
        showFramerateInResolution = showFramerateInResolution,
        showCodecSupportIndicator = showCodecSupportIndicator,
        showProgressBar = showProgressBar,
        showDateChip = showDateChip,
        showUnplayedOldVideoLabel = showUnplayedOldVideoLabel,
        unplayedOldVideoDays = unplayedOldVideoDays,
        showExtensionField = showExtensionField,
        showDurationField = showDurationField,
        centerGridTitles = centerGridTitles,
        thumbnailQuality = thumbnailQuality,
      )
    }

  // Find the most recently played video (highest lastPlayedAt timestamp)
  val mostRecentlyPlayedItem =
    remember(videoItems) {
      videoItems
        .filter { it.playlistItem.lastPlayedAt > 0 }
        .maxByOrNull { it.playlistItem.lastPlayedAt }
    }

  when {
    isLoading -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }

    videoItems.isEmpty() -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        androidx.compose.foundation.layout.Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement =
            androidx.compose.foundation.layout.Arrangement
              .spacedBy(8.dp),
        ) {
          Icon(
            imageVector = if (isAudio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.PlaylistAdd,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = emptyTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = emptyHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (onClearFilters != null) {
            TextButton(
              onClick = onClearFilters,
              modifier = Modifier.settingsGlassSurface(cornerRadius = 18.dp, independent = true),
            ) { Text(stringResource(R.string.playlist_ui_clear_filters)) }
          }
        }
      }
    }

    else -> {
      // Only show scrollbar if list has more than 20 items
      val hasEnoughItems = videoItems.size > 20

      // Animate scrollbar alpha
      val scrollbarAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (!hasEnoughItems) 0f else 1f,
        animationSpec =
          androidx.compose.animation.core.spring(
            dampingRatio = app.infinity.mpvz.ui.theme.AppMotion.Effect.Alpha.dampingRatio,
            stiffness = app.infinity.mpvz.ui.theme.AppMotion.Effect.Alpha.stiffness,
          ),
        label = "scrollbarAlpha",
      )

      // Reorderable state
      val reorderableLazyListState =
        rememberReorderableLazyListState(listState) { from, to ->
          if (isReorderMode) {
            onReorder(from.index, to.index)
          }
        }

      Box(modifier = modifier.fillMaxSize()) {
          if (isM3uPlaylist && isGridMode) {
            val gridState = rememberLazyGridState()
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val gridColumns =
              if (manualGridColumnsEnabled) {
                if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait
              } else {
                if (configuration.smallestScreenWidthDp >= 600 || isLandscape) 4 else 2
              }
            LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            gridItems(videoItems, key = { it.playlistItem.id }) { item ->
              M3UVideoCard(
                title = item.video.displayName,
                url = item.video.path,
                logoUrl = item.playlistItem.tvgLogo,
                groupTitle = item.playlistItem.groupTitle,
                hasDrm = !item.playlistItem.licenseType.isNullOrBlank() || !item.playlistItem.licenseKey.isNullOrBlank(),
                hasCustomUserAgent = !item.playlistItem.userAgent.isNullOrBlank(),
                onClick = { onVideoItemClick(item) },
                onLongClick = { onVideoItemLongClick(item) },
                onFavoriteClick = onToggleFavorite?.let { { it(item) } },
                isSelected = selectionManager.isSelected(item),
                isRecentlyPlayed = item.playlistItem.id == mostRecentlyPlayedItem?.playlistItem?.id,
                isFavorite = item.playlistItem.isFavorite,
                video = item.video,
                isGridMode = true,
                isRadio = isRadio,
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }
        } else {
          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp),
          ) {
          items(
            count = videoItems.size,
            key = { index -> videoItems[index].playlistItem.id },
          ) { index ->
            ReorderableItem(reorderableLazyListState, key = videoItems[index].playlistItem.id) {
              val item = videoItems[index]

              val progressPercentage =
                if (item.playlistItem.lastPosition > 0 && item.video.duration > 0) {
                  item.playlistItem.lastPosition.toFloat() / item.video.duration.toFloat() * 100f
                } else {
                  null
                }

              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                if (isM3uPlaylist) {
                  M3UVideoCard(
                    title = item.video.displayName,
                    url = item.video.path,
                    logoUrl = item.playlistItem.tvgLogo,
                    groupTitle = item.playlistItem.groupTitle,
                    hasDrm =
                      !item.playlistItem.licenseType.isNullOrBlank() || !item.playlistItem.licenseKey.isNullOrBlank(),
                    hasCustomUserAgent = !item.playlistItem.userAgent.isNullOrBlank(),
                    onClick = { onVideoItemClick(item) },
                    onLongClick = { onVideoItemLongClick(item) },
                    onFavoriteClick = onToggleFavorite?.let { { it(item) } },
                    isSelected = selectionManager.isSelected(item),
                    isRecentlyPlayed = item.playlistItem.id == mostRecentlyPlayedItem?.playlistItem?.id,
                    isFavorite = item.playlistItem.isFavorite,
                    isRadio = isRadio,
                    video = item.video,
                    isGridMode = false,
                    modifier = Modifier.weight(1f),
                  )
                } else {
                  VideoCard(
                    video = if (isAudio && !item.video.isAudio) item.video.copy(isAudio = true) else item.video,
                    progressPercentage = progressPercentage,
                    isRecentlyPlayed = item.playlistItem.id == mostRecentlyPlayedItem?.playlistItem?.id,
                    isSelected = selectionManager.isSelected(item),
                    onClick = { onVideoItemClick(item) },
                    onLongClick = { onVideoItemLongClick(item) },
                    onThumbClick =
                      if (tapThumbnailToSelect) {
                        { selectionManager.toggle(item) }
                      } else {
                        { onVideoItemClick(item) }
                      },
                    showSubtitleIndicator = showSubtitleIndicator,
                    thumbnailWidthPx = if (isAudio) audioThumbnailSizePx else null,
                    thumbnailHeightPx = if (isAudio) audioThumbnailSizePx else null,
                    modifier = Modifier.weight(1f),
                    uiConfig = videoCardUiConfig,
                  )
                }

                if (isReorderMode) {
                  IconButton(
                    onClick = { },
                    modifier =
                      Modifier
                        .size(48.dp)
                        .draggableHandle(),
                  ) {
                    Icon(
                      imageVector = Icons.RoundedFilled.DragHandle,
                      contentDescription =
                        androidx.compose.ui.res.stringResource(
                          app.infinity.mpvz.R.string.ui_drag_to_reorder,
                        ),
                      tint = MaterialTheme.colorScheme.primary,
                    )
                  }
                }
              }
            }
          }
          }
        }

        if (hasEnoughItems && scrollbarAlpha > 0.01f) {
          ExpressiveScrollBar(
            listState = listState,
            dragLabelProvider = { index ->
              fastScrollGlyph(videoItems.getOrNull(index)?.video?.displayName)
            },
            modifier =
              Modifier
                .align(Alignment.CenterEnd)
                .padding(vertical = 6.dp)
                .graphicsLayer { alpha = scrollbarAlpha },
          )
        }
      }
    }
  }
}

@Composable
private fun M3UPlaylistFilterRow(
  categories: List<String>,
  categoryCounts: Map<String, Int>,
  totalCount: Int,
  favoriteCount: Int,
  hasFavorites: Boolean,
  selectedFilter: String,
  onFilterSelected: (String) -> Unit,
) {
  val glass by koinInject<AppearancePreferences>().liquidGlassSurfaces.collectAsState()
  LazyRow(
    modifier = Modifier.fillMaxWidth(),
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      FilterChip(
        modifier = Modifier.settingsGlassSurface(cornerRadius = 18.dp),
        colors = FilterChipDefaults.filterChipColors(
          containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
          selectedContainerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.secondaryContainer),
        ),
        selected = selectedFilter == M3U_FILTER_ALL,
        border = if (glass) null else FilterChipDefaults.filterChipBorder(enabled = true, selected = selectedFilter == M3U_FILTER_ALL),
        onClick = { onFilterSelected(M3U_FILTER_ALL) },
        label = { Text(stringResource(R.string.playlist_ui_m3u_all_count, totalCount)) },
      )
    }

    if (hasFavorites) {
      item {
        FilterChip(
          modifier = Modifier.settingsGlassSurface(cornerRadius = 18.dp),
          colors = FilterChipDefaults.filterChipColors(
            containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            selectedContainerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.secondaryContainer),
          ),
          selected = selectedFilter == M3U_FILTER_FAVORITES,
          border = if (glass) null else FilterChipDefaults.filterChipBorder(enabled = true, selected = selectedFilter == M3U_FILTER_FAVORITES),
          onClick = { onFilterSelected(M3U_FILTER_FAVORITES) },
          label = { Text(stringResource(R.string.playlist_ui_m3u_saved_count, favoriteCount)) },
        )
      }
    }

    items(categories, key = { it }) { category ->
      FilterChip(
        modifier = Modifier.settingsGlassSurface(cornerRadius = 18.dp),
        colors = FilterChipDefaults.filterChipColors(
          containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
          selectedContainerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.secondaryContainer),
        ),
        selected = selectedFilter == category,
        border = if (glass) null else FilterChipDefaults.filterChipBorder(enabled = true, selected = selectedFilter == category),
        onClick = { onFilterSelected(category) },
        label = {
          Text(
            text = stringResource(R.string.playlist_ui_m3u_category_count, category, categoryCounts[category] ?: 0),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          )
        },
      )
    }
  }
}

private fun buildM3UHeadersExtra(
  playlist: PlaylistEntity?,
  item: PlaylistItemEntity,
): Array<String>? {
  val userAgent = item.userAgent?.takeIf { it.isNotBlank() } ?: playlist?.userAgent?.takeIf { it.isNotBlank() }
  return userAgent?.let { arrayOf("User-Agent", it) }
}

@Composable
private fun StreamUrlDialog(
  url: String,
  onDismiss: () -> Unit,
  onCopy: () -> Unit,
) {
  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.settingsGlassSurface(cornerRadius = 28.dp, independent = true),
    containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
    title = {
      Text(
        androidx.compose.ui.res
          .stringResource(app.infinity.mpvz.R.string.ui_stream_url),
      )
    },
    text = {
      Text(
        text = url,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      androidx.compose.material3.TextButton(
        onClick = {
          onCopy()
          onDismiss()
        },
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.ContentCopy,
          contentDescription = null,
          modifier = Modifier.padding(end = 4.dp).size(18.dp),
        )
        Text(
          androidx.compose.ui.res
            .stringResource(app.infinity.mpvz.R.string.ui_copy),
        )
      }
    },
    dismissButton = {
      androidx.compose.material3.TextButton(onClick = onDismiss) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.infinity.mpvz.R.string.ui_close),
        )
      }
    },
  )
}

@Composable
private fun RemoveFromPlaylistDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
  itemCount: Int,
) {
  if (!isOpen) return

  val itemText = if (itemCount == 1) "video" else "videos"

  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = "Remove $itemCount $itemText from playlist?",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
      )
    },
    text = {
      androidx.compose.foundation.layout.Column(
        verticalArrangement =
          androidx.compose.foundation.layout.Arrangement
            .spacedBy(16.dp),
      ) {
        androidx.compose.material3.Card(
          colors =
            androidx.compose.material3.CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            ),
          shape = MaterialTheme.shapes.extraLarge,
        ) {
          Text(
            text = "The selected $itemText will be removed from this playlist. The original ${if (itemCount == 1) "file" else "files"} will not be deleted.",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(16.dp),
          )
        }
      }
    },
    confirmButton = {
      androidx.compose.material3.Button(
        onClick = {
          onConfirm()
          onDismiss()
        },
        colors =
          androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
          ),
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.infinity.mpvz.R.string.ui_remove_from_playlist),
          fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
      }
    },
    dismissButton = {
      androidx.compose.material3.TextButton(
        onClick = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.infinity.mpvz.R.string.generic_cancel),
          fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        )
      }
    },
    containerColor = MaterialTheme.colorScheme.surface,
    tonalElevation = 6.dp,
    shape = MaterialTheme.shapes.extraLarge,
  )
}

private fun getFileNameFromUri(
  context: android.content.Context,
  uri: Uri,
): String? =
  try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
      cursor.moveToFirst()
      cursor.getString(nameIndex)
    } ?: uri.lastPathSegment
  } catch (e: Exception) {
    uri.lastPathSegment
  }

private const val M3U_FILTER_ALL = PlaylistPresentationPolicy.FILTER_ALL
private const val M3U_FILTER_FAVORITES = PlaylistPresentationPolicy.FILTER_FAVORITES
