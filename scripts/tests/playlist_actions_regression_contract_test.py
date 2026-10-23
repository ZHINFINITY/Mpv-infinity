"""Caller-level guards for real playlist actions and playing-state semantics."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"


def source(relative):
    return (JAVA / relative).read_text()


class PlaylistActionsContractTest(unittest.TestCase):
    def test_playlist_actions_keep_context_and_real_callbacks(self):
        add = source("ui/browser/dialogs/AddToPlaylistDialog.kt")
        sheet = source("ui/browser/sheets/PlaylistActionSheet.kt")
        self.assertIn("playlist_actions_add_title", add)
        self.assertIn("viewModel.addToPlaylist", add)
        self.assertIn("viewModel.createAndAdd", add)
        self.assertIn("onCreatePlaylist(playlistName.trim())", sheet)
        self.assertIn("onCreateM3UPlaylistFromFile", sheet)
        self.assertIn("isLoading", sheet)

    def test_accessible_targets_and_actual_queue_playing_state(self):
        add = source("ui/browser/dialogs/AddToPlaylistDialog.kt")
        sheet = source("ui/browser/sheets/PlaylistActionSheet.kt")
        player = source("ui/player/controls/components/sheets/PlaylistSheet.kt")
        self.assertIn("heightIn(min = 64.dp)", sheet)
        self.assertIn("heightIn(min = 64.dp)", add)
        self.assertIn(".size(48.dp)", player)
        self.assertIn("stateDescription", player)
        self.assertIn("effectiveItem.isPlaying", player)
        self.assertIn("playlist_actions_now_playing_count", player)

    def test_resources_are_backed_by_real_callers(self):
        text = (ROOT / "app/src/main/res/values/strings_playlist_actions.xml").read_text()
        for name in ("playlist_actions_add_title", "playlist_actions_playlist_count", "playlist_actions_create_group"):
            self.assertIn(f'name="{name}"', text)
        self.assertIn("playlist_actions_create_group", source("ui/browser/sheets/PlaylistActionSheet.kt"))


if __name__ == "__main__":
    unittest.main()
