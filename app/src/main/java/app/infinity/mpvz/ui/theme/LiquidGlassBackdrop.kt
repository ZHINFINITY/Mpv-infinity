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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Immutable snapshot: window coordinates prevent every control showing the entire frame. */
data class LiquidGlassFrame(val image: ImageBitmap, val boundsInWindow: Rect)

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
  val allowed = GlassSamplingPolicy.canSample(enabled, visible, audioOnly, hdr, lowRam, thermal)
  val frame = remember { mutableStateOf<LiquidGlassFrame?>(null) }
  val ready by rememberUpdatedState(isReady)
  val playing by rememberUpdatedState(isPlaying)
  LaunchedEffect(surfaceView, allowed, generation, lifecycleOwner) {
    frame.value = null
    val source = surfaceView ?: return@LaunchedEffect
    if (!allowed) return@LaunchedEffect
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      var failures = 0
      try {
        while (currentCoroutineContext().isActive) {
          if (!ready() || !source.isAttachedToWindow || !source.holder.surface.isValid) {
            frame.value = null
            delay(GlassSamplingPolicy.RetryIntervalMs)
            continue
          }
          val dimensions = GlassSamplingPolicy.sampleSize(source.width, source.height)
          if (dimensions == null) {
            delay(GlassSamplingPolicy.RetryIntervalMs)
            continue
          }
          val bitmap = copyGlassFrame(source, dimensions.first, dimensions.second)
          if (bitmap == null) {
            frame.value = null // never display a stale frame after a lost/secure surface
            failures++
            if (failures >= GlassSamplingPolicy.FailureLimit) break
          } else {
            failures = 0
            val location = IntArray(2)
            source.getLocationOnScreen(location)
            frame.value = LiquidGlassFrame(
              bitmap.asImageBitmap(),
              Rect(location[0].toFloat(), location[1].toFloat(),
                (location[0] + source.width).toFloat(), (location[1] + source.height).toFloat()),
            )
            // Published immutable bitmaps are owned by Compose/GC; never recycle a displayed image.
          }
          delay(GlassSamplingPolicy.interval(playing(), failures))
        }
      } finally {
        frame.value = null
      }
    }
  }
  CompositionLocalProvider(LocalLiquidGlassBackdrop provides frame.takeIf { allowed }, content = content)
}

private suspend fun copyGlassFrame(source: SurfaceView, width: Int, height: Int): Bitmap? =
  suspendCancellableCoroutine { continuation ->
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
      PixelCopy.request(source, bitmap, { result ->
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

/**
 * Background-only optics keep text/icons sharp. API 33+: sampled-scene refraction and 5-tap
 * frost using an original rounded-pane lens. Older Android: filtered scene plus readable film.
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
  val tint = MaterialTheme.colorScheme.surfaceContainerHigh
  val alpha = GlassSurfacePolicy.opacity(opacity)
  val hostView = LocalView.current
  val glow = GlassSurfacePolicy.highlight(highlight)
  var paneBounds by remember { mutableStateOf(Rect.Zero) }
  val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
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
    .clip(shape)
    .drawWithCache {
      val radius = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
      val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
      val shader = if (Build.VERSION.SDK_INT >= 33) runCatching { RuntimeShader(GLASS_OPTICS) }.getOrNull() else null
      val matrix = Matrix()
      var lastImage: ImageBitmap? = null
      var imageShader: BitmapShader? = null
      val rim = Brush.linearGradient(listOf(
        Color.White.copy(alpha = 0.24f + glow),
        Color.White.copy(alpha = 0.04f),
        Color.White.copy(alpha = 0.12f + glow * 0.35f),
      ))
      onDrawBehind {
        val scene = backdrop?.value
        if (scene != null && scene.boundsInWindow.width > 0f && scene.boundsInWindow.height > 0f) {
          if (scene.image !== lastImage) {
            lastImage = scene.image
            imageShader = BitmapShader(scene.image.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
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
              shader.setFloatUniform("strength", 9.dp.toPx())
              shader.setFloatUniform("frost", 10.dp.toPx())
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
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = glow * 0.5f),
          Color.Transparent, Color.Black.copy(alpha = 0.08f))), cornerRadius = CornerRadius(radius))
        if (drawRim) {
          drawRoundRect(rim, cornerRadius = CornerRadius(radius), style = Stroke(1.dp.toPx()))
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
  return scene.eval(uv * (imageSize - 1.0));
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
