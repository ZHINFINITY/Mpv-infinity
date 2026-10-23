/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlActionPolicyTest {
  private val actions = setOf("CAST", "DECODER", "NONE", "FRAME_INTERPOLATION", "FRAME_INTERPOLATION_FPS")
  private fun parse(csv: String, used: MutableSet<String> = mutableSetOf()) =
    PlayerControlActionPolicy.parse(csv, used, "NONE") { it.takeIf(actions::contains) }

  @Test fun bothNewActionsRoundTripInOrderWithExistingActions() {
    val order = listOf("CAST", "FRAME_INTERPOLATION", "DECODER", "FRAME_INTERPOLATION_FPS")
    assertEquals(order, parse(order.joinToString(",")))
  }
  @Test fun legacyLayoutsAreUnchangedAndNoNewActionsAreForced() {
    assertEquals(listOf("CAST", "DECODER"), parse("CAST,DECODER"))
  }
  @Test fun duplicatesPlaceholdersUnknownAndBlankEntriesAreFiltered() {
    assertEquals(listOf("FRAME_INTERPOLATION", "FRAME_INTERPOLATION_FPS"),
      parse(" none, unknown, frame_interpolation, ,FRAME_INTERPOLATION,FRAME_INTERPOLATION_FPS"))
  }
  @Test fun sharedUsedSetPreventsDuplicatesAcrossControlRows() {
    val used = mutableSetOf<String>()
    assertEquals(listOf("FRAME_INTERPOLATION"), parse("FRAME_INTERPOLATION", used))
    assertEquals(listOf("FRAME_INTERPOLATION_FPS"), parse("FRAME_INTERPOLATION,FRAME_INTERPOLATION_FPS", used))
  }
  @Test fun existingEnabledInterpolationCanAlwaysBeTurnedOff() {
    assertTrue(available(enabled = true, vulkan = false, runtime = false, config = true, anime = true))
  }
  @Test fun mpvRequiresVulkanRuntimeAndUnownedCompatibleOptions() {
    assertTrue(available())
    assertFalse(available(vulkan = false))
    assertFalse(available(runtime = false))
    assertFalse(available(config = true))
    assertFalse(available(anime = true))
  }
  @Test fun nativeUsesItsOwnVulkanPathWithoutMpvOnlyRestrictions() {
    assertTrue(available(native = true, runtime = false, config = true, anime = true))
    assertFalse(available(native = true, vulkan = false))
  }
  private fun available(enabled: Boolean = false, vulkan: Boolean = true, native: Boolean = false,
    runtime: Boolean = true, config: Boolean = false, anime: Boolean = false) =
    PlayerControlActionPolicy.interpolationAvailable(enabled, vulkan, native, runtime, config, anime)
}
