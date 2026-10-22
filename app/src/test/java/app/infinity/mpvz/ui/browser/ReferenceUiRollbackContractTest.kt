/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level tripwires for the approved 0dd2a9cd browser rollback. */
class ReferenceUiRollbackContractTest {
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private fun read(path: String) = File(root, path).readText()
  private fun sha(value: String) = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

  @Test fun originalFolderCardSourceFingerprintIsPreserved() {
    assertEquals("18760196bbe09db6601592650ce9c243f8a8ff6ddba80e9e2bc0b4bab8fdb563",
      sha(read("app/src/main/java/app/infinity/mpvz/ui/browser/cards/FolderCard.kt")))
  }

  @Test fun preservedPlayerSourcesKeepBaseline7eFingerprints() {
    val expected = mapOf(
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt" to "e94a3ddb5a4e1b9d48964292258dd2fc5174a024b153d5e0e6d4c22712916529",
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/Seekbar.kt" to "b0d7ca11fdd2729871e052a0f6ea3b2cba64fb3e29039e5d3c42a7acf4d426c9",
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/GlassControls.kt" to "c5266167f59009db21006a0b0cb68bea1b144350f8046f77cc6cc216c4447ff3",
      "app/src/main/java/app/infinity/mpvz/presentation/components/PlayerSheet.kt" to "fdcf88d13735e1876059f0e7bdcc41088ecedd06d58fffde0283c9e32967e96a",
      "app/src/main/java/app/infinity/mpvz/presentation/components/PlayerGlassOverlays.kt" to "82a115d195711b391235b5f32a4d7b2bac75e494aef0ff22be55251f9307b54f",
    )
    expected.forEach { (path, digest) -> assertEquals(path, digest, sha(read(path))) }
  }

