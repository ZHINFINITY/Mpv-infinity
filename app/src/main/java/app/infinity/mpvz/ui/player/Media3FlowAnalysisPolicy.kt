package app.infinity.mpvz.ui.player

import app.infinity.mpvz.preferences.effectiveMpvFlowMaxDimension

/**
 * Media3's decoder SurfaceTexture route uses a denser motion field to reduce spatial quantization
 * around thin moving contours. The bounded increase affects analysis detail only, not target FPS,
 * output dimensions, or MPV's shared profile.
 */
internal object Media3FlowAnalysisPolicy {
  private const val EXTRA_ANALYSIS_PIXELS = 96

  fun maxDimension(targetFps: Int, quality: Int): Int {
    val sharedDimension = effectiveMpvFlowMaxDimension(targetFps, quality).coerceIn(160, 640)
    return (sharedDimension + EXTRA_ANALYSIS_PIXELS).coerceAtMost(640)
  }

  /** Uses the same target-rate/quality motion-search profile as MPV Flow. */
  fun searchRadius(targetFps: Int, quality: Int): Int =
    app.infinity.mpvz.preferences.effectiveMpvFlowSearchRadius(targetFps, quality)
}
