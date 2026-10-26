#!/usr/bin/env python3
"""Guard the Native decoder-error to per-item MPV recovery contract."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLAYER = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt").read_text()
POLICY = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/NativePlaybackRecoveryPolicy.kt").read_text()
ENGINE = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/NativeMedia3Engine.kt").read_text()

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
error_binding = ENGINE.split("private fun tagMediaItemForNextPlayback(", 1)[1].split(
    "private fun playbackErrorTokenFor(", 1
)[0]
assert "NativePlaybackErrorToken(" in error_binding
assert "PlaybackSession.state.value.generation" in error_binding
assert "setTag(token)" in error_binding
event_lookup = ENGINE.split("private fun playbackErrorTokenFor(", 1)[1].split(
    "private fun invalidatePlaybackMediaItemToken()", 1
)[0]
assert "eventTime.timeline" in event_lookup
assert "eventTime.windowIndex" in event_lookup
assert "eventTime.mediaPeriodId" in event_lookup
assert "tokenFromMediaItemTag(mediaItem.localConfiguration?.tag)" in event_lookup
analytics_error = ENGINE.split("private val analyticsListener =", 1)[1].split("private val listener =", 1)[0]
assert "override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException)" in analytics_error
assert "callback(error, itemToken)" in analytics_error
assert "override fun onPlayerError(error: PlaybackException)" not in ENGINE

assert ENGINE.count("tagMediaItemForNextPlayback(") == 4
assert ENGINE.count("player.setMediaItem(") == 3

stop = ENGINE.split("fun stop()", 1)[1].split("fun release()", 1)[0]
assert stop.index("invalidatePlaybackMediaItemToken()") < stop.index("player.stop()")
release = ENGINE.split("fun release()", 1)[1].split("private fun publishSnapshot()", 1)[0]
assert release.index("invalidatePlaybackMediaItemToken()") < release.index("player.release()")

native_error_callback = PLAYER.split("engine.onPlaybackError =", 1)[1].split("nativeEngineConfigured = true", 1)[0]
assert "currentPlaybackGeneration = PlaybackSession.state.value.generation" in native_error_callback
print("Native decoder fallback contract passed: error requests same-item MPV recovery without a popup or global engine preference change.")
