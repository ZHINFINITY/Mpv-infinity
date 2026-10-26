/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms.
 * Uses Kyant0/AndroidLiquidGlass (Apache-2.0) as the lens engine. */
package app.infinity.mpvz.ui.theme.lens

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.theme.LiquidGlassFrame
import app.infinity.mpvz.ui.theme.LocalGlassCustomization
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Feeds the player's ONE existing video sampler (LocalLiquidGlassBackdrop) to the lens engine.
 * No extra capture: it draws the already-captured frame at the consumer's position. The frame's
 * bounds use the same window+screen origin as the pane code, so placement matches exactly.
 */
internal class VideoFrameBackdrop(
  private val frame: State<LiquidGlassFrame?>,
  private val hostView: View,
) : Backdrop {
  override val isCoordinatesDependent: Boolean = true

  override fun DrawScope.drawBackdrop(
    density: Density,
    coordinates: LayoutCoordinates?,
    layerBlock: (GraphicsLayerScope.() -> Unit)?,
  ) {
    val scene = frame.value ?: return
    val c = coordinates ?: return
    if (!c.isAttached) return
    val window = IntArray(2)
    val screen = IntArray(2)
    hostView.getLocationInWindow(window)
    hostView.getLocationOnScreen(screen)
    val p = c.positionInWindow()
    val origin = Offset(p.x + (screen[0] - window[0]), p.y + (screen[1] - window[1]))
    val placement = VideoLensPolicy.placement(scene.boundsInWindow.left, scene.boundsInWindow.top,
      scene.boundsInWindow.width, scene.boundsInWindow.height, origin.x, origin.y) ?: return
    drawImage(
      scene.image,
      srcOffset = IntOffset.Zero,
      srcSize = IntSize(scene.image.width, scene.image.height),
      dstOffset = IntOffset(placement.left, placement.top),
      dstSize = IntSize(placement.width, placement.height),
      filterQuality = FilterQuality.Low,
    )
  }
}

/** Pure numbers for the video lens chips (unit-tested). */
object VideoLensPolicy {
  data class Placement(val left: Int, val top: Int, val width: Int, val height: Int)

  /** Where the captured frame lands in the consumer's local space, or null if unusable. */
  fun placement(frameLeft: Float, frameTop: Float, frameWidth: Float, frameHeight: Float,
                consumerX: Float, consumerY: Float): Placement? {
    val values = floatArrayOf(frameLeft, frameTop, frameWidth, frameHeight, consumerX, consumerY)
    if (values.any { !it.isFinite() } || frameWidth < 1f || frameHeight < 1f) return null
    return Placement(Math.round(frameLeft - consumerX), Math.round(frameTop - consumerY),
      Math.round(frameWidth), Math.round(frameHeight))
  }

  /** Only pill/circle chips use the lens; sheets and panels keep the frosted video path. */
  fun isLensChip(cornerRadiusDp: Float): Boolean = cornerRadiusDp >= 999f

  /**
   * Readability film over the video: brighter video gets a darker film so white icons stay
   * legible, while dark video stays clear (the "clearer, video-tinted" look).
   */
  fun filmAlpha(underlayLuma: Float, opacity: Float): Float {
    val luma = if (underlayLuma.isFinite()) underlayLuma.coerceIn(0f, 1f) else 1f
    val o = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else .5f
    return (0.10f + 0.26f * luma) * (0.6f + 0.4f * o)
  }

  const val BlurDp = 3f
  const val RefractionHeightDp = 8f
  const val RefractionAmountDp = 16f
}

/** Clear, video-tinted lens chip for player buttons and pills. */
@Composable
fun Modifier.videoLensChip(frame: State<LiquidGlassFrame?>, cornerRadiusDp: Float, opacity: Float, highlight: Float): Modifier {
  val view = LocalView.current
  val tuning = LocalGlassCustomization.current
  val backdrop = remember(frame, view) { VideoFrameBackdrop(frame, view) }
  val shape = if (cornerRadiusDp >= 999f) CircleShape else RoundedCornerShape(cornerRadiusDp.dp)
  val rim = highlight.coerceIn(0f, 1f)
  return this.drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
      vibrancy()
      blur((VideoLensPolicy.BlurDp * tuning.frost.coerceIn(0f, 2f)).dp.toPx())
      if (frame.value?.refractionEnabled != false) lens(
        refractionHeight = LensGlassPolicy.refractionHeightPx(VideoLensPolicy.RefractionHeightDp.dp.toPx(), size.minDimension / 2f, tuning.lensDepth),
        refractionAmount = LensGlassPolicy.refractionAmountPx(VideoLensPolicy.RefractionAmountDp.dp.toPx(), size.minDimension, tuning.lensRefraction),
        depthEffect = true,
        chromaticAberration = tuning.colourEdge,
      )
    },
    highlight = { Highlight.Default.copy(alpha = (0.45f + rim).coerceAtMost(1f)) },
    shadow = { Shadow(radius = 6.dp, color = Color.Black.copy(alpha = .14f)) },
    onDrawSurface = {
      drawRect(Color.Black.copy(alpha = VideoLensPolicy.filmAlpha(frame.value?.underlayLuma ?: 1f, opacity)))
    },
  )
}
