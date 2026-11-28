/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.framecapture.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.BrowserPageViewPreferences
import app.infinity.mpvz.preferences.BrowserPreferences
import app.infinity.mpvz.preferences.MediaLayoutMode
import app.infinity.mpvz.preferences.SnapshotFolderSortType
import app.infinity.mpvz.preferences.SnapshotSortType
import app.infinity.mpvz.preferences.SortOrder
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.browser.dialogs.GridColumnSelector
import app.infinity.mpvz.ui.browser.dialogs.SortDialog
import app.infinity.mpvz.ui.browser.dialogs.MultiViewModeSelector
import app.infinity.mpvz.ui.browser.dialogs.ViewModeOption
import app.infinity.mpvz.ui.browser.dialogs.ViewModeSelector
import app.infinity.mpvz.ui.browser.dialogs.VisibilityToggle
import app.infinity.mpvz.ui.icons.Icons
import org.koin.compose.koinInject

/**
 * One dialog for both snapshot levels.
 *
 * [forFolders] picks the whole configuration — sort fields, sort preferences and which page's layout
 * preference the layout selector writes to — because the two levels sort on different things and must
 * remember their list/grid choice separately.
 */
@Composable
fun SnapshotSortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  forFolders: Boolean = false,
) {
  if (!isOpen) return
  val preferences = koinInject<BrowserPreferences>()
  val viewPreferences: BrowserPageViewPreferences =
    if (forFolders) preferences.snapshotLibraryView else preferences.snapshotItemView

  val layoutMode by viewPreferences.layoutMode.collectAsState()
  val manualGrid by viewPreferences.manualGridColumnsEnabled.collectAsState()
  val configuration = LocalConfiguration.current
  val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val columnsPreference = if (landscape) viewPreferences.gridColumnsLandscape else viewPreferences.gridColumnsPortrait
  val requestedColumns by columnsPreference.collectAsState()
  // Photos are square and small, so the cap is generous: a phone in a grid layout can comfortably
  // show five, and anything past that is a choice the user is allowed to make.
  val maxColumns = if (landscape) 8 else 6
  val columns = if (requestedColumns > 0) requestedColumns.coerceIn(1, maxColumns) else maxColumns

  val ascendingLabel = stringResource(R.string.playlist_sort_ascending)
  val descendingLabel = stringResource(R.string.playlist_sort_descending)

  val layoutModeSelector =
    MultiViewModeSelector(
      label = stringResource(R.string.playlist_layout),
      options =
        listOf(
          ViewModeOption(
            label = stringResource(R.string.playlist_view_list),
            icon = Icons.RoundedFilled.ViewList,
            isSelected = layoutMode == MediaLayoutMode.LIST,
            onClick = { viewPreferences.layoutMode.set(MediaLayoutMode.LIST) },
          ),
          ViewModeOption(
            label = stringResource(R.string.playlist_view_grid),
            icon = Icons.RoundedFilled.GridView,
            isSelected = layoutMode == MediaLayoutMode.GRID,
            onClick = { viewPreferences.layoutMode.set(MediaLayoutMode.GRID) },
          ),
        ) +
          (
            if (forFolders) {
              emptyList()
            } else {
              listOf(
                ViewModeOption(
                  label = stringResource(R.string.playlist_view_mosaic),
                  icon = Icons.RoundedFilled.ViewQuilt,
                  isSelected = layoutMode == MediaLayoutMode.MOSAIC,
                  onClick = { viewPreferences.layoutMode.set(MediaLayoutMode.MOSAIC) },
                ),
              )
            }
          ),
    )

  val manualGridToggle =
    if (layoutMode != MediaLayoutMode.LIST) {
      VisibilityToggle(
        label = stringResource(R.string.playlist_manual_grid),
        checked = manualGrid,
        onCheckedChange = { enabled ->
          if (enabled && requestedColumns <= 0) columnsPreference.set(columns)
          viewPreferences.manualGridColumnsEnabled.set(enabled)
        },
      )
    } else {
      null
    }

  val columnSelector =
    if (layoutMode != MediaLayoutMode.LIST && manualGrid && maxColumns > 1) {
      GridColumnSelector(
        label =
          stringResource(
            if (landscape) R.string.playlist_columns_landscape else R.string.playlist_columns_portrait,
          ),
        currentValue = columns.coerceIn(1, maxColumns),
        onValueChange = columnsPreference::set,
        valueRange = 1f..maxColumns.toFloat(),
        steps = maxColumns - 2,
      )
    } else {
      null
    }

  if (forFolders) {
    val sortType by preferences.snapshotFolderSortType.collectAsState()
    val sortOrder by preferences.snapshotFolderSortOrder.collectAsState()
    val labels =
      mapOf(
        SnapshotFolderSortType.Name to stringResource(R.string.snapshot_sort_folder_name),
        SnapshotFolderSortType.CreatedAt to stringResource(R.string.snapshot_sort_created_at),
        SnapshotFolderSortType.ItemCount to stringResource(R.string.snapshot_sort_item_count),
      )

    SortDialog(
      isOpen = isOpen,
      onDismiss = onDismiss,
      title = stringResource(R.string.sort_view_options),
      sortType = labels.getValue(sortType),
      onSortTypeChange = { selected ->
        labels.entries.firstOrNull { it.value == selected }?.let { preferences.snapshotFolderSortType.set(it.key) }
      },
      sortOrderAsc = sortOrder.isAscending,
      onSortOrderChange = {
        preferences.snapshotFolderSortOrder.set(if (it) SortOrder.Ascending else SortOrder.Descending)
      },
      types = SnapshotFolderSortType.entries.map(labels::getValue),
      icons =
        SnapshotFolderSortType.entries.map { type ->
          when (type) {
            SnapshotFolderSortType.Name -> Icons.RoundedFilled.Title
            SnapshotFolderSortType.CreatedAt -> Icons.RoundedFilled.CalendarToday
            SnapshotFolderSortType.ItemCount -> Icons.RoundedFilled.Image
          }
        },
      getLabelForType = { type, _ ->
        if (type == labels.getValue(SnapshotFolderSortType.Name)) {
          "A-Z" to "Z-A"
        } else {
          ascendingLabel to descendingLabel
        }
      },
      showSortOptions = true,
      enableViewModeOptions = false,
      enableLayoutModeOptions = false,
      viewModeSelector = layoutModeSelector,
      videoGridColumnSelector = columnSelector,
    )
    return
  }

  val sortType by preferences.snapshotSortType.collectAsState()
  val sortOrder by preferences.snapshotSortOrder.collectAsState()
  val labels =
    mapOf(
      SnapshotSortType.CapturedAt to stringResource(R.string.snapshot_sort_captured_at),
      SnapshotSortType.VideoTitle to stringResource(R.string.snapshot_sort_video_title),
      SnapshotSortType.Position to stringResource(R.string.snapshot_sort_position),
    )

  SortDialog(
    isOpen = isOpen,
    onDismiss = onDismiss,
    title = stringResource(R.string.sort_view_options),
    sortType = labels.getValue(sortType),
    onSortTypeChange = { selected ->
      labels.entries.firstOrNull { it.value == selected }?.let { preferences.snapshotSortType.set(it.key) }
    },
    sortOrderAsc = sortOrder.isAscending,
    onSortOrderChange = { preferences.snapshotSortOrder.set(if (it) SortOrder.Ascending else SortOrder.Descending) },
    types = SnapshotSortType.entries.map(labels::getValue),
    icons =
      SnapshotSortType.entries.map { type ->
        when (type) {
          SnapshotSortType.CapturedAt -> Icons.RoundedFilled.CalendarToday
          SnapshotSortType.VideoTitle -> Icons.RoundedFilled.Title
          SnapshotSortType.Position -> Icons.RoundedFilled.AccessTime
        }
      },
    getLabelForType = { type, _ ->
      if (type == labels.getValue(SnapshotSortType.VideoTitle)) {
        "A-Z" to "Z-A"
      } else {
        ascendingLabel to descendingLabel
      }
    },
    showSortOptions = true,
    enableViewModeOptions = false,
    enableLayoutModeOptions = false,
    viewModeSelector = layoutModeSelector,
    videoGridColumnSelector = columnSelector,
  )
}
