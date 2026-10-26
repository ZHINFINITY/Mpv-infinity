#!/usr/bin/env python3
"""Contracts for the owner-reported music bugs seen in the v2.2.0 logcat.

1. One-second song open, music not playing, wrong duration: the engine preference collector
   re-resolved the engine without an audio check, so with Native selected every music open
   handed the playing song from MPV to Media3 (Vulkan preflight on Main, Media3 restart, then
   MPV restart). Music must resolve to MPV before the AUTO/NATIVE branches.
2. Music stopped on minimize: "Cannot start background playback: video not ready" ended the
   service while the shared MPV session was already READY. A ready MPV session must count.
3. Minimize looked frozen: a landed minimize whose Back was consumed (sheet/panel) or whose
   background handoff failed must grow the page back to full.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PLAYER = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player"
ACTIVITY = (PLAYER / "PlayerActivity.kt").read_text()
CONTROLS = (PLAYER / "controls/AudioPlayerControls.kt").read_text()


def function_body(source: str, signature: str) -> str:
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 0
    for index in range(brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[brace:index + 1]
    raise AssertionError(f"unterminated body for {signature}")


class MusicEngineSelectionTest(unittest.TestCase):
    def test_collector_resolves_music_to_mpv_before_auto_or_native(self):
        marker = "// Music always plays on MPV, matching the load path."
        self.assertIn(marker, ACTIVITY)
        block = ACTIVITY.split(marker, 1)[1][:1200]
        audio = block.index("currentQueueItem?.isDefinitelyAudioOnly() == true ||")
        declared = block.index("currentQueueItem?.declaredMediaKind() == DeclaredPlaybackMediaKind.AUDIO ->")
        mpv = block.index("PlaybackEngineMode.MPV", declared)
        auto = block.index("engine == PlaybackEngineMode.AUTO ->")
        self.assertLess(audio, declared)
        self.assertLess(declared, mpv)
        self.assertLess(mpv, auto, "music must be decided before the AUTO/NATIVE branches")

    def test_engine_reads_in_shared_paths_do_not_construct_native_media3(self):
        body = function_body(ACTIVITY, "private fun startBackgroundPlaybackInternal(")
        self.assertNotIn("nativeEngine.", body)
        self.assertIn("nativeEngineOrNull()", body)


class BackgroundStartTest(unittest.TestCase):
    def test_ready_mpv_session_allows_background_start(self):
        body = function_body(ACTIVITY, "private fun startBackgroundPlaybackInternal(")
        self.assertIn("val mpvSessionReady =", body)
        ready = body.split("val mpvSessionReady =", 1)[1].split("if (", 1)[0]
        self.assertIn("!isNativeEngineActive()", ready)
        self.assertIn("PlaybackPhase.READY", ready)
        self.assertIn("PlaybackPhase.BACKGROUND", ready)
        self.assertIn("!isReady && !mpvSessionReady &&", body)


class MinimizeAbortTest(unittest.TestCase):
    def test_abort_is_signalled_once_per_landed_minimize(self):
        body = function_body(ACTIVITY, "private fun abortLandedAudioMinimize()")
        self.assertIn("if (!audioMinimizePresentationCompleted || isFinishing || isDestroyed) return", body)
        self.assertIn("audioMinimizePresentationCompleted = false", body)
        self.assertIn("audioMinimizeAbortRequest.intValue++", body)

    def test_consumed_back_and_failed_handoff_abort_the_minimize(self):
        back = function_body(ACTIVITY, "private fun handleBackPress()")
        sheet = back.split("if (viewModel.sheetShown.value != Sheets.None) {", 1)[1].split("return", 1)[0]
        panel = back.split("if (viewModel.panelShown.value != Panels.None) {", 1)[1].split("return", 1)[0]
        self.assertIn("abortLandedAudioMinimize()", sheet)
        self.assertIn("abortLandedAudioMinimize()", panel)
        failed = ACTIVITY.split("if (failedBackgroundHandoff) {", 1)[1].split("}", 1)[0]
        self.assertIn("abortLandedAudioMinimize()", failed)

    def test_controls_grow_the_page_back_on_abort(self):
        self.assertIn("val minimizeAbortRequest = playerActivity?.audioMinimizeAbortRequest?.intValue ?: 0", CONTROLS)
        effect = CONTROLS.split("LaunchedEffect(minimizeAbortRequest) {", 1)[1].split("\n  }\n", 1)[0]
        self.assertIn("miniGesture.progress <= 0f", effect)
        self.assertIn("settleTarget = 0f", effect)
        self.assertIn("settleGeneration++", effect)
        self.assertNotIn("delay(", effect)


if __name__ == "__main__":
    unittest.main()
