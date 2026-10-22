/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.sheets

import android.net.Uri
import app.infinity.mpvz.ui.player.TrackMetadataFormatter
import app.infinity.mpvz.ui.player.TrackMetadataFlag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.TrackNode
import app.infinity.mpvz.ui.theme.spacing

@Composable
fun AddTrackRow(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  actions: @Composable RowScope.() -> Unit = {},
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .height(56.dp)
        .padding(horizontal = MaterialTheme.spacing.medium),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    Icon(
      Icons.RoundedFilled.Add,
      contentDescription = null,
      modifier = Modifier.size(24.dp),
    )
    Text(
      text = title,
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier.weight(1f),
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      actions()
    }
  }
}

@Composable
fun getTrackTitle(track: TrackNode): String {
  val title = track.effectiveTitle
  val lang = track.effectiveLang
  val hasTitle = !title.isNullOrBlank()
  val hasLang = !lang.isNullOrBlank()

  if (track.isSubtitle && track.external == true && !hasTitle && !hasLang && track.externalFilename != null) {
    val decoded = Uri.decode(track.externalFilename)
    val fileName = decoded.substringAfterLast("/")
    return stringResource(R.string.player_sheets_track_title_wo_lang, track.id, fileName)
  }

  return when {
    hasTitle && hasLang ->
      stringResource(
        R.string.player_sheets_track_title_w_lang,
        track.id,
        title,
        lang,
      )
    hasTitle -> stringResource(R.string.player_sheets_track_title_wo_lang, track.id, title)
    hasLang -> stringResource(R.string.player_sheets_track_lang_wo_title, track.id, lang)
    !track.codecDesc.isNullOrBlank() -> stringResource(R.string.player_sheets_track_title_wo_lang, track.id, track.codecDesc)
    track.isSubtitle -> stringResource(R.string.player_sheets_chapter_title_substitute_subtitle, track.id)
    track.isAudio -> stringResource(R.string.player_sheets_chapter_title_substitute_audio, track.id)
    else -> ""
  }
}

/** Small accent bar + title used above each group of tracks or options in player sheets. */
@Composable
fun SheetSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  trailing: @Composable RowScope.() -> Unit = {},
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    androidx.compose.foundation.layout.Box(
      Modifier
        .width(3.dp)
        .height(16.dp)
        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
    )
    Text(
      text = title,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.weight(1f),
    )
    trailing()
  }
}

/** One metadata chip; accent-tinted when its track is selected. */
@Composable
private fun TrackMetadataChipLabel(text: String, selected: Boolean) {
  val accent = MaterialTheme.colorScheme.primary
  val onSurface = MaterialTheme.colorScheme.onSurface
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = if (selected) accent else onSurface.copy(alpha = 0.78f),
    maxLines = 1,
    modifier = Modifier
      .background(if (selected) accent.copy(alpha = 0.16f) else onSurface.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp),
  )
}

/**
 * One rounded translucent row per track: indicator, title and metadata chips
 * (language, codec, channels, flags). The selected row is accent-tinted.
 */
@Composable
fun TrackSheetRow(
  track: TrackNode,
  selected: Boolean,
  onClick: () -> Unit,
  checkbox: Boolean = false,
  secondaryIndicator: String? = null,
  trailing: @Composable RowScope.() -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val chips = TrackMetadataFormatter.chips(track)
  val shape = RoundedCornerShape(16.dp)
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = MaterialTheme.spacing.medium, vertical = 4.dp)
      .clip(shape)
      .background(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
      )
      .clickable(onClick = onClick)
      .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (checkbox) Checkbox(checked = selected, onCheckedChange = { onClick() })
    else RadioButton(selected = selected, onClick = onClick)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
      Text(
        text = getTrackTitle(track),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (chips.isNotEmpty() || secondaryIndicator != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          chips.forEach { chip ->
            val label = when (chip.flag) {
              null -> chip.value
              TrackMetadataFlag.DEFAULT -> stringResource(R.string.track_metadata_default)
              TrackMetadataFlag.FORCED -> stringResource(R.string.track_metadata_forced)
              TrackMetadataFlag.SDH -> stringResource(R.string.track_metadata_sdh)
              TrackMetadataFlag.EXTERNAL -> stringResource(R.string.track_metadata_external)
            }
            TrackMetadataChipLabel(label, selected)
          }
          secondaryIndicator?.let { TrackMetadataChipLabel(it, selected = true) }
        }
      }
    }
    trailing()
  }
}
