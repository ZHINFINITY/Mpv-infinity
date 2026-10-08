package app.infinity.mpvz.ui.player

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Reduced dimensions for motion estimation; source/output textures stay at source dimensions. */
internal data class Media3FlowMotionSize(val width: Int, val height: Int)

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
}
