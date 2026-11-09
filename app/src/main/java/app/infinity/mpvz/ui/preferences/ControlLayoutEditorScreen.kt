/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.preferences

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.infinity.mpvz.ui.preferences.components.SettingsScaffold as Scaffold
import androidx.compose.material3.Text
import app.infinity.mpvz.ui.preferences.components.SettingsTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.PlayerButton
import app.infinity.mpvz.preferences.allPlayerButtons
import app.infinity.mpvz.preferences.preference.Preference
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.presentation.components.ConfirmDialog
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.controls.components.AbLoopIcon
import app.infinity.mpvz.ui.preferences.components.PlayerButtonChip
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.popSafely
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import app.infinity.mpvz.ui.icons.Icon as AppSymbolIcon
import kotlinx.coroutines.delay

@Serializable
data class ControlLayoutEditorScreen(
  val region: ControlRegion,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val preferences = koinInject<AppearancePreferences>()

    // Get all 4 preferences as a List
    val prefs =
      remember(region) {
        when (region) {
          ControlRegion.TOP_RIGHT ->
            listOf(
              preferences.topRightControls,
              preferences.topLeftControls,
              preferences.bottomRightControls,
              preferences.bottomLeftControls,
            )
          ControlRegion.BOTTOM_RIGHT ->
            listOf(
              preferences.bottomRightControls,
              preferences.topLeftControls,
              preferences.topRightControls,
              preferences.bottomLeftControls,
            )
          ControlRegion.BOTTOM_LEFT ->
            listOf(
              preferences.bottomLeftControls,
              preferences.topLeftControls,
              preferences.topRightControls,
              preferences.bottomRightControls,
            )
          ControlRegion.PORTRAIT_BOTTOM ->
            listOf(
              preferences.portraitBottomControls,
            )
        }
      }

    val prefToEdit: Preference<String> = prefs[0]

    val topLeftControlsValue by preferences.topLeftControls.collectAsState()
    val topRightControlsValue by preferences.topRightControls.collectAsState()
    val bottomRightControlsValue by preferences.bottomRightControls.collectAsState()
    val bottomLeftControlsValue by preferences.bottomLeftControls.collectAsState()
    val portraitBottomControlsValue by preferences.portraitBottomControls.collectAsState()
    val activeControlsValue by prefToEdit.collectAsState()

    fun parseButtons(value: String): List<PlayerButton> =
      value.split(',').filter(String::isNotBlank).mapNotNull { name ->
        runCatching { PlayerButton.valueOf(name) }.getOrNull()
      }

    // State for buttons used in *other* regions
    val otherControlValues =
      when (region) {
        ControlRegion.TOP_RIGHT -> listOf(topLeftControlsValue, bottomRightControlsValue, bottomLeftControlsValue)
        ControlRegion.BOTTOM_RIGHT -> listOf(topLeftControlsValue, topRightControlsValue, bottomLeftControlsValue)
        ControlRegion.BOTTOM_LEFT -> listOf(topLeftControlsValue, topRightControlsValue, bottomRightControlsValue)
        ControlRegion.PORTRAIT_BOTTOM -> emptyList()
      }
    val disabledButtons =
      remember(region, otherControlValues) {
        otherControlValues.flatMap { it.split(',') }
          .filter(String::isNotBlank)
          .mapNotNull { name -> runCatching { PlayerButton.valueOf(name) }.getOrNull() }
          .toSet()
      }

    var selectedButtons by remember(region, activeControlsValue) {
      mutableStateOf(parseButtons(activeControlsValue))
    }

    val playbackControlsPosition by preferences.playbackControlsPosition.collectAsState()
    val previewTopRightButtons =
      if (region == ControlRegion.TOP_RIGHT) selectedButtons else preferences.parseButtons(topRightControlsValue, mutableSetOf())
    val previewBottomRightButtons =
      if (region == ControlRegion.BOTTOM_RIGHT) selectedButtons else preferences.parseButtons(bottomRightControlsValue, mutableSetOf())
    val previewBottomLeftButtons =
      if (region == ControlRegion.BOTTOM_LEFT) selectedButtons else preferences.parseButtons(bottomLeftControlsValue, mutableSetOf())
    val previewPortraitBottomButtons =
      if (region == ControlRegion.PORTRAIT_BOTTOM) selectedButtons else preferences.parseButtons(portraitBottomControlsValue, mutableSetOf())

    // Coalesce rapid reorder updates, but apply the final layout while this editor remains open.
    LaunchedEffect(region, activeControlsValue, selectedButtons) {
      val serialized = selectedButtons.joinToString(",")
      if (serialized == activeControlsValue) return@LaunchedEffect
      delay(180)
      if (prefToEdit.get() == activeControlsValue) prefToEdit.set(serialized)
    }

    DisposableEffect(region, prefToEdit) {
      onDispose {
        val serialized = selectedButtons.joinToString(",")
        if (prefToEdit.get() != serialized) prefToEdit.set(serialized)
      }
    }

    val title =
      remember(region) {
        when (region) {
          ControlRegion.TOP_RIGHT -> "Edit Top Right"
          ControlRegion.BOTTOM_RIGHT -> "Edit Bottom Right"
          ControlRegion.BOTTOM_LEFT -> "Edit Bottom Left"
          ControlRegion.PORTRAIT_BOTTOM -> "Edit Portrait Bottom"
        }
      }

    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
      ConfirmDialog(
        title = stringResource(R.string.controls_reset_default_title),
        subtitle = stringResource(R.string.controls_reset_default_summary),
        onConfirm = {
          prefToEdit.delete()
          selectedButtons = parseButtons(prefToEdit.get())
          showResetDialog = false
        },
        onCancel = {
          showResetDialog = false
        },
      )
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(text = title) },
          navigationIcon = {
            IconButton(onClick = { backstack.popSafely() }) {
              AppSymbolIcon(
                Icons.RoundedFilled.ArrowBack,
                contentDescription =
                  androidx.compose.ui.res
                    .stringResource(app.infinity.mpvz.R.string.back),
              )
            }
          },
          actions = {
            IconButton(onClick = { showResetDialog = true }) {
              AppSymbolIcon(
                Icons.RoundedFilled.Refresh,
                contentDescription =
                  androidx.compose.ui.res.stringResource(
                    app.infinity.mpvz.R.string.pref_layout_reset_default,
                  ),
              )
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        val gridState = rememberLazyGridState()
        val reorderableState =
          rememberReorderableLazyGridState(gridState) { from, to ->
            val fromKey = from.key as? PlayerButton
            val toKey = to.key as? PlayerButton

            val fromIndex = selectedButtons.indexOf(fromKey)
            val toIndex = selectedButtons.indexOf(toKey)

            // Only update if indices are valid to prevent unnecessary state changes
            if (fromIndex in selectedButtons.indices && toIndex in selectedButtons.indices && fromIndex != toIndex) {
              selectedButtons =
                selectedButtons.toMutableList().apply {
                  add(toIndex, removeAt(fromIndex))
                }
            }
          }

        LazyVerticalGrid(
          state = gridState,
          columns = GridCells.Adaptive(minSize = 72.dp),
          contentPadding =
            androidx.compose.foundation.layout
              .PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          item(span = { GridItemSpan(maxLineSpan) }) {
            PlayerLayoutPreview(
              topRightButtons = previewTopRightButtons,
              bottomLeftButtons = previewBottomLeftButtons,
              bottomRightButtons = previewBottomRightButtons,
              position = playbackControlsPosition,
              activeRegion = region,
              portraitBottomButtons = previewPortraitBottomButtons,
              modifier = Modifier.padding(bottom = 4.dp),
              onPreviewButtonTap = { button ->
                if (button in selectedButtons) selectedButtons = selectedButtons - button
              },
              onPreviewReorder = { fromButton, toButton ->
                val fromIndex = selectedButtons.indexOf(fromButton)
                val toIndex = selectedButtons.indexOf(toButton)
                if (fromIndex in selectedButtons.indices && toIndex in selectedButtons.indices && fromIndex != toIndex) {
                  selectedButtons = selectedButtons.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                }
              },
            )
          }

          // --- 1. Header & Active Selected Zone ---
          item(span = { GridItemSpan(maxLineSpan) }) {
            androidx.compose.material3.Text(
              text =
                androidx.compose.ui.res.stringResource(
                  app.infinity.mpvz.R.string.ui_long_press_to_reorder_items_tap_the_icon_to_remove_them,
                ),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
            )
          }

          // --- 2. Selected Controls (Reorderable) ---
          if (selectedButtons.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              androidx.compose.material3.Surface(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                shape =
                  androidx.compose.foundation.shape
                    .RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border =
                  BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                  ),
              ) {
                androidx.compose.foundation.layout.Column(
                  modifier = Modifier.fillMaxSize(),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.Center,
                ) {
                  AppSymbolIcon(
                    imageVector = Icons.RoundedFilled.AddCircle,
                    contentDescription = null,
                    modifier =
                      Modifier
                        .size(32.dp)
                        .padding(bottom = 8.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                  )
                  androidx.compose.material3.Text(
                    text =
                      androidx.compose.ui.res.stringResource(
                        app.infinity.mpvz.R.string.ui_drop_zone_is_empty,
                      ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  androidx.compose.material3.Text(
                    text =
                      androidx.compose.ui.res.stringResource(
                        app.infinity.mpvz.R.string.ui_tap_buttons_from_the_available_palette_below,
                      ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                  )
                }
              }
            }
          } else {
            items(
              count = selectedButtons.size,
              key = { selectedButtons[it] },
              span = { index ->
                val button = selectedButtons[index]
                if (button == PlayerButton.CURRENT_CHAPTER || button == PlayerButton.VIDEO_TITLE) {
                  GridItemSpan(maxLineSpan)
                } else {
                  GridItemSpan(1)
                }
              },
            ) { index ->
              val button = selectedButtons[index]
              ReorderableItem(reorderableState, key = button) { isDragging ->
                val elevation by animateFloatAsState(
                  targetValue = if (isDragging) 8f else 0f,
                  label = "drag_elevation",
                )

                // Wrap in Box to control alignment/filling within the grid cell
                androidx.compose.material3.Surface(
                  modifier =
                    Modifier
                      .draggableHandle()
                      .then(
                        if (button == PlayerButton.CURRENT_CHAPTER || button == PlayerButton.VIDEO_TITLE) {
                          Modifier.wrapContentWidth(Alignment.Start)
                        } else {
                          Modifier
                        },
                      ),
                  shape =
                    androidx.compose.foundation.shape
                      .RoundedCornerShape(24.dp),
                  // Match chip border radius
                  shadowElevation = elevation.dp,
                  color = Color.Transparent,
                ) {
                  PlayerButtonChip(
                    button = button,
                    enabled = true,
                    onClick = { selectedButtons = selectedButtons - button },
                    badgeIcon = Icons.RoundedFilled.RemoveCircle,
                    badgeColor = Color(0xFFEF5350),
                  )
                }
              }
            }
          }

          // --- 3. SPaceing ---
          item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(modifier = Modifier.height(40.dp))
          }

          // --- 4. Available Controls  ---
          item(span = { GridItemSpan(maxLineSpan) }) {
            androidx.compose.material3.Card(
              modifier = Modifier.fillMaxWidth(),
              shape =
                androidx.compose.foundation.shape
                  .RoundedCornerShape(16.dp),
              colors =
                androidx.compose.material3.CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
              FlowRow(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                val availableButtons = allPlayerButtons.filter { it !in selectedButtons }
                availableButtons.forEach { button ->
                  val isEnabled = button !in disabledButtons
                  PlayerButtonChip(
                    button = button,
                    enabled = isEnabled,
                    onClick = { selectedButtons = selectedButtons + button },
                    badgeIcon = Icons.RoundedFilled.AddCircle,
                    badgeColor =
                      if (isEnabled) {
                        MaterialTheme.colorScheme.primary
                      } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                      },
                  )
                }

                if (availableButtons.isEmpty()) {
                  androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                  ) {
                    androidx.compose.material3.Text(
                      text =
                        androidx.compose.ui.res.stringResource(
                          app.infinity.mpvz.R.string.ui_all_available_buttons_are_in_use,
                        ),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }

          // --- 5. Icons Legend ---
          item(span = { GridItemSpan(maxLineSpan) }) {
            IconsLegend()
            Spacer(Modifier.height(16.dp))
          }
        }
      }
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI Components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IconsLegend() {
  androidx.compose.material3.Card(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(top = 24.dp, bottom = 8.dp),
    shape =
      androidx.compose.foundation.shape
        .RoundedCornerShape(16.dp),
    colors =
      androidx.compose.material3.CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      ),
  ) {
    androidx.compose.foundation.layout.Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Header
      androidx.compose.foundation.layout.Column {
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.infinity.mpvz.R.string.ui_icons_legend),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.infinity.mpvz.R.string.ui_what_is_each_icon_for),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // FlowRow grid of icons
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // We use allPlayerButtons to avoid showing NONE and defaults like Back/Title which are static
        allPlayerButtons.forEach { button ->
          androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.wrapContentWidth(),
          ) {
            if (button == PlayerButton.AB_LOOP) {
              AbLoopIcon(
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              val modifier =
                if (button == PlayerButton.VERTICAL_FLIP) {
                  Modifier.rotate(90f)
                } else {
                  Modifier
                }

              AppSymbolIcon(
                imageVector = button.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                  Modifier
                    .size(20.dp)
                    .then(modifier),
              )
            }

            Text(
              text =
                app.infinity.mpvz.preferences
                  .getPlayerButtonLabel(button),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(end = 4.dp),
            )
          }
        }
      }
    }
  }
}
