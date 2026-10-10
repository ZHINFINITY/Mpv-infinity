package app.infinity.mpvz.ui.player

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Reduced dimensions for motion estimation; source/output textures stay at source dimensions. */
internal data class Media3FlowMotionSize(val width: Int, val height: Int)

internal const val MEDIA3_FLOW_GRID_STEP = 6
internal const val MEDIA3_FLOW_BLOCK_SIZE = 8
internal const val MEDIA3_FLOW_COARSE_BLOCK_SIZE = 4
internal const val MEDIA3_FLOW_COARSE_GRID_STEP = 3
/** Each pyramid level samples candidates every two pixels before a full-resolution local refinement. */
internal const val MEDIA3_FLOW_SEARCH_CANDIDATE_STEP = 2
/** Coarse search radius is measured at half resolution; fine search is centered on scaled coarse hypotheses. */
internal const val MEDIA3_FLOW_COARSE_SEARCH_RADIUS = 4
internal const val MEDIA3_FLOW_FINE_SEARCH_RADIUS = 2
internal const val MEDIA3_FLOW_FINE_PRIOR_SCALE = 2f
internal const val MEDIA3_FLOW_QUARTER_BLOCK_SIZE = 8
internal const val MEDIA3_FLOW_QUARTER_GRID_STEP = 6
internal const val MEDIA3_FLOW_QUARTER_SEARCH_RADIUS = 4
internal const val MEDIA3_FLOW_MID_PRIOR_CELL_SCALE = 0.25f
internal const val MEDIA3_FLOW_FINE_PRIOR_CELL_SCALE = 1f
internal const val MEDIA3_FLOW_SUBPIXEL_MIN_CURVATURE = 0.00001f
internal const val MEDIA3_FLOW_VISIBILITY_CONFIDENCE_START = 0.15f
internal const val MEDIA3_FLOW_VISIBILITY_CONFIDENCE_END = 0.45f

internal data class Media3FlowGridSize(val width: Int, val height: Int)

internal data class Media3FlowVector(val x: Float, val y: Float)

internal data class Media3FlowGuideSample(
  val vector: Media3FlowVector,
  val guideValue: Float,
  val spatialWeight: Float,
)

