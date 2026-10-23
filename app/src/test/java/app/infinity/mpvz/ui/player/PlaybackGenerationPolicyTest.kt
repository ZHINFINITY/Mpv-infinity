/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackGenerationPolicyTest {
  @Test fun coreRetirementAndReplacementLoadNeverReuseAnOldToken() {
    val firstLoad = PlaybackGenerationPolicy.next(0L)
    val retiredCore = PlaybackGenerationPolicy.next(firstLoad)
    val replacementLoad = PlaybackGenerationPolicy.next(retiredCore)
    assertTrue(retiredCore > firstLoad)
    assertTrue(replacementLoad > retiredCore)
    assertNotEquals(firstLoad, replacementLoad)
  }
  @Test fun repeatedStopsAndCoreRestartsStayMonotonic() {
    var generation = 0L
    val seen = mutableSetOf<Long>()
    repeat(1000) {
      generation = PlaybackGenerationPolicy.next(generation)
      assertTrue(seen.add(generation))
    }
    assertEquals(1000, seen.size)
  }
}
