#!/usr/bin/env python3
"""Static contract checks for applying interpolation preferences to live playback."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt"
CONTROLS = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/FrameInterpolationControls.kt"
SHEET = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/FrameInterpolationFpsSheet.kt"


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle}")


def main() -> None:
    activity = ACTIVITY.read_text()
    controls = CONTROLS.read_text()
    sheet = SHEET.read_text()
    require(activity, "fun applyInterpolationPreferencesFromControls()", "public activity callback")
    require(activity, "interpolationApplyMutex.withLock", "serialized restart")
    require(activity, "PlaybackSession.removeObserver(playerObserver)", "observer detach")
    require(activity, "player.releaseSurface()", "surface release")
    require(activity, "PlaybackSession.invalidateCoreConfiguration()", "core invalidation")
    require(activity, "PlaybackPositionRestoreOverride(position, paused)", "position/pause restore")
    require(activity, "PlaybackSession.state.first", "generation readiness wait")
    require(activity, "PlaybackPhase.READY", "ready phase")
    for prop in ("video-aspect-override", "video-zoom", "sub-delay", "sub-speed", "audio-delay", "speed", "ytdl-format"):
        require(activity, f'"{prop}"', f"{prop} preservation")
    callback = activity.split("fun applyInterpolationPreferencesFromControls()", 1)[1].split("private fun handleNativePlaybackEnded", 1)[0]
    native = callback.split("if (activeEngineMode == PlaybackEngineMode.NATIVE)", 1)[1].split("if (!mpvInitialized", 1)[0]
    require(native, "nativeEngine.positionForPersistenceMs()", "Native pending-seek-aware position")
    require(native, "paused = !nativeEngine.currentPlayer.playWhenReady", "Native pause intent")
    require(native, "saveVideoPlaybackState(fileName, immediate = true)?.join()", "Native shared track/aspect state commit")
    require(native, "interpolationRecreationScheduled = true", "duplicate recreation guard")
    require(activity, "outState.putDouble(STATE_INTERPOLATION_POSITION", "Native position Bundle write")
    require(activity, "outState.putBoolean(STATE_INTERPOLATION_PAUSED", "Native pause Bundle write")
    require(activity, "savedInstanceState.getDouble(STATE_INTERPOLATION_POSITION)", "Native position Bundle read")
    require(activity, "manualEngineOverride = pendingInterpolationEngine", "Native manual renderer restoration")
    require(activity, "pendingInterpolationEngine?.first == mediaIdentifier", "restart snapshot item identity")
    require(activity, "loadPositionRestoreOverride ?: AudiobookPlayback.positionForLoad", "actual load snapshot forwarding")
    require(activity, "player.aid", "audio track capture")
    require(activity, "player.sid", "subtitle track capture")
    require(activity, "player.secondarySid", "secondary subtitle capture")
    require(controls, "applyInterpolationPreferencesFromControls()", "interpolation toggle caller")
    require(sheet, "applyInterpolationPreferencesFromControls()", "FPS sheet caller")
    require(controls, "mpvFlowFrameInterpolation", "interpolation control preference")
    require(sheet, "mpvFlowTargetFps", "FPS preference")
    print("interpolation runtime apply contract: PASS")


if __name__ == "__main__":
    main()
