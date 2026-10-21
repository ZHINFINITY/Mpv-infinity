#!/usr/bin/env python3
"""Integration guards complement deterministic JVM geometry and cache-policy tests."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/app/infinity/mpvz'


def source(path):
    return (JAVA / path).read_text()


class SceneStabilityContracts(unittest.TestCase):
    def test_settings_groups_own_one_material_but_popups_are_independent(self):
        card = source('ui/preferences/CardPreferences.kt')
        settings = source('ui/preferences/components/SettingsGlass.kt')
        wrappers = source('ui/preferences/components/SettingsPreferenceWrappers.kt')
        self.assertIn('LocalSettingsGlassOwner provides true', card)
        self.assertIn('if (LocalSettingsGlassOwner.current && !independent) return this', settings)
        self.assertLess(settings.index('if (LocalSettingsGlassOwner.current'), settings.index('val appearance ='))
        self.assertGreaterEqual(wrappers.count('independent = true'), 3)

    def test_all_six_appearance_sliders_commit_only_on_release(self):
        appearance = source('ui/preferences/AppearancePreferencesScreen.kt')
        wrappers = source('ui/preferences/components/SettingsPreferenceWrappers.kt')
        self.assertEqual(5, appearance.count('GlassCustomizationPreference('))  # four calls + declaration
        self.assertGreaterEqual(appearance.count('onSliderValueChange = {}'), 3)  # opacity, highlight, four-control helper
        self.assertNotRegex(appearance, r'onSliderValueChange\s*=\s*\{[^}]*glassSurface(?:Opacity|Highlight)\.set')
        self.assertIn('value = latest, onValueChange = { latest = it }', wrappers)
        self.assertIn('onValueChangeFinished = { onValueChange(latest) }', wrappers)

    def test_transient_failures_recover_without_cross_media_or_lifecycle_retention(self):
        renderer = source('ui/theme/LiquidGlassBackdrop.kt')
        self.assertIn('DisposableEffect(surfaceView, generation, eligible, lifecycleOwner)', renderer)
        self.assertIn('Lifecycle.Event.ON_STOP', renderer)
        self.assertIn('GlassScenePolicy.retainTransientFrame(now - first)', renderer)
        self.assertNotIn('if (failures >= GlassSamplingPolicy.FailureLimit) break', renderer)
        self.assertIn('frame.takeIf { eligible }', renderer)
        self.assertIn('GlassScenePolicy.sourceRect(source.width, source.height, aspect())', renderer)

    def test_audio_uses_screen_space_crop_and_large_panes_do_not_refract(self):
        audio = source('ui/player/controls/AudioPlayerControls.kt')
        renderer = source('ui/theme/LiquidGlassBackdrop.kt')
        surface = source('ui/theme/GlassSurface.kt')
        self.assertIn('GlassScenePolicy.coverBounds(', audio)
        self.assertIn('LiquidGlassFrame(image, imageBounds, refractionEnabled = false)', audio)
        self.assertIn('size.minDimension <= 128.dp.toPx()', renderer)
        self.assertIn('size.maxDimension <= 512.dp.toPx()', renderer)
        self.assertIn('if (enabled && backdrop != null && !hasSharedScene)', surface)
        self.assertIn('if (border && !enabled)', surface)

    def test_frame_controls_and_empty_playlist_use_real_glass_hosts(self):
        frame = source('ui/player/controls/components/sheets/FrameNavigationSheet.kt')
        sheets = source('ui/player/controls/PlayerSheets.kt')
        self.assertEqual(4, frame.count('Modifier.size(56.dp).playerGlassPane(enabled = glass'))
        self.assertIn('containerColor = if (glass) Color.Transparent', frame)
        self.assertIn('Playlist is empty', sheets)
        self.assertIn('PlayerSheet(onDismissRequest = onDismissRequest)', sheets)

    def test_folder_restore_precedes_scans_and_hidden_ui_does_not_purge_cache(self):
        folder = source('ui/browser/cards/FolderCard.kt')
        repository = source('domain/thumbnail/ThumbnailRepository.kt')
        self.assertLess(folder.index('getFolderThumbnailFromMemory(folder.bucketId)'), folder.index('LaunchedEffect('))
        self.assertIn('folderMemoryKeys = LruCache<String, String>(256)', repository)
        self.assertNotIn('level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL', repository)
        self.assertNotIn('level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW', repository)


if __name__ == '__main__':
    unittest.main()
