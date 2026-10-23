/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class InterpolationRuntimeApplyPolicyTest {
  @Test fun activePlaybackWithoutVerifiedApiStaysPending() {
    assertEquals(
      InterpolationRuntimeApplyPolicy.Decision.PENDING_UNTIL_NEXT_PLAYBACK,
      InterpolationRuntimeApplyPolicy.decision(hasLiveReconfigureApi = false, playbackActive = true),
    )
  }

  @Test fun verifiedApiMayApplyOnlyToActivePlayback() {
    assertEquals(
      InterpolationRuntimeApplyPolicy.Decision.APPLY_LIVE,
      InterpolationRuntimeApplyPolicy.decision(hasLiveReconfigureApi = true, playbackActive = true),
    )
    assertEquals(
      InterpolationRuntimeApplyPolicy.Decision.PENDING_UNTIL_NEXT_PLAYBACK,
      InterpolationRuntimeApplyPolicy.decision(hasLiveReconfigureApi = true, playbackActive = false),
    )
  }
  @Test fun explicitPermissionOnlyRestartsAnActiveMpvSession() {
    assertEquals(InterpolationRuntimeApplyPolicy.Decision.RESTART_PRESERVING_STATE,
      InterpolationRuntimeApplyPolicy.decision(false, true, restartAuthorized = true, isMpv = true))
    assertEquals(InterpolationRuntimeApplyPolicy.Decision.PENDING_UNTIL_NEXT_PLAYBACK,
      InterpolationRuntimeApplyPolicy.decision(false, true, restartAuthorized = false, isMpv = true))
    assertEquals(InterpolationRuntimeApplyPolicy.Decision.PENDING_UNTIL_NEXT_PLAYBACK,
      InterpolationRuntimeApplyPolicy.decision(false, false, restartAuthorized = true, isMpv = true))
    assertEquals(InterpolationRuntimeApplyPolicy.Decision.PENDING_UNTIL_NEXT_PLAYBACK,
      InterpolationRuntimeApplyPolicy.decision(false, true, restartAuthorized = true, isMpv = false))
  }
  @Test fun nativeLiveAcknowledgementAlwaysWinsWithoutRestart() {
    assertEquals(InterpolationRuntimeApplyPolicy.Decision.APPLY_LIVE,
      InterpolationRuntimeApplyPolicy.decision(true, true, restartAuthorized = true, isMpv = false))
  }
}
