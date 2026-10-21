/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms.
 * Independently implemented for Mpv Infinity; no third-party glass source is included.
 */
package app.infinity.mpvz.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint as AndroidPaint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.graphics.Rect as AndroidRect
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Immutable snapshot: window coordinates prevent every control showing the entire frame. */
data class LiquidGlassFrame(val image: ImageBitmap, val boundsInWindow: Rect, val refractionEnabled: Boolean = true)

// State is read in the draw phase, not propagated as a new composition tree at sample cadence.
val LocalLiquidGlassBackdrop = compositionLocalOf<State<LiquidGlassFrame?>?> { null }

/**
 * One sampler for the entire player, not one sampler per control. SurfaceView is a separate
 * compositor layer, so ordinary Compose blur cannot sample it. Never requests window copies,
 * changes decoder settings, invokes screenshots, or reads HDR/protected video into a UI texture.
 */
@Composable
fun LiquidGlassBackdropHost(
  surfaceView: SurfaceView?,
  enabled: Boolean,
  visible: Boolean,
  audioOnly: Boolean,
  hdr: Boolean,
  generation: Any?,
  isReady: () -> Boolean,
  isPlaying: () -> Boolean,
  sampleAspect: () -> Double? = { null },
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val lowRam = remember(context) {
    context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
  }
  val power = remember(context) { context.getSystemService(PowerManager::class.java) }
  var thermal by remember { mutableStateOf(0) }
  DisposableEffect(enabled, power) {
    if (enabled && power != null && Build.VERSION.SDK_INT >= 29) {
      thermal = power.currentThermalStatus
      val listener = PowerManager.OnThermalStatusChangedListener { thermal = it }
      power.addThermalStatusListener(context.mainExecutor, listener)
      onDispose { power.removeThermalStatusListener(listener) }
    } else {
      onDispose { }
    }
  }
  val eligible = GlassSamplingPolicy.canSample(enabled, true, audioOnly, hdr, lowRam, thermal)
  val allowed = eligible && visible
  val tuning = LocalGlassCustomization.current
  val frostPasses = GlassCustomizationPolicy.frostPasses(tuning.frost)
  val frame = remember { mutableStateOf<LiquidGlassFrame?>(null) }
  val ready by rememberUpdatedState(isReady)
  val playing by rememberUpdatedState(isPlaying)
  val aspect by rememberUpdatedState(sampleAspect)
  // Visibility changes stop work, but do not flash the last good scene to black during sheet handoff.
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
  LaunchedEffect(surfaceView, allowed, generation, lifecycleOwner, frostPasses) {
    val source = surfaceView ?: return@LaunchedEffect
    if (!allowed) return@LaunchedEffect
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      var failures = 0
      var unavailableSince: Long? = null
      fun transientFailure() {
        val now = SystemClock.uptimeMillis()
        val first = unavailableSince ?: now.also { unavailableSince = it }
        if (!GlassScenePolicy.retainTransientFrame(now - first)) frame.value = null
      }
      while (currentCoroutineContext().isActive) {
        if (!ready() || !source.isAttachedToWindow || !source.holder.surface.isValid) {
          transientFailure()
          delay(if (frame.value != null) GlassScenePolicy.TransientGraceMs else GlassSamplingPolicy.RetryIntervalMs)
          continue
        }
        val crop = GlassScenePolicy.sourceRect(source.width, source.height, aspect())
        val dimensions = crop?.let { GlassSamplingPolicy.sampleSize(it.width, it.height) }
        if (crop == null || dimensions == null) {
          transientFailure()
          delay(GlassSamplingPolicy.RetryIntervalMs)
          continue
        }
        // Only the tiny UI copy is cropped/frosted. The actual video surface remains untouched.
        val bitmap = copyGlassFrame(source, crop, dimensions.first, dimensions.second)
          ?.let { frostGlassFrame(it, tuning.frost) }
        if (bitmap == null) {
          transientFailure()
          failures = (failures + 1).coerceAtMost(GlassSamplingPolicy.FailureLimit)
        } else {
          failures = 0
          unavailableSince = null
          val location = IntArray(2)
          source.getLocationOnScreen(location)
          frame.value = LiquidGlassFrame(bitmap.asImageBitmap(), Rect(
            (location[0] + crop.left).toFloat(), (location[1] + crop.top).toFloat(),
            (location[0] + crop.left + crop.width).toFloat(), (location[1] + crop.top + crop.height).toFloat()))
        }
        // Recover after transient failures instead of permanently disabling glass after three errors.
        delay(if (failures > 0 && frame.value != null) GlassScenePolicy.TransientGraceMs
          else GlassSamplingPolicy.interval(playing(), failures))
      }
    }
  }
  CompositionLocalProvider(LocalLiquidGlassBackdrop provides frame.takeIf { eligible }, content = content)
}

