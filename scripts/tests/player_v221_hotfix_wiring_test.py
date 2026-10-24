#!/usr/bin/env python3
"""Surrounding-wiring contracts for the v2.2.1 hotfix. Interpolation internals stay unchanged."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt"
ENUMS = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerEnums.kt"
CONTROLS = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt"
RESUME = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/AudioPlaybackResumeStore.kt"
POLICY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerStatsLuaCommandPolicy.kt"
SHORTCUT = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/FrameInterpolationControls.kt"
MPV_VIEW = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt"
BUILD = ROOT / ".github/workflows/build.yml"


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise SystemExit(f"forbidden {label}: {needle}")


def main() -> None:
    activity = ACTIVITY.read_text(encoding="utf-8")
    enums = ENUMS.read_text(encoding="utf-8")
    controls = CONTROLS.read_text(encoding="utf-8")
    resume = RESUME.read_text(encoding="utf-8")
    policy = POLICY.read_text(encoding="utf-8")
    shortcut = SHORTCUT.read_text(encoding="utf-8")
    mpv_view = MPV_VIEW.read_text(encoding="utf-8")
    build = BUILD.read_text(encoding="utf-8")

    require(shortcut, "preferences.mpvFlowFrameInterpolation.set(!preferences.mpvFlowFrameInterpolation.get())", "shortcut still writes the interpolation pref")
    require(shortcut, "activity.applyInterpolationPreferencesFromControls()", "shortcut still asks activity to apply")
    require(mpv_view, "internal fun interpolationConfigurationApplied(enabled: Boolean, targetFps: Int): Boolean", "interpolation ACK helper stays")
    require(mpv_view, "acceptedInterpolationConfiguration", "accepted interpolation config stays")

    require(enums, "HWPlus(\"HW+\", \"mediacodec\")", "HW+ value is still mediacodec")
    require(enums, 'val token = value.substringBefore(\',\').trim()', "interpolation hwdec lists map from first token")
    require(enums, "HW.value -> HW", "copy path stays HW")
    require(enums, "HWPlus.value -> HWPlus", "direct mediacodec stays HW+")

    require(controls, "decoderPreferences.tryHWDecoding.set(it != Decoder.SW)", "decoder sheet persists hardware decoding")
    require(controls, 'PlaybackSession.setPropertyString("hwdec", it.value)', "decoder sheet still sets hwdec")

    require(resume, "currentItem.declaredMediaKind() == DeclaredPlaybackMediaKind.VIDEO", "last-song save rejects video, not unknown audio")
    require(resume, "item.declaredMediaKind() != DeclaredPlaybackMediaKind.VIDEO", "last-song load keeps unknown audio")
    forbid(resume, "isDefinitelyAudioOnly()", "extensionless last-song must not require a definite audio mime")

    require(activity, 'putExtra("media_artist", currentItem.artist)', "last-song restore keeps artist")
    require(activity, 'putStringArrayListExtra("playlist_artwork_urls", arrayListOf(artwork))', "last-song restore keeps artwork")
    require(activity, "return pauseRestored", "MPV shortcut success is restored playback, not ACK-only")
    forbid(activity.split("private fun requestMpvInterpolationRestart", 1)[1].split("private fun handleNativePlaybackEnded", 1)[0], "return optionsApplied && pauseRestored", "ACK must not fail a completed MPV restart")

    vo = activity.split("private fun onInterpolationVoReconfig", 1)[1].split("True when the current playback session was launched from the Secure Folder", 1)[0]
    forbid(vo, "player.applyStartupStatistics(force = true)", "vo-reconfig must not send a second display-page")
    require(policy, "fun shouldReapplyAfterVoReconfig(luaRestored: Boolean, alreadyReapplied: Boolean): Boolean =\n    false", "vo-reconfig policy does not toggle")
    require(build, "hotfix/v2.2.1-player-restore", "hotfix branch is arm64-only")
    require(build, "OVERLAY_MPVFLOW_ARM64", "signed CI overlay flag")
    require(build, "Overlay Flow-only runtime and rebuild Standard arm64-v8a release", "cached Flow overlay before sign")
    require(build, "-PmpvFlowMpvBuild=true", "Flow arm64 signed rebuild")
    forbid(build, "softprops/action-gh-release", "CI/CD must not publish a GitHub Release")
    print("v2.2.1 hotfix surrounding wiring contracts: PASS")


if __name__ == "__main__":
    main()
