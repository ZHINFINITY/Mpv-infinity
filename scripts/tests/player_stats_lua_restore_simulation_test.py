#!/usr/bin/env python3
"""Simulate interpolation stats restore and music/minimize compositor hiding."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


GONE = 8
INVISIBLE = 4
VISIBLE = 0


@dataclass
class Decision:
    toggle_lua: bool = False
    display_page: Optional[int] = None
    lua_visible: Optional[bool] = None
    disable_console: bool = False
    enable_console: bool = False


def for_chip(to_page: int, previous_page: int, lua_shown: bool, console_open: bool) -> Decision:
    disable_console = to_page != 7 and console_open
    if to_page in (0, 6):
        return Decision(toggle_lua=lua_shown, lua_visible=False, disable_console=disable_console)
    if to_page == 7:
        return Decision(toggle_lua=lua_shown, lua_visible=False, enable_console=not console_open)
    return Decision(
        toggle_lua=previous_page in (0, 6, 7),
        display_page=to_page,
        lua_visible=True,
        disable_console=disable_console,
    )


def for_startup(page: int) -> Optional[Decision]:
    return Decision(display_page=page, lua_visible=True) if page in range(1, 6) else None


def for_video_resume(page: int, lua_shown: bool) -> Optional[Decision]:
    return Decision(display_page=page, lua_visible=True) if page in range(1, 6) and not lua_shown else None


def for_music_hide(lua_shown: bool, console_open: bool) -> Decision:
    return for_chip(0, 0, lua_shown, console_open)


def should_wait_for_playback_before_restore(file_loaded: bool, time_pos_finite: bool) -> bool:
    return file_loaded and time_pos_finite


def should_reapply_after_vo_reconfig(lua_restored: bool, already_reapplied: bool) -> bool:
    return lua_restored and not already_reapplied


@dataclass
class StatsLua:
    bound: bool = False
    shown: bool = False
    page: int = 0
    commands: list[tuple[str, ...]] = field(default_factory=list)

    def handle(self, *args: str) -> None:
        self.commands.append(args)
        if args == ("script-binding", "stats/display-stats-toggle"):
            if not self.bound:
                return
            self.shown = not self.shown
            return
        if len(args) == 2 and args[0] == "script-binding" and args[1].startswith("stats/display-page-"):
            page = int(args[1].rsplit("-", 1)[1])
            if not self.bound:
                return
            if self.shown and self.page == page:
                self.shown = False
            else:
                self.shown = True
                self.page = page

    def bind(self) -> None:
        self.bound = True
        self.shown = False
        self.page = 0

    def vo_reconfig(self) -> None:
        if self.bound:
            self.shown = False


class Player:
    def __init__(self) -> None:
        self.enabled_page = 1
        self.lua = StatsLua()
        self.lua_visible = False
        self.startup_applied = False
        self.player_visibility = VISIBLE
        self.overlay_visibility = VISIBLE
        self.file_loaded = False
        self.time_pos: Optional[float] = None
        self.lua_restored = False
        self.vo_reapplied = False
        self.awaiting = False
        self.is_music = False

    def apply(self, decision: Optional[Decision]) -> None:
        if decision is None:
            return
        if decision.toggle_lua:
            self.lua.handle("script-binding", "stats/display-stats-toggle")
        if decision.display_page is not None:
            self.lua.handle("script-binding", f"stats/display-page-{decision.display_page}")
        if decision.lua_visible is not None:
            self.lua_visible = decision.lua_visible

    def apply_startup(self, force: bool = False) -> None:
        if self.is_music:
            return
        if not force and self.startup_applied:
            return
        self.startup_applied = True
        self.apply(for_startup(self.enabled_page))

    def try_restore(self, force: bool = False) -> bool:
        if self.is_music:
            self.awaiting = False
            return True
        if not should_wait_for_playback_before_restore(
            self.file_loaded and self.lua.bound,
            self.time_pos is not None,
        ):
            return False
        self.apply_startup(force=force)
        self.lua_restored = True
        self.awaiting = False
        return True

    def interpolation_reload_success(self) -> None:
        self.lua = StatsLua()
        self.lua_visible = False
        self.startup_applied = False
        self.file_loaded = False
        self.time_pos = None
        self.lua_restored = False
        self.vo_reapplied = False
        self.awaiting = True

    def fire_and_forget_restore(self) -> None:
        self.apply_startup()

    def on_file_loaded(self) -> None:
        self.file_loaded = True
        self.lua.bind()
        self.try_restore()

    def on_first_time_pos(self, value: float) -> None:
        self.time_pos = value
        self.try_restore()

    def on_vo_reconfig(self) -> None:
        if not should_reapply_after_vo_reconfig(self.lua_restored, self.vo_reapplied):
            return
        self.lua.vo_reconfig()
        self.lua_visible = False
        self.vo_reapplied = True
        self.apply_startup(force=True)

    def hide_for_music_or_minimize(self) -> None:
        self.player_visibility = GONE
        self.overlay_visibility = GONE
        self.apply(for_music_hide(self.lua.shown, console_open=False))

    def restore_video_surface(self) -> None:
        self.player_visibility = VISIBLE
        self.apply(for_video_resume(self.enabled_page, lua_shown=self.lua.shown))

    def composites_stats(self) -> bool:
        return self.player_visibility != GONE and self.lua.shown


def assert_true(cond: bool, message: str) -> None:
    if not cond:
        raise SystemExit(message)


def test_interpolation_waits_then_reapplies_once() -> None:
    player = Player()
    player.interpolation_reload_success()
    early = Player()
    early.interpolation_reload_success()
    early.fire_and_forget_restore()
    assert_true(not early.lua.shown, "unbound lua must ignore early display-page")
    early.on_file_loaded()
    early.on_first_time_pos(12.5)
    assert_true(not early.lua.shown, "startup-applied must not hide/show again after missed restore")

    assert_true(not player.lua.bound, "replacement core starts with lua unbound")
    assert_true(not player.try_restore(), "must wait for file-loaded/time-pos")
    player.on_file_loaded()
    assert_true(not player.lua.shown, "file-loaded without time-pos still waits")
    player.on_first_time_pos(12.5)
    assert_true(player.lua.shown and player.lua.page == 1, "restore shows page 1 after lua is alive")
    commands = [cmd for cmd in player.lua.commands if cmd[0] == "script-binding"]
    assert_true(all(cmd[1].startswith("stats/display-page-") for cmd in commands), "restore is display-page only")
    assert_true(("script-binding", "stats/display-stats-toggle") not in player.lua.commands, "no toggle on restore")

    player.on_vo_reconfig()
    assert_true(player.lua.shown and player.lua.page == 1, "vo-reconfig re-applies page 1 once")
    shown_after_first = player.lua.shown
    player.on_vo_reconfig()
    assert_true(player.lua.shown == shown_after_first, "second vo-reconfig must not invert page 1")
    display_pages = [cmd for cmd in player.lua.commands if cmd[1].startswith("stats/display-page-")]
    assert_true(len(display_pages) == 2, f"display-page should apply twice, got {display_pages}")


def test_off_never_toggles_hidden_lua() -> None:
    off = for_chip(0, 1, lua_shown=False, console_open=False)
    assert_true(not off.toggle_lua, "Off must not toggle hidden lua")
    assert_true(off.display_page is None, "Off must not send display-page")
    player = Player()
    player.lua.bind()
    player.apply(off)
    assert_true(not player.lua.shown, "hidden lua stays hidden")
    assert_true(("script-binding", "stats/display-stats-toggle") not in player.lua.commands, "no invert on Off")


def test_music_and_minimize_use_gone_and_hide_lua_once() -> None:
    player = Player()
    player.lua.bind()
    player.apply(for_startup(1))
    assert_true(player.lua.shown, "video stats start shown")
    player.hide_for_music_or_minimize()
    assert_true(player.player_visibility == GONE, "music must GONE the SurfaceView")
    assert_true(player.overlay_visibility == GONE, "music must GONE the subtitle overlay")
    assert_true(player.player_visibility != INVISIBLE, "INVISIBLE still composites lua")
    assert_true(not player.lua.shown, "entering music hides lua once")
    assert_true(player.enabled_page == 1, "music hide must not clear enabledStatisticsPage")
    assert_true(not player.composites_stats(), "Now Playing must not show lua")
    player.hide_for_music_or_minimize()
    toggles = [cmd for cmd in player.lua.commands if cmd == ("script-binding", "stats/display-stats-toggle")]
    assert_true(len(toggles) == 1, "second music hide must not invert")

    player.is_music = False
    player.restore_video_surface()
    assert_true(player.player_visibility == VISIBLE, "video surface returns")
    assert_true(player.lua.shown and player.lua.page == 1, "video stats return via display-page")
    resume = for_video_resume(1, lua_shown=False)
    assert_true(
        resume is not None and not resume.toggle_lua and resume.display_page == 1,
        "video resume is display-page only",
    )
    assert_true(("script-binding", "stats/display-page-1") in player.lua.commands, "video resume sent display-page-1")


def test_music_skips_interpolation_restore() -> None:
    player = Player()
    player.is_music = True
    player.interpolation_reload_success()
    player.on_file_loaded()
    player.on_first_time_pos(1.0)
    assert_true(not player.lua.shown, "audio never sends display-page")
    assert_true(("script-binding", "stats/display-page-1") not in player.lua.commands, "no audio display-page")


def main() -> None:
    test_interpolation_waits_then_reapplies_once()
    test_off_never_toggles_hidden_lua()
    test_music_and_minimize_use_gone_and_hide_lua_once()
    test_music_skips_interpolation_restore()
    print("player stats.lua interpolation/music simulations: PASS")


if __name__ == "__main__":
    main()
