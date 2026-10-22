"""Actual stream-card callers: glass, accessible controls and preserved artwork routing."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "app/src/main/java/app/infinity/mpvz"


class StreamCardsContractTest(unittest.TestCase):
    def test_stream_card_uses_one_master_gated_shared_background(self):
        card = (BASE / "ui/browser/cards/M3UVideoCard.kt").read_text()
        self.assertIn("liquidGlassSurfaces.collectAsState()", card)
        self.assertIn("if (liquidGlass)", card)
        self.assertEqual(1, card.count("BrowserGlassSurface("))
        self.assertIn("opacity = glassOpacity", card)
        self.assertIn("highlight = glassHighlight", card)
        self.assertNotIn("text = url", card)
        self.assertIn("remember(url) { StreamingCardPresentationPolicy.safeSourceLabel(url) }", card)

    def test_network_and_memory_thumbnail_paths_remain_real(self):
        card = (BASE / "ui/browser/cards/M3UVideoCard.kt").read_text()
        for path in ("NetworkPlaybackUri.parse", "thumbnailKeyForNetworkPath", "thumbnailReadyKeys.filter", "getThumbnailForNetworkSource", "getThumbnailFromMemory"):
            self.assertIn(path, card)

    def test_radio_geometry_and_both_favorite_targets_are_accessible(self):
        card = (BASE / "ui/browser/cards/M3UVideoCard.kt").read_text()
        self.assertIn("if (isRadio) 64.dp else 96.dp", card)
        self.assertIn("if (isRadio) 1f else 16f / 9f", card)
        self.assertEqual(2, card.count("Modifier.size(48.dp)"))
        self.assertEqual(2, card.count("stream_card_unsave_action"))
        self.assertNotIn("Modifier.size(32.dp)", card)
        self.assertIn("selected = isSelected", card)

    def test_radio_kind_is_distinct_from_audio_and_reaches_actual_rows(self):
        card = (BASE / "ui/browser/cards/PlaylistCard.kt").read_text()
        self.assertIn("RadioM3uPlaylistPolicy.isStoredRadioName", card)
        self.assertIn("isAudioOnly = radioPlaylist", card)
        detail = (BASE / "ui/browser/playlist/PlaylistDetailScreen.kt").read_text()
        self.assertGreaterEqual(detail.count("isRadio = "), 2)

    def test_reference_folder_card_keeps_native_geometry_and_memory_thumbnail_path(self):
        folder = (BASE / "ui/browser/cards/FolderCard.kt").read_text()
        self.assertIn("val cardShape = AppShapeScale.large", folder)
        self.assertIn("Box(modifier = Modifier.fillMaxWidth())", folder)
        self.assertIn(".padding(12.dp)", folder)
        self.assertNotIn("BrowserGlassSurface(", folder)
        self.assertIn("getFolderThumbnailFromMemory(folder.bucketId)", folder)
        self.assertIn("onQuickPlay: (() -> Unit)?", folder)


if __name__ == "__main__":
    unittest.main()
