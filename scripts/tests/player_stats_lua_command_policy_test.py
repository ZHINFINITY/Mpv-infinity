#!/usr/bin/env python3
"""Source contracts for v2.2.3 stats.lua wiring and the existing playback integrations."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt"
MPV_VIEW = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt"
MORE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt"
SHARED_CONTROLS = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsShared.kt"
ENGINE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/NativeMedia3Engine.kt"
POLICY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerStatsLuaCommandPolicy.kt"
RESTORE_POLICY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerStatsLuaRestorePolicy.kt"
OVERLAY_POLICY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerStatsOverlayPolicy.kt"
LIFECYCLE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerLifecyclePolicy.kt"
CONTROLS = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt"
AUDIO = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt"
OVERLAY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/StatsLuaOverlay.kt"


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
    shared_controls = SHARED_CONTROLS.read_text(encoding="utf-8")
    engine = ENGINE.read_text(encoding="utf-8")
    lifecycle = LIFECYCLE.read_text(encoding="utf-8")
    controls = CONTROLS.read_text(encoding="utf-8")
    audio = AUDIO.read_text(encoding="utf-8")
    overlay = OVERLAY.read_text(encoding="utf-8")

    # Public v2.2.3 is the sole Lua stats visibility owner.
    if POLICY.exists():
        raise SystemExit("forbidden PlayerStatsLuaCommandPolicy.kt is back")
    if RESTORE_POLICY.exists() or OVERLAY_POLICY.exists():
        raise SystemExit("follow-up stats policy must not replace the release call flow")
    require(overlay, "@Volatile", "release in-memory visibility state")
    require(overlay, "var shown: Boolean = false", "release in-memory visibility state")
    require(overlay, "fun onCoreStarted()", "release new-core state reset")
    require(overlay, "fun hide() {\n    if (!shown) return", "Off never toggles hidden stats on")
    require(overlay, 'PlaybackSession.command("script-binding", "stats/display-stats-toggle")', "release stats toggle")
    require(overlay, 'PlaybackSession.command("script-binding", "stats/display-page-$page")', "release page selection")
    forbid(overlay, "VISIBLE_PROPERTY", "MPV user-data is not visibility authority")

    # Release startup restoration lives in MPVView.postInitOptions, not in later Activity hooks.
    post_init = mpv_view.split("override fun postInitOptions()", 1)[1].split("\n  }\n", 1)[0]
    require(post_init, "StatsLuaOverlay.onCoreStarted()", "post-init state reset")
    require(post_init, "isDefinitelyAudioOnly() != true", "post-init is video-only")
    require(post_init, "StatsLuaOverlay.show(advancedPreferences.enabledStatisticsPage.get())", "post-init saved page")
    require(post_init, "StatsLuaOverlay.onCoreStarted()\n    if", "release reset-before-show order")
    forbid(mpv_view, "applyStartupStatistics", "no parallel startup restore helper")
    forbid(mpv_view, "PlayerStatsLuaRestorePolicy", "no marker-based restore policy")

    # More-sheet Off and page selection dispatch first, then persist exactly as the release did.
    require(more, "StatsLuaOverlay.show(page)", "More sheet page selection")
    require(more, "0 -> {\n                  StatsLuaOverlay.hide()", "More sheet Off hide")
    require(more, "6 -> {\n                  StatsLuaOverlay.hide()", "Compose page 6 hides Lua")
    require(more, "if (activeEngine == PlaybackEngineMode.NATIVE) {", "Native page branch")
    stats_selection = more.split('val isConsoleOpen = PlaybackSession.getPropertyBoolean("user-data/mpv/console/open") == true', 1)[1]
    page_commands = stats_selection.find("when (page)")
    saved_page = stats_selection.find("advancedPreferences.enabledStatisticsPage.set(page)")
    if page_commands < 0 or saved_page <= page_commands:
        raise SystemExit("release stats command must run before persisting the selected page")

    # The time/network button's page-6 path also hides Lua before persisting page 6.
    require(shared_controls, "StatsLuaOverlay.hide()", "time/network hides Lua stats")
    page_six_branch = shared_controls.split("if (statisticsPage == 6) {", 1)[1]
    hide_lua = page_six_branch.find("StatsLuaOverlay.hide()")
    save_page_six = page_six_branch.find("advancedPreferences.enabledStatisticsPage.set(6)")
    if hide_lua < 0 or save_page_six <= hide_lua:
        raise SystemExit("time/network page 6 must hide Lua before saving the page")

    # No competing Activity or interpolation restore is allowed to re-show stats after Off.
    forbid(activity, "PlayerStatsLuaCommandPolicy", "no Activity visibility policy")
    forbid(activity, "PlayerStatsLuaRestorePolicy", "no marker restore policy")
    forbid(activity, "applyStartupStatistics", "no file-load stats restore")
    forbid(activity, "scheduleInterpolationStatsRestore", "no interpolation stats restore loop")
    forbid(activity, "restoreLuaAfterVideoSurfaceShown", "no surface-return stats restore")
    forbid(activity, "stats/display-", "Activity must not send stats.lua commands")
    require(activity, "hideMpvSurfaceAndOverlayForAudioOrMinimize()", "music/minimize surface helper")
    require(activity, "binding.player.visibility = View.GONE", "music GONE surface")
    require(activity, "binding.media3SubtitleOverlay.visibility = View.GONE", "music GONE subtitle overlay")
    forbid(activity, "View.INVISIBLE", "INVISIBLE still composites stats.lua")
    require(activity, "onAudioMinimizePresentationComplete()", "minimize completion exists")
    minimize = activity.split("fun onAudioMinimizePresentationComplete()", 1)[1].split("val audioOpenFromMiniRequest", 1)[0]
    require(minimize, "hideMpvSurfaceAndOverlayForAudioOrMinimize()", "minimize hides compositor")

    # No raw stats toggle can bypass the stable owner.
    for path in (ROOT / "app/src/main/java").rglob("*.kt"):
        if path.name != "StatsLuaOverlay.kt":
            forbid(path.read_text(encoding="utf-8"), "stats/display-stats-toggle", path.name + " raw stats toggle")

    # Keep the unrelated background recovery, native cue, and music-hide paths intact.
    require(lifecycle, "LONG_BACKGROUND_REBUILD_THRESHOLD_MS = 900_000L", "15-minute ColorOS rebuild")
    require(lifecycle, "fun shouldCaptureForegroundResumePosition", "video captures last position")
    require(activity, "captureForegroundResumePosition()", "background captures playback time")
    require(activity, "restoreForegroundResumePositionIfNeeded()", "background restores current position")
    require(activity, "rebuildRendererAfterLongBackground()", "15-minute rebuild remains")
    require(activity, "preserveTorrentSession = activeTorrentSourceUri != null", "torrent session kept on rebuild")
    require(activity, "private var nativeEngineConfigured = false", "native configured flag")
    require(activity, "val nativePlaybackSnapshot get() = nativeEngineOrNull()?.snapshot ?: idleNativePlaybackSnapshot", "native snapshot getter")
    require(activity, "val engine = nativeEngineOrNull() ?: return@repeatOnLifecycle", "native cue collector")
    require(activity, "engine.subtitleCueText.collect { cue ->", "subtitle cue collection")
    forbid(activity, "flatMapLatest", "collector regression")
    forbid(activity, "emptyFlow()", "emptyFlow cue stall")
    forbid(activity, "collectAsState(initial = NativePlaybackSnapshot())", "idle snapshot initial")
    forbid(controls, "collectAsState(initial = NativePlaybackSnapshot())", "PlayerControls idle snapshot initial")
    forbid(audio, "collectAsState(initial = NativePlaybackSnapshot())", "AudioPlayerControls idle snapshot initial")
    require(audio, "if (currentItem?.isDefinitelyAudioOnly() == true) StatsLuaOverlay.hide()", "per-track music stats hide")
    require(controls, "StatsLuaOverlay.hide()", "music entry stats hide")
    forbid(audio, "StatsLuaOverlay.show(", "music never shows stats")
    forbid(controls, "StatsLuaOverlay.show(", "music entry never shows stats")
    require(engine, "NativeSubtitleParserFactory(AssSubtitleParserFactory(assHandler))", "ASS-only native factory")
    forbid(engine, "DefaultSubtitleParserFactory", "composite VTT parser stays reverted")
    print("v2.2.3 stats.lua call order and playback wiring contracts: PASS")


if __name__ == "__main__":
    main()
