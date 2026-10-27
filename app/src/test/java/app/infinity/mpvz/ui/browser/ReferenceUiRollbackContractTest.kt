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

  @Test fun preservedPlayerSourcesKeepPinnedFingerprints() {
    val expected = mapOf(
      // Build 1910 smoothness: chapter index via derivedStateOf so position ticks do not recompose the whole overlay.
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt" to "2219b79942125f0f6de4c1e543742605b6949a126d07fc4f05d32fc11240b27a" /* music entry hides stats via StatsLuaOverlay (no blind toggle, saved page kept); before that: 1901 (380a778b) stats overlay + music-entry stats hide restored; clean-room BrowserMusicSheetState replaces the mpvRx sheet type; outer-container preference now gates the track glass rail */,
      // Owner follow-up: music uses its legacy visual rail while video retains the global-glass path.
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/Seekbar.kt" to "b0d7ca11fdd2729871e052a0f6ea3b2cba64fb3e29039e5d3c42a7acf4d426c9",
      // Build 1910: shared rimLight/thick-surface routing is intentionally owned by this stream.
      // Build 1910 integration: union of the rim-light routing and thickSurface stream edits.
      // Owner follow-up: rounded player panes preserve the press shadow and inherit global glass tuning.
      // Accessibility follow-up: shared press, slider, and lens feedback respects reduced motion.
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/GlassControls.kt" to "e5ef3b62703cb91f04ae7275c2ae4d5838b6ea5cd6fa6662ed4b6d223c84d814",
      // Build 1910: player sheets opt into the centralized inner thickness bands.
      // Build 1910 integration: edge-attached landscape sheets draw one rim on the asymmetric shape.
      "app/src/main/java/app/infinity/mpvz/presentation/components/PlayerSheet.kt" to "7838a785e17d4bd1479a348a04c6f9ac7af0bacc0891ba817b165a1f53348c81",
      "app/src/main/java/app/infinity/mpvz/presentation/components/PlayerGlassOverlays.kt" to "82a115d195711b391235b5f32a4d7b2bac75e494aef0ff22be55251f9307b54f",
    )
    expected.forEach { (path, digest) -> assertEquals(path, digest, sha(read(path))) }
  }

  /** Keep the player-scoped ambient guard and bound lens refraction to the pane's actual corner radius. */
  @Test fun liquidGlassBackdropPreservesScopedAmbientAndBoundedRefraction() {
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
    // Build 1910 smoothness: capture reuses a double-buffered bitmap pair and pixel/luma scratch
    // arrays (GlassCaptureBuffers) instead of allocating per frame. Restored first, hunk by hunk.
    val smoothness = listOf(
      """  val frostPasses = GlassCustomizationPolicy.frostPasses(tuning.frost)
  val frame = remember { mutableStateOf<LiquidGlassFrame?>(null) }
  val captureBuffers = remember { GlassCaptureBuffers() }
  DisposableEffect(Unit) { onDispose { captureBuffers.recycle() } }
  val ready by rememberUpdatedState(isReady)
  val playing by rememberUpdatedState(isPlaying)
""" to
        """  val frostPasses = GlassCustomizationPolicy.frostPasses(tuning.frost)
  val frame = remember { mutableStateOf<LiquidGlassFrame?>(null) }
  val ready by rememberUpdatedState(isReady)
  val playing by rememberUpdatedState(isPlaying)
""",
      """        }
        // Only the tiny UI copy is cropped/frosted. The actual video surface remains untouched.
        val sampled = captureBuffers.acquire(dimensions.first, dimensions.second)?.let { target ->
          copyGlassFrame(source, crop, target)?.let {
            frostGlassFrame(target, tuning.frost, captureBuffers.pixels, captureBuffers.lumaBins)
          }
        }
        val bitmap = sampled?.first
        if (sampled == null || bitmap == null) {
""" to
        """        }
        // Only the tiny UI copy is cropped/frosted. The actual video surface remains untouched.
        val sampled = copyGlassFrame(source, crop, dimensions.first, dimensions.second)
          ?.let { frostGlassFrame(it, tuning.frost) }
        val bitmap = sampled?.first
        if (sampled == null || bitmap == null) {
""",
      """            (location[0] + crop.left + crop.width).toFloat(), (location[1] + crop.top + crop.height).toFloat()),
            underlayLuma = sampled.second)
          captureBuffers.markPublished(bitmap)
        }
        // Recover after transient failures instead of permanently disabling glass after three errors.
""" to
        """            (location[0] + crop.left + crop.width).toFloat(), (location[1] + crop.top + crop.height).toFloat()),
            underlayLuma = sampled.second)
        }
        // Recover after transient failures instead of permanently disabling glass after three errors.
""",
      """}

internal class GlassCaptureBuffers {
  private var first: Bitmap? = null
  private var second: Bitmap? = null
  private val retired = ArrayList<Bitmap>(2)
  private var nextFirst = true
  private var published: Bitmap? = null
  var pixels: IntArray = IntArray(0)
    private set
  val lumaBins = IntArray(64)
  fun acquire(width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0) return null
    if (first?.width != width || first?.height != height) {
      first?.let(retired::add); second?.let(retired::add)
      first = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      second = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      pixels = IntArray(width * height)
      nextFirst = true
    }
    // The last bitmap handed to Compose remains drawable until the state swap is
    // observed. Never let PixelCopy or frosting write into that published image.
    val preferred = if (nextFirst) first else second
    val alternate = if (preferred === first) second else first
    val result = when {
      preferred != null && preferred !== published -> preferred
      alternate != null && alternate !== published -> alternate
      else -> null
    }
    nextFirst = !nextFirst
    return result
  }
  fun markPublished(bitmap: Bitmap) {
    check(bitmap === first || bitmap === second)
    published = bitmap
  }
  fun recycle() {
    first?.recycle(); second?.recycle()
    retired.forEach { it.recycle() }
    retired.clear()
    first = null; second = null
    published = null
    pixels = IntArray(0)
  }
}

private suspend fun copyGlassFrame(source: SurfaceView, crop: GlassScenePolicy.SourceRect, bitmap: Bitmap): Bitmap? =
  suspendCancellableCoroutine { continuation ->
    try {
      PixelCopy.request(source, AndroidRect(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height), bitmap, { result ->
""" to
        """}

private suspend fun copyGlassFrame(source: SurfaceView, crop: GlassScenePolicy.SourceRect, width: Int, height: Int): Bitmap? =
  suspendCancellableCoroutine { continuation ->
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
      PixelCopy.request(source, AndroidRect(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height), bitmap, { result ->
""",
      """          continuation.resume(bitmap)
        } else {
          if (continuation.isActive) continuation.resume(null)
        }
      }, Handler(Looper.getMainLooper()))
    } catch (_: RuntimeException) {
      if (continuation.isActive) continuation.resume(null)
    }
""" to
        """          continuation.resume(bitmap)
        } else {
          bitmap.recycle() // only unpublished/failed copies can be recycled safely
          if (continuation.isActive) continuation.resume(null)
        }
      }, Handler(Looper.getMainLooper()))
    } catch (_: RuntimeException) {
      bitmap.recycle()
      if (continuation.isActive) continuation.resume(null)
    }
""",
      """
// Returns the frosted bitmap and its bright-percentile luma, measured on the same background pass.
private suspend fun frostGlassFrame(bitmap: Bitmap, strength: Float, pixels: IntArray, lumaBins: IntArray): Pair<Bitmap, Float>? = try {
  withContext(Dispatchers.Default) {
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    // Every API level keeps the same CPU frost on this tiny sample: the API 33+ refraction
    // shader and the older paint path both expect an already-frosted input (visual parity).
    val frosted = GlassFrostPolicy.frostArgb(pixels, bitmap.width, bitmap.height, strength)
    bitmap.setPixels(frosted, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    bitmap to GlassSurfacePolicy.underlayLuma(frosted, lumaBins)
  }
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (_: RuntimeException) {
  null
}

""" to
        """
// Returns the frosted bitmap and its bright-percentile luma, measured on the same background pass.
private suspend fun frostGlassFrame(bitmap: Bitmap, strength: Float): Pair<Bitmap, Float>? = try {
  withContext(Dispatchers.Default) {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val frosted = GlassFrostPolicy.frostArgb(pixels, bitmap.width, bitmap.height, strength)
    bitmap.setPixels(frosted, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    bitmap to GlassSurfacePolicy.underlayLuma(frosted)
  }
} catch (cancelled: CancellationException) {
  bitmap.recycle() // This bitmap was never published to Compose.
  throw cancelled
} catch (_: RuntimeException) {
  bitmap.recycle()
  null
}

""",
    )
    smoothness.forEach { (now, before) ->
      assertEquals(now, 1, film.split(now).size - 1)
      film = film.replace(now, before)
    }
    // Keep last frosted sample during ambience/interpolation; reverse before hashing.
    val frostRetention = listOf(
      """  // Visibility changes stop work, but do not flash the last good scene to black during sheet handoff.
  // Destroy and ineligible still discard. Background stop keeps the last frost so
  // resume is not a clear pane for a second.
  DisposableEffect(surfaceView, generation, eligible, lifecycleOwner) {
    if (!eligible) frame.value = null
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_DESTROY) frame.value = null
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      // New generation/surface: drop the previous source frost. Background ON_STOP
      // does not dispose this effect, so resume can keep the last frosted frame.
      frame.value = null
    }
  }
""" to
        """  // Visibility changes stop work, but do not flash the last good scene to black during sheet handoff.
  // Media, safety and lifecycle boundaries always discard the old scene immediately.
  DisposableEffect(surfaceView, generation, eligible, lifecycleOwner) {
    frame.value = null
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) frame.value = null
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      frame.value = null
    }
  }
""",
      """        // Keep the last frosted sample during ambience/interpolation hiccups.
        if (!GlassScenePolicy.retainTransientFrame(now - first) &&
          !GlassScenePolicy.shouldKeepLastFrostedFrame(
            lifecycleStopped = false,
            sourceChanged = false,
            ineligible = !eligible,
          )
        ) frame.value = null
""" to
        """        if (!GlassScenePolicy.retainTransientFrame(now - first)) frame.value = null
""",
    )
    frostRetention.forEach { (now, before) ->
      assertEquals(now, 1, film.split(now).size - 1)
      film = film.replace(now, before)
    }
    latency.forEach { (now, before) ->
      assertEquals(now, 1, film.split(now).size - 1)
      film = film.replace(now, before)
    }
    val isolation = listOf(
      """import android.graphics.Paint as AndroidPaint
import android.graphics.Shader
""" to
        """import android.graphics.Paint as AndroidPaint
import android.graphics.RuntimeShader
import android.graphics.Shader
""",
      """            val bevel = GlassBevelPolicy.params(size.width, size.height, radius,
              tuning.depth, tuning.refraction, reducedMotion, highContrast)
            // Keep the bend on normal dialogs and sheets; only very large
            // surfaces are bounded to avoid turning a full-screen layer into
            // a refraction pass.
            val lensAllowed = scene.refractionEnabled &&
              if (materialRole == GlassSurfacePolicy.MaterialRole.Regular) size.maxDimension <= 1024.dp.toPx()
              else size.minDimension <= 128.dp.toPx() && size.maxDimension <= 512.dp.toPx()
            val frostDp = if (materialRole == GlassSurfacePolicy.MaterialRole.Regular)
              maxOf(GlassSurfacePolicy.RegularMinimumFrostDp, 10f * tuning.frost * material.frostMultiplier)
            else 10f * tuning.frost * material.frostMultiplier
            val mapped = RuntimeShaderSupport.bindGlassOpticsOrNull(
              shader = shader,
              sourceShader = sourceShader,
              paint = paint,
              paneWidth = size.width,
              paneHeight = size.height,
              originX = paneBounds.left,
              originY = paneBounds.top,
              sceneLeft = scene.boundsInWindow.left,
              sceneTop = scene.boundsInWindow.top,
              sceneWidth = scene.boundsInWindow.width,
              sceneHeight = scene.boundsInWindow.height,
              imageWidth = scene.image.width.toFloat(),
              imageHeight = scene.image.height.toFloat(),
              radius = radius,
              bevel = bevel.bevel,
              lightX = bevel.normalX,
              lightY = bevel.normalY,
              specular = bevel.specular,
              fresnel = bevel.fresnel,
              strength = if (lensAllowed) 4.dp.toPx() * material.refractionMultiplier *
                GlassSurfacePolicy.refractionStrength(tuning.refraction, reducedMotion, highContrast) else 0f,
              frostPx = frostDp * density,
            )
""" to
        """            val mapped = shader != null && Build.VERSION.SDK_INT >= 33 && runCatching {
              sourceShader.setLocalMatrix(null)
              shader.setInputShader("scene", sourceShader)
              shader.setFloatUniform("pane", size.width, size.height)
              shader.setFloatUniform("origin", paneBounds.left, paneBounds.top)
              shader.setFloatUniform("sceneOrigin", scene.boundsInWindow.left, scene.boundsInWindow.top)
              shader.setFloatUniform("sceneSize", scene.boundsInWindow.width, scene.boundsInWindow.height)
              shader.setFloatUniform("imageSize", scene.image.width.toFloat(), scene.image.height.toFloat())
              shader.setFloatUniform("radius", radius)
              val bevel = GlassBevelPolicy.params(size.width, size.height, radius,
                tuning.depth, tuning.refraction, reducedMotion, highContrast)
              shader.setFloatUniform("bevel", bevel.bevel)
              shader.setFloatUniform("light", bevel.normalX, bevel.normalY)
              shader.setFloatUniform("specular", bevel.specular)
              shader.setFloatUniform("fresnel", bevel.fresnel)
              // Keep the bend on normal dialogs and sheets; only very large
              // surfaces are bounded to avoid turning a full-screen layer into
              // a refraction pass.
              val lensAllowed = scene.refractionEnabled &&
                if (materialRole == GlassSurfacePolicy.MaterialRole.Regular) size.maxDimension <= 1024.dp.toPx()
                else size.minDimension <= 128.dp.toPx() && size.maxDimension <= 512.dp.toPx()
              shader.setFloatUniform("strength", if (lensAllowed) 4.dp.toPx() * material.refractionMultiplier *
                GlassSurfacePolicy.refractionStrength(tuning.refraction, reducedMotion, highContrast) else 0f)
              val frostDp = if (materialRole == GlassSurfacePolicy.MaterialRole.Regular)
                maxOf(GlassSurfacePolicy.RegularMinimumFrostDp, 10f * tuning.frost * material.frostMultiplier)
              else 10f * tuning.frost * material.frostMultiplier
              shader.setFloatUniform("frost", frostDp * density)
              paint.shader = shader
            }.isSuccess
""",
      "internal const val GLASS_OPTICS" to "private const val GLASS_OPTICS",
      // After latency reverse, the isolated remember site still lacks origin/main's SDK 33 guard.
      """  val shader = remember {
    runCatching { RuntimeShader(GLASS_OPTICS) }.getOrNull()
  }
""" to
        """  val shader = remember {
    if (Build.VERSION.SDK_INT >= 33) runCatching { RuntimeShader(GLASS_OPTICS) }.getOrNull() else null
  }
""",
    )
    isolation.forEach { (now, before) ->
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
      // Build 1908: unsupported or glass-crashed devices skip sampling (GlassDeviceSupport), and
      // the startup sentinel watches the first glass frames. Optics and cadence are unchanged.
      "canSample(enabled && GlassDeviceSupport.allowsGlass(context), true," to "canSample(enabled, true,",
      "  val allowed = eligible && visible\n  GlassStartupWatch(active = allowed)\n" to
        "  val allowed = eligible && visible\n",
    )
    var restored = film.replace(guarded, baseline)
    cadence.forEach { (now, before) ->
      assertEquals(now, 1, restored.split(now).size - 1)
      restored = restored.replace(now, before)
    }
    // Build 1910: centralized rim/depth bands replace the old pane-local contour; the integration
    // keeps the bounded three-band lower-right exterior contact shadow (depth cue, no bitmap blur).
    assertTrue(current.contains("val minCornerRadiusPx = cornerRadius.toPx().coerceAtMost(size.minDimension / 2f)"))
    assertTrue(current.contains("refractionHeight = LensGlassPolicy.refractionHeightPx(24.dp.toPx(), minCornerRadiusPx, tuning.lensDepth)"))
    assertEquals("01f65408e855f52c32f53739d1c461968c0be81f37d4cd4834fc8d0b86a20e66", sha(restored))
  }
  @Test fun glassChipAndPlayerControlsRespectAccessibilityMotion() {
    val chip = read("app/src/main/java/app/infinity/mpvz/ui/components/GlassChip.kt")
    assertTrue(chip.contains("verticalAlignment = Alignment.CenterVertically"))
    val controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/GlassControls.kt")
    assertTrue(controls.contains("reducedMotion: Boolean = AppMotion.shouldReduceMotion()"))
    assertTrue(controls.contains(".glassWaterRipple(interactionSource, enabled, glass = glass && !reducedMotion)"))
    assertTrue(controls.contains("if (reducedMotion) snap<Float>() else spring<Float>"))
    assertEquals(2, controls.split("val reducedMotion = AppMotion.shouldReduceMotion()").size - 1)
    assertTrue(controls.contains("!(refraction && GlassWaterRippleEffects.available())"))
    assertTrue(controls.contains("if (lensEnabled && !reducedMotion && (switchPressed || switchDragged)) 1f else 0f"))
    assertTrue(controls.contains("if (lensEnabled && !reducedMotion && (pressed || touched)) 1f else 0f"))
    assertTrue(controls.contains("GlassSliderPolicy.stretch(pressed, reducedMotion)"))
    assertTrue(controls.contains("GlassSliderPolicy.squash(pressed, reducedMotion)"))
    assertTrue(controls.contains("The fade is the whole effect on fallback devices"))
    assertEquals(1f, app.infinity.mpvz.ui.player.controls.components.GlassSliderPolicy.stretch(true, reducedMotion = true), 0f)
    assertEquals(1f, app.infinity.mpvz.ui.player.controls.components.GlassSliderPolicy.squash(true, reducedMotion = true), 0f)
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
    // Build 1943: the drag moved to the shared Initial-pass navPillDragGesture (1942's Main-pass
    // slop detector never started on the phone).
    // Owner follow-up: retain the normal 44dp selected outline/placement; the glass refraction
    // follows finger-driven drag motion without an elevated pop or altered tab hit targets.
    assertEquals("555f92e1272db06d7ae9df41021a817c546cad73a9bbf6f03de981609b6ef1f1", sha(navigation.substring(start, end)))
    val scene = read("app/src/main/java/app/infinity/mpvz/ui/theme/BrowserGlassScene.kt")
    val lensStart = scene.indexOf("internal const val BROWSER_LENS")
    val q = scene.indexOf("\"\"\"", lensStart)
    val lensEnd = scene.indexOf("\"\"\"", q + 3) + 3
    assertEquals("2d28e2fd38cafae49de118a956a611050585dc89d60846548a5cbfd7b7050369", sha(scene.substring(lensStart, lensEnd)))
    assertTrue(scene.contains("private const val BROWSER_LATEST_LENS"))
  }
}
