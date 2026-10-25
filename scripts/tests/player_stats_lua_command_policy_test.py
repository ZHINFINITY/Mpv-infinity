#!/usr/bin/env python3
"""Source contracts for known-good stats.lua wiring and Native 7cd7e cue wiring."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt"
MPV_VIEW = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt"
MORE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt"
ENGINE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/NativeMedia3Engine.kt"
POLICY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerStatsLuaCommandPolicy.kt"
LIFECYCLE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerLifecyclePolicy.kt"
CONTROLS = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt"
AUDIO = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt"


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise SystemExit(f"forbidden {label}: {needle}")


def main() -> None:
    activity = ACTIVITY.read_text(encoding="utf-8")
    mpv_view = MPV_VIEW.read_text(encoding="utf-8")
    more = MORE.read_text(encoding="utf-8")
    engine = ENGINE.read_text(encoding="utf-8")
    lifecycle = LIFECYCLE.read_text(encoding="utf-8")
    controls = CONTROLS.read_text(encoding="utf-8")
    audio = AUDIO.read_text(encoding="utf-8")

    # Known-good 380a778b stats.lua wiring: no policy object, no Activity restore machinery.
    if POLICY.exists():
        raise SystemExit("forbidden PlayerStatsLuaCommandPolicy.kt is back")
    # Same 380a778b command sequence (toggle on once + display-page-N; toggle off for Off/6/7),
    # sent through StatsLuaOverlay so the app tracks the real overlay state and music never
    # flips a blind toggle that turns stats ON (owner report: stats over a FLAC).
    tracker = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/StatsLuaOverlay.kt").read_text(encoding="utf-8")
    require(tracker, 'PlaybackSession.command("script-binding", "stats/display-stats-toggle")', "tracker toggle")
    require(tracker, 'PlaybackSession.command("script-binding", "stats/display-page-$page")', "tracker page select")
    require(tracker, "fun hide() {\n    if (!shown) return", "hide never turns stats on")
    require(more, "0 -> {\n                  StatsLuaOverlay.hide()", "known-good Off hides")
    require(more, "StatsLuaOverlay.show(page)", "known-good page select")
    require(more, "if (activeEngine == PlaybackEngineMode.NATIVE) {", "Native chip only sets the page")
    require(mpv_view, "StatsLuaOverlay.onCoreStarted()", "new core starts with no overlay")
    require(mpv_view, "StatsLuaOverlay.show(advancedPreferences.enabledStatisticsPage.get())", "postInitOptions restore")
    require(mpv_view, "isDefinitelyAudioOnly() != true", "postInitOptions restore is video-only")
    for path in (ROOT / "app/src/main/java").rglob("*.kt"):
        if path.name != "StatsLuaOverlay.kt":
            forbid(path.read_text(encoding="utf-8"), "stats/display-stats-toggle", path.name + " raw stats toggle")
    for text, label in ((activity, "PlayerActivity"), (mpv_view, "MPVView"), (more, "MoreSheet")):
        forbid(text, "PlayerStatsLuaCommandPolicy", label + " policy object")
        forbid(text, "applyStartupStatistics", label + " startup restore helper")
    forbid(activity, "scheduleInterpolationStatsRestore", "interpolation restore machinery")
    forbid(activity, "stats/display-", "Activity must not send stats.lua commands")
    require(activity, "hideMpvSurfaceAndOverlayForAudioOrMinimize()", "music/minimize hide helper")
    require(activity, "binding.player.visibility = View.GONE", "music GONE surface")
    require(activity, "binding.media3SubtitleOverlay.visibility = View.GONE", "music GONE subtitle overlay")
    forbid(activity, "View.INVISIBLE", "INVISIBLE still composites stats.lua")
    require(activity, "onAudioMinimizePresentationComplete()", "minimize completion exists")
    minimize = activity.split("fun onAudioMinimizePresentationComplete()", 1)[1].split("val audioOpenFromMiniRequest", 1)[0]
    require(minimize, "hideMpvSurfaceAndOverlayForAudioOrMinimize()", "minimize hides compositor")
    require(lifecycle, "LONG_BACKGROUND_REBUILD_THRESHOLD_MS = 900_000L", "15-minute ColorOS rebuild")
    require(lifecycle, "fun shouldCaptureForegroundResumePosition", "video captures last position")
    require(activity, "captureForegroundResumePosition()", "pause/stop captures time-pos before Surface death")
    require(activity, "restoreForegroundResumePositionIfNeeded()", "any background restores last position")
    require(activity, "rebuildRendererAfterLongBackground()", "15-minute rebuild remains")
    require(activity, "preserveTorrentSession = activeTorrentSourceUri != null", "torrent session kept on rebuild")

    require(activity, "private var nativeEngineConfigured = false", "7cd7e configured flag")
    require(
        activity,
        "val nativePlaybackSnapshot get() = nativeEngineOrNull()?.snapshot ?: idleNativePlaybackSnapshot",
        "7cd7e snapshot getter",
    )
    require(activity, "val engine = nativeEngineOrNull() ?: return@repeatOnLifecycle", "7cd7e cue collector")
    require(activity, "engine.subtitleCueText.collect { cue ->", "subtitleCueText collection")
    forbid(activity, "flatMapLatest", "CodeRabbit collector regression")
    forbid(activity, "emptyFlow()", "emptyFlow cue stall")
    forbid(activity, "collectAsState(initial = NativePlaybackSnapshot())", "idle snapshot initial")
    forbid(controls, "collectAsState(initial = NativePlaybackSnapshot())", "PlayerControls idle snapshot initial")
    forbid(audio, "collectAsState(initial = NativePlaybackSnapshot())", "AudioPlayerControls idle snapshot initial")
    # 380a778b music stats hides kept in the same places (per track and on music entry), but
    # they can only hide: a hide when stats are already off no longer toggles them back on.
    require(audio, "if (currentItem?.isDefinitelyAudioOnly() == true) StatsLuaOverlay.hide()", "per-track music stats hide")
    require(controls, "if (useAudioPlayer) StatsLuaOverlay.hide()", "music entry stats hide")
    forbid(audio, "StatsLuaOverlay.show(", "music never shows stats")
    forbid(controls, "StatsLuaOverlay.show(", "music entry never shows stats")
    require(engine, "NativeSubtitleParserFactory(AssSubtitleParserFactory(assHandler))", "ASS-only native factory")
    forbid(engine, "DefaultSubtitleParserFactory", "composite VTT parser stays reverted")
    print("player stats lua command and native cue wiring contracts: PASS")


if __name__ == "__main__":
    main()
