/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Theme-aware colors for [androidx.compose.material3.SegmentedButton], used across all
 * Video/Audio-style segmented tab rows in the app (Recently Played, Media Library, Sort
 * Dialog, Ambient Sheet, About screen, Jellyfin auth selector).
 *
 * Design intent:
 * - No solid/grey pill for the selected segment. Instead a subtle tint of the current
 *   theme's accent color ([activeContainerAlpha]) is used as the selected background.
 * - Selected text/icons use the surface content color for contrast against the tint.
 * - Unselected segments are transparent and use the secondary surface content color.
 * - The outer border uses the theme's outline color at low opacity so it stays subtle
 *   across all dynamic themes (Aurora, Nord, Dracula, Catppuccin, etc.).
 */
@Composable
fun themedSegmentedButtonColors(
  activeContainerAlpha: Float = 0.18f,
  borderAlpha: Float = 0.3f,
): SegmentedButtonColors {
  val colorScheme = MaterialTheme.colorScheme
  val activeContentColor = if (colorScheme.onSurface.luminance() > colorScheme.surface.luminance()) Color.White else Color.Black
  return SegmentedButtonDefaults.colors(
    activeContainerColor = colorScheme.primary.copy(alpha = activeContainerAlpha),
    activeContentColor = activeContentColor,
    activeBorderColor = colorScheme.outline.copy(alpha = borderAlpha),
    inactiveContainerColor = Color.Transparent,
    inactiveContentColor = colorScheme.onSurfaceVariant,
    inactiveBorderColor = colorScheme.outline.copy(alpha = borderAlpha),
  )
}
