/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowSharedWriteFailurePolicyTest {
  @Test fun repeatedWriteFailuresFallBackPerContextAndSize() {
    val policy = Media3FlowSharedWriteFailurePolicy(failureLimit = 3)
    assertFalse(policy.shouldUseStaging(1L, 1920, 1080))
    repeat(2) { policy.recordFailure(1L, 1920, 1080) }
    assertFalse(policy.shouldUseStaging(1L, 1920, 1080))
    policy.recordFailure(1L, 1920, 1080)
    assertTrue(policy.shouldUseStaging(1L, 1920, 1080))
    assertFalse(policy.shouldUseStaging(1L, 1280, 720))
    policy.recordFailure(1L, 1280, 720)
    assertFalse(policy.shouldUseStaging(2L, 1280, 720))
  }

  @Test fun successfulSharedWriteResetsOnlyItsKey() {
    val policy = Media3FlowSharedWriteFailurePolicy(failureLimit = 2)
    repeat(2) { policy.recordFailure(9L, 640, 360) }
    assertTrue(policy.shouldUseStaging(9L, 640, 360))
    policy.recordSuccess(9L, 640, 360)
    assertFalse(policy.shouldUseStaging(9L, 640, 360))
  }
}
