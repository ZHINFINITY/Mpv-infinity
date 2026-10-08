package app.infinity.mpvz.ui.player

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Reduced dimensions for motion estimation; source/output textures stay at source dimensions. */
internal data class Media3FlowMotionSize(val width: Int, val height: Int)

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
