package app.infinity.mpvz.ui.player.controls

import app.infinity.mpvz.ui.player.controls.components.BufferingVisibilityDebouncePolicy
import org.junit.Assert.assertEquals
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
  fun nativeIndicatorUsesOnlyItsDebouncedStateAndIgnoresStaleMpvLoading() {
    assertFalse(
      PlayerControlVisibilityPolicy.shouldShowBufferingIndicator(
        nativeEngineActive = true,
        nativeBufferingVisible = false,
        mpvBufferingVisible = true,
        isSeeking = false,
      ),
    )
    assertTrue(
      PlayerControlVisibilityPolicy.shouldShowBufferingIndicator(
        nativeEngineActive = true,
        nativeBufferingVisible = true,
        mpvBufferingVisible = false,
        isSeeking = false,
      ),
    )
  }

  @Test
  fun terminalNativeErrorIsNotClassifiedAsAnEndlessBufferingStall() {
    assertFalse(
      PlayerControlVisibilityPolicy.isNativePlaybackStalled(
        isEnded = false,
        hasError = true,
        isReady = false,
        isLoading = true,
        isBuffering = true,
      ),
    )
    assertTrue(
      PlayerControlVisibilityPolicy.isNativePlaybackStalled(
        isEnded = false,
        hasError = false,
        isReady = false,
        isLoading = true,
        isBuffering = true,
      ),
    )
  }

  @Test
  fun mpvUsesItsDebouncedBufferingStateAndSeekingSuppressesEitherEngine() {
    assertTrue(
      PlayerControlVisibilityPolicy.shouldShowBufferingIndicator(
        nativeEngineActive = false,
        nativeBufferingVisible = false,
        mpvBufferingVisible = true,
        isSeeking = false,
      ),
    )
    assertFalse(
      PlayerControlVisibilityPolicy.shouldShowBufferingIndicator(
        nativeEngineActive = true,
        nativeBufferingVisible = true,
        mpvBufferingVisible = true,
        isSeeking = true,
      ),
    )
  }

  @Test
  fun bufferingDebounceKeepsTheReleaseTimingAndPreferenceCanHideImmediately() {
    assertEquals(350L, BufferingVisibilityDebouncePolicy.delayFor(shouldShow = true))
    assertEquals(300L, BufferingVisibilityDebouncePolicy.delayFor(shouldShow = false))
    assertFalse(BufferingVisibilityDebouncePolicy.isVisible(enabled = false, debouncedVisible = true))
    assertFalse(BufferingVisibilityDebouncePolicy.isVisible(enabled = true, debouncedVisible = false))
    assertTrue(BufferingVisibilityDebouncePolicy.isVisible(enabled = true, debouncedVisible = true))
  }
}
