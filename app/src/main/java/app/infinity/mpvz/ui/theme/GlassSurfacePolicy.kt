/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.theme

/** Keep foreground readable, even after invalid imported appearance preferences. */
object GlassSurfacePolicy {
  const val MinOpacity = 0.72f
  const val MaxOpacity = 1f
  const val MinHighlight = 0f
  const val MaxHighlight = 0.36f

  fun opacity(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MinOpacity, MaxOpacity) else 0.88f

  fun highlight(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MinHighlight, MaxHighlight) else 0.18f
}
