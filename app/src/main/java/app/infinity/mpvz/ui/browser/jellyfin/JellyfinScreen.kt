/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.jellyfin

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.infinity.mpvz.presentation.components.RemoteImage
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private val StarYellow = Color(0xFFFFC107)

// ─── Main JellyfinScreen ────────────────────────────────────────────
@Composable
fun JellyfinScreen(
  viewModel: JellyfinViewModel,
  httpClient: OkHttpClient,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsState()
  val context = LocalContext.current

  LaunchedEffect(httpClient) {
    viewModel.setHttpClient(httpClient)
  }

  if (uiState.session == null) {
    JellyfinLoginContent(uiState = uiState, viewModel = viewModel)
  } else {
    JellyfinHomeContent(uiState = uiState, viewModel = viewModel, context = context)
  }
}

// ─── Login Screen ───────────────────────────────────────────────────
@Composable
private fun JellyfinLoginContent(
  uiState: JellyfinUiState,
  viewModel: JellyfinViewModel,
) {
  var serverUrl by remember { mutableStateOf("") }
  var username by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var useToken by remember { mutableStateOf(false) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.primaryContainer,
      modifier = Modifier.size(80.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.RoundedFilled.PlayArrow,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(40.dp),
        )
      }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
      text = "Connect to Jellyfin",
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "Stream your media library directly with hardware acceleration.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(24.dp))

    OutlinedTextField(
      value = serverUrl,
      onValueChange = { serverUrl = it },
      label = { Text("Server URL") },
      placeholder = { Text("http://192.168.1.100:8096") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
      value = username,
      onValueChange = { username = it },
      label = { Text(if (useToken) "Token Label" else "Username") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
      value = password,
      onValueChange = { password = it },
      label = { Text(if (useToken) "API Token" else "Password") },
      visualTransformation = PasswordVisualTransformation(),
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = { useToken = !useToken }) {
      Text(if (useToken) "Use password instead" else "Use API token instead")
    }
    Spacer(modifier = Modifier.height(16.dp))

    if (uiState.isAuthenticating) {
      CircularProgressIndicator(modifier = Modifier.size(36.dp))
    } else {
      Button(
        onClick = {
          if (useToken) viewModel.loginWithToken(serverUrl, username, password) { }
          else viewModel.login(serverUrl, username, password) { }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
      ) {
        Text(if (useToken) "Connect with Token" else "Sign In")
      }
    }

    uiState.authError?.let { error ->
      Spacer(modifier = Modifier.height(12.dp))
      Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
  }
}

// ─── Home Content ───────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JellyfinHomeContent(
  uiState: JellyfinUiState,
  viewModel: JellyfinViewModel,
  context: android.content.Context,
) {
  val scope = rememberCoroutineScope()
  var isSearching by remember { mutableStateOf(false) }
  var isSortDialogOpen by remember { mutableStateOf(false) }
  var isMoreMenuOpen by remember { mutableStateOf(false) }
  var isSeerrInfoOpen by remember { mutableStateOf(false) }

  BackHandler(enabled = isSearching || uiState.detailItem != null || uiState.openLibrary != null) {
    when {
      isSearching -> {
        isSearching = false
        viewModel.setSearchQuery("")
        viewModel.refresh()
      }
      else -> viewModel.navigateBack()
    }
  }

  val headerBg = if (MaterialTheme.colorScheme.background == Color.Black) Color.Black else MaterialTheme.colorScheme.surfaceContainer

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    // Top Bar
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(headerBg),
    ) {
      AnimatedVisibility(
        visible = isSearching,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = { viewModel.setSearchQuery(it); viewModel.search(it) },
            placeholder = { Text("Search movies, shows...") },
            leadingIcon = {
              Icon(imageVector = Icons.RoundedFilled.Search, contentDescription = null)
            },
            trailingIcon = {
              IconButton(onClick = {
                if (uiState.searchQuery.isNotEmpty()) {
                  viewModel.setSearchQuery("")
                  viewModel.refresh()
                } else {
                  isSearching = false
                }
              }) {
                Icon(imageVector = Icons.RoundedFilled.Close, contentDescription = null)
              }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            JellyfinSearchCategory.entries.forEach { category ->
              val selected = uiState.searchCategory == category
              FilterChip(
                selected = selected,
                onClick = { viewModel.setSearchCategory(category) },
                label = { Text(category.displayName, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
              )
            }
          }
        }
      }

      if (!isSearching) {
        TopAppBar(
          title = {
            Text(
              text = uiState.openLibrary?.title ?: uiState.activeServer?.name ?: "Jellyfin",
              fontWeight = FontWeight.Bold,
            )
          },
          navigationIcon = {
            if (uiState.openLibrary != null) {
              IconButton(onClick = { viewModel.navigateBack() }) {
                Icon(imageVector = Icons.RoundedFilled.ArrowBack, contentDescription = "Back")
              }
            }
          },
          actions = {
            IconButton(onClick = { viewModel.refresh() }) {
              Icon(imageVector = Icons.RoundedFilled.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = { isSearching = true }) {
              Icon(imageVector = Icons.RoundedFilled.Search, contentDescription = "Search")
            }
            if (uiState.openLibrary != null) {
              Box {
                var showSortMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { showSortMenu = true }) {
                  Icon(imageVector = Icons.RoundedFilled.Tune, contentDescription = "Sort")
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                  JellyfinSortBy.entries.forEach { sortBy ->
                    DropdownMenuItem(
                      text = { Text(sortBy.displayName) },
                      onClick = { viewModel.setSort(sortBy, uiState.sortOrder); showSortMenu = false },
                    )
                  }
                  DropdownMenuItem(
                    text = { Text("Reverse order") },
                    onClick = {
                      val newOrder = if (uiState.sortOrder == JellyfinSortOrder.ASCENDING) JellyfinSortOrder.DESCENDING else JellyfinSortOrder.ASCENDING
                      viewModel.setSort(uiState.sortBy, newOrder)
                      showSortMenu = false
                    },
                  )
                }
              }
            }
            Box {
              IconButton(onClick = { isMoreMenuOpen = true }) {
                Icon(imageVector = Icons.RoundedFilled.MoreVert, contentDescription = "More Jellyfin options")
              }
              DropdownMenu(
                expanded = isMoreMenuOpen,
                onDismissRequest = { isMoreMenuOpen = false },
              ) {
                DropdownMenuItem(
                  text = { Text("Manage servers") },
                  leadingIcon = { Icon(imageVector = Icons.RoundedFilled.BringYourOwnIp, contentDescription = null) },
                  onClick = { isMoreMenuOpen = false },
                )
                DropdownMenuItem(
                  text = { Text("Seerr requests") },
                  leadingIcon = { Icon(imageVector = Icons.RoundedFilled.PlaylistAdd, contentDescription = null) },
                  onClick = {
                    isMoreMenuOpen = false
                    isSeerrInfoOpen = true
                  },
                )
                DropdownMenuItem(
                  text = { Text("Disconnect") },
                  leadingIcon = { Icon(imageVector = Icons.RoundedFilled.LinkOff, contentDescription = null) },
                  onClick = {
                    isMoreMenuOpen = false
                    viewModel.logout()
                  },
                )
              }
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = headerBg),
        )
      }
    }

    // Main Body
    Box(
      modifier = Modifier
        .fillMaxSize()
        .weight(1f),
    ) {
      when {
        uiState.isLoading && uiState.libraries.isEmpty() && uiState.heroItems.isEmpty() -> {
          CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        uiState.error != null && uiState.libraries.isEmpty() && uiState.currentItems.isEmpty() -> {
          Column(
            modifier = Modifier.padding(24.dp).align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Icon(imageVector = Icons.RoundedFilled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(uiState.error ?: "An error occurred", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { viewModel.refresh() }) { Text("Retry") }
          }
        }

        // Home Dashboard (no library open, no search)
        uiState.openLibrary == null && uiState.searchQuery.isBlank() -> {
          HomeDashboard(uiState = uiState, viewModel = viewModel, context = context)
        }

        // Library content or search results
        else -> {
          LibraryContent(uiState = uiState, viewModel = viewModel, context = context)
        }
      }
    }

    if (isSeerrInfoOpen) {
      AlertDialog(
        onDismissRequest = { isSeerrInfoOpen = false },
        title = { Text("Seerr requests") },
        text = {
          Text("Seerr is available as a Jellyfin-side request service. Configure it in the server profile before submitting requests.")
        },
        confirmButton = {
          TextButton(onClick = { isSeerrInfoOpen = false }) { Text("OK") }
        },
      )
    }
  }
}

// ─── Home Dashboard ─────────────────────────────────────────────────
@Composable
private fun HomeDashboard(
  uiState: JellyfinUiState,
  viewModel: JellyfinViewModel,
  context: android.content.Context,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    // 1. Hero Banner
    if (uiState.heroItems.isNotEmpty()) {
      item {
        JellyfinHeroBanner(
          items = uiState.heroItems,
          session = uiState.session!!,
          onPlay = { viewModel.playItem(context, it) },
          onDetails = { /* TODO: detail sheet */ },
        )
      }
    }

    // 2. Libraries
    val homeLibraries = uiState.libraries.filter {
      !it.collectionType.equals("playlists", ignoreCase = true) && !it.name.equals("playlists", ignoreCase = true)
    }
    if (homeLibraries.isNotEmpty()) {
      item {
        SectionHeader(title = "Libraries")
      }
      item {
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
          items(homeLibraries, key = { it.id }) { lib ->
            LibraryCard(
              collection = lib,
              session = uiState.session!!,
              onClick = { viewModel.loadLibrary(lib.id) },
            )
          }
        }
      }
    }

    // 3. Latest Movies
    if (uiState.latestMovies.isNotEmpty()) {
      item {
        SectionHeader(title = "Latest Movies", subtitle = "Newly added")
      }
      item {
        HorizontalSection(
          items = uiState.latestMovies,
          session = uiState.session!!,
          onItemPlay = { viewModel.playItem(context, it) },
        )
      }
    }

    // 4. Latest Shows
    if (uiState.latestShows.isNotEmpty()) {
      item {
        SectionHeader(title = "Latest Episodes", subtitle = "Newly updated")
      }
      item {
        HorizontalSection(
          items = uiState.latestShows,
          session = uiState.session!!,
          onItemPlay = { viewModel.playItem(context, it) },
        )
      }
    }

    // 5. Music
    if (uiState.latestMusic.isNotEmpty()) {
      item {
        SectionHeader(title = "Music", subtitle = "Albums & Tracks")
      }
      item {
        HorizontalSection(
          items = uiState.latestMusic,
          session = uiState.session!!,
          onItemPlay = { viewModel.playItem(context, it) },
        )
      }
    }
  }
}

// ─── Library Content ────────────────────────────────────────────────
@Composable
private fun LibraryContent(
  uiState: JellyfinUiState,
  viewModel: JellyfinViewModel,
  context: android.content.Context,
) {
  if (uiState.openLibrary?.isMusic == true) {
    MusicContent(uiState = uiState, viewModel = viewModel, context = context)
  } else {
    if (uiState.currentItems.isEmpty() && !uiState.isLoading) {
      Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(imageVector = Icons.RoundedFilled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(48.dp))
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = if (uiState.searchQuery.isNotBlank()) "No results found" else "No media found",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    } else {
      LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(uiState.currentItems, key = { it.id }) { track ->
          ListItemCard(
            track = track,
            session = uiState.session!!,
            onClick = { viewModel.playItem(context, track) },
          )
        }
        if (uiState.isLoadingMore) {
          item {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          }
        }
      }
    }
  }
}

// ─── Music Content (Tabs: Home, Songs, Albums, Artists, Playlists) ──
@Composable
private fun MusicContent(
  uiState: JellyfinUiState,
  viewModel: JellyfinViewModel,
  context: android.content.Context,
) {
  val scope = rememberCoroutineScope()
  val musicTabs = remember {
    listOf(JellyfinMusicTab.HOME, JellyfinMusicTab.TRACKS, JellyfinMusicTab.ALBUMS, JellyfinMusicTab.ARTISTS, JellyfinMusicTab.PLAYLISTS)
  }
  val pagerState = rememberPagerState(
    initialPage = musicTabs.indexOf(uiState.musicActiveTab).coerceIn(0, musicTabs.lastIndex),
    pageCount = { musicTabs.size },
  )

  LaunchedEffect(pagerState.settledPage, musicTabs) {
    musicTabs.getOrNull(pagerState.settledPage)?.let { tab ->
      if (uiState.musicActiveTab != tab) viewModel.setMusicTab(tab)
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    PrimaryScrollableTabRow(
      selectedTabIndex = pagerState.currentPage.coerceIn(0, musicTabs.lastIndex),
      containerColor = Color.Transparent,
      contentColor = MaterialTheme.colorScheme.primary,
      edgePadding = 8.dp,
      divider = {},
    ) {
      musicTabs.forEachIndexed { index, tab ->
        Tab(
          selected = pagerState.currentPage == index,
          onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
          text = {
            Text(
              text = tab.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Medium,
              maxLines = 1,
            )
          },
        )
      }
    }

    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxSize(),
      beyondViewportPageCount = 1,
    ) { page ->
      val tab = musicTabs.getOrNull(page) ?: JellyfinMusicTab.HOME
      when (tab) {
        JellyfinMusicTab.HOME -> MusicHomeView(uiState = uiState, session = uiState.session!!, context = context, viewModel = viewModel)
        JellyfinMusicTab.TRACKS -> MusicListView(uiState.musicTracks.ifEmpty { uiState.latestMusic }, uiState.session!!, context, viewModel)
        JellyfinMusicTab.ALBUMS -> MusicGridView(uiState.musicAlbums.ifEmpty { uiState.latestMusic }, uiState.session!!)
        JellyfinMusicTab.ARTISTS -> MusicGridView(uiState.musicArtists, uiState.session!!)
        JellyfinMusicTab.PLAYLISTS -> MusicGridView(uiState.musicTracks, uiState.session!!)
      }
    }
  }
}

@Composable
private fun MusicHomeView(
  uiState: JellyfinUiState,
  session: JellyfinSession,
  context: android.content.Context,
  viewModel: JellyfinViewModel,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(20.dp),
    contentPadding = PaddingValues(bottom = 16.dp),
  ) {
    if (uiState.latestMusic.isNotEmpty()) {
      item { SectionHeader(title = "Recently Added") }
      item {
        LazyRow(
          contentPadding = PaddingValues(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(uiState.latestMusic.take(10), key = { it.id }) { track ->
            PosterCard(track = track, session = session, onClick = { viewModel.playItem(context, track) })
          }
        }
      }
    }
  }
}

@Composable
private fun MusicListView(
  items: List<JellyfinTrack>,
  session: JellyfinSession,
  context: android.content.Context,
  viewModel: JellyfinViewModel,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    items(items, key = { it.id }) { track ->
      ListItemCard(track = track, session = session, onClick = { viewModel.playItem(context, track) })
    }
  }
}