private suspend fun copyGlassFrame(source: SurfaceView, crop: GlassScenePolicy.SourceRect, width: Int, height: Int): Bitmap? =
  suspendCancellableCoroutine { continuation ->
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
      PixelCopy.request(source, AndroidRect(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height), bitmap, { result ->
        if (result == PixelCopy.SUCCESS && continuation.isActive) {
          continuation.resume(bitmap)
        } else {
          bitmap.recycle() // only unpublished/failed copies can be recycled safely
          if (continuation.isActive) continuation.resume(null)
        }
      }, Handler(Looper.getMainLooper()))
    } catch (_: RuntimeException) {
      bitmap.recycle()
      if (continuation.isActive) continuation.resume(null)
    }
  }

private suspend fun frostGlassFrame(bitmap: Bitmap, strength: Float): Bitmap? = try {
  withContext(Dispatchers.Default) {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val frosted = GlassFrostPolicy.frostArgb(pixels, bitmap.width, bitmap.height, strength)
    bitmap.setPixels(frosted, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    bitmap
  }
} catch (cancelled: CancellationException) {
  bitmap.recycle() // This bitmap was never published to Compose.
  throw cancelled
} catch (_: RuntimeException) {
  bitmap.recycle()
  null
}

/**
 * Background-only optics keep text/icons sharp. All video panes reuse one smoothly frosted
 * sample. API 33+ adds bilinear sampled-scene refraction with an original rounded-pane lens.
 * Older Android uses Paint-filtered frost plus readable film, never nearest-neighbour blocks.
 * With no usable scene (HDR, audio, errors, low-RAM or severe heat): transparent film/rim only.
 */
@Composable
fun Modifier.liquidGlassPane(
  enabled: Boolean,
  opacity: Float = 0.88f,
  highlight: Float = 0.18f,
  cornerRadius: Dp = 24.dp,
  drawRim: Boolean = true,
): Modifier {
  if (!enabled) return this
  val backdrop = LocalLiquidGlassBackdrop.current
  val tuning = LocalGlassCustomization.current
  val tint = MaterialTheme.colorScheme.surfaceContainerHigh
  val alpha = GlassSurfacePolicy.opacity(if (tuning.overrideMaterial) tuning.opacity else opacity)
  val hostView = LocalView.current
  val glow = GlassSurfacePolicy.highlight(if (tuning.overrideMaterial) tuning.highlight else highlight)
  var paneBounds by remember { mutableStateOf(Rect.Zero) }
  val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
  val shader = remember {
    if (Build.VERSION.SDK_INT >= 33) runCatching { RuntimeShader(GLASS_OPTICS) }.getOrNull() else null
  }
  return this
    .onGloballyPositioned { coordinates ->
      val windowOrigin = coordinates.localToWindow(Offset.Zero)
      val windowLocation = IntArray(2)
      val screenLocation = IntArray(2)
      hostView.getLocationInWindow(windowLocation)
      hostView.getLocationOnScreen(screenLocation)
      val origin = Offset(
        windowOrigin.x + (screenLocation[0] - windowLocation[0]),
        windowOrigin.y + (screenLocation[1] - windowLocation[1]),
      )
      val next = Rect(origin.x, origin.y, origin.x + coordinates.size.width,
        origin.y + coordinates.size.height)
      if (next != paneBounds) paneBounds = next
    }
    .then(if (drawRim && tuning.depth > 0f) Modifier.shadow(4.dp * tuning.depth, shape, clip = false) else Modifier)
    .clip(shape)
    .drawWithCache {
      val radius = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
      val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
      val matrix = Matrix()
      var lastImage: ImageBitmap? = null
      var imageShader: BitmapShader? = null
      val rim = Brush.linearGradient(listOf(
        Color.White.copy(alpha = (0.10f + glow * 0.30f + tuning.depth * 0.025f).coerceAtMost(1f)),
        Color.White.copy(alpha = 0.04f),
        Color.White.copy(alpha = 0.06f + glow * 0.20f + tuning.depth * 0.015f),
      ))
      onDrawBehind {
        val scene = backdrop?.value
        if (scene != null && scene.boundsInWindow.width > 0f && scene.boundsInWindow.height > 0f) {
          if (scene.image !== lastImage) {
            lastImage = scene.image
            imageShader = BitmapShader(scene.image.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
              .also {
                // A RuntimeShader input defaults to NEAREST even with FILTER_BITMAP_FLAG.
                if (Build.VERSION.SDK_INT >= 33) it.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
              }
          }
          val sourceShader = imageShader
          if (sourceShader != null) {
            val mapped = shader != null && Build.VERSION.SDK_INT >= 33 && runCatching {
              sourceShader.setLocalMatrix(null)
              shader.setInputShader("scene", sourceShader)
              shader.setFloatUniform("pane", size.width, size.height)
              shader.setFloatUniform("origin", paneBounds.left, paneBounds.top)
              shader.setFloatUniform("sceneOrigin", scene.boundsInWindow.left, scene.boundsInWindow.top)
              shader.setFloatUniform("sceneSize", scene.boundsInWindow.width, scene.boundsInWindow.height)
              shader.setFloatUniform("imageSize", scene.image.width.toFloat(), scene.image.height.toFloat())
              shader.setFloatUniform("radius", radius)
              val lensAllowed = scene.refractionEnabled && size.minDimension <= 128.dp.toPx() && size.maxDimension <= 512.dp.toPx()
              shader.setFloatUniform("strength", if (lensAllowed) 4.dp.toPx() * tuning.refraction else 0f)
              shader.setFloatUniform("frost", 10.dp.toPx() * tuning.frost)
              paint.shader = shader
            }.isSuccess
            if (!mapped) {
              matrix.setScale(scene.boundsInWindow.width / scene.image.width,
                scene.boundsInWindow.height / scene.image.height)
              matrix.postTranslate(scene.boundsInWindow.left - paneBounds.left,
                scene.boundsInWindow.top - paneBounds.top)
              sourceShader.setLocalMatrix(matrix)
              paint.shader = sourceShader
            }
            drawIntoCanvas { canvas ->
              canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
            }
          }
        }
        // Film guarantees readable controls, even when sampling is intentionally unavailable.
        drawRoundRect(tint.copy(alpha = alpha * if (scene == null) 0.60f else 0.42f),
          cornerRadius = CornerRadius(radius))
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = glow * 0.18f + tuning.depth * 0.025f),
          Color.Transparent, Color.Black.copy(alpha = 0.04f + tuning.depth * 0.035f))), cornerRadius = CornerRadius(radius))
        if (drawRim) {
          drawRoundRect(rim, cornerRadius = CornerRadius(radius), style = Stroke(1.dp.toPx()))
          if (tuning.depth > 0f && size.minDimension > 4.dp.toPx()) {
            val inset = 1.5.dp.toPx()
            drawRoundRect(
              brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.035f * tuning.depth),
                Color.Transparent, Color.Black.copy(alpha = 0.045f * tuning.depth))),
              topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
              cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)), style = Stroke(1.dp.toPx()),
            )
          }
        }
      }
    }
}

