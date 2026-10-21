#!/usr/bin/env python3
"""New player actions must use existing layout persistence, decoder choices and runtime apply."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/app/infinity/mpvz'
def read(path):
    return (JAVA / path).read_text()

class PlayerInterpolationControlsTest(unittest.TestCase):
    def test_both_actions_are_in_existing_customization_model(self):
        model = read('preferences/PlayerButton.kt')
        editor = read('ui/preferences/ControlLayoutEditorScreen.kt')
        renderer = read('ui/player/controls/PlayerControlsShared.kt')
        for action in ('FRAME_INTERPOLATION', 'FRAME_INTERPOLATION_FPS'):
            self.assertIn(action + '(Icons.', model)
            self.assertIn('PlayerButton.' + action + ' -> stringResource(', model)
            self.assertIn('PlayerButton.' + action, renderer)
        self.assertIn('allPlayerButtons', editor)
        self.assertIn('FrameInterpolationPlayerButton(', renderer)
    def test_existing_layout_parser_is_shared_and_name_based(self):
        self.assertIn('PlayerControlActionPolicy.parse(csv, usedButtons, PlayerButton.NONE)', read('preferences/AppearancePreferences.kt'))
        self.assertIn('PlayerButton.valueOf(name)', read('preferences/AppearancePreferences.kt'))
        self.assertIn('.mapNotNull(resolve)', read('preferences/PlayerControlActionPolicy.kt'))
    def test_toggle_reads_and_writes_the_existing_preference_then_applies(self):
        button = read('ui/player/controls/components/FrameInterpolationControls.kt')
        self.assertIn('mpvFlowFrameInterpolation.set(!preferences.mpvFlowFrameInterpolation.get())', button)
        self.assertIn('activity.applyInterpolationPreferencesFromControls()', button)
        self.assertIn('PlayerControlActionPolicy.interpolationAvailable(', button)
        self.assertIn('MpvConfigControlledFeatures.FRAME_INTERPOLATION', button)
        self.assertIn('playerGlassPane(', button)
        self.assertNotIn('preferenceStore.get', button)
    def test_fps_selector_uses_shared_options_and_does_not_enable_interpolation(self):
        sheet = read('ui/player/controls/components/sheets/FrameInterpolationFpsSheet.kt')
        self.assertIn('MPVFLOW_TARGET_FPS_OPTIONS.forEach', sheet)
        self.assertIn('preferences.mpvFlowTargetFps.set(fps)', sheet)
        self.assertIn('activity.applyInterpolationPreferencesFromControls()', sheet)
        self.assertNotIn('mpvFlowFrameInterpolation.set(', sheet)
        self.assertIn('PlayerSheet(', sheet)
        self.assertIn('Sheets.FrameInterpolationFps ->', read('ui/player/controls/PlayerSheets.kt'))
    def test_no_soft_blend_or_quality_overrides_and_default_only_is_120(self):
        preferences = read('preferences/DecoderPreferences.kt')
        self.assertIn('DEFAULT_MPVFLOW_TARGET_FPS = 120', preferences)
        self.assertIn('getInt("mpvflow_target_fps", DEFAULT_MPVFLOW_TARGET_FPS)', preferences)
        self.assertIn('getBoolean("mpvflow_soft_blend_debug", false)', preferences)
        for path in ('ui/player/controls/components/FrameInterpolationControls.kt', 'ui/player/controls/components/sheets/FrameInterpolationFpsSheet.kt'):
            source = read(path)
            for forbidden in ('mpvFlowQuality.set', 'mpvFlowSoftBlendDebug.set', 'mpvFlowVisibilityResolverDebug.set'):
                self.assertNotIn(forbidden, source)
    def test_existing_drawer_highlights_the_same_live_toggle_state(self):
        controls = read('ui/player/controls/PlayerControls.kt')
        self.assertIn('frameInterpolationEnabled by decoderPreferences.mpvFlowFrameInterpolation.collectAsState()', controls)
        self.assertIn('if (frameInterpolationEnabled) add(PlayerButton.FRAME_INTERPOLATION)', controls)

if __name__ == '__main__':
    unittest.main()
