/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.jellyfin

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.presentation.components.RemoteImage
import app.infinity.mpvz.ui.player.PlayerActivity
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import kotlin.math.roundToInt

// ─── Color constants ────────────────────────────────────────────────
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
    JellyfinLoginContent(
      uiState = uiState,
      viewModel = viewModel,
    )
  } else {
    JellyfinHomeContent(
      uiState = uiState,
      viewModel = viewModel,
      context = context,
    )
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
          imageVector = Icons.Filled.Add,
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
          if (useToken) {
            viewModel.loginWithToken(serverUrl, username, password) { }
          } else {
            viewModel.login(serverUrl, username, password) { }
          }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
      ) {
        Text(if (useToken) "Connect with Token" else "Sign In")
      }
    }

    uiState.authError?.let { error ->
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = error,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
      )
    }

    uiState.servers.forEach { profile ->
      Spacer(modifier = Modifier.height(16.dp))
      OutlinedButton(
        onClick = { viewModel.loginWithToken(profile.serverUrl, profile.username, profile.accessToken) { } },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Switch to ${profile.name} @ ${profile.serverUrl}")
      }
    }
  }
}

// ─── Home Content ───────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JellyfinHomeContent(
  uiState: JellyfinUiState,
  viewModel: JellyfinViewModel,
  context: Context,
) {
  var showSearch by remember { mutableStateOf(false) }
  var searchQuery by remember { mutableStateOf("") }
  var showSortMenu by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          if (showSearch) {
            OutlinedTextField(
              value = searchQuery,
              onValueChange = {
                searchQuery = it
                viewModel.search(it)
              },
              placeholder = { Text("Search Jellyfin...") },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
            )
          } else {
            Text(
              text = uiState.openLibrary?.title ?: "Jellyfin",
              fontWeight = FontWeight.Bold,
            )
          }
        },
        navigationIcon = {
          if (uiState.openLibrary != null) {
            IconButton(onClick = {
              viewModel.setSearchQuery("")
              viewModel.loadLibrary(uiState.openLibrary!!.id)
            }) {
              Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
          }
        },
        actions = {
          IconButton(onClick = { showSearch = !showSearch }) {
            Icon(
              if (showSearch) Icons.Filled.Close else Icons.Filled.Search,
              contentDescription = "Search",
            )
          }
          Box {
            IconButton(onClick = { showSortMenu = true }) {
              Icon(Icons.Filled.Sort, contentDescription = "Sort")
            }
            DropdownMenu(
              expanded = showSortMenu,
              onDismissRequest = { showSortMenu = false },
            ) {
              JellyfinSortBy.entries.forEach { sortBy ->
                DropdownMenuItem(
                  text = { Text(sortBy.displayName) },
                  onClick = {
                    viewModel.setSort(sortBy, uiState.sortOrder)
                    showSortMenu = false
                  },
                )
              }
              DropdownMenuItem(
                text = { Text("Reverse order") },
                  onClick = {
                    val newOrder = if (uiState.sortOrder == JellyfinSortOrder.ASCENDING)
                      JellyfinSortOrder.DESCENDING else JellyfinSortOrder.ASCENDING
                    viewModel.setSort(uiState.sortBy, newOrder)
                    showSortMenu = false
                  },
                )
            }
          }
          TextButton(onClick = { viewModel.logout() }) {
            Text("Logout")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        ),
      )
    },
  ) { padding ->
    if (uiState.isLoading && uiState.currentItems.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator()
      }
    } else if (uiState.error != null) {
      Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
          Spacer(modifier = Modifier.height(12.dp))
          Button(onClick = { uiState.session?.let { /* retry */ } }) {
            Text("Retry")
          }
        }
      }
    } else if (uiState.openLibrary != null && uiState.currentItems.isNotEmpty()) {
      // Library browsing mode
      LibraryGrid(
        items = uiState.currentItems,
        viewModel = viewModel,
        context = context,
        modifier = Modifier.padding(padding),
      )
    } else if (uiState.searchQuery.isNotBlank()) {
      // Search results mode
      LibraryGrid(
        items = uiState.currentItems,
        viewModel = viewModel,
        context = context,
        modifier = Modifier.padding(padding),
      )
    } else {
      // Home dashboard
      HomeDashboard(
        uiState = uiState,
        viewModel = viewModel,
        context = context,
        modifier = Modifier.padding(padding),
      )
    }
  }
}

