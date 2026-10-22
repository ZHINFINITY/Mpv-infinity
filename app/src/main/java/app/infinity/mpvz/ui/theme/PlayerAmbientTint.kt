/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Bridges the YouTube-style ambient colour into player glass.
 *
 * The ambient glow is drawn by its own ComposeView beneath the video, so the SurfaceView sample
 * used by player glass never contains it. Without this bridge every sheet and the chapter pill
 * was tinted with the theme's dark surface colour and read as solid black.
 *
 * Glass panes read [color] only in their draw phase, so smoothed ambient changes redraw panes
 * without recomposing the controls.
 */
object PlayerAmbientTint {
  private val state = mutableStateOf<Color?>(null)

  /** Current ambient base colour, or null when the ambient presentation is not visible. */
  val color: Color? get() = state.value

  fun publish(color: Color?) {
    val next = color?.takeIf { it.alpha > 0f }?.copy(alpha = 1f)
    if (state.value != next) state.value = next
  }

  fun clear() = publish(null)
}

object PlayerAmbientTintPolicy {
  /** How far the ambient colour is pulled toward black so white sheet text stays legible. */
  const val LegibilityDarkening = 0.42f

  /**
   * Film colour for a glass pane. Only player hosts (which provide a video backdrop) may adopt
   * the ambient colour; browser and settings panes always keep their themed film.
   */
  fun filmColor(themed: Color, ambient: Color?, isPlayerHost: Boolean): Color =
    if (!isPlayerHost || ambient == null) themed
    else lerp(ambient.copy(alpha = 1f), Color.Black, LegibilityDarkening)
}