internal data class Media3FlowEndpointOffsets(
  val frame0: Media3FlowVector,
  val frame1: Media3FlowVector,
)

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

  fun motionGridSize(
    processingWidth: Int,
    processingHeight: Int,
    blockSize: Int = MEDIA3_FLOW_BLOCK_SIZE,
    gridStep: Int = MEDIA3_FLOW_GRID_STEP,
  ): Media3FlowGridSize {
    require(blockSize > 0 && gridStep > 0) { "Motion block and grid step must be positive" }
    require(processingWidth >= blockSize && processingHeight >= blockSize) {
      "Processing dimensions must fit a motion block"
    }
    return Media3FlowGridSize(
      width = (processingWidth - blockSize) / gridStep + 1,
      height = (processingHeight - blockSize) / gridStep + 1,
    )
  }

  /** A 2× box-reduced luma level; odd dimensions round up so every source pixel contributes. */
  fun coarseMotionSize(processingWidth: Int, processingHeight: Int): Media3FlowMotionSize {
    require(processingWidth > 0 && processingHeight > 0) { "Processing dimensions must be positive" }
    return Media3FlowMotionSize((processingWidth + 1) / 2, (processingHeight + 1) / 2)
  }

  /** A 4× box-reduced level for wider-range motion hypotheses. */
  fun quarterMotionSize(processingWidth: Int, processingHeight: Int): Media3FlowMotionSize {
    require(processingWidth > 0 && processingHeight > 0) { "Processing dimensions must be positive" }
    return Media3FlowMotionSize((processingWidth + 3) / 4, (processingHeight + 3) / 4)
  }

  /** Child cell index projected into a parent grid after the 2× pyramid scale change. */
  fun priorCellScale(childGridStep: Int, parentGridStep: Int): Float {
    require(childGridStep > 0 && parentGridStep > 0) { "Grid steps must be positive" }
    return childGridStep / (2f * parentGridStep)
  }

  /** Block-matching vectors describe the center of each sampled patch, not its top-left corner. */
  fun motionGridAnchorOffset(): Float = MEDIA3_FLOW_BLOCK_SIZE / 2f

  /** Fits a parabola through three neighboring SAD costs; unstable or non-minimum fits stay integer. */
  fun parabolicSubpixelOffset(minusCost: Float, centerCost: Float, plusCost: Float): Float {
    if (!minusCost.isFinite() || !centerCost.isFinite() || !plusCost.isFinite() ||
      centerCost > minusCost || centerCost > plusCost
    ) return 0f
    val curvature = minusCost - 2f * centerCost + plusCost
    if (curvature <= MEDIA3_FLOW_SUBPIXEL_MIN_CURVATURE) return 0f
    return (0.5f * (minusCost - plusCost) / curvature).coerceIn(-0.5f, 0.5f)
  }

  /**
   * Reference for the synthesis shader's local-variance-normalized, source-luma-guided blend.
   * Spatial bilinear weights are retained within one appearance layer and suppressed across an
   * image edge, so independent foreground/background vectors are not averaged into a third flow.
   */
  fun edgeAwareVectorSample(queryGuide: Float, candidates: List<Media3FlowGuideSample>): Media3FlowVector {
    require(candidates.isNotEmpty()) { "At least one flow candidate is required" }
    require(queryGuide.isFinite() && candidates.all {
      it.guideValue.isFinite() && it.spatialWeight.isFinite() && it.spatialWeight >= 0f
    }) { "Flow guide samples must be finite and have non-negative spatial weights" }

    val spatialTotal = candidates.sumOf { it.spatialWeight.toDouble() }.toFloat()
    if (spatialTotal <= 0f) return candidates.first().vector
    val squaredDifferences = candidates.map { sample ->
      val difference = sample.guideValue - queryGuide
      difference * difference
    }
    val variance = candidates.indices.sumOf { index ->
      (candidates[index].spatialWeight / spatialTotal * squaredDifferences[index]).toDouble()
    }.toFloat()
    val normalizedVariance = max(variance, 0.000001f)
    val weights = candidates.indices.map { index ->
      candidates[index].spatialWeight *
        exp((-squaredDifferences[index] / normalizedVariance).toDouble()).toFloat()
    }
    val weightTotal = weights.sumOf { it.toDouble() }.toFloat()
    if (weightTotal <= 0f) return candidates.first().vector
    return Media3FlowVector(
      x = candidates.indices.sumOf { (candidates[it].vector.x * weights[it]).toDouble() }.toFloat() / weightTotal,
      y = candidates.indices.sumOf { (candidates[it].vector.y * weights[it]).toDouble() }.toFloat() / weightTotal,
    )
  }

  /** Returns the visibility-weighted source-1 mix, or null when neither warp is trustworthy. */
  fun sourceVisibilityBlendAlpha(alpha: Float, confidence0: Float, confidence1: Float): Float? {
    if (!alpha.isFinite() || !confidence0.isFinite() || !confidence1.isFinite()) return null
    val t = alpha.coerceIn(0f, 1f)
    val visible0 = smoothstep(
      MEDIA3_FLOW_VISIBILITY_CONFIDENCE_START,
      MEDIA3_FLOW_VISIBILITY_CONFIDENCE_END,
      confidence0,
    )
    val visible1 = smoothstep(
      MEDIA3_FLOW_VISIBILITY_CONFIDENCE_START,
      MEDIA3_FLOW_VISIBILITY_CONFIDENCE_END,
      confidence1,
    )
    val weight0 = (1f - t) * visible0
    val weight1 = t * visible1
    val total = weight0 + weight1
    if (total <= 0.0001f) return null
    return (weight1 / total).coerceIn(0f, 1f)
  }

  private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  /** Initial target-to-endpoint displacements from forward/backward endpoint flow fields. */
  fun targetTimeEndpointOffsets(
    alpha: Float,
    forward: Media3FlowVector,
    backward: Media3FlowVector,
  ): Media3FlowEndpointOffsets {
    val t = alpha.coerceIn(0f, 1f)
    val oneMinusT = 1f - t
    return Media3FlowEndpointOffsets(
      frame0 = Media3FlowVector(
        x = -oneMinusT * t * forward.x + t * t * backward.x,
        y = -oneMinusT * t * forward.y + t * t * backward.y,
      ),
      frame1 = Media3FlowVector(
        x = oneMinusT * oneMinusT * forward.x - t * oneMinusT * backward.x,
        y = oneMinusT * oneMinusT * forward.y - t * oneMinusT * backward.y,
      ),
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
