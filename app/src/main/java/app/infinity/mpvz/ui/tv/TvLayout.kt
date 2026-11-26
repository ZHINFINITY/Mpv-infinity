/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.tv

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.player.controls.components.DeviceFormFactor

/** Android TV, Google TV and Fire TV: television UI mode or leanback hardware. */
@Composable
@ReadOnlyComposable
internal fun isTelevision(): Boolean = DeviceFormFactor.isTelevision(LocalContext.current)

/**
 * Overscan-safe margins from the Android TV design guidelines: 5% of the 960x540dp frame every TV
 * reports, so nothing focusable or readable sits where older sets crop the picture. Backgrounds
 * still run to the screen edge; only content is inset.
 */
internal object TvSafeArea {
  val Horizontal: Dp = 48.dp
  val Vertical: Dp = 27.dp
  val Padding: PaddingValues = PaddingValues(horizontal = Horizontal, vertical = Vertical)
}

/** Screens that show full-bleed media, or lay out their own TV safe area, opt out of TV insets. */
interface TvEdgeToEdgeScreen

/** TV-only overscan padding for controls drawn over full-bleed media. */
internal fun Modifier.tvOverscanPadding(
  top: Boolean = true,
  bottom: Boolean = true,
): Modifier =
  composed {
    if (!isTelevision()) return@composed this
    this.padding(
      start = TvSafeArea.Horizontal,
      end = TvSafeArea.Horizontal,
      top = if (top) TvSafeArea.Vertical else 0.dp,
      bottom = if (bottom) TvSafeArea.Vertical else 0.dp,
    )
  }
