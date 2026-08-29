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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.window.Dialog
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.infinity.mpvz.presentation.components.RemoteImage
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.preferences.PreferencesScreen
import app.infinity.mpvz.ui.browser.LocalNavigationBarHeight
import app.infinity.mpvz.ui.utils.LocalBackStack
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
  val backStack = LocalBackStack.current

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
  var showAddServer by remember { mutableStateOf(false) }
  var showSeerrInfo by remember { mutableStateOf(false) }
  var serverUrl by remember { mutableStateOf("") }
  var serverName by remember { mutableStateOf("") }
  var username by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var useToken by remember { mutableStateOf(false) }
  val backStack = LocalBackStack.current

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .statusBarsPadding(),
  ) {
    TopAppBar(
      title = {
        Text(
          text = "Jellyfin",
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Medium,
        )
      },
      actions = {
        IconButton(onClick = { showAddServer = true }) {
          Icon(imageVector = Icons.RoundedFilled.BringYourOwnIp, contentDescription = "Jellyfin servers")
        }
        IconButton(onClick = { backStack.add(PreferencesScreen) }) {
          Icon(imageVector = Icons.RoundedFilled.Settings, contentDescription = "App settings")
        }
      },
      colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
      ),
    )

    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.size(116.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.RoundedFilled.VideoLibrary,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(58.dp),
            )
          }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
          text = "Connect to Jellyfin",
          style = MaterialTheme.typography.headlineMedium,
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = "Stream your media library directly with hardware acceleration and zero transcoding.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(
          onClick = { showAddServer = true },
          shape = RoundedCornerShape(24.dp),
          contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
        ) {
          Icon(imageVector = Icons.RoundedFilled.Add, contentDescription = null)
          Spacer(modifier = Modifier.width(10.dp))
          Text("Add Jellyfin Server", style = MaterialTheme.typography.titleMedium)
        }
      }
    }
  }

  if (showAddServer) {
    Dialog(onDismissRequest = { if (!uiState.isAuthenticating) showAddServer = false }) {
      Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Add Jellyfin Server", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
              Text("Enter your server address and account details", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showAddServer = false }, enabled = !uiState.isAuthenticating) {
              Icon(imageVector = Icons.RoundedFilled.Close, contentDescription = "Close")
            }
          }
          OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server address") },
            placeholder = { Text("jellyfin.example.com:8096") },
            supportingText = { Text("HTTPS is tried first; http:// is also supported") },
            leadingIcon = { Icon(imageVector = Icons.RoundedFilled.BringYourOwnIp, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
          )
          OutlinedTextField(
            value = serverName,
            onValueChange = { serverName = it },
            label = { Text("Profile name") },
            placeholder = { Text("Home server") },
            leadingIcon = { Icon(imageVector = Icons.RoundedFilled.Edit, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
          )
          Text("Authentication method", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
              selected = !useToken,
              onClick = { useToken = false },
              label = { Text("Credentials") },
              leadingIcon = { Icon(imageVector = Icons.RoundedFilled.Person, contentDescription = null) },
            )
            FilterChip(
              selected = useToken,
              onClick = { useToken = true },
              label = { Text("API token") },
              leadingIcon = { Icon(imageVector = Icons.RoundedFilled.Security, contentDescription = null) },
            )
          }
          if (useToken) {
            OutlinedTextField(
              value = password,
              onValueChange = { password = it },
              label = { Text("API token") },
              placeholder = { Text("Dashboard → API Keys") },
              leadingIcon = { Icon(imageVector = Icons.RoundedFilled.Security, contentDescription = null) },
              visualTransformation = PasswordVisualTransformation(),
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
              value = username,
              onValueChange = { username = it },
              label = { Text("Profile user label") },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              shape = RoundedCornerShape(16.dp),
            )
          } else {
            OutlinedTextField(
              value = username,
              onValueChange = { username = it },
              label = { Text("Username") },
              leadingIcon = { Icon(imageVector = Icons.RoundedFilled.Person, contentDescription = null) },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
              value = password,
              onValueChange = { password = it },
              label = { Text("Password") },
              leadingIcon = { Icon(imageVector = Icons.RoundedFilled.Lock, contentDescription = null) },
              visualTransformation = PasswordVisualTransformation(),
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              shape = RoundedCornerShape(16.dp),
            )
          }
          if (uiState.isAuthenticating) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
          } else {
            Button(
              onClick = {
                if (useToken) viewModel.loginWithToken(serverUrl, username, password, serverName) { }
                else viewModel.login(serverUrl, username, password, serverName) { }
              },
              modifier = Modifier.fillMaxWidth(),
              enabled = serverUrl.isNotBlank() && password.isNotBlank() && (useToken || username.isNotBlank()),
              shape = RoundedCornerShape(18.dp),
            ) {
              Icon(imageVector = Icons.RoundedFilled.Link, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text(if (useToken) "Connect with API token" else "Connect to server")
            }
          }
          uiState.authError?.let { error ->
            Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
  }

  if (showSeerrInfo) {
    AlertDialog(
      onDismissRequest = { showSeerrInfo = false },
      title = { Text("Seerr requests") },
      text = { Text("Connect to a Jellyfin server first. Seerr requests will appear here when a Seerr server is configured.") },
      confirmButton = { TextButton(onClick = { showSeerrInfo = false }) { Text("OK") } },
    )
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
  var isServerMenuOpen by remember { mutableStateOf(false) }
  var isServerManagerOpen by remember { mutableStateOf(false) }
  var isSeerrInfoOpen by remember { mutableStateOf(false) }
  var seerrUrl by remember {
    mutableStateOf(
      context.getSharedPreferences("jellyfin_profiles", android.content.Context.MODE_PRIVATE)
        .getString("seerr_url", "")
        .orEmpty(),
    )
  }
  var serverUrl by remember { mutableStateOf("") }
  var serverName by remember { mutableStateOf("") }
  var serverUsername by remember { mutableStateOf("") }
  var serverPassword by remember { mutableStateOf("") }
  var useToken by remember { mutableStateOf(false) }
  val backStack = LocalBackStack.current

  BackHandler(enabled = isSearching || uiState.detailItem != null || uiState.openLibrary != null) {
    when {
      uiState.detailItem != null -> viewModel.closeDetail()
      isSearching -> {
        isSearching = false
        viewModel.setSearchQuery("")
        viewModel.refresh()
      }
      else -> viewModel.navigateBack()
    }
  }

  if (uiState.detailItem != null) {
    JellyfinDetailPage(
      mediaItem = uiState.detailItem,
      session = uiState.session!!,
      seasons = uiState.detailSeasons,
      episodes = uiState.detailEpisodes,
      isEpisodesLoading = uiState.isDetailEpisodesLoading,
      similarItems = uiState.detailSimilarItems,
      onBack = { viewModel.closeDetail() },
      onPlay = { viewModel.playItem(context, uiState.detailItem!!) },
      onEpisodePlay = { episode -> viewModel.playItem(context, episode) },
      onOpenTrailer = { url ->
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
      },
      onSimilarClick = { viewModel.openDetail(it) },
    )
    return
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
            .statusBarsPadding()
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
            IconButton(onClick = { isSearching = true }) {
              Icon(imageVector = Icons.RoundedFilled.Search, contentDescription = "Search")
            }
            IconButton(onClick = { isSeerrInfoOpen = true }) {
              Icon(imageVector = Icons.RoundedFilled.Explore, contentDescription = "Discover and Seerr")
            }
            IconButton(onClick = { isServerManagerOpen = true }) {
              Icon(imageVector = Icons.RoundedFilled.Language, contentDescription = "Manage Jellyfin servers")
            }
            IconButton(onClick = { backStack.add(PreferencesScreen) }) {
              Icon(imageVector = Icons.RoundedFilled.Settings, contentDescription = "App settings")
            }
            Box {
              DropdownMenu(expanded = isServerMenuOpen, onDismissRequest = { isServerMenuOpen = false }) {
                Text("Active server", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                uiState.servers.forEach { server ->
                  DropdownMenuItem(
                    text = { Text(if (server.id == uiState.activeServer?.id) "✓ ${server.name}" else server.name) },
                    onClick = { isServerMenuOpen = false; viewModel.switchServer(server) },
                  )
                }
                DropdownMenuItem(
                  text = { Text("Manage servers") },
                  leadingIcon = { Icon(imageVector = Icons.RoundedFilled.Settings, contentDescription = null) },
                  onClick = { isServerMenuOpen = false; isServerManagerOpen = true },
                )
              }
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
                  onClick = { isMoreMenuOpen = false; isServerManagerOpen = true },
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

    if (isServerManagerOpen) {
      Dialog(onDismissRequest = { if (!uiState.isAuthenticating) isServerManagerOpen = false }) {
        Surface(
          shape = RoundedCornerShape(28.dp),
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 8.dp,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Manage Jellyfin servers", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Switch servers or add another connection", color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              IconButton(onClick = { isServerManagerOpen = false }, enabled = !uiState.isAuthenticating) {
                Icon(imageVector = Icons.RoundedFilled.Close, contentDescription = "Close")
              }
            }
            uiState.servers.forEach { server ->
              FilledTonalButton(
                onClick = { isServerManagerOpen = false; viewModel.switchServer(server) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
              ) {
                Icon(imageVector = Icons.RoundedFilled.Language, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (server.id == uiState.activeServer?.id) "Connected: ${server.name}" else "Use ${server.name}")
              }
            }
            Text("Add another server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
              value = serverUrl,
              onValueChange = { serverUrl = it },
              label = { Text("Server address") },
              placeholder = { Text("jellyfin.example.com:8096") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
              value = serverName,
              onValueChange = { serverName = it },
              label = { Text("Profile name") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(selected = !useToken, onClick = { useToken = false }, label = { Text("Credentials") })
              FilterChip(selected = useToken, onClick = { useToken = true }, label = { Text("API token") })
            }
            OutlinedTextField(
              value = serverUsername,
              onValueChange = { serverUsername = it },
              label = { Text(if (useToken) "User ID" else "Username") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
              value = serverPassword,
              onValueChange = { serverPassword = it },
              label = { Text(if (useToken) "API token" else "Password") },
              visualTransformation = PasswordVisualTransformation(),
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(16.dp),
            )
            Button(
              onClick = {
                if (useToken) viewModel.loginWithToken(serverUrl, serverUsername, serverPassword, serverName) { success -> if (success) isServerManagerOpen = false }
                else viewModel.login(serverUrl, serverUsername, serverPassword, serverName) { success -> if (success) isServerManagerOpen = false }
              },
              enabled = serverUrl.isNotBlank() && serverPassword.isNotBlank() && (useToken || serverUsername.isNotBlank()) && !uiState.isAuthenticating,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(18.dp),
            ) {
              Icon(imageVector = Icons.RoundedFilled.Add, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text("Add server")
            }
            uiState.authError?.let { error -> Text(error, color = MaterialTheme.colorScheme.error) }
          }
        }
      }
    }

    if (isSeerrInfoOpen) {
      AlertDialog(
        onDismissRequest = { isSeerrInfoOpen = false },
        title = { Text("Discover with Seerr") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
              "Connect your Seerr or Overseerr server to browse titles and submit media requests.",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
              value = seerrUrl,
              onValueChange = { seerrUrl = it },
              label = { Text("Seerr server URL") },
              placeholder = { Text("https://seerr.example.com") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(14.dp),
            )
          }
        },
        dismissButton = {
          TextButton(onClick = { isSeerrInfoOpen = false }) { Text("Cancel") }
        },
        confirmButton = {
          TextButton(
            onClick = {
              val enteredUrl = seerrUrl.trim().removeSuffix("/")
              val url = if (enteredUrl.startsWith("http://", ignoreCase = true) || enteredUrl.startsWith("https://", ignoreCase = true)) {
                enteredUrl
              } else {
                "https://$enteredUrl"
              }
              if (url != "https://") {
                seerrUrl = url
                context.getSharedPreferences("jellyfin_profiles", android.content.Context.MODE_PRIVATE)
                  .edit().putString("seerr_url", url).apply()
                runCatching {
                  context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                  )
                }
                isSeerrInfoOpen = false
              }
            },
            enabled = seerrUrl.isNotBlank(),
          ) { Text("Open Seerr") }
        },
      )
    }
  }
}

@Composable
private fun JellyfinDetailPage(
  mediaItem: JellyfinTrack,
  session: JellyfinSession,
  seasons: List<JellyfinTrack>,
  episodes: List<JellyfinTrack>,
  isEpisodesLoading: Boolean,
  similarItems: List<JellyfinTrack>,
  onBack: () -> Unit,
  onPlay: () -> Unit,
  onEpisodePlay: (JellyfinTrack) -> Unit,
  onOpenTrailer: (String) -> Unit,
  onSimilarClick: (JellyfinTrack) -> Unit,
) {
  val imageUrl = mediaItem.artworkUrl ?: "${session.serverUrl}/Items/${mediaItem.id}/Images/Primary?maxWidth=900&quality=90&api_key=${java.net.URLEncoder.encode(session.accessToken, "UTF-8")}"
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .statusBarsPadding(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) { Icon(imageVector = Icons.RoundedFilled.ArrowBack, contentDescription = "Back") }
      Text(mediaItem.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = 24.dp + LocalNavigationBarHeight.current),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        Box(modifier = Modifier.fillMaxWidth().height(280.dp)) {
          RemoteImage(url = imageUrl, contentDescription = mediaItem.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
          Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
          Column(modifier = Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
              Text(
                when {
                  mediaItem.mediaType.equals("Series", ignoreCase = true) -> "TV SHOW"
                  mediaItem.mediaType.equals("Season", ignoreCase = true) -> "SEASON"
                  mediaItem.mediaType.equals("Episode", ignoreCase = true) -> "EPISODE"
                  mediaItem.isVideo -> "MOVIE"
                  else -> "AUDIO"
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
              )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(mediaItem.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
          }
        }
      }
      item {
        Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            mediaItem.communityRating?.let { rating -> DetailBadge("★ ${String.format(java.util.Locale.US, "%.1f", rating)}") }
            mediaItem.productionYear?.let { DetailBadge(it.toString()) }
            mediaItem.officialRating?.let { DetailBadge(it) }
            mediaItem.status?.let { status -> DetailBadge(status) }
            mediaItem.qualityLabel?.let { DetailBadge(it) }
            if (mediaItem.chapterCount > 0) DetailBadge("${mediaItem.chapterCount} chapters")
          }
          if (mediaItem.genres.isNotEmpty()) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              mediaItem.genres.take(6).forEach { genre -> DetailBadge(genre) }
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPlay, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f)) {
              Icon(imageVector = Icons.RoundedFilled.PlayArrow, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                when {
                  mediaItem.mediaType.equals("Series", ignoreCase = true) -> "Play Series"
                  mediaItem.mediaType.equals("Episode", ignoreCase = true) -> "Play Episode"
                  mediaItem.isVideo -> "Play Movie"
                  else -> "Play Track"
                },
              )
            }
            mediaItem.trailerUrl?.let { trailer ->
              FilledTonalButton(onClick = { onOpenTrailer(trailer) }, shape = RoundedCornerShape(16.dp)) {
                Icon(imageVector = Icons.RoundedFilled.PlayCircle, contentDescription = "Trailer")
                Spacer(modifier = Modifier.width(6.dp))
                Text("Trailer")
              }
            }
          }
          val runtime = mediaItem.durationMs.takeIf { it > 0 }?.let { duration ->
            val totalMinutes = duration / 60_000L
            "${totalMinutes / 60}h ${totalMinutes % 60}m"
          }
          if (mediaItem.studio != null || runtime != null) {
            Text("Technical Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              mediaItem.studio?.let { studio ->
                Column { Text("Studio", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(studio, fontWeight = FontWeight.Medium) }
              }
              runtime?.let { length ->
                Column(horizontalAlignment = Alignment.End) { Text("Length", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(length, fontWeight = FontWeight.Medium) }
              }
            }
          }
          mediaItem.originalTitle?.takeIf { it.isNotBlank() && it != mediaItem.title }?.let { originalTitle ->
            Text("Original title", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(originalTitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          if (mediaItem.premiereDate != null || mediaItem.endDate != null) {
            Text("Release information", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
              listOfNotNull(
                mediaItem.premiereDate?.take(10)?.let { "Released $it" },
                mediaItem.endDate?.take(10)?.let { "Ended $it" },
              ).joinToString(" • "),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (mediaItem.overview.isNotBlank()) {
            Text("Overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(mediaItem.overview, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      if (mediaItem.mediaType.equals("Series", ignoreCase = true)) {
        if (seasons.isNotEmpty()) {
          item {
            Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Text("Seasons", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
              Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                seasons.forEach { season ->
                  DetailBadge(season.title)
                }
              }
            }
          }
        }
        item {
          Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Episodes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            when {
              isEpisodesLoading -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
              episodes.isEmpty() -> Text("No episodes available", color = MaterialTheme.colorScheme.onSurfaceVariant)
              else -> episodes.take(50).forEach { episode ->
                JellyfinEpisodeRow(episode = episode, onClick = { onEpisodePlay(episode) })
              }
            }
          }
        }
      }
      if (similarItems.isNotEmpty()) {
        item {
          Text("More Like This", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp))
        }
        item {
          LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(similarItems.take(10), key = { it.id }) { similar ->
              PosterCard(track = similar, session = session, onClick = { onSimilarClick(similar) })
            }
          }
        }
      }
    }
  }
}

@Composable
private fun JellyfinEpisodeRow(
  episode: JellyfinTrack,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .clickable(onClick = onClick)
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .padding(8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(modifier = Modifier.width(112.dp).height(64.dp).clip(RoundedCornerShape(10.dp))) {
      episode.artworkUrl?.let { url ->
        RemoteImage(url = url, contentDescription = episode.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
      } ?: Icon(imageVector = Icons.RoundedFilled.PlayCircle, contentDescription = null, modifier = Modifier.align(Alignment.Center))
    }
    Column(modifier = Modifier.weight(1f)) {
      val number = listOfNotNull(episode.seasonNumber?.let { "S%02d".format(it) }, episode.episodeNumber?.let { "E%02d".format(it) }).joinToString("")
      Text(if (number.isBlank()) episode.title else "$number · ${episode.title}", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
      if (episode.overview.isNotBlank()) Text(episode.overview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Icon(imageVector = Icons.RoundedFilled.PlayArrow, contentDescription = "Play episode", tint = MaterialTheme.colorScheme.primary)
  }
}

@Composable
private fun DetailBadge(text: String) {
  Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge)
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
    contentPadding = PaddingValues(bottom = LocalNavigationBarHeight.current),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    // 1. Hero Banner
    if (uiState.heroItems.isNotEmpty()) {
      item {
        JellyfinHeroBanner(
          items = uiState.heroItems,
          session = uiState.session!!,
          onPlay = { if (it.isPlayable) viewModel.playItem(context, it) else viewModel.openDetail(it) },
          onDetails = { viewModel.openDetail(it) },
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

    // 3. Server-side Watch History (Jellyfin tab only)
    if (uiState.session != null && uiState.watchHistory.isNotEmpty()) {
      item {
        SectionHeader(title = "Watch History", subtitle = "Played on this Jellyfin server")
      }
      item {
        HorizontalSection(
          items = uiState.watchHistory,
          session = uiState.session,
          onItemPlay = { if (it.isPlayable) viewModel.playItem(context, it) else viewModel.openDetail(it) },
          onItemDetails = { viewModel.openDetail(it) },
        )
      }
    }

    // 4. Latest Movies
    if (uiState.latestMovies.isNotEmpty()) {
      item {
        SectionHeader(title = "Latest Movies", subtitle = "Newly added")
      }
      item {
        HorizontalSection(
          items = uiState.latestMovies,
          session = uiState.session!!,
          onItemPlay = { viewModel.playItem(context, it) },
          onItemDetails = { viewModel.openDetail(it) },
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
          onItemDetails = { viewModel.openDetail(it) },
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
    return
  }
  var selectedGenre by remember(uiState.selectedLibraryId) { mutableStateOf<String?>(null) }
  val genres = remember(uiState.currentItems) {
    uiState.currentItems.flatMap { it.genres }.distinct().sorted()
  }
  val categoryItems = if (uiState.searchQuery.isBlank()) {
    uiState.currentItems
  } else {
    when (uiState.searchCategory) {
      JellyfinSearchCategory.ALL -> uiState.currentItems
      JellyfinSearchCategory.MOVIES -> uiState.currentItems.filter { it.mediaType.equals("Movie", ignoreCase = true) }
      JellyfinSearchCategory.SHOWS -> uiState.currentItems.filter { it.mediaType.equals("Series", ignoreCase = true) || it.mediaType.equals("Season", ignoreCase = true) }
      JellyfinSearchCategory.EPISODES -> uiState.currentItems.filter { it.mediaType.equals("Episode", ignoreCase = true) }
    }
  }
  val visibleItems = if (selectedGenre == null) categoryItems else categoryItems.filter { selectedGenre in it.genres }
  Column(modifier = Modifier.fillMaxSize()) {
    if (genres.isNotEmpty()) {
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilterChip(selected = selectedGenre == null, onClick = { selectedGenre = null }, label = { Text("All") })
        genres.forEach { genre ->
          FilterChip(selected = selectedGenre == genre, onClick = { selectedGenre = genre }, label = { Text(genre) })
        }
      }
    }
    if (visibleItems.isEmpty() && !uiState.isLoading) {
      Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(imageVector = Icons.RoundedFilled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(48.dp))
          Spacer(modifier = Modifier.height(12.dp))
          Text(text = if (uiState.searchQuery.isNotBlank()) "No results found" else "No media found", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalNavigationBarHeight.current),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        gridItems(visibleItems, key = { it.id }) { track ->
          PosterCard(track = track, session = uiState.session!!, onClick = { if (track.isVideo) viewModel.openDetail(track) else viewModel.playItem(context, track) }, cardWidth = 0.dp)
        }
        if (uiState.isLoadingMore) {
          item { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
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
  onItemDetails: (JellyfinTrack) -> Unit = onItemPlay,
) {
  if (items.isEmpty()) return
  LazyRow(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(horizontal = 16.dp),
  ) {
    items(items, key = { it.id }) { item ->
      PosterCard(track = item, session = session, onClick = { onItemDetails(item) })
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
  val imageUrl = remember(session.serverUrl, track.id, session.accessToken, track.artworkUrl) {
    track.artworkUrl ?: "${session.serverUrl}/Items/${track.id}/Images/Primary?maxWidth=600&quality=90&api_key=${java.net.URLEncoder.encode(session.accessToken, "UTF-8")}"
  }
  Column(
    modifier = Modifier
      .then(if (cardWidth == 0.dp) Modifier.fillMaxWidth() else Modifier.width(cardWidth))
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(bottom = 2.dp),
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
      track.qualityLabel?.let { quality ->
        Surface(
          modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
        ) {
          Text(
            text = quality,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
      }
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
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(bottom = 4.dp),
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
      collection.artworkUrl?.let { artworkUrl ->
        RemoteImage(
          url = artworkUrl,
          contentDescription = collection.name,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      }
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
