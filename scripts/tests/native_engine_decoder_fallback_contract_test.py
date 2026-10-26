#!/usr/bin/env python3
"""Guard the Native decoder-error to per-item MPV recovery contract."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLAYER = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt").read_text()
POLICY = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/NativePlaybackRecoveryPolicy.kt").read_text()

handler = PLAYER.split("private fun handleNativePlaybackError(", 1)[1].split("override fun nativeSetZoom", 1)[0]
assert "NativePlaybackRecoveryPolicy.fallbackKind(" in handler
assert "selectEngineForCurrentVideo(PlaybackEngineMode.MPV)" in handler
assert "viewModel.showToast" not in handler
assert "Native ${fallbackKind.name.lowercase()} failure; switching this item to MPV" in handler

selection = PLAYER.split("fun selectEngineForCurrentVideo(", 1)[1].split("fun currentEngineSelectionForControls(", 1)[0]
assert "manualEngineOverride = mediaId to engine" in selection
assert "engineSelectionRequests.tryEmit(engine)" in selection
assert "decoderPreferences.playbackEngine.set" not in selection

handoff = PLAYER.split("val useNative = effectiveEngine == PlaybackEngineMode.NATIVE", 1)[1].split("private fun", 1)[0]
assert "activeEngineMode = PlaybackEngineMode.MPV" in handoff
assert "nativeEngineOrNull()?.stop()" in handoff
assert "positionRestoreOverride = PlaybackPositionRestoreOverride(" in handoff
assert "loadPlaylistItemInternal(" in handoff
assert "startMediaLoad(" in handoff
assert "PlaybackSession.state.first" in handoff
assert "surfaceAttached" in handoff

assert "DecoderInitializationException" in POLICY
assert "VIDEO_DECODER" in POLICY
print("Native decoder fallback contract passed: error requests same-item MPV recovery without a popup or global engine preference change.")
