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
  fun bufferingHidesOnlyCenterPlaybackControls() {
    val otherControlsVisible = PlayerControlVisibilityPolicy.areButtonsVisible(true, false, false)

    assertTrue(otherControlsVisible)
    assertFalse(
      PlayerControlVisibilityPolicy.shouldShowPlaybackControls(
        controlsVisible = otherControlsVisible,
        bufferingIndicatorVisible = true,
      ),
    )
    assertTrue(
      PlayerControlVisibilityPolicy.shouldShowPlaybackControls(
        controlsVisible = otherControlsVisible,
        bufferingIndicatorVisible = false,
      ),
    )
  }

  @Test
  fun bufferingOrFirstFrameWaitDoesNotPinControlsWhenPlaybackIsPausedOrUnknown() {
    assertTrue(
      PlayerControlVisibilityPolicy.shouldAutoHideControls(
        isAudioOnly = false,
        controlsShown = true,
        paused = true,
        bufferingOrWaiting = true,
        isSeeking = false,
        isUnlockSliderDragging = false,
        drawerBlocksAutoHide = false,
      ),
    )
    assertTrue(
      PlayerControlVisibilityPolicy.shouldAutoHideControls(
        isAudioOnly = false,
        controlsShown = true,
        paused = null,
        bufferingOrWaiting = false,
        isSeeking = false,
        isUnlockSliderDragging = false,
        drawerBlocksAutoHide = false,
      ),
    )
  }

  @Test
  fun pausedReadyVideoAndActiveInteractionsKeepTheirExistingProtection() {
    assertFalse(
      PlayerControlVisibilityPolicy.shouldAutoHideControls(
        isAudioOnly = false,
        controlsShown = true,
        paused = true,
        bufferingOrWaiting = false,
        isSeeking = false,
        isUnlockSliderDragging = false,
        drawerBlocksAutoHide = false,
      ),
    )
    assertFalse(
      PlayerControlVisibilityPolicy.shouldAutoHideControls(
        isAudioOnly = false,
        controlsShown = true,
        paused = false,
        bufferingOrWaiting = false,
        isSeeking = true,
        isUnlockSliderDragging = false,
        drawerBlocksAutoHide = false,
      ),
    )
    assertFalse(
      PlayerControlVisibilityPolicy.shouldAutoHideControls(
        isAudioOnly = true,
        controlsShown = true,
        paused = false,
        bufferingOrWaiting = false,
        isSeeking = false,
        isUnlockSliderDragging = false,
        drawerBlocksAutoHide = false,
      ),
    )
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
