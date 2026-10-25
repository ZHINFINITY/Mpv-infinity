#!/usr/bin/env python3
"""Simulate interpolation stats restore and music/minimize compositor hiding.

stats.lua contract (upstream):
  display-stats-toggle  -> persistent overlay; second toggle hides
  display-page-N        -> oneshot (~4s); does not hide an already-shown overlay
  mixed persistent/oneshot -> no-op
  show-text "" 0        -> clears leftover OSD (oneshot or osd_message overlay)
"""
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
    clear_osd: bool = False


def for_chip(to_page: int, previous_page: int, lua_shown: bool, console_open: bool) -> Decision:
    disable_console = to_page != 7 and console_open
    if to_page in (0, 6):
        return Decision(
            toggle_lua=previous_page in range(1, 6) or lua_shown,
            lua_visible=False,
            disable_console=disable_console,
            clear_osd=True,
        )
    if to_page == 7:
        return Decision(
            toggle_lua=previous_page in range(1, 6) or lua_shown,
            lua_visible=False,
            enable_console=not console_open,
            clear_osd=True,
        )
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
    # display-page-N is a toggle; a second restore after vo-reconfig hides a live overlay.
    return False


@dataclass
class StatsLua:
    bound: bool = False
    shown: bool = False
    page: int = 0
    mode: Optional[str] = None  # "oneshot" | "toggle" | None
    commands: list[tuple[str, ...]] = field(default_factory=list)

    def handle(self, *args: str) -> None:
        self.commands.append(args)
        if args == ("show-text", "", "0"):
            self.shown = False
            self.mode = None
            return
        if args == ("script-binding", "stats/display-stats-toggle"):
            if not self.bound:
                return
            if self.shown and self.mode == "toggle":
                self.shown = False
                self.mode = None
            elif self.shown and self.mode == "oneshot":
                return  # mixed mode no-op
            else:
                self.shown = True
                self.mode = "toggle"
            return
        if len(args) == 2 and args[0] == "script-binding" and args[1].startswith("stats/display-page-"):
            page = int(args[1].rsplit("-", 1)[1])
            if not self.bound:
                return
            if self.shown and self.mode == "toggle":
                return  # mixed mode no-op
            self.shown = True
            self.page = page
            self.mode = "oneshot"

    def bind(self) -> None:
        self.bound = True
        self.shown = False
        self.page = 0
        self.mode = None

    def vo_reconfig(self) -> None:
        # stats.lua reprints a persistent overlay; it does not hide oneshot either.
        return


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
        if decision.clear_osd:
            self.lua.handle("show-text", "", "0")
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
            self.lua.vo_reconfig()
            self.lua_visible = False
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

    def off_chip(self, previous_page: int = 1, lua_shown_flag: Optional[bool] = None) -> None:
        shown_flag = self.lua_visible if lua_shown_flag is None else lua_shown_flag
        self.apply(for_chip(0, previous_page, shown_flag, False))
        self.enabled_page = 0


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
    assert_true(player.lua.shown and player.lua.page == 1, "vo-reconfig must not toggle away page 1")
    shown_after_first = player.lua.shown
    player.on_vo_reconfig()
    assert_true(player.lua.shown == shown_after_first, "second vo-reconfig must not invert page 1")
    display_pages = [cmd for cmd in player.lua.commands if cmd[1].startswith("stats/display-page-")]
    assert_true(len(display_pages) == 1, f"display-page should apply once after interpolation, got {display_pages}")


def test_off_hides_after_interpolation_when_visible_property_is_no() -> None:
    player = Player()
    player.interpolation_reload_success()
    player.on_file_loaded()
    player.on_first_time_pos(12.5)
    player.on_vo_reconfig()
    assert_true(player.lua.shown, "oneshot overlay is still on screen")
    assert_true(not player.lua_visible, "vo-reconfig lied that lua is hidden")
    player.off_chip(previous_page=1, lua_shown_flag=False)
    assert_true(not player.lua.shown, "Off must clear oneshot leftover")
    assert_true(("script-binding", "stats/display-stats-toggle") in player.lua.commands, "Off still sends hide toggle")
    assert_true(("show-text", "", "0") in player.lua.commands, "Off sends empty OSD clear")
    assert_true(player.enabled_page == 0, "Off pref is page 0")


def test_off_hides_when_chip_already_shows_off() -> None:
    player = Player()
    player.lua.bind()
    player.lua.handle("script-binding", "stats/display-page-1")
    assert_true(player.lua.shown, "oneshot page is visible")
    player.enabled_page = 0
    player.off_chip(previous_page=0, lua_shown_flag=False)
    assert_true(not player.lua.shown, "tapping Off again still clears leftover OSD")


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
    test_off_hides_after_interpolation_when_visible_property_is_no()
    test_off_hides_when_chip_already_shows_off()
    test_music_and_minimize_use_gone_and_hide_lua_once()
    test_music_skips_interpolation_restore()
    print("player stats.lua interpolation/music simulations: PASS")


if __name__ == "__main__":
    main()
