/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFlipDecoderPolicyTest {
  @Test
  fun onlyActiveMpvHwPlusRequiresSwitchingDecoderForFlip() {
    assertTrue(VideoFlipDecoderPolicy.requiresHwOrSwDecoder(PlaybackEngineMode.MPV, "mediacodec"))
    assertTrue(VideoFlipDecoderPolicy.requiresHwOrSwDecoder(PlaybackEngineMode.MPV, " MediaCodec "))
    assertFalse(VideoFlipDecoderPolicy.requiresHwOrSwDecoder(PlaybackEngineMode.MPV, "mediacodec-copy"))
    assertFalse(VideoFlipDecoderPolicy.requiresHwOrSwDecoder(PlaybackEngineMode.MPV, "no"))
    assertFalse(VideoFlipDecoderPolicy.requiresHwOrSwDecoder(PlaybackEngineMode.MPV, "auto"))
    assertFalse(VideoFlipDecoderPolicy.requiresHwOrSwDecoder(PlaybackEngineMode.NATIVE, "mediacodec"))
    assertFalse(VideoFlipDecoderPolicy.requiresHwOrSwDecoder(PlaybackEngineMode.MPV, null))
  }
}
