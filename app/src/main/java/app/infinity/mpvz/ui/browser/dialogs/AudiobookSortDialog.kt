package app.infinity.mpvz.ui.browser.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.infinity.mpvz.preferences.AudiobookSortType
import app.infinity.mpvz.preferences.MediaLayoutMode
import app.infinity.mpvz.preferences.SortOrder

@Composable
fun AudiobookSortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  sortType: AudiobookSortType,
  sortOrder: SortOrder,
  layoutMode: MediaLayoutMode,
  onSortTypeChange: (AudiobookSortType) -> Unit,
  onSortOrderChange: (SortOrder) -> Unit,
  onLayoutModeChange: (MediaLayoutMode) -> Unit,
) {
  if (!isOpen) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Audiobooks") },
    text = {
      Text("Sort: ${sortType.name}\nOrder: ${sortOrder.name}\nLayout: ${layoutMode.name}")
    },
    confirmButton = {
      TextButton(onClick = {
        onSortTypeChange(AudiobookSortType.entries[(sortType.ordinal + 1) % AudiobookSortType.entries.size])
      }) { Text("Next sort") }
    },
    dismissButton = {
      TextButton(onClick = {
        onSortOrderChange(if (sortOrder == SortOrder.Ascending) SortOrder.Descending else SortOrder.Ascending)
        onLayoutModeChange(if (layoutMode == MediaLayoutMode.GRID) MediaLayoutMode.LIST else MediaLayoutMode.GRID)
        onDismiss()
      }) { Text("Done") }
    },
  )
}
