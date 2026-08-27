/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.jellyfin

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.presentation.components.RemoteImage
import app.infinity.mpvz.ui.player.PlaybackItem
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.player.PreparedPlaybackLaunchStore
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import okhttp3.OkHttpClient

@Composable
fun JellyfinScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val client = koinInject<OkHttpClient>()
  val prefs = remember { context.getSharedPreferences("jellyfin_debug", Context.MODE_PRIVATE) }
  val scope = rememberCoroutineScope()
  var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "") ?: "") }
  var username by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
  var password by remember { mutableStateOf("") }
  var session by remember { mutableStateOf<JellyfinSession?>(null) }
  var tracks by remember { mutableStateOf<List<JellyfinTrack>>(emptyList()) }
  var isLoading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    val savedUrl = prefs.getString("server_url", "").orEmpty()
    val savedToken = prefs.getString("access_token", "").orEmpty()
    val savedUserId = prefs.getString("user_id", "").orEmpty()
    if (savedUrl.isNotBlank() && savedToken.isNotBlank() && savedUserId.isNotBlank()) {
      val savedSession = JellyfinSession(savedUrl, savedUserId, savedToken)
      session = savedSession
      isLoading = true
      JellyfinClient(client, context).loadAudio(savedSession).fold(
        onSuccess = { tracks = it; error = null },
        onFailure = { error = it.message ?: "Unable to load Jellyfin audio" },
      )
      isLoading = false
    }
  }

  fun login() {
    scope.launch {
      isLoading = true
      error = null
      JellyfinClient(client, context).authenticate(serverUrl, username, password).fold(
        onSuccess = { authenticated ->
          prefs.edit()
            .putString("server_url", authenticated.serverUrl)
            .putString("username", username)
            .putString("access_token", authenticated.accessToken)
            .putString("user_id", authenticated.userId)
            .apply()
          session = authenticated
          password = ""
          JellyfinClient(client, context).loadAudio(authenticated).fold(
            onSuccess = { tracks = it },
            onFailure = { error = it.message ?: "Unable to load Jellyfin audio" },
          )
        },
        onFailure = { error = it.message ?: "Jellyfin login failed" },
      )
      isLoading = false
    }
  }

  if (session == null) {
    JellyfinLoginForm(
      serverUrl = serverUrl,
      username = username,
      password = password,
      isLoading = isLoading,
      error = error,
      onServerUrlChange = { serverUrl = it },
      onUsernameChange = { username = it },
      onPasswordChange = { password = it },
      onLogin = ::login,
      modifier = modifier,
    )
  } else {
    JellyfinLibrary(
      tracks = tracks,
      isLoading = isLoading,
      error = error,
      onTrackClick = { index -> playJellyfinTracks(context, tracks, index) },
      onLogout = {
        prefs.edit().remove("access_token").remove("user_id").apply()
        session = null
        tracks = emptyList()
      },
      modifier = modifier,
    )
  }
}

@Composable
private fun JellyfinLoginForm(
  serverUrl: String,
  username: String,
  password: String,
  isLoading: Boolean,
  error: String?,
  onServerUrlChange: (String) -> Unit,
  onUsernameChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onLogin: () -> Unit,
  modifier: Modifier,
) {
  Column(
    modifier = modifier.fillMaxSize().padding(20.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Jellyfin", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text("Connect to your personal Jellyfin server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(serverUrl, onServerUrlChange, Modifier.fillMaxWidth(), label = { Text("Server URL") }, singleLine = true)
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(username, onUsernameChange, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
      value = password,
      onValueChange = onPasswordChange,
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Password") },
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
    )
    if (!error.isNullOrBlank()) {
      Spacer(Modifier.height(10.dp))
      Text(error, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = onLogin, enabled = !isLoading && serverUrl.isNotBlank() && username.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
      if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text("Connect")
    }
  }
}

@Composable
private fun JellyfinLibrary(
  tracks: List<JellyfinTrack>,
  isLoading: Boolean,
  error: String?,
  onTrackClick: (Int) -> Unit,
  onLogout: () -> Unit,
  modifier: Modifier,
) {
  Column(modifier = modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Jellyfin music", style = MaterialTheme.typography.titleLarge)
      Text("Disconnect", modifier = Modifier.clickable(onClick = onLogout), color = MaterialTheme.colorScheme.primary)
    }
    if (isLoading) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (!error.isNullOrBlank()) {
      Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) { Text(error, color = MaterialTheme.colorScheme.error) }
    } else if (tracks.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) { Text("No audio items found on this Jellyfin server.") }
    } else {
      LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable { onTrackClick(index) }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
              if (track.artworkUrl != null) {
                RemoteImage(
                  url = track.artworkUrl,
                  contentDescription = track.title,
                  modifier = Modifier.fillMaxSize(),
                  contentScale = ContentScale.Crop,
                )
              }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(track.title, maxLines = 1)
              Text("${track.artist} · ${track.album}", maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
    }
  }
}

private fun playJellyfinTracks(context: Context, tracks: List<JellyfinTrack>, index: Int) {
  if (tracks.isEmpty()) return
  val queue = tracks.map { track ->
    PlaybackItem.fromUri(
      uri = track.streamUrl,
      title = track.title,
      artist = track.artist,
      mimeType = "audio/*",
      artworkUri = track.artworkUrl,
    )
  }
  val safeIndex = index.coerceIn(queue.indices)
  val token = PreparedPlaybackLaunchStore.stage(queue, safeIndex, isExplicitQueue = true)
  val first = tracks[safeIndex]
  val intent = Intent(Intent.ACTION_VIEW, first.uri).apply {
    setClass(context, PlayerActivity::class.java)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    putExtra("internal_launch", true)
    putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, true)
    putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_TOKEN, token)
    putExtra("playlist_index", safeIndex)
    putExtra("launch_source", "jellyfin_music")
    putExtra("media_library_audio", true)
    putExtra("is_audio", true)
    putExtra("title", first.title)
  }
  context.startActivity(intent)
}
