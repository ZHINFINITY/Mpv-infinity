#!/usr/bin/env python3
"""Source wiring guards; visual quality and touch behavior still need phone testing."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/app/infinity/mpvz'


def source(path):
    return (JAVA / path).read_text()


class SeekbarGlassContracts(unittest.TestCase):
    def setUp(self):
        self.video = source('ui/player/controls/components/Seekbar.kt').split(
            'fun StandardSeekbar(\n  positionProvider:', 1)[1].split('@Preview', 1)[0]
        self.audio = source('ui/player/controls/components/AudioWavySeekBar.kt').split(
            'fun AudioWavySeekBar(', 1)[1]

    def test_video_is_one_original_canvas_not_a_second_slider(self):
        self.assertEqual(1, self.video.count('Canvas('))
        self.assertIn('Canvas(modifier = modifier.fillMaxWidth().height(thumbHeight))', self.video)
        self.assertNotIn('GlassSlider(', self.video)
        self.assertNotIn('Slider(', self.video)
        self.assertNotIn('Box(', self.video)

    def test_progress_is_read_only_in_draw_scope(self):
        before_draw, draw = self.video.split('Canvas(', 1)
        self.assertNotIn('positionProvider()', before_draw)
        self.assertEqual(1, draw.count('positionProvider()'))
        self.assertIn('bufferedEndPx(bufferDuration, safeDuration', draw)
        self.assertIn('seekbarTrackSegments(', draw)
        self.assertIn('loopStart?.let', draw)
        self.assertIn('loopEnd?.let', draw)

    def test_glass_is_master_gated_and_brushes_are_remembered(self):
        full_source = source('ui/player/controls/components/Seekbar.kt')
        self.assertIn('import app.infinity.mpvz.preferences.preference.collectAsState', full_source)
        self.assertIn('liquidGlassSurfaces.collectAsState()', self.video)
        self.assertIn('val glass = liquidGlassEnabled', self.video)
        self.assertIn('if (glass)', self.video)
        before_draw, draw = self.video.split('Canvas(', 1)
        self.assertIn('val glassTrackBrush = remember(', before_draw)
        self.assertIn('val glassThumbBrush = remember(', before_draw)
        self.assertNotIn('Brush.', draw)
        self.assertIn('startY = opticalCenterY - opticalTrackHeight / 2f', before_draw)

    def test_thumb_highlight_follows_thumb_not_screen_center(self):
        self.assertIn('inset(thumbTopLeft.x, thumbTopLeft.y,', self.video)
        self.assertIn('brush = glassThumbHighlightBrush', self.video)
        self.assertIn('} else {\n      drawRoundRect(\n        color = primaryColor,', self.video)

    def test_music_keeps_one_interactive_slider_and_classic_paint(self):
        self.assertEqual(1, self.audio.count('GlassSlider('))
        self.assertIn('onSeek(it)', self.audio)
        self.assertIn('onSeekFinished(dragPosition.coerceIn(0f, boundedDuration))', self.audio)
        self.assertIn('interactionSource = interactionSource', self.audio)
        self.assertIn('.alpha(if (glassEnabled) 1f else 0f)', self.audio)

    def test_no_giant_seekbar_card_or_decorative_slider_mode(self):
        controls = source('ui/player/controls/PlayerControls.kt')
        self.assertNotIn('.playerGlassPane(enabled = useLiquidGlassSeekbar', controls)
        self.assertIn('useLiquidGlassSeekbar -> Color.Transparent', controls)
        self.assertIn('if (useLiquidGlassSeekbar) Modifier', controls)
        slider = source('ui/player/controls/components/GlassControls.kt')
        self.assertNotIn('visualOnly', slider)

    def test_settings_and_music_slider_style_the_actual_thin_track(self):
        slider = source('ui/player/controls/components/GlassControls.kt').split(
            'fun GlassSlider(', 1)[1].split('private fun Modifier.glassTrackOptics', 1)[0]
        self.assertIn('track = { sliderState ->', slider)
        self.assertIn('SliderDefaults.Track(', slider)
        self.assertIn('modifier = Modifier.height(4.dp)', slider)
        self.assertIn('.glassTrackOptics(opacity, highlight, 2.dp, height = 4.dp)', slider)
        self.assertNotIn('modifier = modifier.then(', slider)


if __name__ == '__main__':
    unittest.main()
