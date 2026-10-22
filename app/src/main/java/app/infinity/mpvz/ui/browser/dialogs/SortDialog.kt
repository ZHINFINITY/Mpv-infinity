/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.dialogs

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import app.infinity.mpvz.ui.player.controls.components.GlassSlider as Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.infinity.mpvz.ui.components.themedSegmentedButtonColors
import app.infinity.mpvz.ui.icons.AppIcon
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.theme.AppShapeScale
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.preferences.components.settingsGlassContainerColor
import app.infinity.mpvz.ui.preferences.components.settingsGlassSurface
import app.infinity.mpvz.ui.theme.liquidGlassPane
import kotlin.math.roundToInt
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  title: String,
  sortType: String,
  onSortTypeChange: (String) -> Unit,
  sortOrderAsc: Boolean,
  onSortOrderChange: (Boolean) -> Unit,
  types: List<String>,
  icons: List<AppIcon>,
  getLabelForType: (String, Boolean) -> Pair<String, String>,
  modifier: Modifier = Modifier,
  visibilityToggles: List<VisibilityToggle> = emptyList(),
  viewModeSelector: MultiViewModeSelector? = null,
  layoutModeSelector: ViewModeSelector? = null,
  folderGridColumnSelector: GridColumnSelector? = null,
  videoGridColumnSelector: GridColumnSelector? = null,
  showSortOptions: Boolean = true,
  enableViewModeOptions: Boolean = true,
  enableLayoutModeOptions: Boolean = true,
) {
  if (!isOpen) return

  var isFieldsExpanded by rememberSaveable { mutableStateOf(false) }
  val appearancePreferences = koinInject<AppearancePreferences>()
  val glassEnabled by appearancePreferences.liquidGlassSurfaces.collectAsState()

  val (ascLabel, descLabel) = getLabelForType(sortType, sortOrderAsc)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column {
        HorizontalDivider()
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .verticalScroll(rememberScrollState()),
        ) {
          if (showSortOptions) {
            DialogSectionTitle(text = "Sort by")
            SortTypeSelector(
              sortType = sortType,
              onSortTypeChange = onSortTypeChange,
              types = types,
              icons = icons,
              glassEnabled = glassEnabled,
              modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            SortOrderSelector(
              sortOrderAsc = sortOrderAsc,
              onSortOrderChange = onSortOrderChange,
              ascLabel = ascLabel,
              descLabel = descLabel,
              glassEnabled = glassEnabled,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          if (viewModeSelector != null) {
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
            DialogSectionTitle(text = viewModeSelector.label)
            SingleChoiceSegmentedButtonRow(
              modifier = Modifier.fillMaxWidth(),
            ) {
              viewModeSelector.options.forEachIndexed { index, option ->
                SegmentedButton(
                  modifier = Modifier.liquidGlassPane(enabled = glassEnabled, cornerRadius = 18.dp),
                  selected = option.isSelected,
                  onClick = { if (enableViewModeOptions) option.onClick() },
                  shape = SegmentedButtonDefaults.itemShape(index = index, count = viewModeSelector.options.size),
                  colors = themedSegmentedButtonColors(borderAlpha = if (glassEnabled) 0f else 0.3f),
                ) {
                  Text(text = option.label)
                }
              }
            }
          }

          if (layoutModeSelector != null) {
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
            DialogSectionTitle(text = layoutModeSelector.label)
            SingleChoiceSegmentedButtonRow(
              modifier = Modifier.fillMaxWidth(),
            ) {
              val isFirstSelected = layoutModeSelector.isFirstOptionSelected
              SegmentedButton(
                modifier = Modifier.liquidGlassPane(enabled = glassEnabled, cornerRadius = 18.dp),
                selected = isFirstSelected,
                onClick = { if (enableLayoutModeOptions) layoutModeSelector.onViewModeChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                colors = themedSegmentedButtonColors(borderAlpha = if (glassEnabled) 0f else 0.3f),
                icon = {
                  Icon(
                    imageVector = layoutModeSelector.firstOptionIcon,
                    contentDescription = layoutModeSelector.firstOptionLabel,
                    modifier = Modifier.size(16.dp),
                  )
                },
              ) {
                Text(text = layoutModeSelector.firstOptionLabel)
              }
              SegmentedButton(
                modifier = Modifier.liquidGlassPane(enabled = glassEnabled, cornerRadius = 18.dp),
                selected = !isFirstSelected,
                onClick = { if (enableLayoutModeOptions) layoutModeSelector.onViewModeChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                colors = themedSegmentedButtonColors(borderAlpha = if (glassEnabled) 0f else 0.3f),
                icon = {
                  Icon(
                    imageVector = layoutModeSelector.secondOptionIcon,
                    contentDescription = layoutModeSelector.secondOptionLabel,
                    modifier = Modifier.size(16.dp),
                  )
                },
              ) {
                Text(text = layoutModeSelector.secondOptionLabel)
              }
            }
            if (layoutModeSelector.checkboxLabel != null && layoutModeSelector.onCheckboxChange != null) {
              Spacer(modifier = Modifier.height(4.dp))
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .liquidGlassPane(enabled = glassEnabled, cornerRadius = 8.dp)
                    .clickable {
                      if (enableLayoutModeOptions) {
                        layoutModeSelector.onCheckboxChange.invoke(!layoutModeSelector.isCheckboxChecked)
                      }
                    }.padding(vertical = 2.dp),
              ) {
                Checkbox(
                  checked = layoutModeSelector.isCheckboxChecked,
                  onCheckedChange = { checked ->
                    if (enableLayoutModeOptions) {
                      layoutModeSelector.onCheckboxChange.invoke(checked)
                    }
                  },
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = layoutModeSelector.checkboxLabel,
                  style = MaterialTheme.typography.bodyMedium,
                )
              }
            }
          }

              GridColumnsNextSection(
                folderGridColumnSelector = folderGridColumnSelector,
                videoGridColumnSelector = videoGridColumnSelector,
                glassEnabled = glassEnabled,
              )

          if (visibilityToggles.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
            Column(
              modifier =
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(animationSpec = tween(durationMillis = 250)),
            ) {
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .liquidGlassPane(enabled = glassEnabled, cornerRadius = 18.dp)
                    .clickable { isFieldsExpanded = !isFieldsExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text =
                    androidx.compose.ui.res
                      .stringResource(app.infinity.mpvz.R.string.ui_fields),
                  style = MaterialTheme.typography.titleSmall,
                )
                Icon(
                  imageVector = if (isFieldsExpanded) Icons.RoundedFilled.KeyboardArrowUp else Icons.RoundedFilled.KeyboardArrowDown,
                  contentDescription = if (isFieldsExpanded) "Collapse" else "Expand",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(20.dp),
                )
              }
              if (isFieldsExpanded) {
                FlowRow(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .wrapContentHeight(align = Alignment.Top),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  visibilityToggles.forEach { toggle ->
                    FilterChip(
                      modifier = Modifier.liquidGlassPane(enabled = glassEnabled, cornerRadius = 18.dp),
                      shape = RoundedCornerShape(18.dp),
                      selected = toggle.checked,
                      onClick = { toggle.onCheckedChange(!toggle.checked) },
                      label = { Text(text = toggle.label) },
                      colors =
                        if (glassEnabled) {
                          FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            selectedContainerColor = Color.Transparent,
                          )
                        } else {
                          FilterChipDefaults.filterChipColors()
                        },
                      border =
                        if (glassEnabled) null else FilterChipDefaults.filterChipBorder(
                          enabled = true,
                          selected = toggle.checked,
                          selectedBorderWidth = 1.dp,
                          selectedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss,
        modifier = Modifier.liquidGlassPane(enabled = glassEnabled, cornerRadius = 18.dp),
      ) {
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.infinity.mpvz.R.string.ui_done),
        )
      }
    },
    containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
    tonalElevation = 6.dp,
    shape = MaterialTheme.shapes.extraLarge,
    modifier =
      modifier
        .widthIn(max = 500.dp)
        .fillMaxWidth(0.88f)
        .settingsGlassSurface(cornerRadius = 28.dp, independent = true),
    properties =
      DialogProperties(
        usePlatformDefaultWidth = false,
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
      ),
  )
}

@Composable
private fun SortTypeSelector(
  sortType: String,
  onSortTypeChange: (String) -> Unit,
  types: List<String>,
  icons: List<AppIcon>,
  glassEnabled: Boolean,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    types.forEachIndexed { index, type ->
      val selected = sortType == type
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(2.dp).selectable(
          selected = selected,
          role = Role.RadioButton,
          onClick = { onSortTypeChange(type) },
        ),
      ) {
        Box(
          modifier =
            Modifier
              .size(64.dp)
              .clip(AppShapeScale.large)
              .background(
                color =
                  if (glassEnabled) {
                    Color.Transparent
                  } else if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                  } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                  },
              )
              .liquidGlassPane(enabled = glassEnabled, cornerRadius = 18.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = icons[index],
            contentDescription = type,
            modifier = Modifier.size(30.dp),
            tint =
              if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
          )
        }
        Text(
          text = type,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
          color =
            if (selected) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
    }
  }
}

@Composable
private fun SortOrderSelector(
  sortOrderAsc: Boolean,
  onSortOrderChange: (Boolean) -> Unit,
  ascLabel: String,
  descLabel: String,
  glassEnabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val options = listOf(ascLabel, descLabel)
  val selectedIndex = if (sortOrderAsc) 0 else 1

  SingleChoiceSegmentedButtonRow(
    modifier = modifier,
  ) {
    options.forEachIndexed { index, label ->
      SegmentedButton(
        modifier = Modifier.liquidGlassPane(enabled = glassEnabled, cornerRadius = 18.dp),
        selected = index == selectedIndex,
        onClick = { onSortOrderChange(index == 0) },
        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
        colors = themedSegmentedButtonColors(borderAlpha = if (glassEnabled) 0f else 0.3f),
        icon = {
          Icon(
            imageVector =
              if (index ==
                0
              ) {
                Icons.RoundedFilled.KeyboardArrowUp
              } else {
                Icons.RoundedFilled.KeyboardArrowDown
              },
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
        },
      ) {
        Text(text = label)
      }
    }
  }
}

@Composable
private fun DialogSectionTitle(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
  )
}

@Composable
private fun GridColumnsNextSection(
  folderGridColumnSelector: GridColumnSelector?,
  videoGridColumnSelector: GridColumnSelector?,
  glassEnabled: Boolean,
) {
  if (folderGridColumnSelector == null && videoGridColumnSelector == null) return

  val haptic = LocalHapticFeedback.current

  HorizontalDivider(modifier = Modifier.padding(top = 10.dp))

  if (folderGridColumnSelector != null && videoGridColumnSelector != null) {
    DialogSectionTitle(text = "Grid Columns")
    Row(
      modifier = Modifier.fillMaxWidth().liquidGlassPane(enabled = glassEnabled, cornerRadius = 18.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = folderGridColumnSelector.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = if (folderGridColumnSelector.unitSuffix.isEmpty()) "${folderGridColumnSelector.currentValue}" else "${folderGridColumnSelector.currentValue} ${folderGridColumnSelector.unitSuffix}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Slider(
          value = folderGridColumnSelector.currentValue.toFloat(),
          onValueChange = {
            val newValue = it.roundToInt()
            if (newValue != folderGridColumnSelector.currentValue) {
              folderGridColumnSelector.onValueChange(newValue)
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
          },
          valueRange = folderGridColumnSelector.valueRange,
          steps = folderGridColumnSelector.steps,
          modifier = Modifier.fillMaxWidth().semantics { contentDescription = folderGridColumnSelector.label },
        )
      }

      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = videoGridColumnSelector.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = if (videoGridColumnSelector.unitSuffix.isEmpty()) "${videoGridColumnSelector.currentValue}" else "${videoGridColumnSelector.currentValue} ${videoGridColumnSelector.unitSuffix}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Slider(
          value = videoGridColumnSelector.currentValue.toFloat(),
          onValueChange = {
            val newValue = it.roundToInt()
            if (newValue != videoGridColumnSelector.currentValue) {
              videoGridColumnSelector.onValueChange(newValue)
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
          },
          valueRange = videoGridColumnSelector.valueRange,
          steps = videoGridColumnSelector.steps,
          modifier = Modifier.fillMaxWidth().semantics { contentDescription = videoGridColumnSelector.label },
        )
      }
    }
  } else {
    val selector = folderGridColumnSelector ?: videoGridColumnSelector!!
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        .liquidGlassPane(enabled = glassEnabled, cornerRadius = 18.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      DialogSectionTitle(text = selector.label)
      Text(
        text = if (selector.unitSuffix.isEmpty()) "${selector.currentValue}" else "${selector.currentValue} ${selector.unitSuffix}",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Slider(
      value = selector.currentValue.toFloat(),
      onValueChange = {
        val newValue = it.roundToInt()
        if (newValue != selector.currentValue) {
          selector.onValueChange(newValue)
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
      },
      valueRange = selector.valueRange,
      steps = selector.steps,
      modifier = Modifier.fillMaxWidth().semantics { contentDescription = selector.label },
    )
  }
}

data class VisibilityToggle(
  val label: String,
  val checked: Boolean,
  val onCheckedChange: (Boolean) -> Unit,
)

data class ViewModeOption(
  val label: String,
  val icon: AppIcon,
  val isSelected: Boolean,
  val onClick: () -> Unit,
)

data class MultiViewModeSelector(
  val label: String,
  val options: List<ViewModeOption>,
)

data class ViewModeSelector(
  val label: String,
  val firstOptionLabel: String,
  val secondOptionLabel: String,
  val firstOptionIcon: AppIcon,
  val secondOptionIcon: AppIcon,
  val isFirstOptionSelected: Boolean,
  val onViewModeChange: (Boolean) -> Unit,
  val checkboxLabel: String? = null,
  val isCheckboxChecked: Boolean = false,
  val onCheckboxChange: ((Boolean) -> Unit)? = null,
)

data class GridColumnSelector(
  val label: String,
  val currentValue: Int,
  val onValueChange: (Int) -> Unit,
  val valueRange: ClosedFloatingPointRange<Float> = 1f..4f,
  val steps: Int = 2,
  val unitSuffix: String = "cols",
)
