#!/usr/bin/env python3
"""Source contracts for the v2.2.3 stats.lua Off route and restore timing."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt"
MPV_VIEW = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt"
MORE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt"
OVERLAY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/StatsLuaOverlay.kt"
RESTORE_POLICY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerStatsLuaRestorePolicy.kt"
LIFECYCLE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerLifecyclePolicy.kt"
CONTROLS = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt"
SHARED_CONTROLS = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsShared.kt"
AUDIO = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt"
ENGINE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/NativeMedia3Engine.kt"


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
    overlay = OVERLAY.read_text(encoding="utf-8")
    restore_policy = RESTORE_POLICY.read_text(encoding="utf-8")
    lifecycle = LIFECYCLE.read_text(encoding="utf-8")
    controls = CONTROLS.read_text(encoding="utf-8")
    shared_controls = SHARED_CONTROLS.read_text(encoding="utf-8")
    audio = AUDIO.read_text(encoding="utf-8")
    engine = ENGINE.read_text(encoding="utf-8")

    # Public v2.2.3 owns Lua visibility in a process-local volatile flag.
    require(overlay, "@Volatile", "release in-memory visibility state")
    require(overlay, "var shown: Boolean = false", "release in-memory visibility state")
    require(overlay, "fun onCoreStarted()", "new-core visibility reset")
    require(overlay, "if (!wasShown)", "Off is a hide-only action")
    require(overlay, 'PlaybackSession.command("script-binding", "stats/display-stats-toggle")', "release stats toggle")
    forbid(overlay, "VISIBLE_PROPERTY", "MPV user-data is not visibility authority")

    # Off, Compose page 6, and console selection all return through the same stable overlay owner.
    require(more, "StatsLuaOverlay.hide()", "More sheet Off hide path")
    require(more, "StatsLuaOverlay.show(page)", "More sheet stats page selection")
    forbid(more, "PlayerStatsLuaCommandPolicy", "no parallel Lua visibility policy")
    stats_selection = more.split('val isConsoleOpen = PlaybackSession.getPropertyBoolean("user-data/mpv/console/open") == true', 1)[1]
    saved_page = stats_selection.find("advancedPreferences.enabledStatisticsPage.set(page)")
    dispatched_command = stats_selection.find("when (page)")
    if saved_page < 0 or dispatched_command < 0 or saved_page > dispatched_command:
        raise SystemExit("stats page preference must be persisted before hide/show commands")
    require(more, '0, 6 -> StatsLuaOverlay.hide()', "Off and Compose-page hide")
    require(more, 'StatsLuaOverlay.show(page)', "page show")

    # Startup and interpolation callbacks may restore only the selected page; they do not own
    # visibility state and must observe Off after a tap.
    require(restore_policy, "STARTUP_APPLIED_PROPERTY", "per-core startup restore marker")
    require(restore_policy, "fun shouldWaitForPlaybackBeforeRestore", "restore readiness guard")
    require(restore_policy, "fun shouldReapplyAfterVoReconfig", "one-time VO restore guard")
    forbid(restore_policy, "VISIBLE_PROPERTY", "restore policy does not own visibility")
    forbid(restore_policy, "display-stats-toggle", "restore policy does not issue toggles")
    require(mpv_view, "StatsLuaOverlay.onCoreStarted()", "new MPV core resets release overlay state")
    require(mpv_view, "PlayerStatsLuaRestorePolicy.onCoreRecreated()", "startup marker resets per core")
    require(mpv_view, "fun applyStartupStatistics(force: Boolean = false)", "guarded startup restore")
    require(mpv_view, "StatsLuaOverlay.show(advancedPreferences.enabledStatisticsPage.get())", "startup uses release overlay")
    require(activity, "StatsLuaOverlay.hide()", "audio/minimize uses release hide")
    require(activity, "StatsLuaOverlay.show(page)", "video-surface return uses release show")
    require(activity, "PlayerStatsLuaRestorePolicy.shouldWaitForPlaybackBeforeRestore", "interpolation restore waits for playback")
    require(activity, "PlayerStatsLuaRestorePolicy.shouldReapplyAfterVoReconfig", "interpolation VO restore is bounded")

    # Keep the other existing stats/UI contracts intact.
    require(controls, "StatsLuaOverlay.hide()", "music screen hides stats through shared owner")
    require(shared_controls, "StatsLuaOverlay.hide()", "time/network page 6 hides Lua stats")
    require(audio, "StatsLuaOverlay.hide()", "audio player hides stats through shared owner")
    require(activity, "hideMpvSurfaceAndOverlayForAudioOrMinimize()", "music/minimize compositor helper")
    require(activity, "binding.player.visibility = View.GONE", "music GONE surface")
    require(activity, "binding.media3SubtitleOverlay.visibility = View.GONE", "music GONE subtitle overlay")
    forbid(activity, "View.INVISIBLE", "INVISIBLE still composites stats.lua")
    require(lifecycle, "LONG_BACKGROUND_REBUILD_THRESHOLD_MS = 900_000L", "15-minute ColorOS rebuild")
    require(lifecycle, "fun shouldCaptureForegroundResumePosition", "foreground position capture")
    require(activity, "captureForegroundResumePosition()", "background captures playback time")
    require(activity, "restoreForegroundResumePositionIfNeeded()", "background restores current position")
    require(activity, "rebuildRendererAfterLongBackground()", "long-background renderer rebuild")
    require(activity, "preserveTorrentSession = activeTorrentSourceUri != null", "torrent session preserved")

    require(activity, "private var nativeEngineConfigured = false", "7cd7e configured flag")
    require(activity, "val nativePlaybackSnapshot get() = nativeEngineOrNull()?.snapshot ?: idleNativePlaybackSnapshot", "7cd7e snapshot getter")
    require(activity, "val engine = nativeEngineOrNull() ?: return@repeatOnLifecycle", "7cd7e cue collector")
    require(activity, "engine.subtitleCueText.collect { cue ->", "subtitle cue collection")
    forbid(activity, "flatMapLatest", "CodeRabbit collector regression")
    forbid(activity, "emptyFlow()", "emptyFlow cue stall")
    require(engine, "NativeSubtitleParserFactory(AssSubtitleParserFactory(assHandler))", "ASS-only native factory")
    forbid(engine, "DefaultSubtitleParserFactory", "composite VTT parser stays reverted")
    print("v2.2.3 stats.lua Off route and restore contracts: PASS")


if __name__ == "__main__":
    main()
