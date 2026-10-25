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
    require(more, 'if (statisticsPage in 1..5) PlaybackSession.command("script-binding", "stats/display-stats-toggle")', "known-good Off toggle")
    require(more, 'PlaybackSession.command("script-binding", "stats/display-page-$page")', "known-good page select")
    require(more, "if (activeEngine == PlaybackEngineMode.NATIVE) {", "Native chip only sets the page")
    require(mpv_view, 'PlaybackSession.command("script-binding", "stats/display-page-$it")', "postInitOptions restore")
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
    # Known-good 380a778b music stats hides: per track in AudioPlayerControls and once on entry in PlayerControls.
    require(audio, 'if (currentItem?.isDefinitelyAudioOnly() == true && advancedPreferences.enabledStatisticsPage.get() in 1..5) {\n      PlaybackSession.command("script-binding", "stats/display-stats-toggle")', "1901 per-track music stats hide")
    require(controls, 'if (useAudioPlayer && statisticsPage in 1..5) {\n      advancedPreferences.enabledStatisticsPage.set(0)\n      PlaybackSession.command("script-binding", "stats/display-stats-toggle")', "1901 music entry stats hide")
    require(engine, "NativeSubtitleParserFactory(AssSubtitleParserFactory(assHandler))", "ASS-only native factory")
    forbid(engine, "DefaultSubtitleParserFactory", "composite VTT parser stays reverted")
    print("player stats lua command and native cue wiring contracts: PASS")


if __name__ == "__main__":
    main()
