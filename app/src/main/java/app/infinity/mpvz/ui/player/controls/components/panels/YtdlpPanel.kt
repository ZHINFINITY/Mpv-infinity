/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.panels

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.panelCardsColors
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlCodecPreference
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpInstallationStatus
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpOptionSettings
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpOptionsBuilder
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpReleaseChannel
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.currentMpvConfigOverrideOptions
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YtdlpPanel(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var isRunning by remember { mutableStateOf(false) }

  val ytdlPreferences = koinInject<YtdlPreferences>()
  val configOwnedOptions = currentMpvConfigOverrideOptions()
  val formatOptionsEnabled = "ytdl-format" !in configOwnedOptions
  val rawOptionsEnabled = "ytdl-raw-options" !in configOwnedOptions
  val ytdlQuality by ytdlPreferences.ytdlQuality.collectAsState()
  val preferH264 by ytdlPreferences.preferH264.collectAsState()
  val codecPreference by ytdlPreferences.codecPreference.collectAsState()
  val writeSubs by ytdlPreferences.writeSubs.collectAsState()
  val writeAutoSubs by ytdlPreferences.writeAutoSubs.collectAsState()

  val installationInfo by YtdlpManager.installationInfo.collectAsState()
  val hasYtdlp = installationInfo?.isInstalled == true

  LaunchedEffect(Unit) {
    YtdlpManager.refreshInstallationInfo(context)
  }

  fun runOperation(operation: suspend () -> Unit) {
    scope.launch {
      isRunning = true
      try {
        operation()
      } finally {
        isRunning = false
      }
    }
  }

  val stableActionLabel =
    when {
      !hasYtdlp -> stringResource(app.gyrolet.mpvrx.R.string.ui_install_stable)
      installationInfo?.channel == YtdlpReleaseChannel.STABLE ->
        stringResource(app.gyrolet.mpvrx.R.string.ui_update_stable)
      else -> stringResource(app.gyrolet.mpvrx.R.string.ui_switch_to_stable)
    }
  val nightlyActionLabel =
    if (installationInfo?.channel == YtdlpReleaseChannel.NIGHTLY) {
      stringResource(app.gyrolet.mpvrx.R.string.ui_update_nightly)
    } else {
      stringResource(app.gyrolet.mpvrx.R.string.ui_switch_to_nightly)
    }

  val automaticLabel = stringResource(app.gyrolet.mpvrx.R.string.ui_automatic)
  val qualityLabel = if (ytdlQuality == -1) automaticLabel else "${ytdlQuality}p"

  DraggablePanel(
    modifier = modifier,
    header = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium)
            .padding(top = MaterialTheme.spacing.small, bottom = MaterialTheme.spacing.extraSmall),
      ) {
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_yt_dlp_manager),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onDismissRequest) {
          Icon(Icons.RoundedFilled.Close, null, modifier = Modifier.size(24.dp))
        }
      }
    },
  ) {
    Column(
      modifier =
        Modifier
          .padding(MaterialTheme.spacing.medium)
          .animateContentSize(),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      // Settings Panel

      YtdlpInstallationStatus(
        info = installationInfo,
        isRunning = isRunning,
      )

      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
      ) {
        Text(
          text = stringResource(app.gyrolet.mpvrx.R.string.ui_release_channel),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
          onClick = {
            runOperation {
              if (installationInfo?.channel == YtdlpReleaseChannel.STABLE) {
                YtdlpManager.runUpdate(context) {}
              } else {
                YtdlpManager.runInstall(context) {}
              }
            }
          },
          enabled = !isRunning,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.RoundedFilled.CloudDownload, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text(stableActionLabel)
        }

        OutlinedButton(
          onClick = {
            runOperation { YtdlpManager.runUpdateToNightly(context) {} }
          },
          enabled = !isRunning && hasYtdlp,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth(),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
          Icon(Icons.RoundedFilled.Update, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text(nightlyActionLabel)
        }
      }

      // Quick Quality Chip Panel
      val cardsColors = panelCardsColors()
      Surface(
        shape = MaterialTheme.shapes.large,
        color = cardsColors.containerColor,
        tonalElevation = 0.dp,
        border =
          BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
          ),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
          Text(
            text =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_quick_quality_selection),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = MaterialTheme.spacing.extraSmall),
          )
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            modifier = Modifier.fillMaxWidth(),
          ) {
            val quickQualities = listOf(-1 to automaticLabel, 1080 to "1080p", 720 to "720p", 480 to "480p")
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.weight(1f),
            ) {
              quickQualities.forEach { (level, label) ->
                FilterChip(
                  selected = ytdlQuality == level,
                  enabled = formatOptionsEnabled,
                  onClick = {
                    ytdlPreferences.ytdlQuality.set(level)
                    updateFormatString(ytdlPreferences)
                  },
                  label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                  leadingIcon =
                    if (ytdlQuality == level) {
                      { Icon(Icons.RoundedFilled.Check, null, modifier = Modifier.size(14.dp)) }
                    } else {
                      null
                    },
                )
              }
            }

            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.secondaryContainer,
              modifier = Modifier.padding(start = 4.dp),
            ) {
              Text(
                text = qualityLabel,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
              )
            }
          }
        }
      }

      Surface(
        shape = MaterialTheme.shapes.large,
        color = cardsColors.containerColor,
        tonalElevation = 0.dp,
        border =
          BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
          ),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
          Text(
            text =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_codec_preset),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = MaterialTheme.spacing.extraSmall),
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            val selectedCodec =
              if (codecPreference == YtdlCodecPreference.AUTO && preferH264) {
                YtdlCodecPreference.H264
              } else {
                codecPreference
              }
            YtdlCodecPreference.commonPlaybackChoices.forEach { codec ->
              FilterChip(
                selected = selectedCodec == codec,
                enabled = formatOptionsEnabled,
                onClick = {
                  ytdlPreferences.codecPreference.set(codec)
                  ytdlPreferences.preferH264.set(codec == YtdlCodecPreference.H264)
                  updateFormatString(ytdlPreferences)
                },
                label = { Text(codec.title, style = MaterialTheme.typography.labelSmall) },
                leadingIcon =
                  if (codecPreference == codec) {
                    { Icon(Icons.RoundedFilled.Check, null, modifier = Modifier.size(14.dp)) }
                  } else {
                    null
                  },
              )
            }
          }
        }
      }

      // Subtitles Switches Card
      Surface(
        shape = MaterialTheme.shapes.large,
        color = cardsColors.containerColor,
        tonalElevation = 0.dp,
        border =
          BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
          ),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_quick_subtitle_config),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = MaterialTheme.spacing.extraSmall, bottom = 2.dp),
          )

          // Subtitle download toggle
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_download_subtitles),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
              )
              Text(
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_fetch_subs_from_stream_sources),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconSwitch(
              checked = writeSubs,
              enabled = rawOptionsEnabled,
              onCheckedChange = { ytdlPreferences.writeSubs.set(it) },
            )
          }

          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

          // Auto subtitles toggle
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_auto_generated_captions),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
              )
              Text(
                androidx.compose.ui.res.stringResource(
                  app.gyrolet.mpvrx.R.string.ui_include_auto_captions_transcripts,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconSwitch(
              checked = writeAutoSubs,
              enabled = rawOptionsEnabled,
              onCheckedChange = { ytdlPreferences.writeAutoSubs.set(it) },
            )
          }
        }
      }

    }
  }
}

private fun updateFormatString(prefs: YtdlPreferences) {
  prefs.ytdlFormat.set(
    YtdlpOptionsBuilder.buildFormat(
      YtdlpOptionSettings(
        codecPreference = prefs.codecPreference.get(),
        legacyPreferH264 = prefs.preferH264.get(),
        maxHeight = prefs.ytdlQuality.get(),
        maxFps = prefs.maxFps.get(),
        hdrPreference = prefs.hdrPreference.get(),
        containerPreference = prefs.containerPreference.get(),
        audioPreference = prefs.audioPreference.get(),
      ),
    ),
  )
}
