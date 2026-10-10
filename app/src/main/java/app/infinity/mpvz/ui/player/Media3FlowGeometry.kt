package app.infinity.mpvz.ui.player

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Reduced dimensions for motion estimation; source/output textures stay at source dimensions. */
internal data class Media3FlowMotionSize(val width: Int, val height: Int)

internal const val MEDIA3_FLOW_GRID_STEP = 6
internal const val MEDIA3_FLOW_BLOCK_SIZE = 8
/** Motion-search radius in reduced-image pixels; retained from the smooth 1fda64fe baseline. */
internal const val MEDIA3_FLOW_SEARCH_RADIUS = 4
internal const val MEDIA3_FLOW_SUBPIXEL_MIN_CURVATURE = 0.00001f

internal data class Media3FlowGridSize(val width: Int, val height: Int)

internal data class Media3FlowBlitGeometry(
  val viewportX: Int,
  val viewportY: Int,
  val viewportWidth: Int,
  val viewportHeight: Int,
  val textureScaleX: Float,
  val textureScaleY: Float,
  val textureOffsetX: Float,
  val textureOffsetY: Float,
)

internal object Media3FlowGeometry {
  fun motionSize(sourceWidth: Int, sourceHeight: Int, maxDimension: Int): Media3FlowMotionSize {
    require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
    require(maxDimension > 0) { "Motion dimension cap must be positive" }
    val scale = min(1f, maxDimension.toFloat() / max(sourceWidth, sourceHeight).toFloat())
    return Media3FlowMotionSize(
      width = max(16, (sourceWidth * scale).roundToInt()),
      height = max(16, (sourceHeight * scale).roundToInt()),
    )
  }

  fun motionGridSize(processingWidth: Int, processingHeight: Int): Media3FlowGridSize {
    require(processingWidth >= MEDIA3_FLOW_BLOCK_SIZE && processingHeight >= MEDIA3_FLOW_BLOCK_SIZE) {
      "Processing dimensions must fit a motion block"
    }
    return Media3FlowGridSize(
      width = (processingWidth - MEDIA3_FLOW_BLOCK_SIZE) / MEDIA3_FLOW_GRID_STEP + 1,
      height = (processingHeight - MEDIA3_FLOW_BLOCK_SIZE) / MEDIA3_FLOW_GRID_STEP + 1,
    )
  }

  /** Block-matching vectors describe the center of each sampled patch, not its top-left corner. */
  fun motionGridAnchorOffset(): Float = MEDIA3_FLOW_BLOCK_SIZE / 2f

  /** Fits a parabola through three SAD costs and returns a stable half-pixel correction. */
  fun parabolicSubpixelOffset(minusCost: Float, centerCost: Float, plusCost: Float): Float {
    if (!minusCost.isFinite() || !centerCost.isFinite() || !plusCost.isFinite() ||
      centerCost > minusCost || centerCost > plusCost
    ) return 0f
    val curvature = minusCost - 2f * centerCost + plusCost
    if (curvature <= MEDIA3_FLOW_SUBPIXEL_MIN_CURVATURE) return 0f
    return (0.5f * (minusCost - plusCost) / curvature).coerceIn(-0.5f, 0.5f)
  }

  /** Geometry for the custom sink's visible blit; Crop adjusts UVs, Fit letterboxes, Stretch fills. */
  fun blitGeometry(
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    aspect: VideoAspect,
  ): Media3FlowBlitGeometry {
    require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
    require(outputWidth > 0 && outputHeight > 0) { "Output dimensions must be positive" }
    val sourceAspect = sourceWidth.toDouble() / sourceHeight
    val outputAspect = outputWidth.toDouble() / outputHeight

    if (aspect == VideoAspect.Fit) {
      val viewportWidth: Int
      val viewportHeight: Int
      if (sourceAspect > outputAspect) {
        viewportWidth = outputWidth
        viewportHeight = (outputWidth / sourceAspect).roundToInt().coerceIn(1, outputHeight)
      } else {
        viewportWidth = (outputHeight * sourceAspect).roundToInt().coerceIn(1, outputWidth)
        viewportHeight = outputHeight
      }
      return Media3FlowBlitGeometry(
        viewportX = (outputWidth - viewportWidth) / 2,
        viewportY = (outputHeight - viewportHeight) / 2,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        textureScaleX = 1f,
        textureScaleY = 1f,
        textureOffsetX = 0f,
        textureOffsetY = 0f,
      )
    }

    var textureScaleX = 1f
    var textureScaleY = 1f
    when (aspect) {
      VideoAspect.Crop -> {
        if (sourceAspect > outputAspect) {
          textureScaleX = (outputAspect / sourceAspect).toFloat()
        } else if (sourceAspect < outputAspect) {
          textureScaleY = (sourceAspect / outputAspect).toFloat()
        }
      }
      VideoAspect.Stretch -> Unit
      VideoAspect.Fit -> error("Fit viewport geometry should have been returned above")
    }
    return Media3FlowBlitGeometry(
      viewportX = 0,
      viewportY = 0,
      viewportWidth = outputWidth,
      viewportHeight = outputHeight,
      textureScaleX = textureScaleX,
      textureScaleY = textureScaleY,
      textureOffsetX = (1f - textureScaleX) / 2f,
      textureOffsetY = (1f - textureScaleY) / 2f,
    )
  }
}
