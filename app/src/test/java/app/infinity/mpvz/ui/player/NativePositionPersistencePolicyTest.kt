/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePositionPersistencePolicyTest {
  @Test
  fun preparingPlaceholderZeroIsUnknown() {
    assertFalse(
      NativePositionPersistencePolicy.isPositionKnown(
        hasCurrentMediaItem = true,
        isReady = false,
        hasEstablishedPlaybackPosition = false,
      ),
    )
  }

  @Test
  fun readyPositionZeroIsKnown() {
    assertTrue(
      NativePositionPersistencePolicy.isPositionKnown(
        hasCurrentMediaItem = true,
        isReady = true,
        hasEstablishedPlaybackPosition = false,
      ),
    )
  }

  @Test
  fun bufferingAfterReadyRetainsKnownPosition() {
    assertTrue(
      NativePositionPersistencePolicy.isPositionKnown(
        hasCurrentMediaItem = true,
        isReady = false,
        hasEstablishedPlaybackPosition = true,
      ),
    )
  }

  @Test
  fun clearedMediaIsUnknownEvenIfPositionWasEstablished() {
    assertFalse(
      NativePositionPersistencePolicy.isPositionKnown(
        hasCurrentMediaItem = false,
        isReady = false,
        hasEstablishedPlaybackPosition = true,
      ),
    )
  }

  @Test
  fun stableNearEndPositionRemainsKnown() {
    assertTrue(
      NativePositionPersistencePolicy.isPositionKnown(
        hasCurrentMediaItem = true,
        isReady = false,
        hasEstablishedPlaybackPosition = true,
      ),
    )
  }
}
