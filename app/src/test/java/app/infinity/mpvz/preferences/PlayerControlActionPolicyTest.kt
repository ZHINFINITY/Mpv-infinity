/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlActionPolicyTest {
  @Test
  fun enabledInterpolationCanAlwaysBeSwitchedOff() {
    assertTrue(
      PlayerControlActionPolicy.interpolationAvailable(
        currentlyEnabled = true,
        vulkanAvailable = false,
        nativeEngine = false,
        mpvRuntimeAvailable = false,
        configOwned = true,
        anime4kActive = true,
      ),
    )
  }

  @Test
  fun mpvShortcutRequiresVulkanAndRuntimeWhenOff() {
    assertTrue(
      PlayerControlActionPolicy.interpolationAvailable(
        currentlyEnabled = false,
        vulkanAvailable = true,
        nativeEngine = false,
        mpvRuntimeAvailable = true,
        configOwned = false,
        anime4kActive = false,
      ),
    )
    assertFalse(
      PlayerControlActionPolicy.interpolationAvailable(
        currentlyEnabled = false,
        vulkanAvailable = true,
        nativeEngine = false,
        mpvRuntimeAvailable = true,
        configOwned = true,
        anime4kActive = false,
      ),
    )
  }
}
