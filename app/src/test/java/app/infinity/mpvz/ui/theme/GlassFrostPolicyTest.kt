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
}
