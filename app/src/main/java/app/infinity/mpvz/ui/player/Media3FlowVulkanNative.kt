package app.infinity.mpvz.ui.player

import android.hardware.HardwareBuffer

/** JNI access to the same libplacebo-backed Vulkan motion pipeline used by MPV Flow. */
internal object Media3FlowVulkanNative {
  init {
    // The selected mpv AAR exports the exact libplacebo runtime linked by the JNI library.
    System.loadLibrary("mpv")
    System.loadLibrary("media3flow_vulkan")
  }

  external fun nativeCreateContext(maxDimension: Int, requestedComputeApi: Int): Long

  /** Returns the currently effective Flow compute backend, including Auto fallback. */
  external fun nativeGetComputeApiName(context: Long): String?

  /** Returns the Vulkan driver's required Android buffer usage for the selected image role. */
  external fun nativeGetRequiredHardwareBufferUsage(context: Long, output: Boolean): Long

  external fun nativeCreateFrameImage(
    context: Long,
    buffer: HardwareBuffer,
    width: Int,
    height: Int,
    output: Boolean,
  ): Long

  /** Must be called on the GL thread with this sink's EGL context current. */
  external fun nativeCreateEglTexture(buffer: HardwareBuffer): Int

  external fun nativePrepareFrame(context: Long, image: Long): Boolean

  /** Must be called on the GL thread with this sink's EGL context current. */
  external fun nativeHoldImageForExternal(context: Long, image: Long): Boolean

  external fun nativeAnalyzePair(context: Long, frame0: Long, frame1: Long): Long

  external fun nativeSynthesize(
    context: Long,
    pair: Long,
    source0: Long,
    source1: Long,
    timestep: Float,
    output: Long,
  ): Boolean

  /** Returns Vulkan timestamp counters only; libplacebo polls with zero timeout and never waits. */
  external fun nativeGetVulkanTimingStats(context: Long): LongArray?

  external fun nativeReleasePair(context: Long, pair: Long)

  external fun nativeDestroyImage(context: Long, image: Long)

  external fun nativeDestroyContext(context: Long)
}
