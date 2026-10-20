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

    def test_scrub_uses_play_intent_and_cancels_before_resuming(self):
        model = (PLAYER / 'PlayerViewModel.kt').read_text()
        self.assertIn('host.nativePlayWhenReady()', function(model, 'beginNativeScrub'))
        finish = function(model, 'finishSeekPreview')
        self.assertLess(finish.index('host.nativeSeekTo('), finish.index('endNativeScrub()'))
        end = function(model, 'endNativeScrub')
        self.assertLess(end.index('host.nativeCancelSeekPreview()'), end.index('host.nativeUnpause()'))
        engine = (PLAYER / 'NativeMedia3Engine.kt').read_text()
        self.assertIn('seekDispatcher.cancel()', function(engine, 'cancelSeekPreview'))

    def test_shared_gesture_and_audio_previews_do_not_acquire_seekbar_pause(self):
        model = (PLAYER / 'PlayerViewModel.kt').read_text()
        preview = function(model, 'seekPreviewTo')
        self.assertNotIn('beginNativeScrub()', preview)
        self.assertNotIn('host.nativePause()', preview)
        controls = (PLAYER / 'controls/PlayerControls.kt').read_text()
        self.assertIn('viewModel.beginNativeScrub()', controls)
        final = function(model, 'seekTo')
        self.assertLess(final.index('host.nativeSeekTo('), final.index('endNativeScrub()'))

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
