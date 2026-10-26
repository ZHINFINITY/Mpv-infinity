#!/usr/bin/env python3
"""Source integration guards; pure sizing/safety behavior is tested by JVM tests in CI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"


class LiquidGlassContracts(unittest.TestCase):
    def test_source_hash_audit_precedes_verified_runtime_overlay(self):
        workflow = (ROOT / ".github/workflows/debug-apk.yml").read_text()
        self.assertLess(workflow.index("Verify permission-only and third-party source boundaries"),
                        workflow.index("Overlay Flow-only runtime into Standard MPV AAR"))

    def test_video_has_one_bounded_shared_sampler(self):
        source = (JAVA / "ui/theme/LiquidGlassBackdrop.kt").read_text()
        self.assertEqual(1, source.count("PixelCopy.request("))
        self.assertIn("GlassSamplingPolicy.sampleSize", source)
        self.assertIn("repeatOnLifecycle(Lifecycle.State.RESUMED)", source)
        self.assertIn(".coerceAtMost(GlassSamplingPolicy.FailureLimit)", source)
        self.assertNotIn("if (failures >= GlassSamplingPolicy.FailureLimit) break", source)
        self.assertIn("ActivityManager", source)
        self.assertIn("currentThermalStatus", source)
        self.assertIn("Build.VERSION.SDK_INT >= 33", source)
        self.assertIn("catch (_: RuntimeException)", source)
        self.assertIn("frame.takeIf { eligible }", source)
        # Destroy still discards the scene; a background stop keeps the last frost so resume
        # is not a clear pane. A new media generation re-keys the effect and clears it.
        self.assertIn("if (event == Lifecycle.Event.ON_DESTROY) frame.value = null", source)
        self.assertNotIn("Lifecycle.Event.ON_STOP", source)
        self.assertIn("DisposableEffect(surfaceView, generation, eligible, lifecycleOwner)", source)

    def test_runtime_input_uses_explicit_linear_filtering_and_pixel_centres(self):
        source = (JAVA / "ui/theme/LiquidGlassBackdrop.kt").read_text()
        self.assertIn("setFilterMode(BitmapShader.FILTER_MODE_LINEAR)", source)
        self.assertIn("AndroidPaint.FILTER_BITMAP_FLAG", source)
        self.assertIn("clamp(uv * imageSize, float2(0.5), imageSize - float2(0.5))", source)
        self.assertNotIn("uv * (imageSize - 1.0)", source)
        self.assertLess(source.index("val shader = remember {"), source.index(".drawWithCache {"))

    def test_renderer_uses_native_rounded_bevel_normals_and_directional_optics(self):
        # Uniforms are set in AgslRuntimeShaders so API < 33 never loads RuntimeShader.
        source = ((JAVA / "ui/theme/LiquidGlassBackdrop.kt").read_text() +
                  (JAVA / "ui/theme/AgslRuntimeShaders.kt").read_text())
        policy = (JAVA / "ui/theme/GlassBevelPolicy.kt").read_text()
        self.assertIn("GlassBevelPolicy.params(size.width, size.height, radius", source)
        for uniform in ("setFloatUniform(\"bevel\"", "setFloatUniform(\"light\"",
                        "setFloatUniform(\"specular\"", "setFloatUniform(\"fresnel\""):
            self.assertIn(uniform, source)
        self.assertIn("float bevel", source)
        self.assertIn("float2 normal", source)
        self.assertIn("directionalReflection", source)
        self.assertIn("pow(clamp(grazing", source)
        # Five samples are used; the sixth occurrence is the helper declaration.
        self.assertEqual(6, source.count("sampleAt("))
        self.assertIn("fun edgeNormal", policy)
        self.assertIn("fun params", policy)

    def test_frost_is_shared_bounded_off_thread_and_precedes_publication(self):
        source = (JAVA / "ui/theme/LiquidGlassBackdrop.kt").read_text()
        self.assertEqual(1, source.count("GlassFrostPolicy.frostArgb("))
        self.assertIn("withContext(Dispatchers.Default)", source)
        # Capture now acquires a reusable unpublished buffer before frosting; publication still follows it.
        self.assertLess(source.index("frostGlassFrame(target, tuning.frost"), source.index("frame.value = LiquidGlassFrame("))
        self.assertIn("GlassScenePolicy.retainTransientFrame(now - first)", source)
        for forbidden in ("source.setRenderEffect", "source.holder.setFixedSize", "source.setLayerType", "video-scale", "setVideoScalingMode"):
            self.assertNotIn(forbidden, source)
        policy = (JAVA / "ui/theme/GlassFrostPolicy.kt").read_text()
        self.assertIn("GlassSamplingPolicy.MaxSampleEdge", policy)
        self.assertIn("val result = pixels.copyOf()", policy)
        self.assertIn("GlassCustomizationPolicy.frostPasses(strength)", policy)
        self.assertIn("repeat(passes)", policy)
        tuning = (JAVA / "ui/theme/GlassCustomizationPolicy.kt").read_text()
        self.assertIn("MaximumFrostPasses = 6", tuning)
        self.assertIn(".coerceIn(0, MaximumFrostPasses)", tuning)
        self.assertNotIn("android.", policy)

    def test_subtitle_and_decoder_sheets_share_frost_without_per_sheet_sampling(self):
        for name in ("SubtitleTracksSheet.kt", "DecodersSheet.kt"):
            source = (JAVA / "ui/player/controls/components/sheets" / name).read_text()
            self.assertIn("PlayerSheet(onDismissRequest)", source, name)
            self.assertNotIn("PixelCopy", source, name)
            self.assertNotIn("Bitmap.createBitmap", source, name)
        sheet = (JAVA / "presentation/components/PlayerSheet.kt").read_text()
        self.assertIn(".liquidGlassPane(", sheet)
        self.assertIn("enabled = liquidGlassSurfaces", sheet)

    def test_artwork_blur_is_background_only_and_off_means_off(self):
        source = (JAVA / "ui/theme/GlassSurface.kt").read_text()
        self.assertIn("liquidGlassPane", source)
        self.assertIn("if (enabled && backdrop != null && !hasSharedScene)", source)
        effect = source.split("val artworkModifier =", 1)[1].split("Box(artworkModifier)", 1)[0]
        self.assertLess(effect.index(".clip(shape)"), effect.index(".graphicsLayer"))
        self.assertGreater(effect.rindex(".clip(shape)"), effect.index(".graphicsLayer"))
        self.assertLess(source.index("backdrop.invoke()"), source.index("    content()"))
        self.assertNotIn("PixelCopy", source)

    def test_controls_preserve_material_semantics_and_have_real_optical_thumbs(self):
        source = (JAVA / "ui/player/controls/components/GlassControls.kt").read_text()
        for expected in ("Switch(", "Slider(", "onCheckedChange", "onValueChangeFinished",
                         "interactionSource = interactionSource", "thumbContent =", "thumb =",
                         "liquidGlassPane", "enabled && preferenceEnabled"):
            self.assertIn(expected, source)
        self.assertIn("thumbContent: (@Composable () -> Unit)? = null", source)
        self.assertIn("thumbContent?.invoke()", source)
        for relative in ("ui/player/controls/PlayerButtonTheme.kt",
                         "ui/player/controls/components/ControlsButton.kt"):
            self.assertIn("import androidx.compose.runtime.getValue", (JAVA / relative).read_text())
        self.assertIn("import androidx.compose.runtime.getValue", (JAVA / "ui/cast/CastPlayerButton.kt").read_text())

    def test_panels_and_sheets_leave_sampled_glass_visible(self):
        for path in ("presentation/components/PlayerSheet.kt",
                     "ui/player/controls/components/panels/DraggablePanel.kt"):
            source = (JAVA / path).read_text()
            self.assertIn("liquidGlassPane", source)
            self.assertIn("Color.Transparent", source)
            self.assertLess(source.index(".offset {"), source.index(".liquidGlassPane("))

    def test_main_player_master_covers_transport_custom_controls_and_seekbar(self):
        source = (JAVA / "ui/player/controls/PlayerControls.kt").read_text()
        self.assertGreaterEqual(source.count(".playerGlassPane("), 10)
        self.assertIn("onGlassVisibilityChange(isPlayerDrawerShown)", source)
        self.assertNotIn("onGlassVisibilityChange(!isPlayerDrawerShown)", source)
        source = (JAVA / "ui/player/controls/components/ControlsButton.kt").read_text()
        self.assertIn("playerGlassPane", source)
        self.assertIn("Color.Transparent", source)

    def test_player_sheets_do_not_use_raw_material_controls(self):
        sheets = JAVA / "ui/player/controls/components/sheets"
        for path in sheets.glob("*.kt"):
            source = path.read_text()
            self.assertNotIn("androidx.compose.material3.Switch", source, path.name)
            self.assertNotIn("androidx.compose.material3.Slider\n", source, path.name)

    def test_audio_backdrops_use_only_palette_colors_not_artwork_or_fake_animations(self):
        audio = (JAVA / "ui/player/controls/AudioPlayerControls.kt").read_text()
        mini = (JAVA / "ui/browser/components/MiniPlayer.kt").read_text()
        scene = (JAVA / "ui/player/controls/AudioPaletteGlassScene.kt").read_text()
        self.assertIn("AudioPaletteGlassScene", audio)
        self.assertIn("topArgb = visualizerPalette.primary", audio)
        self.assertIn("LocalLiquidGlassBackdrop provides frame", scene)
        self.assertIn("AudioPalettePolicy.gradientRows(topArgb, bottomArgb)", scene)
        self.assertNotIn("albumArtBitmap", scene)
        self.assertNotIn("bitmap: Bitmap", scene)
        self.assertNotIn("AudioArtworkBackdrop", audio)
        self.assertIn("albumArtBitmap", audio)
        self.assertEqual(2, audio.count("if (isAudiobook) audiobookModeButtons() else {"))
        self.assertIn("artworkUri", mini)
        self.assertIn("BrowserGlassSurface(", mini)
        self.assertIn("cornerRadius = 20.dp", mini)
        self.assertNotIn("backdrop =", mini)
        self.assertNotIn("repeat(18)", mini)

    def test_native_control_optics_do_not_cover_touch_boxes(self):
        controls = (JAVA / "ui/player/controls/components/GlassControls.kt").read_text()
        self.assertIn("glassTrackOptics", controls)
        self.assertIn("thumbContent", controls)
        self.assertNotIn("modifier = modifier.liquidGlassPane", controls)
        # Build 1910: the native minimum box is documented at the shared capsule modifier.
        self.assertIn("Material keeps its minimum interaction box", controls)

    def test_audio_transport_has_no_enclosing_optical_surface(self):
        audio = (JAVA / "ui/player/controls/AudioPlayerControls.kt").read_text()
        self.assertNotIn("GlassControlSurface(", audio)
        self.assertIn("ReactiveIconButton", audio)
        self.assertIn("playerGlassPane(enabled = liquidGlassSurfaces", audio)


if __name__ == "__main__":
    unittest.main()