// ─── Home Dashboard ─────────────────────────────────────────────────
@Composable
private fun HomeDashboard(
  uiState: JellyfinUiState,
  viewModel: JellyfinViewModel,
  context: Context,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = 16.dp),
  ) {
    // Libraries section
    if (uiState.libraries.isNotEmpty()) {
      item(key = "libraries_header") {
        Text(
          text = "Libraries",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
      item(key = "libraries_row") {
        LazyRow(
          contentPadding = PaddingValues(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(uiState.libraries) { lib ->
            LibraryChip(
              collection = lib,
              onClick = { viewModel.loadLibrary(lib.id) },
            )
          }
        }
      }
    }

    // Hero banner
    if (uiState.heroItems.isNotEmpty()) {
      item(key = "hero") {
        HeroBanner(
          items = uiState.heroItems,
          onItemClick = { viewModel.playItem(context, it) },
        )
      }
    }

    // Latest Movies rail
    if (uiState.latestMovies.isNotEmpty()) {
      item(key = "movies_header") {
        Text(
          text = "Latest Movies",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
      item(key = "movies_rail") {
        ContentRail(
          items = uiState.latestMovies,
          onTrackClick = { index -> viewModel.playTracks(context, uiState.latestMovies, index) },
        )
      }
    }

    // Latest Episodes rail
    if (uiState.latestShows.isNotEmpty()) {
      item(key = "shows_header") {
        Text(
          text = "Latest Episodes",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
      item(key = "shows_rail") {
        ContentRail(
          items = uiState.latestShows,
          onTrackClick = { index -> viewModel.playTracks(context, uiState.latestShows, index) },
        )
      }
    }

    // Latest Music rail
    if (uiState.latestMusic.isNotEmpty()) {
      item(key = "music_header") {
        Text(
          text = "Latest Music",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
      item(key = "music_rail") {
        ContentRail(
          items = uiState.latestMusic,
          onTrackClick = { index -> viewModel.playTracks(context, uiState.latestMusic, index) },
        )
      }
    }
  }
}

// ─── Library Grid ───────────────────────────────────────────────────
@Composable
private fun LibraryGrid(
  items: List<JellyfinTrack>,
  viewModel: JellyfinViewModel,
  context: Context,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    items(items, key = { it.id }) { track ->
      JellyfinMediaCard(
        track = track,
        onClick = { viewModel.playItem(context, track) },
      )
    }
    item {
      if (items.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxWidth().padding(32.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            "No items found",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

// ─── Library Chip ───────────────────────────────────────────────────
@Composable
private fun LibraryChip(
  collection: JellyfinCollection,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.primaryContainer,
    modifier = Modifier.height(40.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = collection.name,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
    }
  }
}

// ─── Hero Banner ────────────────────────────────────────────────────
@Composable
private fun HeroBanner(
  items: List<JellyfinTrack>,
  onItemClick: (JellyfinTrack) -> Unit,
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(240.dp)
      .padding(horizontal = 16.dp, vertical = 8.dp)
      .clip(RoundedCornerShape(20.dp))
      .clickable { if (items.isNotEmpty()) onItemClick(items.first()) },
  ) {
    val first = items.first()
    RemoteImage(
      url = first.artworkUrl ?: "",
      contentDescription = first.title,
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
    )
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
          ),
        ),
    )
    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(16.dp),
    ) {
      Text(
        text = first.title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "${first.artist} · ${first.album}",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.8f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    // Dot indicators
    if (items.size > 1) {
      Row(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        items.take(5).forEachIndexed { index, _ ->
          Box(
            modifier = Modifier
              .size(8.dp)
              .clip(CircleShape)
              .background(
                if (index == 0) Color.White
                else Color.White.copy(alpha = 0.4f),
              ),
          )
        }
      }
    }
  }
}

// ─── Content Rail (Horizontal scrollable row) ───────────────────────
@Composable
private fun ContentRail(
  items: List<JellyfinTrack>,
  onTrackClick: (Int) -> Unit,
) {
  LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    itemsIndexed(items) { index, track ->
      JellyfinPosterCard(
        track = track,
        onClick = { onTrackClick(index) },
      )
    }
  }
}

// ─── Poster Card ────────────────────────────────────────────────────
@Composable
private fun JellyfinPosterCard(
  track: JellyfinTrack,
  onClick: () -> Unit,
) {
  Column(
    modifier = Modifier
      .width(if (track.isVideo) 142.dp else 156.dp)
      .clip(RoundedCornerShape(14.dp))
      .clickable(onClick = onClick),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(if (track.isVideo) 202.dp else 156.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      track.artworkUrl?.let { artwork ->
        RemoteImage(
          url = artwork,
          contentDescription = track.title,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      }
      // Media type badge
      Surface(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(8.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f),
      ) {
        Text(
          text = if (track.isVideo) track.mediaType else "AUDIO",
          modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
          color = MaterialTheme.colorScheme.onPrimary,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
    Spacer(Modifier.height(7.dp))
    Text(
      track.title,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.titleSmall,
    )
    Text(
      if (track.isVideo) track.album else track.artist,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

// ─── Media Card (List-style) ────────────────────────────────────────
@Composable
private fun JellyfinMediaCard(
  track: JellyfinTrack,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .clickable(onClick = onClick)
      .padding(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(if (track.isVideo) 84.dp else 64.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      track.artworkUrl?.let { artwork ->
        RemoteImage(
          url = artwork,
          contentDescription = track.title,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(
        track.title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        if (track.isVideo) "${track.mediaType} · ${track.album}" else "${track.artist} · ${track.album}",
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}
