package app.infinity.mpvz.ui.player.controls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlVisibilityPolicyTest {
  @Test
  fun controlGroupsShareTheSameVisibleState() {
    assertTrue(PlayerControlVisibilityPolicy.areButtonsVisible(true, false, false))
    assertFalse(PlayerControlVisibilityPolicy.areButtonsVisible(false, false, false))
    assertFalse(PlayerControlVisibilityPolicy.areButtonsVisible(true, true, false))
    assertFalse(PlayerControlVisibilityPolicy.areButtonsVisible(true, false, true))
  }

  @Test
  fun nativeReadinessDoesNotInheritStaleMpvLoading() {
    assertTrue(
      PlayerControlVisibilityPolicy.shouldShowBufferingIndicator(
        nativeEngineActive = true,
        nativeReady = false,
        nativeLoading = false,
        nativeBuffering = false,
        nativeEnded = false,
        mpvLoading = false,
        mpvBuffering = false,
        torrentConnecting = false,
        isSeeking = false,
      ),
    )
    assertFalse(
      PlayerControlVisibilityPolicy.shouldShowBufferingIndicator(
        nativeEngineActive = true,
        nativeReady = true,
        nativeLoading = false,
        nativeBuffering = false,
        nativeEnded = false,
        mpvLoading = true,
        mpvBuffering = true,
        torrentConnecting = true,
        isSeeking = false,
      ),
    )
  }

  @Test
  fun nativeBufferingAndMpvLoadingRemainVisibleButSeekingSuppressesSpinner() {
    assertTrue(
      PlayerControlVisibilityPolicy.shouldShowBufferingIndicator(
        nativeEngineActive = true,
        nativeReady = true,
        nativeLoading = false,
        nativeBuffering = true,
        nativeEnded = false,
        mpvLoading = false,
        mpvBuffering = false,
        torrentConnecting = false,
        isSeeking = false,
      ),
    )
    assertTrue(
      PlayerControlVisibilityPolicy.shouldShowBufferingIndicator(
        nativeEngineActive = false,
        nativeReady = false,
        nativeLoading = false,
        nativeBuffering = false,
        nativeEnded = false,
        mpvLoading = true,
        mpvBuffering = false,
        torrentConnecting = false,
        isSeeking = false,
      ),
    )
    assertFalse(
      PlayerControlVisibilityPolicy.shouldShowBufferingIndicator(
        nativeEngineActive = false,
        nativeReady = false,
        nativeLoading = false,
        nativeBuffering = false,
        nativeEnded = false,
        mpvLoading = true,
        mpvBuffering = false,
        torrentConnecting = false,
        isSeeking = true,
      ),
    )
  }
}
