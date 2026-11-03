package app.infinity.mpvz.ui.theme

import org.junit.Assert.*
import org.junit.Test

class GlassFrostPolicyTest {
  @Test fun solidColorsAndAlphaArePreserved() {
    for (color in intArrayOf(0xff112233.toInt(), 0xff000000.toInt(), 0xffffffff.toInt(), 0x80336699.toInt())) {
      assertArrayEquals(IntArray(11 * 9) { color }, GlassFrostPolicy.frostArgb(IntArray(11 * 9) { color }, 11, 9))
    }
  }

  @Test fun highContrastEdgeBecomesContinuousFrostRatherThanBlocks() {
    val source = IntArray(31 * 7) { if (it % 31 < 15) 0xff000000.toInt() else 0xffffffff.toInt() }
    val output = GlassFrostPolicy.frostArgb(source, 31, 7)
    val row = (0 until 31).map { output[3 * 31 + it] and 255 }
    assertTrue(row.zipWithNext().all { (left, right) -> left <= right })
    assertTrue(row.filter { it in 1..254 }.distinct().size >= 8)
    assertEquals(0, row.first())
    assertEquals(255, row.last())
    assertTrue(output.all { it ushr 24 == 255 })
  }

  @Test fun highFrequencyCheckerboardLosesItsSharpGrid() {
    val source = IntArray(31 * 31) { if ((it / 31 + it % 31) % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt() }
    val output = GlassFrostPolicy.frostArgb(source, 31, 31)
    val middle = (10..20).flatMap { y -> (10..20).map { x -> output[y * 31 + x] and 255 } }
    assertTrue(middle.all { it in 120..136 })
  }

  @Test fun filteringDoesNotMutatePublishedOrCallerOwnedPixels() {
    val original = IntArray(19 * 17) { 0xff000000.toInt() or (it * 12345 and 0x00ffffff) }
    val reference = original.copyOf()
    val result = GlassFrostPolicy.frostArgb(original, 19, 17)
    assertArrayEquals(reference, original)
    assertNotSame(original, result)
  }

  @Test fun reusableFrostBuffersMatchThePreviousPixelMathExactly() {
    val dimensions = listOf(1 to 1, 1 to 17, 31 to 31, 192 to 7)
    val strengths = listOf(0f, 0.2f, 0.5f, 1f, 1.5f, 2f)
    dimensions.forEach { (width, height) ->
      val source = IntArray(width * height) { index ->
        0xff000000.toInt() or ((index * 0x45d9f3b) and 0x00ffffff)
      }
      val unchanged = source.copyOf()
      val output = IntArray(source.size)
      val scratch = IntArray(source.size)
      strengths.forEach { strength ->
        val actual = GlassFrostPolicy.frostArgbInto(source, width, height, output, scratch, strength)
        assertSame(output, actual)
        assertArrayEquals(referenceFrostArgb(source, width, height, strength), actual)
        assertArrayEquals(unchanged, source)
      }
    }
  }

  @Test fun thinAndSinglePixelFramesRemainValid() {
    val color = 0xff123456.toInt()
    assertArrayEquals(intArrayOf(color), GlassFrostPolicy.frostArgb(intArrayOf(color), 1, 1))
    assertArrayEquals(IntArray(19) { color }, GlassFrostPolicy.frostArgb(IntArray(19) { color }, 1, 19))
    assertArrayEquals(IntArray(19) { color }, GlassFrostPolicy.frostArgb(IntArray(19) { color }, 19, 1))
  }

  @Test fun identicalRowsStayIdenticalAndDoNotWrapAcrossEdges() {
    val row = IntArray(31) { if (it < 15) 0xff000000.toInt() else 0xffffffff.toInt() }
    val source = IntArray(31 * 13) { row[it % 31] }
    val result = GlassFrostPolicy.frostArgb(source, 31, 13)
    for (y in 1 until 13) assertArrayEquals(result.copyOfRange(0, 31), result.copyOfRange(y * 31, (y + 1) * 31))
    assertEquals(0xff000000.toInt(), result.first())
    assertEquals(0xffffffff.toInt(), result.last())
  }

  @Test fun workCannotAccidentallyScaleToFullResolutionVideo() {
    assertThrows(IllegalArgumentException::class.java) { GlassFrostPolicy.frostArgb(IntArray(193), 193, 1) }
    assertThrows(IllegalArgumentException::class.java) { GlassFrostPolicy.frostArgb(IntArray(193), 1, 193) }
    assertThrows(IllegalArgumentException::class.java) { GlassFrostPolicy.frostArgb(IntArray(0), 0, 0) }
    assertThrows(IllegalArgumentException::class.java) { GlassFrostPolicy.frostArgb(IntArray(3), 2, 2) }
    val edge = GlassSamplingPolicy.MaxSampleEdge
    assertEquals(edge * edge, GlassFrostPolicy.frostArgb(IntArray(edge * edge), edge, edge).size)
  }

  private fun referenceFrostArgb(pixels: IntArray, width: Int, height: Int, strength: Float): IntArray {
    val result = pixels.copyOf()
    val scratch = IntArray(pixels.size)
    repeat(GlassCustomizationPolicy.frostPasses(strength)) {
      referenceBoxPass(result, scratch, width, height, horizontal = true)
      referenceBoxPass(scratch, result, width, height, horizontal = false)
    }
    return result
  }

  private fun referenceBoxPass(input: IntArray, output: IntArray, width: Int, height: Int, horizontal: Boolean) {
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val step = if (horizontal) 1 else width
    val window = GlassFrostPolicy.Radius * 2 + 1
    for (line in 0 until lines) {
      val base = if (horizontal) line * width else line
      var alpha = 0
      var red = 0
      var green = 0
      var blue = 0
      for (offset in -GlassFrostPolicy.Radius..GlassFrostPolicy.Radius) {
        val pixel = input[base + offset.coerceIn(0, length - 1) * step]
        alpha += pixel ushr 24
        red += (pixel ushr 16) and 255
        green += (pixel ushr 8) and 255
        blue += pixel and 255
      }
      for (position in 0 until length) {
        output[base + position * step] = (((alpha + window / 2) / window) shl 24) or
          (((red + window / 2) / window) shl 16) or
          (((green + window / 2) / window) shl 8) or ((blue + window / 2) / window)
        val leaving = input[base + (position - GlassFrostPolicy.Radius).coerceIn(0, length - 1) * step]
        val entering = input[base + (position + GlassFrostPolicy.Radius + 1).coerceIn(0, length - 1) * step]
        alpha += (entering ushr 24) - (leaving ushr 24)
        red += ((entering ushr 16) and 255) - ((leaving ushr 16) and 255)
        green += ((entering ushr 8) and 255) - ((leaving ushr 8) and 255)
        blue += (entering and 255) - (leaving and 255)
      }
    }
  }
}
