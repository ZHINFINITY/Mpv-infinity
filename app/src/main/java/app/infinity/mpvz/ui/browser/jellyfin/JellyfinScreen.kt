/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.jellyfin

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import app.infinity.mpvz.ui.icons.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.presentation.components.RemoteImage
import app.infinity.mpvz.ui.player.PlaybackItem
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.player.PreparedPlaybackLaunchStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import okhttp3.OkHttpClient

private enum class JellyfinLoginMode { PASSWORD, API_TOKEN, QUICK_CONNECT }

@Composable
fun JellyfinScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val client = koinInject<OkHttpClient>()
  val prefs = remember { context.getSharedPreferences("jellyfin_debug", Context.MODE_PRIVATE) }
  val profileStore = remember { JellyfinProfileStore(context) }
  val scope = rememberCoroutineScope()
  var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "") ?: "") }
  var username by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
  var profileName by remember { mutableStateOf("") }
  var profiles by remember { mutableStateOf(profileStore.getAll()) }
  var password by remember { mutableStateOf("") }
  var apiToken by remember { mutableStateOf("") }
  var loginMode by remember { mutableStateOf(JellyfinLoginMode.PASSWORD) }
  var session by remember { mutableStateOf<JellyfinSession?>(null) }
  var libraries by remember { mutableStateOf<List<JellyfinCollection>>(emptyList()) }
  var selectedLibraryId by remember { mutableStateOf<String?>(null) }
  var tracks by remember { mutableStateOf<List<JellyfinTrack>>(emptyList()) }
  var isLoading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var quickConnect by remember { mutableStateOf<JellyfinQuickConnectState?>(null) }

  suspend fun loadServer(sessionToLoad: JellyfinSession, libraryId: String? = selectedLibraryId) {
    isLoading = true
    error = null
    val jellyfin = JellyfinClient(client, context)
    if (libraries.isEmpty()) {
      jellyfin.loadLibraries(sessionToLoad).fold(
        onSuccess = { libraries = it },
        onFailure = { error = it.message ?: "Unable to load Jellyfin libraries" },
      )
    }
    if (error == null) {
      jellyfin.loadMedia(sessionToLoad, parentId = libraryId).fold(
        onSuccess = { tracks = it },
        onFailure = { error = it.message ?: "Unable to load Jellyfin media" },
      )
    }
    isLoading = false
  }

  LaunchedEffect(Unit) {
    val savedUrl = prefs.getString("server_url", "").orEmpty()
    val savedToken = prefs.getString("access_token", "").orEmpty()
    val savedUserId = prefs.getString("user_id", "").orEmpty()
    if (savedUrl.isNotBlank() && savedToken.isNotBlank() && savedUserId.isNotBlank()) {
      val savedSession = JellyfinSession(savedUrl, savedUserId, savedToken)
      session = savedSession
      loadServer(savedSession, null)
    }
  }

  LaunchedEffect(quickConnect) {
    val pending = quickConnect ?: return@LaunchedEffect
    val jellyfin = JellyfinClient(client, context)
    while (true) {
      delay(5_000)
      val authorized = jellyfin.isQuickConnectAuthenticated(pending).getOrDefault(false)
      if (authorized) {
        jellyfin.authenticateWithQuickConnect(pending).onSuccess { authenticated ->
          prefs.edit()
            .putString("server_url", authenticated.serverUrl)
            .putString("access_token", authenticated.accessToken)
            .putString("user_id", authenticated.userId)
            .apply()
          quickConnect = null
          session = authenticated
          libraries = emptyList()
          selectedLibraryId = null
          loadServer(authenticated, null)
        }.onFailure { error = it.message ?: "Quick Connect authentication failed" }
        break
      }
    }
  }

  fun login() {
    scope.launch {
      isLoading = true
      error = null
      if (loginMode == JellyfinLoginMode.QUICK_CONNECT) {
        JellyfinClient(client, context).initiateQuickConnect(serverUrl).fold(
          onSuccess = { quickConnect = it },
          onFailure = { error = it.message ?: "Quick Connect is unavailable on this server" },
        )
        isLoading = false
        return@launch
      }
      val authentication: Result<JellyfinSession> = when (loginMode) {
        JellyfinLoginMode.PASSWORD -> JellyfinClient(client, context).authenticate(serverUrl, username, password)
        JellyfinLoginMode.API_TOKEN -> JellyfinClient(client, context).authenticateWithToken(serverUrl, apiToken)
        JellyfinLoginMode.QUICK_CONNECT -> kotlin.error("Quick Connect handled before password authentication")
      }
      authentication.fold(
        onSuccess = { authenticated ->
          profileStore.upsert(
            JellyfinProfile(
              name = profileName.trim().ifBlank { username.trim().ifBlank { "Jellyfin server" } },
              serverUrl = authenticated.serverUrl,
              username = username,
              userId = authenticated.userId,
              accessToken = authenticated.accessToken,
            ),
          )
          profiles = profileStore.getAll()
          prefs.edit()
            .putString("server_url", authenticated.serverUrl)
            .putString("username", username)
            .putString("access_token", authenticated.accessToken)
            .putString("user_id", authenticated.userId)
            .apply()
          session = authenticated
          libraries = emptyList()
          selectedLibraryId = null
          password = ""
          loadServer(authenticated, null)
        },
        onFailure = { error = it.message ?: "Jellyfin login failed" },
      )
      isLoading = false
    }
  }

  if (session == null) {
          JellyfinLoginForm(
      profiles = profiles,
      profileName = profileName,
      serverUrl = serverUrl,
      username = username,
      password = password,
      apiToken = apiToken,
      loginMode = loginMode,

      isLoading = isLoading,
      error = error,
      onProfileNameChange = { profileName = it },
      onProfileSelected = { profile ->
        profileName = profile.name
        serverUrl = profile.serverUrl
        username = profile.username
        session = JellyfinSession(profile.serverUrl, profile.userId, profile.accessToken)
        libraries = emptyList()
        selectedLibraryId = null
        scope.launch { loadServer(session!!, null) }
      },
      onServerUrlChange = { serverUrl = it },
      onUsernameChange = { username = it },
      onPasswordChange = { password = it },
      onApiTokenChange = { apiToken = it },
      quickConnect = quickConnect,
      onLoginModeChange = { loginMode = it },
      onLogin = ::login,
      modifier = modifier,
    )
  } else {
    JellyfinConnectedContent(
      serverUrl = session!!.serverUrl,
      libraries = libraries,
      selectedLibraryId = selectedLibraryId,
      tracks = tracks,
      isLoading = isLoading,
      error = error,
      onLibrarySelected = { libraryId ->
        selectedLibraryId = libraryId
        scope.launch { loadServer(session!!, libraryId) }
      },
      onRefresh = { scope.launch { loadServer(session!!, selectedLibraryId) } },
      onTrackClick = { index -> playJellyfinTracks(context, tracks, index) },
      onLogout = {
        prefs.edit().remove("server_url").remove("username").remove("access_token").remove("user_id").apply()
        session = null
        libraries = emptyList()
        tracks = emptyList()
        selectedLibraryId = null
        error = null
      },
      modifier = modifier,
    )
  }
}

