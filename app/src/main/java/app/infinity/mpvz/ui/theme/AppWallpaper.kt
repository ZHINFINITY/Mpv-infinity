/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Whether a user wallpaper (custom theme image/video) is currently drawn behind the UI. The
 * wallpaper picker doesn't exist in this build yet, so it is always off and the color helpers
 * pass colors through unchanged; screens call them so the wallpaper-aware path is a one-line
 * change when it lands.
 */
val LocalAppWallpaperActive = staticCompositionLocalOf { false }

val LocalLightTextOnWallpaper = staticCompositionLocalOf { false }

/** Color the screen draws when it sits on the wallpaper; transparent until the picker exists. */
val Color.onWallpaper: Color
  @androidx.compose.runtime.Composable get() = if (LocalAppWallpaperActive.current) Color.Transparent else this

/**
 * Background that stays readable on a wallpaper: on wallpaper the surface becomes translucent so
 * the frosted glass underneath shows through; without a wallpaper the color is returned as-is.
 */
@androidx.compose.runtime.Composable
fun wallpaperAwareBackgroundColor(onWallpaper: Color, plain: Color = onWallpaper): Color =
  if (LocalAppWallpaperActive.current) onWallpaper else plain

/** No-argument form used by components that take no fallback color. */
@androidx.compose.runtime.Composable
fun wallpaperAwareBackgroundColor(): Color =
  if (LocalAppWallpaperActive.current) Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background

/** Readability color over the wallpaper; passes through while the wallpaper picker doesn't exist. */
@androidx.compose.runtime.Composable
fun Color.onWallpaper(): Color = if (LocalAppWallpaperActive.current) Color.Transparent else this