  /** Only the player-scoped ambient fallback guard and ambient film tint (builds 1896/1897) may differ. */
  @Test fun liquidGlassBackdropDiffersFromBaselineOnlyByScopedAmbientGuard() {
    val path = "app/src/main/java/app/infinity/mpvz/ui/theme/LiquidGlassBackdrop.kt"
    val current = read(path)
    val guarded = """        // When video sampling is temporarily unavailable, the ambient video
        // presentation remains underneath this Compose layer. Do not cover it
        // with the theme's dark surface tint (which reads as a black control
        // on devices where SurfaceView sampling is unavailable); keep the
        // fallback to the rim/highlight optics and let the ambient colour show
        // through. A sampled scene still receives the normal glass film.
        // Only player backdrop hosts provide LocalLiquidGlassBackdrop; browser/settings fallbacks
        // (backdrop == null) keep their legibility film.
        if (scene != null || backdrop == null) {
          // Player hosts adopt the ambient glow colour (read here, in draw) instead of the
          // theme's dark surface, so sheets and the chapter pill follow the ambience.
          drawRoundRect(PlayerAmbientTintPolicy.filmColor(themedTint, PlayerAmbientTint.color,
            isPlayerHost = backdrop != null).copy(alpha = filmAlpha),
            cornerRadius = CornerRadius(radius))
        }
"""
    val baseline = """        drawRoundRect(themedTint.copy(alpha = filmAlpha),
          cornerRadius = CornerRadius(radius))
"""
    // Build 1899: adaptive see-through Regular film (593922 target). Each hunk is restored to its
    // 1898 text first, then the earlier scoped steps run unchanged.
    val adaptiveFilm = listOf(
      """/** Immutable snapshot: window coordinates prevent every control showing the entire frame. */
/** [underlayLuma] is the frosted sample's bright-percentile sRGB luma (1 = assume a white backdrop). */
data class LiquidGlassFrame(
  val image: ImageBitmap,
  val boundsInWindow: Rect,
  val refractionEnabled: Boolean = true,
  val underlayLuma: Float = 1f,
)
""" to
        """/** Immutable snapshot: window coordinates prevent every control showing the entire frame. */
data class LiquidGlassFrame(val image: ImageBitmap, val boundsInWindow: Rect, val refractionEnabled: Boolean = true)
""",
      """        // Only the tiny UI copy is cropped/frosted. The actual video surface remains untouched.
        val sampled = copyGlassFrame(source, crop, dimensions.first, dimensions.second)
""" to
        """        // Only the tiny UI copy is cropped/frosted. The actual video surface remains untouched.
        val bitmap = copyGlassFrame(source, crop, dimensions.first, dimensions.second)
""",
      """          ?.let { frostGlassFrame(it, tuning.frost) }
        val bitmap = sampled?.first
        if (sampled == null || bitmap == null) {
""" to
        """          ?.let { frostGlassFrame(it, tuning.frost) }
        if (bitmap == null) {
""",
      """            (location[0] + crop.left).toFloat(), (location[1] + crop.top).toFloat(),
            (location[0] + crop.left + crop.width).toFloat(), (location[1] + crop.top + crop.height).toFloat()),
            underlayLuma = sampled.second)
""" to
        """            (location[0] + crop.left).toFloat(), (location[1] + crop.top).toFloat(),
            (location[0] + crop.left + crop.width).toFloat(), (location[1] + crop.top + crop.height).toFloat()))
""",
      """
// Returns the frosted bitmap and its bright-percentile luma, measured on the same background pass.
private suspend fun frostGlassFrame(bitmap: Bitmap, strength: Float): Pair<Bitmap, Float>? = try {
""" to
        """
private suspend fun frostGlassFrame(bitmap: Bitmap, strength: Float): Bitmap? = try {
""",
      """    bitmap.setPixels(frosted, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    bitmap to GlassSurfacePolicy.underlayLuma(frosted)
""" to
        """    bitmap.setPixels(frosted, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    bitmap
""",
      """  val contrastRim = MaterialTheme.colorScheme.outline
  // Sheet text is white on dark themes and black on light ones (PlayerSheet contentColor).
  val lightText = GlassSurfacePolicy.srgbLuma(MaterialTheme.colorScheme.onSurface) >= 0.5f
  val textColor = if (lightText) Color.White else Color.Black
""" to
        """  val contrastRim = MaterialTheme.colorScheme.outline
""",
      """        // Film guarantees readable controls, even when sampling is intentionally unavailable.
        val filmColor = PlayerAmbientTintPolicy.filmColor(themedTint, PlayerAmbientTint.color,
          isPlayerHost = backdrop != null, lightText = lightText)
        // Regular sheets keep the see-through reference film (opacity * 0.42); only a bright
        // backdrop raises it, bounded at a light RegularMaximumFilmAlpha (never an opaque slab).
""" to
        """        // Film guarantees readable controls, even when sampling is intentionally unavailable.
""",
      """        val centerFilm = if (materialRole == GlassSurfacePolicy.MaterialRole.Regular)
          GlassSurfacePolicy.regularAdaptiveFilmAlpha(alpha, scene?.underlayLuma ?: 1f, filmColor, textColor,
            optics.bodyHighlight, optics.bodyShade)
""" to
        """        val centerFilm = if (materialRole == GlassSurfacePolicy.MaterialRole.Regular)
          GlassSurfacePolicy.regularCenterFilmAlpha(alpha)
""",
      """          // theme's dark surface, so sheets and the chapter pill follow the ambience.
          drawRoundRect(filmColor.copy(alpha = filmAlpha),
""" to
        """          // theme's dark surface, so sheets and the chapter pill follow the ambience.
          drawRoundRect(PlayerAmbientTintPolicy.filmColor(themedTint, PlayerAmbientTint.color,
            isPlayerHost = backdrop != null).copy(alpha = filmAlpha),
""",
    )
    var film = current
    adaptiveFilm.forEach { (now, before) ->
      assertEquals(now, 1, film.split(now).size - 1)
      film = film.replace(now, before)
    }
    assertEquals(1, film.split(guarded).size - 1)
    // Build 1897: the playing sample cadence (video behind decoder/subtitle sheets) is the
    // only other permitted change; each hunk is restored to its baseline before hashing.
    val cadence = listOf(
      "  val aspect by rememberUpdatedState(sampleAspect)\n  val thermalNow by rememberUpdatedState(thermal)\n" to
        "  val aspect by rememberUpdatedState(sampleAspect)\n",
      "        val sampleStarted = SystemClock.uptimeMillis()\n        val crop =" to "        val crop =",
      """          else GlassSamplingPolicy.remainingDelay(
            GlassSamplingPolicy.interval(playing(), failures, thermalNow),
            SystemClock.uptimeMillis() - sampleStarted,
          ))""" to "          else GlassSamplingPolicy.interval(playing(), failures))",
    )
    var restored = film.replace(guarded, baseline)
    cadence.forEach { (now, before) ->
      assertEquals(now, 1, restored.split(now).size - 1)
      restored = restored.replace(now, before)
    }
    assertEquals("f10a7bb20ca6e17e57f8500f5857cae1fc6d9ecf9f30c67512b8925ce2d9b7f3", sha(restored))
  }
  @Test fun expressiveNavigationBodyAndBrowserLensMatchReference() {
    val navigation = read("app/src/main/java/app/infinity/mpvz/ui/browser/MainScreen.kt")
    val start = navigation.indexOf("private fun ExpressivePillNavigationBar(")
    require(start >= 0)
    val open = navigation.indexOf('{', start)
    var depth = 0
    var end = open
    while (end < navigation.length) {
      if (navigation[end] == '{') depth++
      if (navigation[end] == '}') { depth--; if (depth == 0) { end++; break } }
      end++
    }
    assertEquals("af3ac184bf4593d62f64a2339843a7bed000174672caacf68ad06d10d32d8f95", sha(navigation.substring(start, end)))
    val scene = read("app/src/main/java/app/infinity/mpvz/ui/theme/BrowserGlassScene.kt")
    val lensStart = scene.indexOf("private const val BROWSER_LENS")
    val q = scene.indexOf("\"\"\"", lensStart)
    val lensEnd = scene.indexOf("\"\"\"", q + 3) + 3
    assertEquals("5c4a067ebbd5aabfb2239fd33f3bcb1ead3f03532bb04287499462ac96e8bb1e", sha(scene.substring(lensStart, lensEnd)))
    assertTrue(scene.contains("private const val BROWSER_LATEST_LENS"))
  }
}
