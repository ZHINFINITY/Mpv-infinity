/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.ytdlp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gyrolet.mpvrx.R

/**
 * Progress UI shown while [YtdlpManager.runInstall] runs, triggered from
 * [YtdlpInstallPromptDialog]'s Install action. There's no byte-level progress from the
 * installer, so this shows an indeterminate bar plus the latest log line, same idea as
 * [app.gyrolet.mpvrx.ui.securefolder.SecureFolderProgressDialog] but without a percentage.
 *
 * Not dismissable by back-press or outside-tap — [onCancel] is the only way out while busy,
 * same as the secure-folder progress dialog.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YtdlpInstallProgressDialog(
  isOpen: Boolean,
  lastLogLine: String,
  error: String?,
  onCancel: () -> Unit,
) {
  if (!isOpen) return

  Dialog(
    onDismissRequest = { /* no-op: cancel button is the only exit while busy */ },
    properties =
      DialogProperties(
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
      ),
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = AlertDialogDefaults.containerColor,
      tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
      Column(
        modifier = Modifier.padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          stringResource(R.string.ytdlp_installing),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = AlertDialogDefaults.titleContentColor,
        )

        if (error == null) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (lastLogLine.isNotBlank()) {
          Text(
            lastLogLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        if (error != null) {
          Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }

        TextButton(
          onClick = onCancel,
          shape = MaterialTheme.shapes.extraLarge,
        ) {
          Text(
            stringResource(R.string.generic_cancel),
            fontWeight = FontWeight.Medium,
          )
        }
      }
    }
  }
}