// Original analytic edge lens. Only the background shader is distorted; control content is not.
private const val GLASS_OPTICS = """
uniform shader scene;
uniform float2 pane;
uniform float2 origin;
uniform float2 sceneOrigin;
uniform float2 sceneSize;
uniform float2 imageSize;
uniform float radius;
uniform float strength;
uniform float frost;
half4 sampleAt(float2 windowPosition) {
  float2 uv = clamp((windowPosition - sceneOrigin) / sceneSize, float2(0.0), float2(1.0));
  // Pixel centres and explicit bilinear filtering avoid a magnified nearest-neighbour grid.
  return scene.eval(clamp(uv * imageSize, float2(0.5), imageSize - float2(0.5)));
}
half4 main(float2 p) {
  float2 center = pane * 0.5;
  float2 delta = p - center;
  float2 core = max(center - radius, float2(0.0));
  float2 nearest = clamp(delta, -core, core);
  float2 radial = delta - nearest;
  float radialLength = length(radial);
  float distanceToEdge = radius - radialLength;
  float2 normal = radial / max(radialLength, 0.001);
  float edge = 1.0 - smoothstep(0.0, max(radius, 1.0), max(distanceToEdge, 0.0));
  float2 bent = origin + p - normal * strength * edge * edge;
  float2 blurX = float2(frost, 0.0);
  float2 blurY = float2(0.0, frost);
  half4 color = sampleAt(bent) * 0.40;
  color += (sampleAt(bent + blurX) + sampleAt(bent - blurX) +
            sampleAt(bent + blurY) + sampleAt(bent - blurY)) * 0.15;
  return half4(color.rgb, 1.0);
}
"""
