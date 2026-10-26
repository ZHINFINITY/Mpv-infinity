#!/usr/bin/env python3
"""Production-path guards complement executable MiniPlayerArtworkFlightTest geometry tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/app/infinity/mpvz'
POLICY = (JAVA / 'ui/player/controls/MiniPlayerGesturePolicy.kt').read_text()
TRANSITION = (JAVA / 'ui/player/controls/MusicMinimizeTransitionPolicy.kt').read_text()
CONTROLS = (JAVA / 'ui/player/controls/AudioPlayerControls.kt').read_text()
MINI = (JAVA / 'ui/browser/components/MiniPlayer.kt').read_text()
GEOMETRY = (JAVA / 'ui/player/controls/MiniPlayerScreenGeometry.kt').read_text()
ACTIVITY = (JAVA / 'ui/player/PlayerActivity.kt').read_text()


class MusicTransitionGeometryContracts(unittest.TestCase):
    def test_both_activity_windows_use_a_common_screen_coordinate_system(self):
        self.assertIn('getLocationOnScreen(screen)', GEOMETRY)
        self.assertIn('getLocationInWindow(window)', GEOMETRY)
        self.assertIn('boundsInWindow().translate(', GEOMETRY)
        self.assertIn('it.miniPlayerScreenBounds(hostPlayerView)', CONTROLS)
        self.assertIn('coordinates.miniPlayerScreenBounds(hostView)', MINI)

    def test_actual_full_cover_has_frozen_source_and_top_left_transform_origin(self):
        card = CONTROLS.split('// 3. Current Cover Art Card', 1)[1].split('val lyricsPanel', 1)[0]
        # Cover measured inside the page layer (pre-transform), then a separate child layer
        # compensates the page's uniform scale so it lands on the mini artwork.
        self.assertIn('page.localPositionOf(coordinates, Offset.Zero)', card)
        self.assertIn('coverLocalBounds = MusicMinimizeTransitionPolicy.Rect(', card)
        self.assertIn('CoverArtCardImage(', card)
        # Mid-flight the in-page cover hides; the single visible cover is the flying overlay
        # above the page (owner report: duplicate/stretched artwork during minimize).
        self.assertIn('currentTransitionFrame(', card)
        self.assertIn(') != null) 0f else 1f', card)
        flying = CONTROLS.split('// FlyingCover:', 1)[1].split('CoverArtCardImage(', 1)[0]
        self.assertIn('MusicMinimizeTransitionPolicy.flyingCoverLayer(', flying)
        self.assertIn('transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)', flying)
        self.assertIn('translationX = layer.translationX', flying)
        self.assertIn('translationY = layer.translationY', flying)
        # Placed in the layout phase and transformed in the draw phase: no per-frame recomposition.
        self.assertIn('.layout { measurable, _ ->', flying)

    def test_mini_callbacks_preserve_both_measured_endpoints(self):
        self.assertIn('var miniArtworkBounds by remember', MINI)
        self.assertIn('fun publishMiniTransitionGeometry()', MINI)
        self.assertEqual(3, MINI.count('publishMiniTransitionGeometry()'))
        self.assertIn('artworkLeft = art?.left', MINI)
        self.assertIn('artworkLeft: Float? = null', POLICY)
        self.assertIn('minOf(target.width, target.height)', POLICY)

    def test_drag_and_settle_use_one_timeline_without_playback_reset(self):
        gesture = CONTROLS.split('var miniGesture by remember', 1)[1].split('AudioPaletteGlassScene(', 1)[0]
        for token in ('MiniPlayerGesturePolicy.drag(miniGesture', 'VelocityTracker()',
                      'settleAnimation.snapTo(settleStart)', 'settleAnimation.animateTo(target',
                      'settleGeneration == generation'):
            self.assertIn(token, gesture)
        for forbidden in ('PlaybackSession.', 'ACTION_NOTIFICATION_STOP', 'seek(', 'load(', 'recreate(', 'delay('):
            self.assertNotIn(forbidden, gesture)

    def test_completed_flight_is_not_followed_by_another_window_slide(self):
        self.assertIn('playerActivity?.onAudioMinimizePresentationComplete()', CONTROLS)
        completed = CONTROLS.split('if (target == 1f) {', 1)[1].split('settleTarget = null', 1)[0]
        self.assertLess(completed.index('onAudioMinimizePresentationComplete()'), completed.index('currentOnBackPress()'))
        self.assertIn('val exitAnimation = if (audioMinimizePresentationCompleted) 0 else R.anim.slide_out_down', ACTIVITY)
        self.assertIn('overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, exitAnimation)', ACTIVITY)
        self.assertIn('overridePendingTransition(0, exitAnimation)', ACTIVITY)

    def test_fallback_and_actual_playback_controls_are_preserved(self):
        # The in-app sheet shares the browser window, so it never needs the slide fallback.
        self.assertIn('MusicMinimizeTransitionPolicy.useSlideFallback(anchor, windowWidth, windowHeight, inAppSheet != null || transitionTranslucent)', CONTROLS)
        self.assertIn('translationY = size.height * currentMiniProgress()', CONTROLS)
        self.assertIn('MusicMinimizeTransitionPolicy.frame(', CONTROLS)
        for token in ('ACTION_NOTIFICATION_PLAY_PAUSE', 'ACTION_NOTIFICATION_NEXT',
                      'ACTION_NOTIFICATION_STOP', 'ContentScale.Crop', 'ACTION_OPEN_PLAYER'):
            self.assertIn(token, MINI)

    def test_page_frame_uses_open_zero_convention_and_layer_space_clip(self):
        # Regression: 1 - miniProgress made the fully open player draw as the mini rect.
        self.assertNotIn('1f - miniProgress', CONTROLS)
        self.assertIn('minimizeProgress = miniProgress', CONTROLS)
        self.assertIn('MusicMinimizeTransitionPolicy.pageLayer(frame, playerOrigin.x, playerOrigin.y)', CONTROLS)
        self.assertIn('shape = MusicPageClipShape(frame.clipWidth, frame.clipHeight, frame.clipCornerRadiusDp.dp.toPx())', CONTROLS)
        # Split fades: page surface (palette) stays until late, chrome fades early.
        self.assertIn('alpha = MusicMinimizeTransitionPolicy.surfaceAlpha(currentMiniProgress())', CONTROLS)
        self.assertIn('MusicMinimizeTransitionPolicy.chromeAlpha(currentMiniProgress())', CONTROLS)
        self.assertIn('val scale = if (screen.width > 0f) (page.width / screen.width)', TRANSITION)
        # Live anchor every frame with width and height staleness checks.
        self.assertIn('MusicMinimizeTransitionPolicy.currentAnchor(windowWidth, windowWidth, windowHeight)', CONTROLS)

    def test_release_and_programmatic_timing_follow_the_approved_spec(self):
        self.assertIn('MusicMinimizeTransitionPolicy.releaseTargetMiniProgress(', CONTROLS)
        self.assertIn('settle = { velocityY -> releaseToTarget(velocityY) }', CONTROLS)
        # Owner-approved respec: one critically damped spring from the release velocity (or a
        # programmatic kick) replaces the fixed tween durations.
        self.assertIn('androidx.compose.animation.core.spring(', CONTROLS)
        self.assertIn('MusicMinimizeTransitionPolicy.settleInitialVelocity(', CONTROLS)
        self.assertIn('SETTLE_DAMPING_RATIO', TRANSITION)
        self.assertIn('SETTLE_STIFFNESS', TRANSITION)
        self.assertNotIn('settleDurationMs(', CONTROLS)
        self.assertIn('LaunchedEffect(openFromMiniRequest)', CONTROLS)
        self.assertIn('audioOpenFromMiniRequest.intValue++', ACTIVITY)

    def test_window_stays_see_through_until_finish_and_restores_background_when_open(self):
        complete = ACTIVITY.split('fun onAudioMinimizePresentationComplete()', 1)[1].split('\n  }', 1)[0]
        self.assertNotIn('setAudioTransitionTranslucent(false)', complete)
        self.assertIn('audioWindowBackground?.let { window.setBackgroundDrawable(it) }', ACTIVITY)
        self.assertIn('playerActivity?.setAudioTransitionTranslucent(false)', CONTROLS)

    def test_geometry_regressions_execute_real_policy_functions(self):
        test = (ROOT / 'app/src/test/java/app/infinity/mpvz/ui/player/controls/MiniPlayerArtworkFlightTest.kt').read_text()
        for token in ('MiniPlayerGesturePolicy.artworkFlight(', 'parent.scaleX', 'parent.scaleY',
                      'zeroSizeFirstLayoutDoesNotDelete', 'rotationMismatchInvalidates',
                      'rtlRightHandArtwork', 'assertFlight(0.5f)', 'assertFlight(1f)'):
            self.assertIn(token, test)


if __name__ == '__main__':
    unittest.main()
