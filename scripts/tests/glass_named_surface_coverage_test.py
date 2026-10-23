#!/usr/bin/env python3
"""Focused source contract for named Liquid Glass surfaces owned by this audit.

This deliberately checks reachable shared entry points rather than screenshot-only
or dead wrappers: all InlineSearchBar callers route through one implementation,
EditConnectionSheet is the network file-edit surface, QuickPlayFab is the quick
button, and MediaInfoActivity is the media-info activity/surface.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    return (ROOT / relative).read_text()


def main() -> None:
    search = read("app/src/main/java/app/infinity/mpvz/ui/components/InlineSearchBar.kt")
    edit = read("app/src/main/java/app/infinity/mpvz/ui/browser/dialogs/EditConnectionDialog.kt")
    rename = read("app/src/main/java/app/infinity/mpvz/ui/browser/dialogs/RenameDialog.kt")
    quick = read("app/src/main/java/app/infinity/mpvz/ui/browser/fab/QuickPlayFab.kt")
    info = read("app/src/main/java/app/infinity/mpvz/ui/mediainfo/MediaInfoActivity.kt")
    compressor = read("app/src/main/java/app/infinity/mpvz/ui/browser/dialogs/VideoCompressorOverlay.kt")
    clip = read("app/src/main/java/app/infinity/mpvz/ui/player/clip/ClipOverlayView.kt")
    quick_settings = read("app/src/main/java/app/infinity/mpvz/ui/preferences/AppearancePreferencesScreen.kt")

    # Shared search implementation is used by music/home/folder/history/playlist,
    # catalog, torrent and settings callers; preserve the IME callback contract.
    assert "fun InlineSearchBar(" in search
    assert "SearchBarDefaults.InputField" in search
    assert "keyboardController?.hide()" in search
    assert "onSearch(submitted)" in search
    assert "settingsGlassSurface" in search
    assert "settingsGlassContainerColor" in search
    for state in ("focused", "unfocused", "disabled", "error"):
        assert f"{state}ContainerColor = Color.Transparent" in search
    assert "colors = inputColors" in search
    assert "else originalInputColors" in search
    assert "tonalElevation = if (glass) 0.dp else tonalElevation" in search
    assert "shadowElevation = if (glass) 0.dp else shadowElevation" in search

    # File/network edit sheet: conditional transparent Material container + exact
    # rounded local wrapper, without replacing the sheet callbacks or IME padding.
    assert "fun EditConnectionSheet(" in edit
    assert "onDismissRequest = handleDismiss" in edit
    assert "onSave(updatedConnection" in edit
    assert "settingsGlassSurface(cornerRadius = 28.dp, independent = true)" in edit
    assert "settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerLow)" in edit
    assert ".imePadding()" in edit

    # RenameDialog is the shared file/folder/video edit dialog; keep its focus,
    # validation and callback behavior while making the Material container glass.
    assert "fun RenameDialog(" in rename
    assert "focusRequester.requestFocus()" in rename
    assert "onConfirm(baseName.value + (extension ?: \"\"))" in rename
    assert "modifier = Modifier.settingsGlassSurface(cornerRadius = 28.dp)" in rename
    assert "settingsGlassContainerColor(MaterialTheme.colorScheme.surface)" in rename

    # Quick button remains the existing reachable FAB and keeps the callback.
    assert "fun QuickPlayFab(" in quick
    assert 'launchSource = "quick_play_fab"' in quick
    assert "settingsGlassSurface(cornerRadius = 20.dp)" in quick
    assert "settingsGlassContainerColor(MaterialTheme.colorScheme.primaryContainer)" in quick

    # Media-info activity root and top app bar both participate in the same switch.
    assert "class MediaInfoActivity" in info
    assert "settingsGlassSurface(cornerRadius = 0.dp)" in info
    assert "settingsGlassContainerColor(MaterialTheme.colorScheme.background)" in info
    assert "settingsGlassContainerColor(MaterialTheme.colorScheme.surface)" in info
    assert "modifier = modifier.settingsGlassSurface(cornerRadius = 24.dp)" in info
    assert "settingsGlassContainerColor(containerColor)" in info

    # Reachable video-edit surfaces: VideoCompressorOverlay is opened by the
    # file/video list screens; ClipOverlayView is attached by player panels and
    # already delegates its rounded root to shared DraggablePanel glass.
    assert "fun VideoCompressorOverlay(" in compressor
    assert "settingsGlassSurface(cornerRadius = 0.dp)" in compressor
    assert "settingsGlassContainerColor(MaterialTheme.colorScheme.background)" in compressor
    assert "fun ClipOverlayView" in clip or "class ClipOverlayView" in clip
    assert "DraggablePanel(" in clip

    # Quick settings is the existing Appearance preferences route, not a second
    # QuickSettingsSheet class; its Quick Play toggle remains reachable.
    assert "showQuickPlayFab" in quick_settings
    assert "liquidGlassSurfaces" in quick_settings

    # Inventory guard: every known InlineSearchBar caller remains reachable.
    callers = [
        "ui/browser/catalog/StreamScreen.kt",
        "ui/editor/MpvHelpScreen.kt",
        "ui/preferences/SettingsSearchScreen.kt",
        "ui/preferences/CodecCapabilitiesScreen.kt",
        "ui/torrent/TorrentSelectionScreen.kt",
        "ui/browser/filesystem/FileSystemBrowserScreen.kt",
        "ui/browser/folderlist/FolderListScreen.kt",
        "ui/browser/jellyfin/JellyfinContent.kt",
        "ui/browser/jellyfin/seerr/SeerrContent.kt",
        "ui/browser/medialibrary/MediaLibraryContent.kt",
        "ui/browser/music/MusicLibraryContent.kt",
        "ui/browser/networkstreaming/NetworkBrowserScreen.kt",
        "ui/browser/networkstreaming/NetworkStreamingScreen.kt",
        "ui/browser/playlist/PlaylistDetailScreen.kt",
        "ui/browser/playlist/PlaylistScreen.kt",
        "ui/browser/videolist/VideoListScreen.kt",
    ]
    for caller in callers:
        assert "InlineSearchBar" in read(f"app/src/main/java/app/infinity/mpvz/{caller}")

    assert "EditConnectionSheet(" in read("app/src/main/java/app/infinity/mpvz/ui/browser/networkstreaming/NetworkStreamingScreen.kt")
    assert "VideoCompressorOverlay(" in read("app/src/main/java/app/infinity/mpvz/ui/browser/videolist/VideoListScreen.kt")
    assert "VideoCompressorOverlay(" in read("app/src/main/java/app/infinity/mpvz/ui/browser/medialibrary/MediaLibraryContent.kt")

    print("glass_named_surface_coverage_test: PASS")


if __name__ == "__main__":
    main()
