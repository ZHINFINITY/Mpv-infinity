/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPalettePolicyTest {
  @Test fun artworkIsNeverAGlassScene() {
    assertFalse(AudioPalettePolicy.useArtworkAsGlassScene())
  }

  @Test fun identityIsStableAndNullForMissingSource() {
    assertEquals("song.mp3\u0000cover.jpg", AudioPalettePolicy.cacheIdentity("song.mp3", "cover.jpg"))
    assertEquals("song.mp3\u0000", AudioPalettePolicy.cacheIdentity("song.mp3", null))
    assertNull(AudioPalettePolicy.cacheIdentity("", "cover.jpg"))
    assertNull(AudioPalettePolicy.cacheIdentity(null, "cover.jpg"))
  }

  @Test fun generatedSceneContainsOnlyInterpolatedPaletteColors() {
    val rows = AudioPalettePolicy.gradientRows(0xff000000.toInt(), 0xffffffff.toInt(), 3)
    assertEquals(3, rows.size)
    assertEquals(0xff000000.toInt(), rows.first())
    assertEquals(0xff808080.toInt(), rows[1])
    assertEquals(0xffffffff.toInt(), rows.last())
  }

  @Test fun sceneMemoryIsBoundedEvenWithInvalidRequestedDimensions() {
    assertEquals(2, AudioPalettePolicy.gradientRows(0, 0, Int.MIN_VALUE).size)
    assertEquals(64, AudioPalettePolicy.gradientRows(0, 0, Int.MAX_VALUE).size)
    assertEquals(32, AudioPalettePolicy.gradientRows(0, 0).size)
  }
}
