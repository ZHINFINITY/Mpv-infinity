#!/usr/bin/env python3
"""Audio/Subtitles sheets must follow the live engine after an in-player switch."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VIEW_MODEL = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerViewModel.kt"


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise SystemExit(f"forbidden {label}: {needle}")


def sheet_source(native_active: bool, global_engine: str, mpv_tracks: list[str], native_tracks: list[str]) -> list[str]:
    """Live renderer owns the sheet. Global decoder pref is not a sheet source."""
    if native_active:
        return list(native_tracks)
    return list(mpv_tracks)


def main() -> None:
    text = VIEW_MODEL.read_text(encoding="utf-8")
    require(
        text,
        "combine(allTracks, nativeSubtitleTracks, nativeExternalSubtitleTracks, nativeEngineActive)",
        "subtitle sheet combine uses live nativeEngineActive",
    )
    require(
        text,
        "combine(allTracks, nativeAudioTracks, nativeEngineActive)",
        "audio sheet combine uses live nativeEngineActive",
    )
    forbid(
        text,
        "decoderPreferences.playbackEngine.changes()",
        "global decoder pref must not empty sheets after in-player switch",
    )
    forbid(
        text,
        "nativeActive || engine == PlaybackEngineMode.NATIVE",
        "sheet source must not OR with global NATIVE pref",
    )
    require(
        text,
        "if (nativeEngineActive.value) {\n      nativeAudioToggleListener?.invoke(track.id)",
        "audio selection follows live engine",
    )
    require(
        text,
        "if (nativeEngineActive.value) {\n      if (id <= -10_000)",
        "subtitle toggle follows live engine",
    )
    require(
        text,
        "if (nativeEngineActive.value) {\n      return subtitleTracks.value.firstOrNull { it.id == id }?.selected == true",
        "subtitle selected-state follows live engine",
    )
    require(
        text,
        "if (nativeEngineActive.value) {\n      return if (subtitleTracks.value.firstOrNull { it.id == id }?.selected == true) \"P\" else null",
        "subtitle indicator follows live engine",
    )

    mpv_audio = ["eng aac", "jpn aac"]
    mpv_subs = ["eng srt", "jpn ass"]
    native_audio = ["eng media3"]
    native_subs = ["eng media3 sub"]

    global_native_switch_to_mpv = sheet_source(
        native_active=False,
        global_engine="NATIVE",
        mpv_tracks=mpv_audio,
        native_tracks=[],
    )
    if global_native_switch_to_mpv != mpv_audio:
        raise SystemExit(f"MPV sheets empty after leaving Native: {global_native_switch_to_mpv}")

    global_mpv_switch_to_native = sheet_source(
        native_active=True,
        global_engine="MPV",
        mpv_tracks=mpv_audio,
        native_tracks=native_audio,
    )
    if global_mpv_switch_to_native != native_audio:
        raise SystemExit(f"Native audio sheet empty after leaving MPV: {global_mpv_switch_to_native}")

    subs_after_native_to_mpv = sheet_source(False, "NATIVE", mpv_subs, [])
    if subs_after_native_to_mpv != mpv_subs:
        raise SystemExit(f"MPV subtitle sheet empty after leaving Native: {subs_after_native_to_mpv}")

    subs_after_mpv_to_native = sheet_source(True, "MPV", mpv_subs, native_subs)
    if subs_after_mpv_to_native != native_subs:
        raise SystemExit(f"Native subtitle sheet empty after leaving MPV: {subs_after_mpv_to_native}")

    print("player engine-handoff track sheets: PASS")


if __name__ == "__main__":
    main()
