/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls.components

/** Pure rendering policy shared by glass switches and sliders. */
object GlassControlPolicy {
  fun depth(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 2f) else 1f

  fun highlightAlpha(highlight: Float, enabled: Boolean, checked: Boolean): Float {
    val strength = if (highlight.isFinite()) highlight.coerceIn(0f, 1f) else 0f
    return strength * if (!enabled) 0.35f else if (checked) 1f else 0.72f
  }

  fun shadowAlpha(depth: Float, enabled: Boolean): Float =
    depth(depth) * if (enabled) 0.12f else 0.06f

  fun thumbBorderAlpha(enabled: Boolean, checked: Boolean): Float =
    if (!enabled) 0.22f else if (checked) 0.68f else 0.34f

  /** Small inset edge; never grows into the flat rectangle/card backing. */
  fun innerRimAlpha(depth: Float): Float = (depth(depth) * 0.045f).coerceIn(0f, 0.09f)
}
