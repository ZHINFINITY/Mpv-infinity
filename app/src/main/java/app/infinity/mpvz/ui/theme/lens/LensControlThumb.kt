/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms.
 *
 * The touched-lens behaviour (a solid thumb that turns into a clear magnifying lens while
 * pressed, sampling its own track drawn as a sibling layer) follows the slider/toggle samples
 * of Kyant0/AndroidLiquidGlass (Apache-2.0), adapted to this app's controls. */
package app.infinity.mpvz.ui.theme.lens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/** Pure numbers for the touched lens, kept here so they are unit-testable. */
object LensControlPolicy {
  /** How much the thumb grows while touched (1 = rest size). */
  const val PressedScale = 1.45f
  /** How much the track under the lens is magnified while touched. */
  const val TrackMagnification = 1.22f
  const val RestBlurDp = 6f
  const val PressedRefractionHeightDp = 10f
  const val PressedRefractionAmountDp = 14f

  private fun p(progress: Float) = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
  private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * p(t)

  fun thumbScale(progress: Float): Float = lerp(1f, PressedScale, progress)
  fun trackMagnification(progress: Float): Float = lerp(1f, TrackMagnification, progress)
  /** The solid rest surface fades out while the lens takes over. */
  fun surfaceAlpha(progress: Float): Float = 1f - p(progress)
  fun blurDp(progress: Float): Float = RestBlurDp * (1f - p(progress))
  fun shadowAlpha(progress: Float): Float = 0.10f + 0.14f * p(progress)
}

/**
 * Draws a control thumb that is a solid [restColor] capsule at rest and becomes a clear lens
 * magnifying [track] while [progress] goes to 1. [track] must be recorded by a SIBLING of the
 * thumb (never an ancestor), so the thumb never samples itself.
 */
@Composable
fun Modifier.lensControlThumb(
  track: LayerBackdrop,
  shape: Shape,
  restColor: Color,
  progress: () -> Float,
): Modifier {
  val tuning = app.infinity.mpvz.ui.theme.LocalGlassCustomization.current
  val onDraw: DrawScope.(DrawScope.() -> Unit) -> Unit = remember(progress) {
    { drawTrack ->
      val m = LensControlPolicy.trackMagnification(progress())
      scale(m, m) { drawTrack() }
    }
  }
  val magnified = rememberBackdrop(track, onDraw)
  return this.drawBackdrop(
    backdrop = magnified,
    shape = { shape },
    effects = {
      val t = progress()
      blur(LensControlPolicy.blurDp(t).dp.toPx())
      lens(
        refractionHeight = LensGlassPolicy.refractionHeightPx(
          (LensControlPolicy.PressedRefractionHeightDp * t).dp.toPx(), size.minDimension / 2f, tuning.lensDepth),
        refractionAmount = LensGlassPolicy.refractionAmountPx(
          (LensControlPolicy.PressedRefractionAmountDp * t).dp.toPx(), size.minDimension, tuning.lensRefraction),
        depthEffect = true,
        chromaticAberration = tuning.colourEdge,
      )
    },
    highlight = { Highlight.Ambient.copy(alpha = progress().coerceIn(0f, 1f)) },
    shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = LensControlPolicy.shadowAlpha(progress()))) },
    innerShadow = { val t = progress().coerceIn(0f, 1f); InnerShadow(radius = 4.dp * t, alpha = t) },
    layerBlock = {
      val s = LensControlPolicy.thumbScale(progress())
      scaleX = s
      scaleY = s
    },
    onDrawSurface = { drawRect(restColor.copy(alpha = restColor.alpha * LensControlPolicy.surfaceAlpha(progress()))) },
  )
}
