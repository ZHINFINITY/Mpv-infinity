package app.infinity.mpvz.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.infinity.mpvz.R
import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.ui.player.AudiobookPlayback
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.PlaybackPhase
import app.infinity.mpvz.ui.player.Sheets
import app.infinity.mpvz.ui.utils.rememberAppHaptics

@Composable
internal fun AudiobookSheet(sheet: Sheets, onChapterEnd: () -> Unit, onDismiss: () -> Unit) {
  val state by PlaybackSession.state.collectAsStateWithLifecycle()
  val info = state.currentItem?.audiobook ?: return
  val activeBook by AudiobookPlayback.book.collectAsStateWithLifecycle()
  val book = activeBook?.takeIf { it.book.id == info.bookId }
  val timer by AudiobookPlayback.timer.collectAsStateWithLifecycle()
  val ready = state.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
  val haptics = rememberAppHaptics()
  PlayerSheet(onDismissRequest = onDismiss, title = stringResource(when (sheet) {
    Sheets.AudiobookRewind -> R.string.audiobook_smart_rewind
    else -> R.string.audiobook_sleep_timer
  })) {
      LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
        when (sheet) {
          Sheets.AudiobookRewind -> items(listOf(0, 5, 10, 15, 30)) { seconds ->
            BookSettingOption(
              label = if (seconds == 0) stringResource(R.string.audiobook_timer_off) else stringResource(R.string.audiobook_seconds, seconds),
              selected = book?.book?.rewindSeconds == seconds,
              enabled = book != null,
            ) {
              if (book?.book?.rewindSeconds != seconds) {
                AudiobookPlayback.setRewind(seconds)
                haptics.selection(true)
              }
              onDismiss()
            }
          }
          Sheets.AudiobookSleepTimer -> {
            item { BookSettingOption(stringResource(R.string.audiobook_timer_off), timer == null) { AudiobookPlayback.clearTimer(); onDismiss() } }
            items(listOf(5, 10, 15, 30, 45, 60, 90)) { minutes ->
              BookSettingOption(stringResource(R.string.audiobook_minutes, minutes), timer?.durationMinutes == minutes, enabled = ready) {
                AudiobookPlayback.setTimer(minutes)
                haptics.selection(true)
                onDismiss()
              }
            }
            item {
              BookSettingOption(stringResource(R.string.audiobook_end_chapter), timer?.chapterEndMs != null,
                enabled = ready && book != null) {
                onChapterEnd()
                haptics.selection(true)
                onDismiss()
              }
            }
          }
          else -> Unit
        }
      }
  }
}

@Composable
private fun BookSettingOption(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
  ListItem(
    headlineContent = { Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)) },
    leadingContent = { RadioButton(selected, enabled = enabled, onClick = null) },
    modifier = Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
  )
}