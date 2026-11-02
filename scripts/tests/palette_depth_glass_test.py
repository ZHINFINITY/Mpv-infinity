#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
backdrop = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/theme/LiquidGlassBackdrop.kt").read_text()
controls = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/GlassControls.kt").read_text()
policy = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPalettePolicy.kt").read_text()
audio = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt").read_text()
assert ".clip(shape)" in backdrop
assert "Modifier.shadow(4.dp" not in backdrop
assert "clipPath(panePath)" in backdrop
assert "GlassSurfacePolicy.depthOptics" in backdrop
assert "optics.bodyHighlight" in backdrop
assert "optics.bodyShade" in backdrop
# Build 1910: the pane rim is drawn by the shared glassRimLight primitive, whose width
# follows the user's Rim Width slider (high-contrast keeps its stronger rim there).
rim = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/theme/GlassRimLight.kt").read_text()
assert ".glassRimLight(shape = shape, enabled = drawRim" in backdrop
assert "tuning.rimWidthDp" in rim
assert "highContrast" in rim
assert "Size(bounds.width, (trackHeight / 2f)" not in controls
assert "useArtworkAsGlassScene(): Boolean = false" in policy
assert "LocalLiquidGlassBackdrop" not in audio
assert "AudioVisualizerPaletteCache" in audio
scene = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPaletteGlassScene.kt").read_text()
assert "AudioPaletteGlassScene(" in audio
assert "topArgb = visualizerPalette.primary" in audio
assert "bottomArgb = visualizerPalette.secondary" in audio
assert "AudioPalettePolicy.gradientRows(topArgb, bottomArgb)" in scene
assert "LocalLiquidGlassBackdrop provides frame" in scene
assert "refractionEnabled = false" in scene
assert "albumArtBitmap" not in scene
assert "Bitmap.createBitmap(rows, 1, rows.size" in scene
print("palette/depth/glass checks passed")
