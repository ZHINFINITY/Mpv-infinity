#!/usr/bin/env python3
"""Static contract checks for applying interpolation preferences to live playback."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt"
CONTROLS = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/FrameInterpolationControls.kt"
SHEET = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/FrameInterpolationFpsSheet.kt"
SINK = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt"
NATIVE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/NativeMedia3Engine.kt"


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle}")


def main() -> None:
    activity = ACTIVITY.read_text()
    controls = CONTROLS.read_text()
    sheet = SHEET.read_text()
    sink = SINK.read_text()
    native = NATIVE.read_text()
    require(activity, "fun applyInterpolationPreferencesFromControls()", "public activity callback")
    require(activity, "InterpolationRuntimeApplyPolicy.decision", "explicit runtime policy")
    require(activity, "toast_interpolation_pending_next_playback_reason", "user-facing backend-specific pending fallback")
    require(activity, "backend.applyInterpolationRuntime", "actual Native live runtime call")
    require(activity, "val backend = if (wasNative) nativeEngine else null", "MPV must not initialize the lazy Native engine")
    callback = activity.split("fun applyInterpolationPreferencesFromControls()", 1)[1].split("private fun handleNativePlaybackEnded", 1)[0]
    for forbidden in ("recreate()", "releaseSurface()", "PlaybackSession.load(", "setupMPV()", "invalidateCoreConfiguration()"):
        if forbidden in callback:
            raise SystemExit(f"forbidden live playback restart in callback: {forbidden}")
    require(activity, "mpv_flow_has_no_safe_live_option_api", "truthful MPV deferred reason")
    require(activity, "Native interpolation controls applied to the current playback", "live Native confirmation")
    require(sink, "suspend fun applyRuntimeInterpolation", "suspend GL runtime API")
    require(sink, "suspendCancellableCoroutine", "cancellable GL dispatch")
    require(sink, "glHandler.post", "GL handler dispatch")
    require(sink, "continuation.isActive", "cancellation guard")
    require(sink, "continuation.resume(true)", "actual apply ACK")
    require(sink, "enabled = interpolationEnabled", "truthful diagnostics enabled state")
    require(native, "suspend fun applyInterpolationRuntime", "suspend Native API")
    callback = activity.split("fun applyInterpolationPreferencesFromControls()", 1)[1].split("private fun handleNativePlaybackEnded", 1)[0]
    require(callback, "lifecycleScope.launch", "nonblocking activity launch")
    require(callback, "backend.applyInterpolationRuntime(enabled = enabled, targetFps = targetFps)", "Native FPS consumption")
    require(callback, "activeEngineMode == PlaybackEngineMode.NATIVE && nativeEngine === backend", "same backend ACK guard")
    if "runOnGlThreadSync" in callback:
        raise SystemExit("UI callback must not synchronously wait for GL")
    require(controls, "applyInterpolationPreferencesFromControls()", "interpolation toggle caller")
    require(sheet, "applyInterpolationPreferencesFromControls()", "FPS sheet caller")
    require(controls, "mpvFlowFrameInterpolation", "interpolation control preference")
    require(sheet, "mpvFlowTargetFps", "FPS preference")
    print("interpolation runtime apply contract: PASS")


if __name__ == "__main__":
    main()
