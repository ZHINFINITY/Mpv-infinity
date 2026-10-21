/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import app.infinity.mpvz.ui.player.controls.components.LocalForceDarkPlayerButtonsBackground
import app.infinity.mpvz.ui.player.controls.components.LocalHidePlayerButtonsBackground
import app.infinity.mpvz.ui.theme.LocalDarkAppColorScheme
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import org.koin.compose.koinInject

/**
 * Carries the background visibility choice through every player-button implementation.
 *
 * Color overrides are deliberately not applied to this complete subtree: top-player groups also
 * contain status text and the audio player contains artwork, lyrics and sheets. Re-theming that
 * whole hierarchy just to style its buttons caused unrelated UI to switch palette.
 */
@Composable
internal fun PlayerButtonTheme(
  hideBackground: Boolean,
  content: @Composable () -> Unit,
) {
  val appearance = koinInject<AppearancePreferences>()
  val liquidGlassSurfaces by appearance.liquidGlassSurfaces.collectAsState()
  CompositionLocalProvider(
    // Glass is the active pane in this mode; do not let the legacy hide-background
    // switch suppress the sampled pane.
    LocalHidePlayerButtonsBackground provides (hideBackground && !liquidGlassSurfaces),
    content = content,
  )
}

/** Applies the selected dark app palette only to the contents of one player button. */
@Composable
internal fun PlayerButtonContentTheme(content: @Composable () -> Unit) {
  val useDarkPalette =
    LocalForceDarkPlayerButtonsBackground.current && !LocalHidePlayerButtonsBackground.current
  val darkColors = LocalDarkAppColorScheme.current
  if (!useDarkPalette || darkColors == null) {
    content()
    return
  }

  MaterialTheme(
    colorScheme = darkColors,
    typography = MaterialTheme.typography,
    shapes = MaterialTheme.shapes,
    content = content,
  )
}
