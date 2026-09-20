/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.browser.audiobooks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.domain.audiobookshelf.AudiobookshelfServer
import app.infinity.mpvz.ui.browser.dialogs.SharedAddServerDialog
import app.infinity.mpvz.ui.browser.dialogs.SharedManageServersDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAudiobookshelfServerDialog(
  isOpen: Boolean,
  isLoading: Boolean,
  errorMessage: String?,
  initialServer: AudiobookshelfServer? = null,
  onDismiss: () -> Unit,
  onConnect: (serverUrl: String, serverName: String, isToken: Boolean, username: String, password: String, token: String) -> Unit,
) {
  if (!isOpen) return

  var serverUrl by remember(initialServer) { mutableStateOf(initialServer?.serverUrl ?: "") }
  var serverName by remember(initialServer) { mutableStateOf(initialServer?.name ?: "") }
  var isTokenAuth by remember(initialServer) { mutableStateOf(initialServer?.token?.isNotBlank() == true && initialServer.username.isBlank()) }
  var username by remember(initialServer) { mutableStateOf(initialServer?.username ?: "") }
  var password by remember(initialServer) { mutableStateOf("") }
  var token by remember(initialServer) { mutableStateOf(initialServer?.token ?: "") }

  val canConnect =
    serverUrl.isNotBlank() &&
      if (isTokenAuth) {
        token.isNotBlank()
      } else {
        username.isNotBlank() && password.isNotBlank()
      }

  SharedAddServerDialog(
    isOpen = isOpen,
    isLoading = isLoading,
    errorMessage = errorMessage,
    title = if (initialServer == null) "Connect to Audiobookshelf" else "Edit Audiobookshelf Server",
    subtitle = "Enter your Audiobookshelf server address",
    serverUrl = serverUrl,
    onServerUrlChange = { serverUrl = it },
    serverUrlPlaceholder = "audiobooks.example.com or 192.168.1.100:13378",
    serverName = serverName,
    onServerNameChange = { serverName = it },
    serverNamePlaceholder = "Audiobookshelf",
    isTokenAuth = isTokenAuth,
    onAuthModeChange = { isTokenAuth = it },
    username = username,
    onUsernameChange = { username = it },
    password = password,
    onPasswordChange = { password = it },
    token = token,
    onTokenChange = { token = it },
    tokenLabel = "API Token",
    tokenPlaceholder = "Paste your API token from ABS user settings",
    tokenSupportingText = "Audiobookshelf Settings > Users > API Token",
    usernameInTokenMode = false,
    canConnect = canConnect,
    onDismiss = onDismiss,
    onSubmit = {
      val trimmedUrl = serverUrl.trim()
      onConnect(
        trimmedUrl,
        serverName.trim().ifBlank { "Audiobookshelf" },
        isTokenAuth,
        username.trim(),
        password,
        token.trim(),
      )
    },
    headerIcon = {
      Icon(
        painter = painterResource(id = R.drawable.ic_audiobookshelf),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp),
      )
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageAudiobookshelfServersDialog(
  isOpen: Boolean,
  servers: List<AudiobookshelfServer>,
  activeServer: AudiobookshelfServer?,
  onDismiss: () -> Unit,
  onSelectServer: (AudiobookshelfServer) -> Unit,
  onDeleteServer: (AudiobookshelfServer) -> Unit,
  onAddServerClick: () -> Unit,
) {
  SharedManageServersDialog(
    isOpen = isOpen,
    title = "Audiobookshelf Servers",
    servers = servers,
    activeServerId = activeServer?.id,
    getServerId = { it.id },
    getServerName = { it.name },
    getServerUrl = { it.serverUrl },
    getServerSubtitle = { server ->
      if (server.username.isNotBlank()) server.username else null
    },
    onDismiss = onDismiss,
    onSelectServer = onSelectServer,
    onDeleteServer = onDeleteServer,
    onAddServerClick = onAddServerClick,
    avatarContent = { _, isSelected ->
      Surface(
        shape = CircleShape,
        color =
          if (isSelected) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
          },
        modifier = Modifier.size(40.dp),
      ) {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            painter = painterResource(id = R.drawable.ic_audiobookshelf),
            contentDescription = null,
            tint =
              if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            modifier = Modifier.size(20.dp),
          )
        }
      }
    },
  )
}
