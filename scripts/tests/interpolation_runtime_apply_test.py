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
    require(activity, "native_flow_sink_unavailable", "truthful Native sink-unavailable logging")
    require(activity, "backend.applyInterpolationRuntime", "actual Native live runtime call")
    require(activity, "val backend = if (wasNative) nativeEngineOrNull() else null", "MPV must not initialize the lazy Native engine")
    callback = activity.split("fun applyInterpolationPreferencesFromControls()", 1)[1].split("private fun requestMpvInterpolationRestart", 1)[0]
    for forbidden in ("recreate()", "releaseSurface()", "PlaybackSession.load(", "setupMPV()", "invalidateCoreConfiguration()"):
        if forbidden in callback:
            raise SystemExit(f"forbidden live playback restart in callback: {forbidden}")
    require(callback, "if (!wasNative)", "MPV-only restart branch")
    require(callback, "requestMpvInterpolationRestart()", "scoped MPV restart entry")
    native_path = callback.split("val backend", 1)[1]
    if "requestMpvInterpolationRestart()" in native_path:
        raise SystemExit("Native must never enter the MPV restart transaction")
    require(activity, "MpvInterpolationRestartTransaction.run(snapshot", "tested MPV transaction")
    require(activity, "paused = true", "hold reload paused until all live state is restored")
    require(activity, "forceMpvReload = true", "external subtitle descriptor reattachment")
    require(activity, "player.interpolationConfigurationApplied(appliedEnabled, appliedFps)", "actual init-option ACK")
    require(activity, "Native interpolation controls applied to the current playback", "live Native confirmation")
    require(sink, "suspend fun applyRuntimeInterpolation", "suspend GL runtime API")
    require(sink, "suspendCancellableCoroutine", "cancellable GL dispatch")
    require(sink, "glHandler.post", "GL handler dispatch")
    require(sink, "continuation.isActive", "cancellation guard")
    require(sink, "continuation.resume(true)", "actual apply ACK")
    require(sink, "enabled = interpolationEnabled", "truthful diagnostics enabled state")
    require(native, "suspend fun applyInterpolationRuntime", "suspend Native API")
    callback = activity.split("fun applyInterpolationPreferencesFromControls()", 1)[1].split("private fun requestMpvInterpolationRestart", 1)[0]
    require(callback, "lifecycleScope.launch", "nonblocking activity launch")
    require(callback, "backend.applyInterpolationRuntime(enabled = enabled, targetFps = targetFps)", "Native FPS consumption")
    require(callback, "activeEngineMode == PlaybackEngineMode.NATIVE && nativeEngineConfigured && nativeEngine === backend", "same backend ACK guard")
    if "runOnGlThreadSync" in callback:
        raise SystemExit("UI callback must not synchronously wait for GL")
    require(controls, "applyInterpolationPreferencesFromControls()", "interpolation toggle caller")
    require(sheet, "applyInterpolationPreferencesFromControls()", "FPS sheet caller")
    require(controls, "mpvFlowFrameInterpolation", "interpolation control preference")
    require(sheet, "mpvFlowTargetFps", "FPS preference")
    timing = controls.split("internal fun interpolationApplyTimingNote", 1)[1].split("internal fun interpolationControlsAvailable", 1)[0]
    require(timing, "activity.isNativeEngineActive()", "actual engine timing")
    require(timing, "interpolation_feedback_mpv_config", "config override explanation")
    require(timing, "interpolation_feedback_mpv", "MPV explicit controlled-restart timing")
    require(sheet, "interpolationApplyTimingNote(activity)", "FPS sheet engine-specific timing")
    require(sheet, "if (!interpolationEnabled)", "FPS selection does not imply enabled interpolation")
    require(sheet, "interpolation_feedback_target_hint", "explicit enable-first hint")
    require(callback, "requestGeneration != interpolationControlRequestGeneration", "stale request ACK suppression")
    require(callback, "enabled != decoderPreferences.mpvFlowFrameInterpolation.get()", "latest preference ACK")
    if "Toast.makeText" in callback or "showSnackbar" in callback:
        raise SystemExit("routine Native interpolation shortcut popup must stay removed")
    restart = activity.split("private fun requestMpvInterpolationRestart", 1)[1].split("private fun handleNativePlaybackEnded", 1)[0]
    for removed_popup in ("toast_interpolation_restarting_mpv", "toast_interpolation_restarted_mpv", "toast_interpolation_configured_mpv"):
        if removed_popup in restart:
            raise SystemExit(f"routine MPV interpolation popup must stay removed: {removed_popup}")
    require(restart, "toast_interpolation_restart_failed", "genuine MPV failure feedback preserved")
    require(restart, "mpvInterpolationRestartJob?.isActive == true", "single serialized MPV transaction")
    require(restart, "request == mediaRequestGeneration", "media request race guard")
    require(restart, "queueIndexBefore", "queue index race guard")
    require(restart, "expectedRetiredGeneration = snapshot.sessionGeneration", "captured pre-reload generation fence")
    require(restart, "MpvRestartOwnershipPolicy.accepts", "phase-aware native generation fence")
    require(restart, "coreReplacementStarted = true", "explicit authorization of this core gap")
    require(restart, "onGenerationReserved =", "native event ownership published before loadfile")
    require(restart, "ownsOwner = ownsPlaybackSession()", "current activity ownership gate")
    require(restart, "mpvInterpolationNativeWork.execute", "serialized native work off Main")
    require(restart, "verifyNativePathAfterRestart = true", "scoped native-source event identity")
    require(restart, "TrackSelectionMatcher.findMatchIndex", "fingerprint-based track remapping")
    require(restart, "state.positionSeconds", "exact live position restoration")
    require(restart, 'getPropertyBoolean("pause") == state.paused', "actual pause restoration ACK")
    session = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlaybackSession.kt").read_text()
    require(session, "generation = PlaybackGenerationPolicy.next(it.generation)", "retired-core generation tombstone")
    require(session, "MpvRestartLoadFencePolicy.accepts", "native FILE_LOADED cannot promote a mismatched source")
    print("interpolation runtime apply contract: PASS")


if __name__ == "__main__":
    main()
