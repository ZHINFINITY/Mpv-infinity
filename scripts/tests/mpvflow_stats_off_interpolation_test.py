#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MORE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt"
ACTIVITY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt"
MPV = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt"
POLICY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerStatsLuaCommandPolicy.kt"
SHORTCUT = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/FrameInterpolationControls.kt"
SHARED = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsShared.kt"


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise SystemExit(f"forbidden {label}: {needle}")


def main() -> None:
    more = MORE.read_text(encoding="utf-8")
    activity = ACTIVITY.read_text(encoding="utf-8")
    mpv = MPV.read_text(encoding="utf-8")
    policy = POLICY.read_text(encoding="utf-8")
    shortcut = SHORTCUT.read_text(encoding="utf-8")
    shared = SHARED.read_text(encoding="utf-8")

    require(more, "PlayerStatsLuaCommandPolicy.forChip(", "More sheet Off uses lua command policy")
    require(more, 'Text("Engine: ${activeEngine.title}"', "More sheet keeps v2.1.5 Engine row")
    require(more, 'Text("Output: MPV renderer"', "More sheet keeps v2.1.5 MPV Output row")
    forbid(more, "scanout", "More sheet must not claim scanout FPS")
    require(more, "Decoder:", "More sheet keeps v2.1.5 Decoder row")

    frame_nav = shared.split("PlayerButton.FRAME_NAVIGATION")[1].split("PlayerButton.FRAME_INTERPOLATION")[0]
    if frame_nav.count("AnimatedContent(") != 1:
        raise SystemExit("FRAME_NAVIGATION must keep a single AnimatedContent call")
    forbid(
        shortcut,
        "import app.infinity.mpvz.ui.player.controls.LocalHidePlayerButtonsBackground",
        "hide-background local is in the components package",
    )
    require(shortcut, "LocalHidePlayerButtonsBackground.current", "FPS chip still honors hidden button backgrounds")

    require(policy, "clearOsd = true", "Off clears leftover OSD")
    require(policy, "fun forStartup(page: Int): Decision? =", "startup restore helper stays")
    require(policy, "Decision(displayPage = page, luaVisible = true)", "startup restore does not extra-toggle")

    require(shortcut, "preferences.mpvFlowFrameInterpolation.set(!preferences.mpvFlowFrameInterpolation.get())", "shortcut still writes the interpolation pref")
    require(shortcut, "activity.applyInterpolationPreferencesFromControls()", "shortcut still asks activity to apply")
    require(activity, "private fun requestMpvInterpolationRestart()", "MPV interpolation shortcut restarts the core")
    require(activity, "player.interpolationConfigurationApplied(enabled, targetFps)", "restart skips when options already accepted")
    require(activity, "captureForegroundResumePosition()", "video background captures time-pos")
    require(mpv, "internal fun interpolationConfigurationApplied(enabled: Boolean, targetFps: Int): Boolean", "interpolation ACK helper stays")
    print("mpvflow stats Off and interpolation shortcut wiring ok")


if __name__ == "__main__":
    main()
