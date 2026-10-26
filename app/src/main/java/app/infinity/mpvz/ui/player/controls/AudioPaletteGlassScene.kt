/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import app.infinity.mpvz.ui.theme.LiquidGlassFrame
import app.infinity.mpvz.ui.theme.LocalLiquidGlassBackdrop

/** Music gets a tiny color-only scene, not album pixels and not a previous video's frame. */
@Composable
internal fun AudioPaletteGlassScene(
  topArgb: Int,
  bottomArgb: Int,
  enabled: Boolean,
  modifier: Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  val hostView = LocalView.current
  // Plain holder, not state: the minimize transition moves this box every frame, and a
  // bounds-keyed state used to hand every glass pane a new backdrop object per frame,
  // recomposing the whole Now Playing page while the finger was dragging it.
  val bounds = remember { arrayOf(Rect.Zero) }
  // 1 x 32 pixels, created only when a stable extracted palette changes; never keyed to animation.
  val image = remember(topArgb, bottomArgb, enabled) {
    if (!enabled) null else {
      val rows = AudioPalettePolicy.gradientRows(topArgb, bottomArgb)
      Bitmap.createBitmap(rows, 1, rows.size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
  }
  // One State for the scene's lifetime. Panes read its value only while drawing, so a
  // moved scene redraws them without recomposition.
  val frame = remember { mutableStateOf<LiquidGlassFrame?>(null) }
  fun publish(image: ImageBitmap?, next: Rect) {
    frame.value = image?.takeIf { next.width > 0f && next.height > 0f }
      ?.let { LiquidGlassFrame(it, next, refractionEnabled = false) }
  }
  SideEffect { if (frame.value?.image !== image) publish(image, bounds[0]) }
  CompositionLocalProvider(LocalLiquidGlassBackdrop provides frame) {
    Box(
      modifier = modifier.clipToBounds().onGloballyPositioned { coordinates ->
        val window = IntArray(2)
        val screen = IntArray(2)
        hostView.getLocationInWindow(window)
        hostView.getLocationOnScreen(screen)
        val local = coordinates.localToWindow(Offset.Zero)
        val x = local.x + screen[0] - window[0]
        val y = local.y + screen[1] - window[1]
        val next = Rect(x, y, x + coordinates.size.width, y + coordinates.size.height)
        if (next != bounds[0]) {
          bounds[0] = next
          publish(image, next)
        }
      },
      // The scene is a backdrop provider, not an opaque player surface. An opaque
      // fill here hid the activity's shared glass scene behind the entire music
      // metadata area. Callers that need a fallback surface own that surface.
      content = content,
    )
  }
}
