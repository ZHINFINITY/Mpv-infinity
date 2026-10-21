#!/usr/bin/env python3
"""Source wiring guards at every actual Quick Play/menu FAB, not unused helper tokens."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'app/src/main/java/app/infinity/mpvz/ui/browser'

class QuickPlaySelectionGlassTest(unittest.TestCase):
    def test_every_actual_fab_has_one_glass_surface_and_resolved_color_function(self):
        for relative in ('folderlist/FolderListScreen.kt', 'filesystem/FileSystemBrowserScreen.kt',
                         'recentlyplayed/RecentlyPlayedScreen.kt', 'music/MusicLibraryContent.kt',
                         'medialibrary/MediaLibraryContent.kt', 'jellyfin/JellyfinContent.kt'):
            text = (BASE / relative).read_text()
            self.assertIn('import app.infinity.mpvz.ui.preferences.components.settingsGlassSurface', text, relative)
            self.assertIn('import app.infinity.mpvz.ui.browser.fab.quickPlayFabContainerColor', text, relative)
            calls = text.split('ToggleFloatingActionButton(')[1:]
            self.assertTrue(calls, relative)
            for call in calls:
                header = call.split('onCheckedChange =', 1)[0]
                self.assertIn('containerColor = quickPlayFabContainerColor()', header, relative)
                self.assertIn('settingsGlassSurface(cornerRadius = 20.dp)', header, relative)
                self.assertNotIn('containerColor = {', header, relative)
    def test_factory_resolves_composition_locals_before_progress_callback(self):
        helper = (BASE / 'fab/QuickPlayFab.kt').read_text()
        factory = helper.split('fun quickPlayFabContainerColor()', 1)[1].split('/**', 1)[0]
        self.assertIn('ToggleFloatingActionButtonDefaults.containerColor()', factory)
        self.assertIn('return if (glass) { _: Float -> Color.Transparent } else original', factory)
        self.assertIn('defaultElevation = if (glass) 0.dp else 6.dp', helper)
        self.assertIn('pressedElevation = if (glass) 0.dp else 12.dp', helper)
    def test_selection_bar_preserves_custom_colors_only_outside_glass_mode(self):
        selection = (BASE / 'components/BrowserTopBar.kt').read_text().split('private fun SelectionTopBar', 1)[1]
        self.assertIn('settingsGlassSurface(cornerRadius = 28.dp)', selection)
        self.assertIn('scrolledContainerColor = Color.Transparent', selection)
        self.assertIn('colors != null -> colors.copy', selection)
        self.assertIn('!liquidGlassSurfaces -> colors ?: defaultColors', selection)

if __name__ == '__main__':
    unittest.main()