@Composable
private fun JellyfinLoginForm(
  profiles: List<JellyfinProfile>,
  profileName: String,
  serverUrl: String,
  username: String,
  password: String,
  apiToken: String,
  loginMode: JellyfinLoginMode,
  quickConnect: JellyfinQuickConnectState?,
  isLoading: Boolean,
  error: String?,
  onProfileNameChange: (String) -> Unit,
  onProfileSelected: (JellyfinProfile) -> Unit,
  onServerUrlChange: (String) -> Unit,
  onUsernameChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onApiTokenChange: (String) -> Unit,
  onLoginModeChange: (JellyfinLoginMode) -> Unit,
  onLogin: () -> Unit,
  modifier: Modifier,
) {
  Column(
    modifier = modifier.fillMaxSize().statusBarsPadding().padding(20.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Jellyfin", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text("Connect to your personal Jellyfin server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (profiles.isNotEmpty()) {
      Spacer(Modifier.height(14.dp))
      Text("Saved servers", style = MaterialTheme.typography.labelLarge)
      Row(
        modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        profiles.forEach { profile ->
          FilterChip(
            selected = false,
            onClick = { onProfileSelected(profile) },
            label = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          )
        }
      }
    }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
      value = profileName,
      onValueChange = onProfileNameChange,
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Profile name") },
      singleLine = true,
    )
    Spacer(Modifier.height(10.dp))
    Row(
      modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(
        selected = loginMode == JellyfinLoginMode.PASSWORD,
        onClick = { onLoginModeChange(JellyfinLoginMode.PASSWORD) },
        label = { Text("Password") },
      )
      FilterChip(
        selected = loginMode == JellyfinLoginMode.API_TOKEN,
        onClick = { onLoginModeChange(JellyfinLoginMode.API_TOKEN) },
        label = { Text("API token") },
      )
      FilterChip(
        selected = loginMode == JellyfinLoginMode.QUICK_CONNECT,
        onClick = { onLoginModeChange(JellyfinLoginMode.QUICK_CONNECT) },
          label = { Text("Quick Connect") },
      )
    }
    if (quickConnect != null) {
      Spacer(Modifier.height(12.dp))
      Text("Enter this code in Jellyfin Quick Connect", style = MaterialTheme.typography.labelLarge)
      Text(quickConnect.code, style = MaterialTheme.typography.displaySmall)
      Text("Waiting for approval…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
      value = serverUrl,
      onValueChange = onServerUrlChange,
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Server address") },
      placeholder = { Text("jellyfin.example.com") },
      supportingText = { Text("https:// is optional") },
      singleLine = true,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
      value = username,
      onValueChange = onUsernameChange,
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Username") },
      singleLine = true,
    )
    if (loginMode == JellyfinLoginMode.PASSWORD) {
      Spacer(Modifier.height(10.dp))
      OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
      )
    } else if (loginMode == JellyfinLoginMode.API_TOKEN) {
      Spacer(Modifier.height(10.dp))
      OutlinedTextField(
        value = apiToken,
        onValueChange = onApiTokenChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Jellyfin API token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
      )
    } else {
      Spacer(Modifier.height(10.dp))
      Text(
        "Quick Connect will display a temporary code that you approve from an already signed-in Jellyfin client.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (!error.isNullOrBlank()) {
      Spacer(Modifier.height(10.dp))
      Text(error, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(16.dp))
    Button(
      onClick = onLogin,
      enabled = !isLoading && serverUrl.isNotBlank() && when (loginMode) {
        JellyfinLoginMode.PASSWORD -> username.isNotBlank() && password.isNotBlank()
        JellyfinLoginMode.API_TOKEN -> apiToken.isNotBlank()
        JellyfinLoginMode.QUICK_CONNECT -> quickConnect == null
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text("Connect")
    }
  }
}

@Composable
private fun JellyfinConnectedContent(
  serverUrl: String,
  libraries: List<JellyfinCollection>,
  selectedLibraryId: String?,
  tracks: List<JellyfinTrack>,
  isLoading: Boolean,
  error: String?,
  onLibrarySelected: (String?) -> Unit,
  onRefresh: () -> Unit,
  onTrackClick: (Int) -> Unit,
  onLogout: () -> Unit,
  modifier: Modifier,
) {
  val selectedLibrary = libraries.firstOrNull { it.id == selectedLibraryId }
  Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(selectedLibrary?.name ?: "Jellyfin", style = MaterialTheme.typography.titleLarge)
        Text(
          serverUrl.removePrefix("https://").removePrefix("http://"),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onRefresh, enabled = !isLoading) {
          Icon(Icons.RoundedFilled.Refresh, contentDescription = "Refresh Jellyfin library")
        }
        IconButton(onClick = onLogout) {
          Icon(Icons.RoundedFilled.LinkOff, contentDescription = "Disconnect Jellyfin server")
        }
      }
    }

    if (libraries.isNotEmpty()) {
      LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item {
          FilterChip(
            selected = selectedLibraryId == null,
            onClick = { onLibrarySelected(null) },
            label = { Text("Home") },
          )
        }
        items(libraries, key = { it.id }) { library ->
          FilterChip(
            selected = selectedLibraryId == library.id,
            onClick = { onLibrarySelected(library.id) },
            label = { Text(library.name) },
          )
        }
      }
    }

    Spacer(Modifier.height(8.dp))
    if (isLoading) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (!error.isNullOrBlank()) {
      Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Text(error, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRefresh) { Text("Try again") }
      }
    } else if (tracks.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("No playable media found in this Jellyfin view.")
      }
    } else {
      val rails = tracks
        .filter { it.isPlayable }
        .groupBy { track ->
          when {
            track.mediaType.equals("Movie", ignoreCase = true) -> "Latest Movies"
            track.mediaType.equals("Episode", ignoreCase = true) -> "TV Shows"
            else -> "Music"
          }
        }
      LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        rails.forEach { (title, railTracks) ->
          item(key = title) {
            JellyfinMediaRail(
              title = title,
              tracks = railTracks,
              allTracks = tracks,
              onTrackClick = onTrackClick,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun JellyfinMediaRail(
  title: String,
  tracks: List<JellyfinTrack>,
  allTracks: List<JellyfinTrack>,
  onTrackClick: (Int) -> Unit,
) {
  Column {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      items(tracks, key = { it.id }) { track ->
        val index = allTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        JellyfinPosterCard(track = track, onClick = { onTrackClick(index) })
      }
    }
  }
}

@Composable
private fun JellyfinPosterCard(track: JellyfinTrack, onClick: () -> Unit) {
  Column(
    modifier = Modifier.width(if (track.isVideo) 142.dp else 156.dp)
      .clip(RoundedCornerShape(14.dp))
      .clickable(onClick = onClick),
  ) {
    Box(
      modifier = Modifier.fillMaxWidth()
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
      Text(
        text = if (track.isVideo) track.mediaType else "AUDIO",
        modifier = Modifier.align(Alignment.BottomStart)
          .padding(8.dp)
          .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
          .padding(horizontal = 7.dp, vertical = 3.dp),
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Spacer(Modifier.height(7.dp))
    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
    Text(
      if (track.isVideo) track.album else track.artist,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun JellyfinMediaCard(track: JellyfinTrack, onClick: () -> Unit) {
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
      modifier = Modifier.size(if (track.isVideo) 84.dp else 64.dp)
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
      Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
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

private fun playJellyfinTracks(context: Context, tracks: List<JellyfinTrack>, index: Int) {
  val playableTracks = tracks.filter { it.isPlayable }
  if (playableTracks.isEmpty()) return
  val safeIndex = index.coerceIn(playableTracks.indices)
  val queue = playableTracks.map { track ->
    PlaybackItem.fromUri(
      uri = track.streamUrl!!,
      title = track.title,
      artist = track.artist,
      mimeType = if (track.isVideo) "video/*" else "audio/*",
      artworkUri = track.artworkUrl,
    )
  }
  val token = PreparedPlaybackLaunchStore.stage(queue, safeIndex, isExplicitQueue = true)
  val first = playableTracks[safeIndex]
  val firstUri = first.uri ?: return
  val isAudio = !first.isVideo
  val intent = Intent(Intent.ACTION_VIEW, firstUri).apply {
    setClass(context, PlayerActivity::class.java)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    putExtra("internal_launch", true)
    putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, true)
    putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_TOKEN, token)
    putExtra("playlist_index", safeIndex)
    putExtra("launch_source", "jellyfin")
    putExtra("media_library_audio", isAudio)
    putExtra("is_audio", isAudio)
    putExtra("title", first.title)
  }
  context.startActivity(intent)
}
