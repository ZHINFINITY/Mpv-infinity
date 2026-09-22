/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.browser.navidrome

import app.infinity.mpvz.ui.utils.NavigationBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.infinity.mpvz.R
import app.infinity.mpvz.domain.navidrome.NavidromeMusicTab
import app.infinity.mpvz.domain.navidrome.NavidromeServer
import app.infinity.mpvz.repository.JellyfinRepository
import app.infinity.mpvz.repository.NavidromeRepository
import app.infinity.mpvz.ui.browser.components.BrowserTopBar
import app.infinity.mpvz.ui.browser.music.SharedMusicGridCard
import app.infinity.mpvz.ui.browser.music.SharedMusicTrackListItem
import app.infinity.mpvz.ui.browser.music.MusicSourceChooser
import app.infinity.mpvz.presentation.components.pullrefresh.PullRefreshBox
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.browser.audiobooks.AudiobookLibraryScreen
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.preferences.MediaServersPreferencesScreen
import app.infinity.mpvz.ui.preferences.PreferencesScreen
import app.infinity.mpvz.ui.browser.dialogs.MusicSortDialog
import app.infinity.mpvz.ui.browser.music.MusicSortField
import app.infinity.mpvz.ui.browser.LocalNavigationBarHeight
import org.koin.compose.koinInject
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavidromeContent(
  viewModel: NavidromeViewModel,
  modifier: Modifier = Modifier,
  isMusicOnlyMode: Boolean = false,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val isRefreshing = remember { mutableStateOf(false) }
  val context = LocalContext.current
  val backStack = LocalBackStack.current

  val navidromeRepository = koinInject<NavidromeRepository>()
  val jellyfinRepository = koinInject<JellyfinRepository>()
  val jellyfinServers by jellyfinRepository.allServers.collectAsState(initial = emptyList())

  var isSearching by rememberSaveable { mutableStateOf(false) }
  var isAddDialogOpen by remember { mutableStateOf(false) }
  var isSortDialogOpen by rememberSaveable { mutableStateOf(false) }
  val searchFocusRequester = remember { FocusRequester() }

  val musicTabs = remember {
    listOf(
      NavidromeMusicTab.HOME,
      NavidromeMusicTab.TRACKS,
      NavidromeMusicTab.ALBUMS,
      NavidromeMusicTab.ARTISTS,
      NavidromeMusicTab.PLAYLISTS,
    )
  }

  val musicPagerState = rememberPagerState(
    initialPage = 0,
    pageCount = { musicTabs.size },
  )
  val pagerScope = rememberCoroutineScope()

  LaunchedEffect(musicPagerState.settledPage, musicPagerState.isScrollInProgress) {
    if (!musicPagerState.isScrollInProgress) {
      musicTabs.getOrNull(musicPagerState.settledPage)?.let { tab ->
        viewModel.setMusicTab(tab)
      }
    }
  }

  LaunchedEffect(isSearching) {
    if (isSearching) {
      searchFocusRequester.requestFocus()
    }
  }

  LaunchedEffect(uiState.isLoading) {
    isRefreshing.value = uiState.isLoading
  }

  // Intercept back button if searching or detail open
  val isBackEnabled = isSearching || uiState.detailAlbum != null || uiState.detailArtist != null || uiState.detailPlaylist != null

  BackHandler(enabled = isBackEnabled) {
    when {
      uiState.detailAlbum != null || uiState.detailArtist != null || uiState.detailPlaylist != null -> {
        viewModel.closeDetail()
      }
      isSearching -> {
        isSearching = false
        viewModel.onSearchQueryChanged("")
      }
    }
  }

  val headerContainerColor =
    if (false) {
      Color.Transparent
    } else if (MaterialTheme.colorScheme.background == Color.Black) {
      Color.Black
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    // Top Bar Container
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(headerContainerColor),
    ) {
      if (isSearching) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
              .padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 6.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = viewModel::onSearchQueryChanged,
            placeholder = { Text("Search songs, albums, artists...") },
            leadingIcon = {
              IconButton(onClick = {
                isSearching = false
                viewModel.onSearchQueryChanged("")
              }) {
                Icon(
                  Icons.RoundedFilled.ArrowBack,
                  contentDescription = stringResource(R.string.back),
                  tint = MaterialTheme.colorScheme.onSurface,
                )
              }
            },
            trailingIcon = {
              if (uiState.searchQuery.isNotEmpty()) {
                IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                  Icon(
                    Icons.RoundedFilled.Close,
                    contentDescription = stringResource(R.string.pref_clear_content_desc),
                    tint = MaterialTheme.colorScheme.onSurface,
                  )
                }
              }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              focusedBorderColor = Color.Transparent,
              unfocusedBorderColor = Color.Transparent,
            ),
            modifier = Modifier
              .fillMaxWidth()
              .focusRequester(searchFocusRequester),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { }),
          )
        }
      } else {
        BrowserTopBar(
          title = if (isMusicOnlyMode) stringResource(R.string.ui_music) else (uiState.activeServer?.name ?: stringResource(R.string.pref_navidrome_title)),
          showTitle = !isMusicOnlyMode,
          isInSelectionMode = false,
          selectedCount = 0,
          totalCount = 0,
          onCancelSelection = { },
          onSortClick = if (uiState.activeTab != NavidromeMusicTab.HOME) {
            { isSortDialogOpen = true }
          } else null,
          onSearchClick = { isSearching = true },
          onSettingsClick = { backStack.add(PreferencesScreen) },
          leadingActions = {
            if (isMusicOnlyMode) {
              Icon(
                imageVector = Icons.RoundedFilled.Audiotrack,
                contentDescription = stringResource(R.string.ui_music),
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurface,
              )
              MusicSourceChooser(
                hasJellyfin = jellyfinServers.isNotEmpty(),
                hasNavidrome = uiState.servers.isNotEmpty(),
                onManageServers = { backStack.add(MediaServersPreferencesScreen) },
                modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
              )
            }
          },
          additionalActions = {
            if (isMusicOnlyMode) {
              IconButton(
                onClick = { backStack.add(AudiobookLibraryScreen) },
                modifier = Modifier.padding(horizontal = 2.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.AudiobookWave,
                  contentDescription = stringResource(R.string.audiobooks_title),
                  modifier = Modifier.size(22.dp),
                  tint = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
            if (!isMusicOnlyMode) {
              IconButton(
                onClick = { backStack.add(MediaServersPreferencesScreen) },
                modifier = Modifier.padding(horizontal = 2.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Language,
                  contentDescription = "Manage Navidrome servers",
                  modifier = Modifier.size(24.dp),
                  tint = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          },
        )
      }

      if (!isSearching) {
        val selectedTabIndex = musicPagerState.currentPage.coerceIn(0, (musicTabs.size - 1).coerceAtLeast(0))
        PrimaryScrollableTabRow(
          selectedTabIndex = selectedTabIndex,
          containerColor = Color.Transparent,
          contentColor = MaterialTheme.colorScheme.onSurface,
          edgePadding = 8.dp,
          divider = {},
        ) {
          musicTabs.forEachIndexed { index, tab ->
            Tab(
              selected = selectedTabIndex == index,
              onClick = { pagerScope.launch { musicPagerState.animateScrollToPage(index) } },
              text = {
                Text(
                  text = tab.title,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Medium,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis,
                )
              },
              selectedContentColor = MaterialTheme.colorScheme.onSurface,
              unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        HorizontalDivider()
      }
    }

    // Body
    val navigationBarHeight = LocalNavigationBarHeight.current
    val server = uiState.activeServer

    if (server == null) {
      Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        androidx.compose.material3.Card(
          shape = RoundedCornerShape(24.dp),
          colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
          ),
        ) {
          Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            androidx.compose.material3.Icon(
              painter = painterResource(R.drawable.ic_navidrome),
              contentDescription = null,
              modifier = Modifier.size(56.dp),
              tint = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = stringResource(R.string.pref_navidrome_title),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = stringResource(R.string.pref_navidrome_no_server),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            FilledTonalButton(
              onClick = { isAddDialogOpen = true },
            ) {
              Text(stringResource(R.string.generic_configure))
            }
          }
        }
      }
    } else if (isSearching && uiState.searchResult != null) {
      // Search Results
      val result = uiState.searchResult!!
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = navigationBarHeight + 84.dp),
      ) {
        if (result.songs.isNotEmpty()) {
          item {
            Text(
              text = "Songs (${result.songs.size})",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
          items(result.songs, key = { it.id }) { song ->
            SharedMusicTrackListItem(
              title = song.title,
              subtitle = song.artist,
              durationSeconds = song.durationSeconds.toLong(),
              artworkUrl = navidromeRepository.getSongCoverArtUrl(server, song),
              onClick = { viewModel.playSong(context, song) },
            )
          }
        }

        if (result.albums.isNotEmpty()) {
          item {
            Text(
              text = "Albums (${result.albums.size})",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
          items(result.albums, key = { it.id }) { album ->
            SharedMusicTrackListItem(
              title = album.title,
              subtitle = album.artist,
              durationSeconds = null,
              artworkUrl = navidromeRepository.getCoverArtUrl(server, album.coverArtId),
              onClick = { viewModel.openAlbumDetail(album) },
            )
          }
        }

        if (result.artists.isNotEmpty()) {
          item {
            Text(
              text = "Artists (${result.artists.size})",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
          items(result.artists, key = { it.id }) { artist ->
            SharedMusicTrackListItem(
              title = artist.name,
              subtitle = "${artist.albumCount} albums",
              durationSeconds = null,
              artworkUrl = navidromeRepository.getArtistImageUrl(server, artist),
              onClick = { viewModel.openArtistDetail(artist) },
            )
          }
        }
      }
    } else {
      PullRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize(),
      ) {
        NavidromeMusicView(
          uiState = uiState,
          server = server,
          pagerState = musicPagerState,
          visibleTabs = musicTabs,
          onTabSelected = { tab ->
            pagerScope.launch { musicPagerState.animateScrollToPage(musicTabs.indexOf(tab)) }
          },
          onSongClick = { song -> viewModel.playSong(context, song) },
          onAlbumClick = { album -> viewModel.openAlbumDetail(album) },
          onArtistClick = { artist -> viewModel.openArtistDetail(artist) },
          onPlaylistClick = { playlist -> viewModel.openPlaylistDetail(playlist) },
          onToggleFavorite = { song -> viewModel.toggleFavorite(song) },
          navigationBarHeight = navigationBarHeight,
        )
      }
    }

    // Detail sheet
    if (server != null) {
      NavidromeDetailSheet(
        uiState = uiState,
        server = server,
        viewModel = viewModel,
        onDismiss = { viewModel.closeDetail() },
      )
    }

    // Sort & View dialog
    val availableFields = remember(uiState.activeTab) {
      when (uiState.activeTab) {
        NavidromeMusicTab.TRACKS -> listOf(
          MusicSortField.TITLE,
          MusicSortField.ARTIST,
          MusicSortField.ALBUM,
          MusicSortField.DURATION,
          MusicSortField.YEAR,
        )
        NavidromeMusicTab.ALBUMS -> listOf(
          MusicSortField.TITLE,
          MusicSortField.ARTIST,
          MusicSortField.YEAR,
          MusicSortField.TRACK_COUNT,
          MusicSortField.DURATION,
        )
        NavidromeMusicTab.ARTISTS -> listOf(
          MusicSortField.ARTIST,
          MusicSortField.TRACK_COUNT,
        )
        NavidromeMusicTab.PLAYLISTS -> listOf(
          MusicSortField.TITLE,
          MusicSortField.TRACK_COUNT,
          MusicSortField.DURATION,
        )
        else -> emptyList()
      }
    }

    MusicSortDialog(
      isOpen = isSortDialogOpen,
      onDismiss = { isSortDialogOpen = false },
      sortField = uiState.sortField,
      sortOrder = uiState.sortOrder,
      viewMode = uiState.viewMode,
      onSortFieldChange = { viewModel.setSortField(it) },
      onSortOrderChange = { viewModel.setSortOrder(it) },
      onViewModeChange = { viewModel.setViewMode(it) },
      availableFields = availableFields,
    )

    // Add server dialog
    AddNavidromeServerDialog(
      isOpen = isAddDialogOpen,
      isLoading = uiState.isConnectingServer,
      errorMessage = uiState.connectServerError,
      onDismiss = { isAddDialogOpen = false },
      onConnect = { url, name, authMode, username, password, token ->
        viewModel.connectServer(
          serverUrl = url,
          serverName = name,
          authMode = authMode,
          username = username,
          password = password,
          token = token,
          onSuccess = { isAddDialogOpen = false },
        )
      },
    )
  }
}
