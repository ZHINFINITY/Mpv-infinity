/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.TrackNode

@Composable
fun VideoQualitySheet(
  tracks: List<TrackNode>,
  onSelect: (TrackNode) -> Unit,
  onDismissRequest: () -> Unit,
) {
  PlayerSheet(onDismissRequest) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Hd,
          contentDescription = stringResource(R.string.player_video_quality_button),
          modifier = Modifier.size(24.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
        Column {
          Text(
            text = stringResource(R.string.player_video_quality),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = stringResource(R.string.player_video_quality_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(tracks, key = TrackNode::id) { track ->
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable {
                  onSelect(track)
                  onDismissRequest()
                }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = track.isSelected,
              onClick = {
                onSelect(track)
                onDismissRequest()
              },
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = qualityLabel(track),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Medium,
              )
              qualityDetails(track)?.let { details ->
                Text(
                  text = details,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }
}

private fun qualityLabel(track: TrackNode): String {
  val height = track.demuxH?.takeIf { it > 0 }
  val width = track.demuxW?.takeIf { it > 0 }
  val qualityDimension =
    when {
      width != null && height != null -> minOf(width, height)
      height != null -> height
      width != null -> width
      else -> QUALITY_HEIGHT_REGEX.find(track.effectiveTitle.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull()
    }
  val resolution =
    when {
      qualityDimension != null -> "${qualityDimension}p"
      !track.effectiveTitle.isNullOrBlank() -> track.effectiveTitle.orEmpty()
      !track.codecDesc.isNullOrBlank() -> track.codecDesc.orEmpty()
      else -> "#${track.id}"
    }
  val fps = track.demuxFps?.takeIf { it > 0.0 }?.let { value -> "${value.toInt()} fps" }
  return listOfNotNull(resolution, fps).joinToString(" • ")
}

private fun qualityDetails(track: TrackNode): String? {
  val dimensions =
    if ((track.demuxW ?: 0L) > 0L && (track.demuxH ?: 0L) > 0L) {
      "${track.demuxW}×${track.demuxH}"
    } else {
      null
    }
  val codec = track.codecDesc?.takeIf(String::isNotBlank) ?: track.codec?.takeIf(String::isNotBlank)
  val bitrate =
    track.effectiveBitrate
      ?.takeIf { it > 0L }
      ?.let { bitsPerSecond ->
        if (bitsPerSecond >= 1_000_000L) {
          "%.1f Mbps".format(bitsPerSecond / 1_000_000.0)
        } else {
          "${bitsPerSecond / 1_000L} kbps"
        }
      }
  return listOfNotNull(track.ytdlFormatId?.let { "#$it" }, dimensions, codec, bitrate)
    .joinToString(" • ")
    .takeIf(String::isNotBlank)
}

private val QUALITY_HEIGHT_REGEX = Regex("""(?i)(\d{3,4})p""")
