/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

/** Three separable box passes approximate a smooth Gaussian on the tiny, unpublished UI copy.
 * Work and memory are bounded by the existing sample cap, never by decoded video resolution.
 * The input is immutable to callers; neither the SurfaceView nor a displayed snapshot is changed.
 */
object GlassFrostPolicy {
  const val Radius = 2
  const val Passes = 3
  private const val Window = Radius * 2 + 1

  fun frostArgb(pixels: IntArray, width: Int, height: Int): IntArray {
    require(width in 1..GlassSamplingPolicy.MaxSampleEdge && height in 1..GlassSamplingPolicy.MaxSampleEdge)
    require(pixels.size == width * height)
    val result = pixels.copyOf()
    val scratch = IntArray(pixels.size)
    repeat(Passes) {
      boxPass(result, scratch, width, height, horizontal = true)
      boxPass(scratch, result, width, height, horizontal = false)
    }
    return result
  }

  private fun boxPass(input: IntArray, output: IntArray, width: Int, height: Int, horizontal: Boolean) {
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val step = if (horizontal) 1 else width
    for (line in 0 until lines) {
      val base = if (horizontal) line * width else line
      var alpha = 0
      var red = 0
      var green = 0
      var blue = 0
      for (offset in -Radius..Radius) {
        val pixel = input[base + offset.coerceIn(0, length - 1) * step]
        alpha += pixel ushr 24
        red += (pixel ushr 16) and 255
        green += (pixel ushr 8) and 255
        blue += pixel and 255
      }
      for (position in 0 until length) {
        output[base + position * step] = (((alpha + Window / 2) / Window) shl 24) or
          (((red + Window / 2) / Window) shl 16) or
          (((green + Window / 2) / Window) shl 8) or ((blue + Window / 2) / Window)
        val leaving = input[base + (position - Radius).coerceIn(0, length - 1) * step]
        val entering = input[base + (position + Radius + 1).coerceIn(0, length - 1) * step]
        alpha += (entering ushr 24) - (leaving ushr 24)
        red += ((entering ushr 16) and 255) - ((leaving ushr 16) and 255)
        green += ((entering ushr 8) and 255) - ((leaving ushr 8) and 255)
        blue += (entering and 255) - (leaving and 255)
      }
    }
  }
}
