#!/usr/bin/env python3
"""Runtime source-route checks; these do not claim phone/GPU visual verification."""
from pathlib import Path
import re
import unittest

JAVA = Path(__file__).resolve().parents[2] / 'app/src/main/java/app/infinity/mpvz'
def source(path):
    return (JAVA / path).read_text()

class GlassDepthNoReloadWiringTest(unittest.TestCase):
    def test_real_background_sampling_and_film_share_an_explicit_rounded_path_clip(self):
        pane = source('ui/theme/LiquidGlassBackdrop.kt').split('fun Modifier.liquidGlassPane(', 1)[1]
        self.assertIn('addRoundRect(', pane)
        self.assertIn('clipPath(panePath)', pane)
        self.assertLess(pane.index('clipPath(panePath)'), pane.index('canvas.nativeCanvas.drawRoundRect'))
        self.assertLess(pane.index('canvas.nativeCanvas.drawRoundRect'), pane.index('GlassSurfacePolicy.filmAlpha'))
        self.assertIn('GlassSurfacePolicy.depthOptics(tuning.depth, glow, highContrast)', pane)
        self.assertIn('GlassSurfacePolicy.TopLeftLight', pane)
        self.assertIn('optics.bodyHighlight', pane)
        self.assertIn('optics.bodyShade', pane)
    def test_soft_contact_shadow_is_exterior_only_not_a_second_native_backing(self):
        pane = source('ui/theme/LiquidGlassBackdrop.kt').split('fun Modifier.liquidGlassPane(', 1)[1]
        self.assertIn('clipPath(outline, ClipOp.Difference)', pane)
        self.assertLess(pane.index('clipPath(outline, ClipOp.Difference)'), pane.index('.clip(shape)'))
        self.assertNotIn('.shadow(', pane)
        self.assertNotIn('edgeInset(', pane)
    def test_music_glass_does_not_stack_translucent_native_elevation(self):
        button = source('ui/player/controls/AudioPlayerControls.kt').split('private fun ReactiveSurfaceButton(', 1)[1]
        self.assertIn('color = if (liquidGlassSurfaces) Color.Transparent else color', button)
        self.assertIn('shadowElevation = if (liquidGlassSurfaces) 0.dp else shadowElevation', button)
        self.assertIn('AudioPalettePolicy.gradientRows', source('ui/player/controls/AudioPaletteGlassScene.kt'))
        bar = source('ui/browser/components/FloatingBottomBar.kt')
        self.assertIn('shadowElevation = if (liquidGlassSurfaces) 0.dp else 12.dp', bar)
    def test_switch_native_track_cannot_cover_its_custom_material(self):
        controls = source('ui/player/controls/components/GlassControls.kt')
        switch = controls.split('fun GlassSwitch(', 1)[1].split('fun GlassSlider(', 1)[0]
        for name in ('checkedTrackColor', 'uncheckedTrackColor', 'disabledCheckedTrackColor',
                     'disabledUncheckedTrackColor', 'checkedBorderColor', 'uncheckedBorderColor'):
            self.assertIn(name + ' = Color.Transparent', switch)
        # The custom material must be at least as tall as Material's 32dp native track.
        self.assertIn('16.dp, height = GlassControlGeometryPolicy.switchTrackHeightDp.dp', switch)
        track = re.search(r'const val switchTrackHeightDp = ([0-9.]+)f', controls)
        self.assertIsNotNone(track)
        self.assertGreaterEqual(float(track.group(1)), 32.0)
        self.assertNotIn('Size(bounds.width, (trackHeight / 2f)', controls)
        self.assertNotIn('.shadow(', controls)
    def test_native_route_never_restarts_and_mpv_restart_is_explicitly_scoped(self):
        activity = source('ui/player/PlayerActivity.kt')
        callback = activity.split('fun applyInterpolationPreferencesFromControls()', 1)[1].split('private fun requestMpvInterpolationRestart', 1)[0]
        for forbidden in ('recreate()', 'PlaybackSession.load(', 'releaseSurface(', 'setupMPV(', 'startMediaLoad(', 'nativeEngine.stop('):
            self.assertNotIn(forbidden, callback)
        self.assertIn('native_flow_sink_unavailable', callback)
        sheet = source('ui/player/controls/components/sheets/FrameInterpolationFpsSheet.kt')
        self.assertIn('interpolationApplyTimingNote(activity)', sheet)
        controls = source('ui/player/controls/components/FrameInterpolationControls.kt')
        self.assertIn('nextPlaybackNote = interpolationApplyTimingNote(activity)', controls)
        for timing in ('interpolation_feedback_native', 'interpolation_feedback_mpv_config', 'interpolation_feedback_mpv'):
            self.assertIn(timing, controls)
    def test_off_mode_and_accessibility_preserve_readability(self):
        pane = source('ui/theme/LiquidGlassBackdrop.kt').split('fun Modifier.liquidGlassPane(', 1)[1]
        self.assertLess(pane.index('if (!enabled) return this'), pane.index('LocalLiquidGlassBackdrop'))
        self.assertIn('AppMotion.shouldReduceMotion()', pane)
        self.assertNotIn('isHighTextContrastEnabled', pane)
        self.assertIn('Build.VERSION.SDK_INT >= 37', pane)

if __name__ == '__main__':
    unittest.main()
