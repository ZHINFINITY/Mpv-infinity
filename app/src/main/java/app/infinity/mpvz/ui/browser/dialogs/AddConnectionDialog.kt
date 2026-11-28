/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.dialogs
import app.infinity.mpvz.ui.components.GlassModalBottomSheet
import app.infinity.mpvz.ui.components.glassOptionSheetContainerColor
import app.infinity.mpvz.ui.components.glassOptionSheetModifier
import app.infinity.mpvz.ui.theme.GlassDropdownMenu
import app.infinity.mpvz.ui.theme.GlassExposedDropdownMenu

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.domain.network.NetworkConnection
import app.infinity.mpvz.domain.network.NetworkProtocol
import app.infinity.mpvz.data.network.ServerUrlUtils
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics

/** Process-scoped draft of the Add Connection form. The secret field is deliberately never kept. */
internal data class AddConnectionDraft(
  val name: String = "",
  val protocol: NetworkProtocol = NetworkProtocol.SMB,
  val host: String = "",
  val port: String = "",
  val username: String = "",
  val path: String = "/",
  val isAnonymous: Boolean = false,
  val useHttps: Boolean = false,
  val sftpHostKeyFingerprint: String = "",
) {
  companion object {
    @Volatile var current: AddConnectionDraft = AddConnectionDraft()
  }
}

