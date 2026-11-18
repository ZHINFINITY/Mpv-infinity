/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.ui.browser.videolist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.database.entities.PlaybackStateEntity
import app.infinity.mpvz.ui.icons.AppIcon
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons

enum class VideoMarkAs {
  New,
  LastPlayed,
  Finished,
  None,
}

/** Playback fields changed by a manual mark; all audio/subtitle/display settings remain intact. */
internal fun playbackStateForMarkAs(
  current: PlaybackStateEntity,
  markAs: VideoMarkAs,
  durationSeconds: Int,
): PlaybackStateEntity =
  when (markAs) {
    VideoMarkAs.Finished -> current.copy(
      lastPosition = durationSeconds.coerceAtLeast(0),
      timeRemaining = 0,
      hasBeenWatched = true,
    )
    VideoMarkAs.New, VideoMarkAs.LastPlayed, VideoMarkAs.None -> current.copy(
      lastPosition = 0,
      timeRemaining = durationSeconds.coerceAtLeast(0),
      hasBeenWatched = false,
    )
  }

@Composable
fun MarkAsBottomSheet(
  videoName: String,
  onDismiss: () -> Unit,
  onMarkAs: (VideoMarkAs) -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
      Text(
        text = stringResource(R.string.video_mark_as_title, videoName),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
      )
      MarkAsBottomSheetItem(R.string.video_mark_as_new, Icons.RoundedFilled.NewReleases) {
        onMarkAs(VideoMarkAs.New)
      }
      MarkAsBottomSheetItem(R.string.video_mark_as_last_played, Icons.RoundedFilled.History) {
        onMarkAs(VideoMarkAs.LastPlayed)
      }
      MarkAsBottomSheetItem(R.string.video_mark_as_finished, Icons.RoundedFilled.CheckCircle) {
        onMarkAs(VideoMarkAs.Finished)
      }
      MarkAsBottomSheetItem(R.string.video_mark_as_none, Icons.RoundedFilled.Clear) {
        onMarkAs(VideoMarkAs.None)
      }
    }
  }
}

@Composable
private fun MarkAsBottomSheetItem(
  label: Int,
  icon: AppIcon,
  onClick: () -> Unit,
) {
  ListItem(
    headlineContent = { Text(stringResource(label)) },
    leadingContent = { Icon(icon, contentDescription = null) },
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
  )
}
