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

    def test_media_changes_and_teardown_cancel_pending_drag_targets(self):
        engine = (PLAYER / 'NativeMedia3Engine.kt').read_text()
        for name in ('play', 'stop', 'release'):
            self.assertIn('seekDispatcher.cancel()', function(engine, name))


if __name__ == '__main__':
    unittest.main()
