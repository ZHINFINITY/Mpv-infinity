#!/usr/bin/env python3
"""Source contracts for stats.lua Off/restore and Native 7cd7e cue wiring."""
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
    policy = POLICY.read_text(encoding="utf-8")
    lifecycle = LIFECYCLE.read_text(encoding="utf-8")
    controls = CONTROLS.read_text(encoding="utf-8")
    audio = AUDIO.read_text(encoding="utf-8")

    require(
        policy,
        "fun forChip(toPage: Int, previousPage: Int, luaShown: Boolean, consoleOpen: Boolean)",
        "mpvRx MoreSheet page decision",
    )
    require(
        policy,
        "toggleLua = previousPage in 1..5 || luaShown",
        "Off hides from previous lua page even if VISIBLE_PROPERTY is no",
    )
    require(policy, "clearOsd = true", "Off unconditionally clears leftover OSD")
    require(policy, 'PlaybackSession.command("show-text", "", "0")', "Off sends empty OSD hide")
    require(policy, "fun forStartup(page: Int): Decision?", "interpolation restore decision")
    require(
        policy,
        "if (page in 1..5) Decision(displayPage = page, luaVisible = true) else null",
        "interpolation restore is display-page only",
    )
    require(policy, "fun forVideoResume(page: Int, luaShown: Boolean): Decision?", "video resume display-page")
    require(policy, "fun forMusicHide(luaShown: Boolean, consoleOpen: Boolean): Decision", "music Off hide path")
    require(policy, "fun shouldWaitForPlaybackBeforeRestore", "wait for file-loaded/time-pos")
    require(policy, "fun shouldReapplyAfterVoReconfig", "vo-reconfig reapplies once")
    forbid(policy, "Decision(toggleLua = true, displayPage = page, luaVisible = true)", "startup must not toggle")
    require(policy, 'VISIBLE_PROPERTY = "user-data/mpvrx/stats-lua-visible"', "lua visibility property")
    require(policy, "fun onCoreRecreated()", "core recreate resets startup flag")

    require(more, "PlayerStatsLuaCommandPolicy.forChip(", "More sheet uses lua show-state")
    require(more, "previousPage = statisticsPage", "More sheet copies mpvRx previous-page path")
    forbid(more, "if (statisticsPage in 1..5) PlaybackSession.command", "More sheet no longer toggles from persisted page")

    require(mpv_view, "PlayerStatsLuaCommandPolicy.forStartup", "startup restore uses show-state")
    require(mpv_view, "PlayerStatsLuaCommandPolicy.onCoreRecreated()", "new core resets startup-stats-applied")
    require(mpv_view, "fun applyStartupStatistics(force: Boolean = false)", "force restore after vo-reconfig")
    reload = activity.split("MPV reloaded; position, pause, tracks and options confirmed; synthesized frames not inferred", 1)[1]
    reload_block = reload.split("private fun handleNativePlaybackEnded()", 1)[0]
    require(reload_block, "scheduleInterpolationStatsRestore(", "interpolation restore waits for lua")
    forbid(reload_block, "player.applyStartupStatistics()", "reload success is not fire-and-forget display-page")
    require(activity, "MPV_EVENT_VIDEO_RECONFIG", "vo-reconfig reapplies restore")
    require(activity, "tryApplyInterpolationStatsRestore(", "file-loaded applies restore")
    require(activity, "hideMpvSurfaceAndOverlayForAudioOrMinimize()", "music/minimize hide helper")
    require(activity, "binding.player.visibility = View.GONE", "music GONE surface")
    require(activity, "binding.media3SubtitleOverlay.visibility = View.GONE", "music GONE subtitle overlay")
    forbid(activity, "View.INVISIBLE", "INVISIBLE still composites stats.lua")
    require(activity, "forMusicHide(", "music uses Off hide path")
    require(activity, "forVideoResume(", "video return uses display-page")
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
    require(engine, "NativeSubtitleParserFactory(AssSubtitleParserFactory(assHandler))", "ASS-only native factory")
    forbid(engine, "DefaultSubtitleParserFactory", "composite VTT parser stays reverted")
    print("player stats lua command and native cue wiring contracts: PASS")


if __name__ == "__main__":
    main()
