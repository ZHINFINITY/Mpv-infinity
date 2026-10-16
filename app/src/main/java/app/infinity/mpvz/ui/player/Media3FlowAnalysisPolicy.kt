package app.infinity.mpvz.ui.player

import app.infinity.mpvz.preferences.effectiveMpvFlowMaxDimension

/**
 * Media3's decoder SurfaceTexture route uses a denser motion field to reduce spatial quantization
 * around thin moving contours. The bounded increase affects analysis detail only, not target FPS,
 * output dimensions, or MPV's shared profile.
 */
internal object Media3FlowAnalysisPolicy {
  private const val EXTRA_ANALYSIS_PIXELS = 96

  fun maxDimension(
    targetFps: Int,
    quality: Int,
    maximumTestSettings: Boolean = false,
  ): Int {
    if (maximumTestSettings) return 640
    val sharedDimension = effectiveMpvFlowMaxDimension(targetFps, quality).coerceIn(160, 640)
    return (sharedDimension + EXTRA_ANALYSIS_PIXELS).coerceAtMost(640)
  }
}
