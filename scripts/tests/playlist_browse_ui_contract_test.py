#!/usr/bin/env python3
"""Actual playlist callers and fallback contracts; directly executable by CI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "app/src/main/java/app/infinity/mpvz"
DETAIL = (BASE / "ui/browser/playlist/PlaylistDetailScreen.kt").read_text()


def function(name):
    return DETAIL.split("private fun " + name + "(", 1)[1].split("\n@Composable", 1)[0]


class PlaylistBrowseContractTest(unittest.TestCase):
    def test_filters_are_remembered_and_counts_are_one_pass(self):
        policy = (BASE / "ui/browser/playlist/PlaylistPresentationPolicy.kt").read_text()
        self.assertIn("const val FILTER_ALL", policy)
        self.assertIn("const val FILTER_FAVORITES", policy)
        self.assertIn("categoryCounts", policy)
        self.assertIn("HashMap", policy)
        self.assertIn("remember(m3uFilteredItems, isSearching, searchQuery)", DETAIL)
        self.assertIn("gridItems(filteredVideoItems", DETAIL)
        self.assertIn("categoryCounts(videoItems, categories)", DETAIL)
        self.assertIn("val videos = remember(videoItems)", DETAIL)
        self.assertIn("val favoriteStreamCount = remember(videoItems)", DETAIL)
        self.assertIn("favoriteCount = favoriteStreamCount", DETAIL)

    def test_three_standalone_dialog_roots_are_individually_glassed(self):
        for name in ("IptvSettingsDialog", "CreateIptvPlaylistDialog", "StreamUrlDialog"):
            body = function(name)
            self.assertIn("settingsGlassSurface(cornerRadius = 28.dp, independent = true)", body, name)
            self.assertIn("containerColor = settingsGlassContainerColor", body, name)
        self.assertIn("GlassSlider as Slider", DETAIL)
        self.assertIn("GlassSwitch as Switch", DETAIL)
        self.assertNotIn("import androidx.compose.material3.Switch", DETAIL)
        self.assertNotIn("import androidx.compose.material3.Slider", DETAIL)

    def test_filter_controls_have_conditional_fill_and_no_duplicate_glass_border(self):
        for name in ("IptvSettingsDialog", "M3UPlaylistFilterRow"):
            body = function(name)
            self.assertIn("settingsGlassSurface(cornerRadius = 18.dp)", body)
            self.assertIn("selectedContainerColor = settingsGlassContainerColor", body)
            self.assertIn("border = if (glass) null else FilterChipDefaults.filterChipBorder", body)

    def test_rendered_empty_state_cannot_be_overridden_by_audio_or_empty_list(self):
        body = function("PlaylistVideoListContent")
        self.assertIn("text = emptyTitle", body)
        self.assertIn("text = emptyHint", body)
        self.assertNotIn('if (isAudio) "No songs', body)
        self.assertNotIn('if (videoItems.isNotEmpty())', body)
        self.assertIn("onClick = onClearFilters", body)
        self.assertIn("PlaylistPresentationPolicy.emptyKind(", DETAIL)
        self.assertIn('selectedM3UFilter = M3U_FILTER_ALL; searchQuery = ""; isSearching = false', DETAIL)

    def test_radio_identity_and_library_counts_use_real_data(self):
        self.assertIn("isRadioM3uPlaylist", DETAIL)
        self.assertIn("isStoredRadioName", DETAIL)
        screen = (BASE / "ui/browser/playlist/PlaylistScreen.kt").read_text()
        self.assertIn("splitByKind(playlistsWithCount)", screen)
        text = (ROOT / "app/src/main/res/values/strings_playlist_ui.xml").read_text()
        self.assertIn("Radio playlists (%1$d)", text)
        test = (ROOT / "app/src/test/java/app/infinity/mpvz/ui/browser/playlist/PlaylistPresentationPolicyTest.kt").read_text()
        self.assertNotIn("android.net.Uri", test)
        self.assertNotIn("PlaylistEntity", test)


if __name__ == "__main__":
    unittest.main()
