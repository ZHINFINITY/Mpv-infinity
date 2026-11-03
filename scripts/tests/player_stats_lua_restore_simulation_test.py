#!/usr/bin/env python3
"""Simulate v2.2.3 stats.lua visibility, Off ordering, and restore timing."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


GONE = 8
INVISIBLE = 4
VISIBLE = 0


@dataclass
class StatsLuaOverlay:
    """Model the app's release StatsLuaOverlay owner, not MPV user-data state."""

    shown: bool = False
    page: int = 0
    commands: list[tuple[str, ...]] = field(default_factory=list)

    def hide(self) -> None:
        if not self.shown:
            return
        self.commands.append(("script-binding", "stats/display-stats-toggle"))
        self.shown = False

    def show(self, page: int) -> None:
        if page not in range(1, 6):
            return
        if not self.shown:
            self.commands.append(("script-binding", "stats/display-stats-toggle"))
            self.shown = True
        self.commands.append(("script-binding", f"stats/display-page-{page}"))
        self.page = page


@dataclass
class Player:
    enabled_page: int = 1
    lua: StatsLuaOverlay = field(default_factory=StatsLuaOverlay)
    startup_applied: bool = False
    file_loaded: bool = False
    time_pos: Optional[float] = None
    lua_restored: bool = False
    vo_reapplied: bool = False
    awaiting: bool = False
    is_music: bool = False
    interpolation_enabled: bool = False
    player_visibility: int = VISIBLE
    overlay_visibility: int = VISIBLE

    def apply_startup(self, force: bool = False) -> None:
        if self.is_music:
            return
        if not force and self.startup_applied:
            return
        self.startup_applied = True
        self.lua.show(self.enabled_page)

    def try_restore(self, force: bool = False) -> bool:
        if self.is_music:
            self.awaiting = False
            return True
        if not (self.file_loaded and self.time_pos is not None):
            return False
        self.apply_startup(force=force)
        self.lua_restored = True
        self.awaiting = False
        return True

    def interpolation_reload_success(self) -> None:
        # The MPV core and its Lua script are new; the app resets the release state.
        self.lua = StatsLuaOverlay()
        self.startup_applied = False
        self.file_loaded = False
        self.time_pos = None
        self.lua_restored = False
        self.vo_reapplied = False
        self.awaiting = True

    def on_file_loaded(self) -> None:
        self.file_loaded = True
        self.try_restore()

    def on_first_time_pos(self, value: float) -> None:
        self.time_pos = value
        self.try_restore()

    def on_vo_reconfig(self) -> None:
        # stats.lua keeps persistent mode across VO changes and redraws its page.
        if not (self.lua_restored and not self.vo_reapplied):
            return
        self.vo_reapplied = True
        self.apply_startup(force=True)

    def select_off(self) -> None:
        # Persist first: a concurrent forced restore must read page 0, not the old page.
        self.enabled_page = 0
        if self.interpolation_enabled:
            self.on_vo_reconfig()
        self.lua.hide()

    def hide_for_music_or_minimize(self) -> None:
        self.player_visibility = GONE
        self.overlay_visibility = GONE
        self.lua.hide()

    def restore_video_surface(self) -> None:
        self.player_visibility = VISIBLE
        if self.enabled_page in range(1, 6) and not self.lua.shown:
            self.lua.show(self.enabled_page)

    def composites_stats(self) -> bool:
        return self.player_visibility != GONE and self.lua.shown


def assert_true(cond: bool, message: str) -> None:
    if not cond:
        raise SystemExit(message)


def test_off_hides_stats_with_interpolation_enabled_and_disabled() -> None:
    for interpolation_enabled in (False, True):
        player = Player(interpolation_enabled=interpolation_enabled)
        player.lua.show(1)
        assert_true(player.lua.shown, "precondition: stats page 1 is visible")
        player.lua_restored = True
        player.select_off()
        assert_true(player.enabled_page == 0, "Off is persisted before any reapply")
        assert_true(not player.lua.shown, f"Off hides stats with interpolation={interpolation_enabled}")
        toggles = [cmd for cmd in player.lua.commands if cmd == ("script-binding", "stats/display-stats-toggle")]
        assert_true(len(toggles) == 2, "one show toggle and one Off toggle are sent")


def test_off_is_idempotent_when_lua_is_already_hidden() -> None:
    player = Player(interpolation_enabled=True)
    player.enabled_page = 1
    player.select_off()
    assert_true(not player.lua.shown, "hidden stats stay hidden")
    assert_true(player.lua.commands == [], "Off does not toggle a hidden overlay on")


def test_interpolation_restore_waits_and_reapplies_once() -> None:
    player = Player()
    player.interpolation_reload_success()
    assert_true(not player.try_restore(), "restore waits for file-loaded/time-pos")
    player.on_file_loaded()
    assert_true(not player.lua.shown, "file-loaded without time-pos still waits")
    player.on_first_time_pos(12.5)
    assert_true(player.lua.shown and player.lua.page == 1, "restore shows saved page after playback is ready")
    first_toggle_count = sum(cmd == ("script-binding", "stats/display-stats-toggle") for cmd in player.lua.commands)
    player.on_vo_reconfig()
    player.on_vo_reconfig()
    assert_true(player.lua.shown, "VO reconfiguration does not clear persistent stats")
    assert_true(
        sum(cmd == ("script-binding", "stats/display-stats-toggle") for cmd in player.lua.commands) == first_toggle_count,
        "reconfiguration restore does not invert persistent visibility",
    )


def test_music_and_minimize_hide_once_then_video_restores_saved_page() -> None:
    player = Player()
    player.lua.show(1)
    player.hide_for_music_or_minimize()
    assert_true(player.player_visibility == GONE, "music must GONE the SurfaceView")
    assert_true(player.overlay_visibility == GONE, "music must GONE the subtitle overlay")
    assert_true(player.player_visibility != INVISIBLE, "INVISIBLE still composites lua")
    assert_true(not player.lua.shown, "entering music hides lua once")
    assert_true(player.enabled_page == 1, "music hide preserves the user's page")
    assert_true(not player.composites_stats(), "Now Playing must not show lua")
    player.hide_for_music_or_minimize()
    toggles = [cmd for cmd in player.lua.commands if cmd == ("script-binding", "stats/display-stats-toggle")]
    assert_true(len(toggles) == 2, "second music hide does not invert visibility")
    player.is_music = False
    player.restore_video_surface()
    assert_true(player.player_visibility == VISIBLE, "video surface returns")
    assert_true(player.lua.shown and player.lua.page == 1, "video stats return through the release show path")


def main() -> None:
    test_off_hides_stats_with_interpolation_enabled_and_disabled()
    test_off_is_idempotent_when_lua_is_already_hidden()
    test_interpolation_restore_waits_and_reapplies_once()
    test_music_and_minimize_hide_once_then_video_restores_saved_page()
    print("v2.2.3 stats.lua Off/restore simulations: PASS")


if __name__ == "__main__":
    main()
