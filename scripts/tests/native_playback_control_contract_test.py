#!/usr/bin/env python3
"""Protect lifecycle/host wiring that cannot be exercised by the plain JVM policies."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PLAYER = ROOT / 'app/src/main/java/app/infinity/mpvz/ui/player'


def function(source, name):
    start = source.index('fun ' + name + '(')
    body_start = source.index('{', start)
    depth = 1
    end = body_start + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[body_start:end]


class NativePlaybackControlContractTest(unittest.TestCase):
    def test_normal_close_captures_native_position_before_media_is_cleared(self):
        activity = (PLAYER / 'PlayerActivity.kt').read_text()
        body = function(activity, 'silenceAudioOnClose')
        self.assertLess(body.index('saveVideoPlaybackState('), body.index('nativeEngine.stop()'))
        self.assertIn('immediate = true', body)

    def test_native_preview_routes_to_engine_instead_of_returning_without_a_seek(self):
        view_model = (PLAYER / 'PlayerViewModel.kt').read_text()
        self.assertIn('host.nativeSeekPreviewTo(', function(view_model, 'seekPreviewTo'))
        activity = (PLAYER / 'PlayerActivity.kt').read_text()
        self.assertIn('nativeEngine.seekPreviewTo(positionMs)', function(activity, 'nativeSeekPreviewTo'))
        controls = (PLAYER / 'controls/PlayerControls.kt').read_text()
        self.assertIn('viewModel.seekPreviewTo(it)', controls)
        self.assertIn('viewModel.finishSeekPreview(targetPosition)', controls)

    def test_final_seek_is_not_replaced_by_a_trailing_debounce(self):
        engine = (PLAYER / 'NativeMedia3Engine.kt').read_text()
        self.assertIn('seekDispatcher.commit(positionMs)', function(engine, 'seekTo'))
        self.assertNotIn('postDelayed', function(engine, 'seekTo'))
        self.assertIn('seekDispatcher.preview(positionMs)', function(engine, 'seekPreviewTo'))

    def test_preview_and_final_seek_preserve_play_pause_like_mpv(self):
        model = (PLAYER / 'PlayerViewModel.kt').read_text()
        for name in ('seekPreviewTo', 'finishSeekPreview', 'seekTo', 'cancelSeekPreviewInteraction'):
            body = function(model, name)
            self.assertNotIn('host.nativePause()', body)
            self.assertNotIn('host.nativeUnpause()', body)
            self.assertNotIn('beginNativeScrub()', body)
        cancel = function(model, 'cancelSeekPreviewInteraction')
        self.assertIn('host.nativeCancelSeekPreview()', cancel)
        controls = (PLAYER / 'controls/PlayerControls.kt').read_text()
        self.assertNotIn('viewModel.beginNativeScrub()', controls)
        self.assertIn('viewModel.cancelSeekPreviewInteraction()', controls)

    def test_native_and_mpv_share_live_preview_cadence_and_keyframe_policy(self):
        model = (PLAYER / 'PlayerViewModel.kt').read_text()
        native = (PLAYER / 'NativeSeekDispatcher.kt').read_text()
        self.assertIn('delay(SEEK_PREVIEW_INTERVAL_MS)', function(model, 'runSeekPreviewLoop'))
        self.assertIn('"absolute+keyframes"', function(model, 'runSeekPreviewLoop'))
        self.assertIn('throttleMs: Long = SEEK_PREVIEW_INTERVAL_MS', native)
        self.assertIn('SEEK_PREVIEW_INTERVAL_MS = 100L', (PLAYER / 'SeekPreviewPolicy.kt').read_text())
        self.assertIn('SeekParameters.CLOSEST_SYNC', (PLAYER / 'NativeMedia3Engine.kt').read_text())

    def test_position_reset_rearms_one_paused_flow_frame_on_gl_thread(self):
        sink = (PLAYER / 'Media3FlowVideoSink.kt').read_text()
        self.assertIn('allowPausedFrame = resetPosition', function(sink, 'flush'))
        reset = function(sink, 'flushInternal')
        self.assertIn('allowFirstFrameBeforeStarted = allowPausedFrame', reset)
        self.assertLess(reset.index('glHandler.post'), reset.index('allowFirstFrameBeforeStarted ='))
        self.assertIn('allowPausedFrame = allowFirstFrameBeforeStarted', function(sink, 'onInputStreamChanged'))
        self.assertIn('allowFirstFrameBeforeStarted = false', function(sink, 'renderAtPosition'))
        # Paused one-shot rendering must not start playback or its continuous display loop.
        self.assertNotIn('running = true', reset)
        self.assertNotIn('scheduleDisplayFrame()', reset)

    def test_late_decoded_seek_frame_wakes_a_paused_one_shot(self):
        sink = (PLAYER / 'Media3FlowVideoSink.kt').read_text()
        capture = function(sink, 'onInputFrameAvailable')
        wake = capture.index('if (!running && allowFirstFrameBeforeStarted)')
        self.assertLess(capture.index('captureFrame(token.ptsUs, transform)'), wake)
        self.assertIn('postOneShotRender()', capture[wake:])
        self.assertLess(capture.index('token.generation != streamGeneration'), wake)

    def test_seekbar_disposal_and_drag_cancellation_release_interaction(self):
        seekbar = (PLAYER / 'controls/components/Seekbar.kt').read_text()
        self.assertIn('if (isUserInteracting) interactionCallback(false)', seekbar)
        self.assertIn('interaction is DragInteraction.Cancel', seekbar)
        self.assertIn('currentInteractionCallback(false)', seekbar)

    def test_media_changes_and_teardown_cancel_pending_drag_targets(self):
        engine = (PLAYER / 'NativeMedia3Engine.kt').read_text()
        for name in ('play', 'stop', 'release'):
            self.assertIn('seekDispatcher.cancel()', function(engine, name))


if __name__ == '__main__':
    unittest.main()
