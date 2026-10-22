/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.*
import org.junit.Test

class MpvRestartLoadFencePolicyTest {
  @Test fun matchingLoadedGenerationAndSourceAreAccepted() {
    assertTrue(MpvRestartLoadFencePolicy.accepts(MpvRestartLoadFence(7L, "fd://41"), 7L, "fd://41", 2))
  }
  @Test fun oldGenerationCannotBePromotedToNewestLoad() {
    assertFalse(MpvRestartLoadFencePolicy.accepts(MpvRestartLoadFence(7L, "fd://41"), 8L, "fd://41", 2))
  }
  @Test fun staleFileLoadedFromAnotherSourceIsRejected() {
    assertFalse(MpvRestartLoadFencePolicy.accepts(MpvRestartLoadFence(7L, "fd://42"), 7L, "fd://41", 2))
  }
  @Test fun incompleteNativeFileCannotBeReportedReady() {
    val fence = MpvRestartLoadFence(7L, "https://example.test/video.mp4")
    assertFalse(MpvRestartLoadFencePolicy.accepts(fence, 7L, null, 2))
    assertFalse(MpvRestartLoadFencePolicy.accepts(fence, 7L, fence.nativePath, 0))
  }
  @Test fun equivalentFileUriAndDecodedAbsolutePathAreAccepted() {
    assertTrue(MpvRestartLoadFencePolicy.accepts(MpvRestartLoadFence(7L, "file:///storage/movie%20one.mp4"),
      7L, "/storage/movie one.mp4", 2))
  }
}
