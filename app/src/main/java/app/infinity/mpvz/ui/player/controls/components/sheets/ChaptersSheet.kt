/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.infinity.mpvz.R
import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.ui.theme.spacing
import dev.vivvvek.seeker.Segment
import `is`.xyz.mpv.Utils
import kotlinx.collections.immutable.ImmutableList

@Composable
fun ChaptersSheet(
  chapters: ImmutableList<Segment>,
  currentChapter: Segment?,
  onClick: (Segment) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()

  LaunchedEffect(currentChapter, chapters) {
    val index = if (currentChapter != null) chapters.indexOf(currentChapter) else -1
    if (index >= 0) {
      listState.scrollToItem(index)
    }
  }

  PlayerSheet(onDismissRequest) {
    Column(
      modifier =
        modifier
          .padding(vertical = MaterialTheme.spacing.medium),
    ) {
      LazyColumn(state = listState) {
        itemsIndexed(chapters) { index, chapter ->
          ChapterTrack(
            chapter = chapter,
            index = index,
            selected = currentChapter == chapter,
            onClick = { onClick(chapter) },
          )
        }
      }
    }
  }
}

@Composable
fun ChapterTrack(
  chapter: Segment,
  index: Int,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 2.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(
          if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
          else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.35f),
        )
        .then(
          if (selected) {
            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
          } else {
            Modifier
          },
        )
        .clickable(onClick = onClick)
        .padding(vertical = 8.dp, horizontal = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      stringResource(R.string.player_sheets_track_title_wo_lang, index + 1, chapter.name),
      fontStyle = if (selected) FontStyle.Italic else FontStyle.Normal,
      fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      modifier = Modifier.weight(1f),
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      Utils.prettyTime(chapter.start.toInt()),
      fontStyle = if (selected) FontStyle.Italic else FontStyle.Normal,
      fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
  }
}