@Composable
private fun SavedServerShortcuts(
  connections: List<NetworkConnection>,
  onPick: (NetworkConnection) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      androidx.compose.ui.res.stringResource(app.infinity.mpvz.R.string.ui_network_reuse_saved_server),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      connections.distinctBy { Triple(it.protocol, it.host, it.username) }.take(8).forEach { saved ->
        AssistChip(
          onClick = { onPick(saved) },
          label = {
            Text(
              "${saved.protocol.displayName} · ${saved.host}",
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
          shape = RoundedCornerShape(50),
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConnectionSheet(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  onSave: (NetworkConnection) -> Unit,
  modifier: Modifier = Modifier,
  savedConnections: List<NetworkConnection> = emptyList(),
) {
  if (!isOpen) return

  val draft = AddConnectionDraft.current
  var name by remember { mutableStateOf(draft.name) }
  var protocol by remember { mutableStateOf(draft.protocol) }
  var host by remember { mutableStateOf(draft.host) }
  var port by remember { mutableStateOf(draft.port.ifBlank { draft.protocol.defaultPort.toString() }) }
  var username by remember { mutableStateOf(draft.username) }
  var password by remember { mutableStateOf("") }
  var path by remember { mutableStateOf(draft.path) }
  var isAnonymous by remember { mutableStateOf(draft.isAnonymous) }
  var useHttps by remember { mutableStateOf(draft.useHttps) }
  var sftpHostKeyFingerprint by remember { mutableStateOf(draft.sftpHostKeyFingerprint) }
  var protocolMenuExpanded by remember { mutableStateOf(false) }
  val validSftpFingerprint = sftpHostKeyFingerprint.trim().matches(Regex("""SHA256:[A-Za-z0-9+/]{43}"""))

  // Keep the typed fields (never the secret) so closing the sheet or rotating doesn't wipe them.
  SideEffect {
    AddConnectionDraft.current =
      AddConnectionDraft(
        name = name,
        protocol = protocol,
        host = host,
        port = port,
        username = username,
        path = path,
        isAnonymous = isAnonymous,
        useHttps = useHttps,
        sftpHostKeyFingerprint = sftpHostKeyFingerprint,
      )
  }

  val applyHostInput: (String) -> Unit = { raw ->
    val parsed = ServerUrlUtils.parseServerAddress(raw)
    if (parsed == null) {
      host = raw
    } else {
      val schemeProtocol =
        when (parsed.scheme) {
          "smb" -> NetworkProtocol.SMB
          "ftp" -> NetworkProtocol.FTP
          "sftp", "ssh" -> NetworkProtocol.SFTP
          "http", "https", "dav", "davs", "webdav" -> NetworkProtocol.WEBDAV
          else -> null
        }
      val resolvedProtocol = schemeProtocol ?: protocol
      val secure =
        when (parsed.scheme) {
          "https", "davs" -> true
          "http", "dav" -> false
          else -> useHttps
        }
      protocol = resolvedProtocol
      useHttps = secure
      host = parsed.host
      port =
        parsed.port?.toString()
          ?: if (resolvedProtocol == NetworkProtocol.WEBDAV && secure) "443" else resolvedProtocol.defaultPort.toString()
      parsed.path?.let { path = it }
      parsed.username?.let {
        username = it
        isAnonymous = false
      }
      if (name.isBlank()) name = parsed.host
    }
  }

  val handleDismiss = {
    onDismiss()
  }

  val handleSave = {
    AddConnectionDraft.current = AddConnectionDraft()
    val connection =
      NetworkConnection(
        name = name.ifBlank { "${protocol.displayName} - $host" },
        protocol = protocol,
        host = host.trim(),
        port = port.toIntOrNull() ?: protocol.defaultPort,
        username = if (isAnonymous) "" else username.trim(),
        password = if (isAnonymous) "" else password,
        path = path.ifBlank { "/" },
        isAnonymous = isAnonymous,
        useHttps = useHttps,
        sftpHostKeyFingerprint = sftpHostKeyFingerprint.trim(),
      )
    onSave(connection)
  }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  GlassModalBottomSheet(
    onDismissRequest = handleDismiss,
    sheetState = sheetState,
    containerColor = glassOptionSheetContainerColor(MaterialTheme.colorScheme.surfaceContainerLow),) {
    Column(
      modifier = modifier.fillMaxWidth().imePadding(),
    ) {
      Column(
        modifier =
          Modifier
            .widthIn(max = 640.dp)
            .align(Alignment.CenterHorizontally)
            .padding(horizontal = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
      Text(
        text =
          androidx.compose.ui.res
            .stringResource(app.infinity.mpvz.R.string.ui_add_network_connection),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Medium,
      )
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Name and Protocol in one row
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // Connection Name
          OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.infinity.mpvz.R.string.ui_name),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            modifier = Modifier.weight(0.50f),
            singleLine = true,
          )

          // Protocol Dropdown
          ExposedDropdownMenuBox(
            expanded = protocolMenuExpanded,
            onExpandedChange = { protocolMenuExpanded = it },
            modifier = Modifier.weight(0.50f),
          ) {
            OutlinedTextField(
              value = protocol.displayName,
              onValueChange = { },
              readOnly = true,
              label = {
                Text(
                  androidx.compose.ui.res
                    .stringResource(app.infinity.mpvz.R.string.ui_protocol),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              },
              trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolMenuExpanded) },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            GlassExposedDropdownMenu(
              expanded = protocolMenuExpanded,
              onDismissRequest = { protocolMenuExpanded = false },
            ) {
              NetworkProtocol.entries.forEach { proto ->
                DropdownMenuItem(
                  text = { Text(proto.displayName) },
                  onClick = {
                    protocol = proto
                    port = proto.defaultPort.toString()
                    protocolMenuExpanded = false
                  },
                )
              }
            }
          }
        }

        if (savedConnections.isNotEmpty()) {
          SavedServerShortcuts(
            connections = savedConnections,
            onPick = { saved ->
              protocol = saved.protocol
              host = saved.host
              port = saved.port.toString()
              username = saved.username
              path = saved.path
              isAnonymous = saved.isAnonymous
              useHttps = saved.useHttps
              sftpHostKeyFingerprint = saved.sftpHostKeyFingerprint
            },
          )
        }

        // Host: pasting a full address (https://user@nas:5006/dav) fills every field below.
        OutlinedTextField(
          shape = RoundedCornerShape(16.dp),
          value = host,
          onValueChange = applyHostInput,
          supportingText = {
            Text(
              androidx.compose.ui.res.stringResource(app.infinity.mpvz.R.string.ui_network_paste_url_hint),
              maxLines = 2,
            )
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          label = {
            Text(
              androidx.compose.ui.res
                .stringResource(app.infinity.mpvz.R.string.ui_host_ip_address),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          placeholder = { Text("192.168.1.100", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )

        // Port and Path in one row
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // Port
          OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.infinity.mpvz.R.string.ui_port),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            modifier = Modifier.weight(0.3f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          )

          // Path
          OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.infinity.mpvz.R.string.ui_path),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            modifier = Modifier.weight(0.7f),
            singleLine = true,
            placeholder = { Text("/", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          )
        }

        if (protocol == NetworkProtocol.SFTP) {
          OutlinedTextField(
            value = sftpHostKeyFingerprint,
            onValueChange = { sftpHostKeyFingerprint = it.trim() },
            label = { Text("SFTP host-key SHA-256 fingerprint") },
            placeholder = { Text("SHA256:...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Verify this fingerprint with the server administrator before saving.") },
          )
        }

        // Anonymous and HTTPS checkboxes
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Checkbox(
            checked = isAnonymous,
            onCheckedChange = { isAnonymous = it },
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            androidx.compose.ui.res
              .stringResource(app.infinity.mpvz.R.string.ui_anonymous_guest_access),
          )
        }

        // HTTPS checkbox (only for WebDAV)
        if (protocol == NetworkProtocol.WEBDAV) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Checkbox(
              checked = useHttps,
              onCheckedChange = {
                useHttps = it
                // Auto-update port when toggling HTTPS
                if (it && port == "80") {
                  port = "443"
                } else if (!it && port == "443") {
                  port = "80"
                }
              },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              androidx.compose.ui.res
                .stringResource(app.infinity.mpvz.R.string.ui_use_https_secure_connection),
            )
          }
        }

        // Username and Password in one row
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // Username
          OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.infinity.mpvz.R.string.ui_username),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            modifier =
              Modifier
                .weight(0.50f)
                .semantics { contentType = ContentType.Username },
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            enabled = !isAnonymous,
          )

          // Password
          OutlinedTextField(
            shape = RoundedCornerShape(16.dp),
            value = password,
            onValueChange = { password = it },
            label = {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.infinity.mpvz.R.string.ui_password),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            modifier =
              Modifier
                .weight(0.50f)
                .semantics { contentType = ContentType.Password },
            singleLine = true,
            enabled = !isAnonymous,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          )
        }
      }
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = handleDismiss) {
            Text(
              text =
                androidx.compose.ui.res
                  .stringResource(app.infinity.mpvz.R.string.generic_cancel),
              fontWeight = FontWeight.Medium,
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = handleSave,
            enabled =
              host.isNotBlank() &&
                (isAnonymous || username.isNotBlank()) &&
                (protocol != NetworkProtocol.SFTP || validSftpFingerprint),
          ) {
            Text(
              text =
                androidx.compose.ui.res
                  .stringResource(app.infinity.mpvz.R.string.ui_save),
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
      }
    }
  }
}