@Composable
private fun MusicGridView(
  items: List<JellyfinTrack>,
  session: JellyfinSession,
) {
  if (items.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
      Text("No items", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    return
  }
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    items(items, key = { it.id }) { track ->
      ListItemCard(track = track, session = session, onClick = { })
    }
  }
}

// ─── Hero Banner (mpvRx style with auto-scroll) ─────────────────────
@Composable
private fun JellyfinHeroBanner(
  items: List<JellyfinTrack>,
  session: JellyfinSession,
  onPlay: (JellyfinTrack) -> Unit,
  onDetails: (JellyfinTrack) -> Unit,
) {
  if (items.isEmpty()) return

  val pagerState = rememberPagerState(pageCount = { items.size })

  LaunchedEffect(pagerState.settledPage, items.size) {
    if (items.size > 1) {
      delay(5000L)
      if (!pagerState.isScrollInProgress) {
        val nextPage = (pagerState.currentPage + 1) % items.size
        pagerState.animateScrollToPage(nextPage, animationSpec = tween(800))
      }
    }
  }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(360.dp)
      .clip(RoundedCornerShape(20.dp)),
  ) {
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
      val item = items[page]
      val artworkUrl = remember(session.serverUrl, item.id, session.accessToken) {
        "${session.serverUrl}/Items/${item.id}/Images/Backdrop?maxWidth=1280&quality=80&api_key=${session.accessToken}"
      }

      Box(modifier = Modifier.fillMaxSize()) {
        RemoteImage(
          url = artworkUrl,
          contentDescription = item.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                0.0f to Color.Black.copy(alpha = 0.6f),
                0.3f to Color.Transparent,
                0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                1.0f to MaterialTheme.colorScheme.background,
              ),
            ),
        )
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
          verticalArrangement = Arrangement.Bottom,
        ) {
          // Type Badge
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp),
          ) {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)) {
              Text(
                text = if (item.isVideo) "MOVIE" else "AUDIO",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
          Text(
            text = item.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = item.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(modifier = Modifier.height(12.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
              onClick = { onPlay(item) },
              shape = RoundedCornerShape(14.dp),
              contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) {
              Icon(imageVector = Icons.RoundedFilled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(text = "Play Now", fontWeight = FontWeight.Bold)
            }
            FilledTonalButton(
              onClick = { onDetails(item) },
              shape = RoundedCornerShape(14.dp),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
              Icon(imageVector = Icons.RoundedFilled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(text = "Details", fontWeight = FontWeight.Medium)
            }
          }
        }
      }
    }
  }
}

