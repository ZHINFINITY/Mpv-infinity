/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.ytdlp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.presentation.components.PlayerGlassOverlay

/**
 * Shown instead of silently installing/updating yt-dlp in the background before playback
 * (which used to leave the player buffering with no explanation on a link's first play).
 *
 * Mirrors the layout of [app.infinity.mpvz.ui.securefolder.SecureConfirmDialog] /
 * `ConfirmDialog`, but with a third, visually separated action ([onConfigure]) pinned to the
 * start of the row so it doesn't get mistaken for a variant of confirm/cancel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YtdlpInstallPromptDialog(
  isOpen: Boolean,
  onInstall: () -> Unit,
  onConfigure: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!isOpen) return

  BasicAlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
  ) {
    PlayerGlassOverlay(
      cornerRadius = 28.dp,
    ) {
      Column(
        modifier = Modifier.padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Text(
          stringResource(R.string.ui_yt_dlp_not_installed),
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          color = AlertDialogDefaults.titleContentColor,
        )
        Text(
          stringResource(R.string.ytdlp_install_prompt_subtitle),
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          color = AlertDialogDefaults.textContentColor,
        )
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          TextButton(
            onClick = onConfigure,
            shape = MaterialTheme.shapes.extraLarge,
          ) {
            Text(
              stringResource(R.string.generic_configure),
              fontWeight = FontWeight.Medium,
            )
          }
          Row {
            TextButton(
              onClick = onDismiss,
              shape = MaterialTheme.shapes.extraLarge,
            ) {
              Text(
                stringResource(R.string.generic_cancel),
                fontWeight = FontWeight.Medium,
              )
            }
            TextButton(
              onClick = onInstall,
              shape = MaterialTheme.shapes.extraLarge,
            ) {
              Text(
                stringResource(R.string.generic_install),
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }
    }
  }
}
