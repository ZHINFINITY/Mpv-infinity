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
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt" to "aaf057ec8635d288c447eeda65d2d0eb116bf772ae9c02ba123cb35f42af4093" /* build 1902: stats overlay policy */,
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/Seekbar.kt" to "b0d7ca11fdd2729871e052a0f6ea3b2cba64fb3e29039e5d3c42a7acf4d426c9",
      // Build 1902: owner-approved contrasting switch colours (3D glass depth kept).
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/GlassControls.kt" to "ae873298a6b622bcdbe5c5366fc6d8fd37009ee395c3531c1df90f9dd31f6320",
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
    // Build 1901: sheet-open latency (shared compiled optics, transition settle, controls
    // cadence). Restored to the 1900 text first; the earlier scoped steps then run unchanged.
    val latency = listOf(
      """  sampleAspect: () -> Double? = { null },
  // Changes when a sheet/panel opens or closes: sampling pauses briefly so the open
  // animation does not compete with PixelCopy, frost and pane redraws.
  transitionKey: Any? = null,
  // A sheet/panel covers the video: sample at display cadence. Controls only: half rate.
  largeSurfaceVisible: Boolean = true,
""" to
        """  sampleAspect: () -> Double? = { null },
""",
      """  val thermalNow by rememberUpdatedState(thermal)
  val largeSurface by rememberUpdatedState(largeSurfaceVisible)
  val settleUntil = remember { longArrayOf(0L) }
  LaunchedEffect(transitionKey) {
    settleUntil[0] = SystemClock.uptimeMillis() + GlassSamplingPolicy.TransitionSettleMs
  }
  // Warm the shared optics program before the first sheet opens (idle, after first frame).
  LaunchedEffect(enabled) {
    if (enabled && RuntimeShaderSupport.available()) {
      delay(GlassSamplingPolicy.ShaderWarmupDelayMs)
      RuntimeShaderSupport.glassOpticsOrNull()
    }
  }
""" to
        """  val thermalNow by rememberUpdatedState(thermal)
""",
      """        val settleLeft = GlassSamplingPolicy.settleDelay(SystemClock.uptimeMillis(), settleUntil[0], frame.value != null)
        if (settleLeft > 0L) {
          delay(settleLeft)
          continue
        }
""" to
        """""",
      """GlassSamplingPolicy.interval(playing(), failures, thermalNow, largeSurface),""" to
        """GlassSamplingPolicy.interval(playing(), failures, thermalNow),""",
      """RuntimeShaderSupport.glassOpticsOrNull()""" to
        """runCatching { RuntimeShader(GLASS_OPTICS) }.getOrNull()""",
    )
    var film = current
    latency.forEach { (now, before) ->
      assertEquals(now, 1, film.split(now).size - 1)
      film = film.replace(now, before)
    }
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
      // Build 1899: glow-free edge-to-edge bars outside a browser scene (no sheen/rim/shadow).
      "import androidx.compose.foundation.background\n" to "",
      """  // Edge-to-edge top/selection bars outside a browser scene (e.g. settings screens): no
  // white sheen band, rim line or shadow, which read as a glowing bar; page-background film only.
  if (backdrop == null && GlassSurfacePolicy.isEdgeToEdgeChrome(cornerRadius.value)) {
    return this.background(MaterialTheme.colorScheme.background.copy(alpha = GlassSurfacePolicy.EdgeChromeNoSceneFilmAlpha))
  }
""" to "",
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
    // Build 1902: nav pill commits the lens frame before far page jumps and supports drag-to-select.
    assertEquals("6dd01fe6826b20433d5073e6a41b45f2750f27f2b2ce2a6f0b6418263f8358f6", sha(navigation.substring(start, end)))
    val scene = read("app/src/main/java/app/infinity/mpvz/ui/theme/BrowserGlassScene.kt")
    val lensStart = scene.indexOf("private const val BROWSER_LENS")
    val q = scene.indexOf("\"\"\"", lensStart)
    val lensEnd = scene.indexOf("\"\"\"", q + 3) + 3
    assertEquals("5c4a067ebbd5aabfb2239fd33f3bcb1ead3f03532bb04287499462ac96e8bb1e", sha(scene.substring(lensStart, lensEnd)))
    assertTrue(scene.contains("private const val BROWSER_LATEST_LENS"))
  }
}