// ─── Section Header ─────────────────────────────────────────────────
@Composable
private fun SectionHeader(
  title: String,
  subtitle: String? = null,
  onSeeAll: (() -> Unit)? = null,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      if (!subtitle.isNullOrBlank()) {
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    if (onSeeAll != null) {
      TextButton(onClick = onSeeAll, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
        Text("See All", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Icon(imageVector = Icons.RoundedFilled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
      }
    }
  }
}

// ─── Horizontal Section ─────────────────────────────────────────────
@Composable
private fun HorizontalSection(
  items: List<JellyfinTrack>,
  session: JellyfinSession,
  onItemPlay: (JellyfinTrack) -> Unit,
) {
  if (items.isEmpty()) return
  LazyRow(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(horizontal = 16.dp),
  ) {
    items(items, key = { it.id }) { item ->
      PosterCard(track = item, session = session, onClick = { onItemPlay(item) })
    }
  }
}

// ─── Poster Card ────────────────────────────────────────────────────
@Composable
private fun PosterCard(
  track: JellyfinTrack,
  session: JellyfinSession,
  onClick: () -> Unit,
  cardWidth: androidx.compose.ui.unit.Dp = 136.dp,
) {
  val imageUrl = remember(session.serverUrl, track.id, session.accessToken) {
    "${session.serverUrl}/Items/${track.id}/Images/Primary?maxWidth=400&quality=90&api_key=${session.accessToken}"
  }

  Column(
    modifier = Modifier
      .width(cardWidth)
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onClick),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(2f / 3f)
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
      RemoteImage(
        url = imageUrl,
        contentDescription = track.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 6.dp),
    ) {
      Text(
        text = track.title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = if (track.isVideo) track.album else track.artist,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

// ─── Library Card ───────────────────────────────────────────────────
@Composable
private fun LibraryCard(
  collection: JellyfinCollection,
  session: JellyfinSession,
  onClick: () -> Unit,
) {
  val colType = collection.collectionType?.lowercase() ?: ""
  val icon = when {
    colType == "movies" -> Icons.RoundedFilled.Movie
    colType == "tvshows" -> Icons.RoundedFilled.Movie
    colType == "music" -> Icons.RoundedFilled.Audiotrack
    else -> Icons.RoundedFilled.Folder
  }

  Column(
    modifier = Modifier
      .width(200.dp)
      .clickable(onClick = onClick),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      contentAlignment = Alignment.Center,
    ) {
      Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = collection.name,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

// ─── List Item Card ─────────────────────────────────────────────────
@Composable
private fun ListItemCard(
  track: JellyfinTrack,
  session: JellyfinSession,
  onClick: () -> Unit,
) {
  val imageUrl = remember(session.serverUrl, track.id, session.accessToken) {
    "${session.serverUrl}/Items/${track.id}/Images/Primary?maxWidth=400&quality=90&api_key=${session.accessToken}"
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 4.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(
      modifier = Modifier
        .size(width = if (track.isVideo) 140.dp else 84.dp, height = if (track.isVideo) 79.dp else 84.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
      RemoteImage(
        url = imageUrl,
        contentDescription = track.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
        text = track.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = if (track.isVideo) track.album else track.artist,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Icon(
      imageVector = Icons.RoundedFilled.PlayArrow,
      contentDescription = "Play",
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(22.dp),
    )
  }
}
