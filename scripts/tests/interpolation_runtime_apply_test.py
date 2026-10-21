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
    require(activity, "InterpolationRuntimeApplyPolicy.decision", "explicit runtime policy")
    require(activity, "toast_interpolation_pending_next_playback", "user-facing pending fallback")
    callback = activity.split("fun applyInterpolationPreferencesFromControls()", 1)[1].split("private fun handleNativePlaybackEnded", 1)[0]
    for forbidden in ("recreate()", "releaseSurface()", "PlaybackSession.load(", "setupMPV()", "invalidateCoreConfiguration()"):
        if forbidden in callback:
            raise SystemExit(f"forbidden live playback restart in callback: {forbidden}")
    require(activity, "live playback unchanged until next playback load", "no-reload contract")
    require(controls, "applyInterpolationPreferencesFromControls()", "interpolation toggle caller")
    require(sheet, "applyInterpolationPreferencesFromControls()", "FPS sheet caller")
    require(controls, "mpvFlowFrameInterpolation", "interpolation control preference")
    require(sheet, "mpvFlowTargetFps", "FPS preference")
    print("interpolation runtime apply contract: PASS")


if __name__ == "__main__":
    main()
