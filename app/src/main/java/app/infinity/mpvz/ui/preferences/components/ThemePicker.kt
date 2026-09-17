/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.preferences.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.border
import androidx.compose.foundation.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.theme.AppTheme
import app.infinity.mpvz.preferences.CustomThemeData

/**
 * A horizontal scrollable theme picker with preview cards.
 * Displays all available themes with visual previews.
 */
@Composable
fun ThemePicker(
  currentTheme: AppTheme,
  isDarkMode: Boolean,
  onThemeSelected: (AppTheme, Offset) -> Unit,
  customThemes: List<CustomThemeData> = emptyList(),
  activeCustomThemeId: String = "",
  onCustomThemeSelected: (CustomThemeData, Offset) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()

  LaunchedEffect(Unit) {
    val index = AppTheme.entries.indexOf(currentTheme)
    if (index >= 0) {
      listState.animateScrollToItem(maxOf(0, index - 1))
    }
  }

  Column(
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = stringResource(R.string.pref_appearance_theme_picker_label),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )

    LazyRow(
      modifier = Modifier.fillMaxWidth(),
      state = listState,
      contentPadding = PaddingValues(horizontal = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      items(AppTheme.entries, key = { it.name }) { theme ->
        ThemePreviewCard(
          theme = theme,
          isSelected = theme == currentTheme && activeCustomThemeId.isEmpty(),
          isDarkMode = isDarkMode,
          onClick = { position -> onThemeSelected(theme, position) },
        )
      }
      items(customThemes, key = { "custom-${it.id}" }) { theme ->
        CustomThemeRailCard(
          theme = theme,
          isSelected = activeCustomThemeId == theme.id,
          onClick = { position -> onCustomThemeSelected(theme, position) },
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
  }
}


@Composable
private fun CustomThemeRailCard(theme: CustomThemeData, isSelected: Boolean, onClick: (Offset) -> Unit) {
  val bitmap = remember(theme.mediaPath) {
    if (theme.isVideo) {
      runCatching { android.media.MediaMetadataRetriever().run { setDataSource(theme.mediaPath); getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC).also { release() } } }.getOrNull()
    } else android.graphics.BitmapFactory.decodeFile(theme.mediaPath)
  }
  Column(modifier = Modifier.width(100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(modifier = Modifier.size(width = 90.dp, height = 140.dp).clip(RoundedCornerShape(12.dp)).border(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
      bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
      Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = (theme.overlay * 0.35f).coerceIn(0f, 0.35f))))
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(theme.name, style = MaterialTheme.typography.bodySmall, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 2, modifier = Modifier.fillMaxWidth())
  }
}
