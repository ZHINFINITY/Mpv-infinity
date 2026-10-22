/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms.
 * Independently implemented browser optics. No third-party glass source or dependency.
 */
package app.infinity.mpvz.ui.theme

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Stable
private class BrowserScene(val layer: GraphicsLayer) {
  var stripBounds by mutableStateOf(Rect.Zero)
  // Render-thread reentrancy guard, not snapshot state: drawing must not invalidate itself.
  var isRecording = false
  // A GraphicsLayer retains its last display list when the source is measured at
  // zero or leaves composition. Never let a lens consume that detached list.
  var hasValidDisplayList = false

  fun invalidate() {
    hasValidDisplayList = false
  }
}

private data class BrowserGlassCapabilities(val capture: Boolean, val refraction: Boolean, val motion: Boolean)
private val LocalBrowserScene = compositionLocalOf<BrowserScene?> { null }
private val LocalBrowserCapabilities = compositionLocalOf { BrowserGlassCapabilities(false, false, false) }

/** One independent ambient producer. Its display list contains paint commands only,
 * never composable descendants or glass consumers. No UI capture or CPU readback.
 */
@Composable
fun BrowserGlassSceneHost(enabled: Boolean, content: @Composable () -> Unit) {
  val context = LocalContext.current
  val power = remember(context) { context.getSystemService(PowerManager::class.java) }
  val lowRam = remember(context) { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }
  var powerSave by remember { mutableStateOf(power?.isPowerSaveMode == true) }
  var thermal by remember { mutableStateOf(if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus ?: 0 else 0) }
  var motion by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
  DisposableEffect(context, power) {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) { motion = ValueAnimator.areAnimatorsEnabled() }
    }
    context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) { powerSave = power?.isPowerSaveMode == true }
    }
    androidx.core.content.ContextCompat.registerReceiver(context, receiver,
      IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    val listener = if (Build.VERSION.SDK_INT >= 29 && power != null) {
      PowerManager.OnThermalStatusChangedListener { thermal = it }.also {
        power.addThermalStatusListener(context.mainExecutor, it)
      }
    } else null
    onDispose {
      context.contentResolver.unregisterContentObserver(observer)
      context.unregisterReceiver(receiver)
      if (Build.VERSION.SDK_INT >= 29 && listener != null) power?.removeThermalStatusListener(listener)
    }
  }
  val allowed = enabled && !lowRam && !powerSave && thermal < 3
  val layer = rememberGraphicsLayer()
  // Nested hosts inherit the root display-list instead of shadowing it.
  val inheritedScene = LocalBrowserScene.current
  val ownsScene = inheritedScene == null
  val scene = inheritedScene ?: remember(layer) { BrowserScene(layer) }
  val producerTop = MaterialTheme.colorScheme.primaryContainer
  val producerBottom = MaterialTheme.colorScheme.secondaryContainer
  val producerBrush = remember(producerTop, producerBottom) {
    Brush.verticalGradient(listOf(producerTop, producerBottom))
  }
  DisposableEffect(scene, allowed, ownsScene) {
    onDispose {
      if (ownsScene) scene.invalidate()
    }
  }
  CompositionLocalProvider(
    LocalBrowserScene provides scene.takeIf { allowed },
    LocalBrowserCapabilities provides BrowserGlassCapabilities(allowed, allowed && motion && Build.VERSION.SDK_INT >= 33, motion && !lowRam && !powerSave && thermal < 3),
  ) {
    Box(Modifier.fillMaxSize()) {
      if (ownsScene && allowed) {
        // This sibling has no content lambda. A retained glass RenderNode cannot
        // enter its source even if a consumer re-records after this draw pass.
        Box(Modifier.matchParentSize().onGloballyPositioned { coordinates ->
          if (coordinates.isAttached) {
            val origin = coordinates.localToWindow(Offset.Zero)
            scene.stripBounds = Rect(origin.x, origin.y,
              origin.x + coordinates.size.width, origin.y + coordinates.size.height)
          } else scene.invalidate()
        }.drawWithCache {
          var recorded = false
          onDrawBehind {
            if (!GlassScenePolicy.validCaptureSize(size.width, size.height)) {
              scene.invalidate()
            } else if (!recorded || !scene.hasValidDisplayList) {
              scene.invalidate()
              scene.isRecording = true
              try {
                scene.layer.record(size = IntSize(size.width.toInt(), size.height.toInt())) {
                  // DrawScope only: no drawContent(), drawLayer() or UI tree.
                  drawRect(producerBrush)
                }
                scene.hasValidDisplayList = true
                recorded = true
              } finally {
                scene.isRecording = false
              }
            }
          }
        })
      }
      // A single call site in both modes: never dispose/recreate the browser tree
      // merely to change its material or enter a low-power fallback.
      content()
    }
  }
}

/** Compatibility marker. Source ownership lives in the independent host producer;
 * recording this route's content would reintroduce a source -> consumer -> source cycle.
 */
@Composable
fun Modifier.browserGlassSceneSource(): Modifier = this

@Composable
fun rememberBrowserGlassMotionAllowed(): Boolean = LocalBrowserCapabilities.current.motion &&
  !LocalMotionPolicy.current.reduceMotion && LocalGlassCustomization.current.motion > 0f

/** Optics affect only the background node; labels and touch targets remain untouched. */
@Composable
fun BrowserGlassSurface(
  modifier: Modifier = Modifier,
  enabled: Boolean,
  opacity: Float,
  highlight: Float,
  cornerRadius: Dp = 28.dp,
  selected: Boolean = false,
  deformation: Float = 0f,
  content: @Composable BoxScope.() -> Unit = {},
) {
  val scene = LocalBrowserScene.current
  val capabilities = LocalBrowserCapabilities.current
  val tuning = LocalGlassCustomization.current
  val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
  val tint = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
  val film = GlassSurfacePolicy.opacity(opacity)
  val glow = GlassSurfacePolicy.highlight(highlight)
  var paneOrigin by remember { mutableStateOf(Offset.Zero) }
  // Runtime shader compilation is remembered, not repeated for every animated width/frame.
  val shader = remember(enabled, capabilities.refraction) {
    if (enabled && capabilities.refraction && Build.VERSION.SDK_INT >= 33)
      runCatching { RuntimeShader(BROWSER_LENS) }.getOrNull() else null
  }
  val lens = rememberGraphicsLayer()
  Box(modifier
    .shadow(if (enabled) (if (selected) 7.dp else 12.dp) * tuning.depth else 0.dp, shape, clip = false)
    .onGloballyPositioned { if (it.isAttached) paneOrigin = it.localToWindow(Offset.Zero) }
    .clip(shape)
    .then(if (!enabled) Modifier.background(tint, shape) else Modifier)) {
    if (enabled) {
      Box(Modifier.matchParentSize().drawWithCache {
        val radius = cornerRadius.toPx().coerceAtMost(size.height / 2f).coerceAtMost(size.width / 2f)
        val refraction = capabilities.refraction && shader != null && Build.VERSION.SDK_INT >= 33
        val effect = if (refraction) runCatching {
          shader!!.setFloatUniform("pane", size.width, size.height)
          shader.setFloatUniform("radius", radius)
          shader.setFloatUniform("bend", (if (selected) 9f else 5f) * density * tuning.refraction)
          shader.setFloatUniform("motion", deformation.coerceIn(0f, 1f))
          shader.setFloatUniform("thickness", tuning.depth)
          shader.setFloatUniform("highlight", glow)
          val lensEffect = RenderEffect.createRuntimeShaderEffect(shader, "scene")
          (if (tuning.frost > 0f) RenderEffect.createChainEffect(lensEffect,
            RenderEffect.createBlurEffect(2f * density * tuning.frost, 2f * density * tuning.frost, Shader.TileMode.CLAMP))
          else lensEffect).asComposeRenderEffect()
        }.getOrNull() else if (Build.VERSION.SDK_INT >= 31 && scene != null && tuning.frost > 0f) runCatching {
          RenderEffect.createBlurEffect(3f * density * tuning.frost, 3f * density * tuning.frost, Shader.TileMode.CLAMP).asComposeRenderEffect()
        }.getOrNull() else null
        lens.renderEffect = effect
        val edge = Brush.linearGradient(listOf(
          Color.White.copy(alpha = 0.20f + glow * 0.65f),
          Color.White.copy(alpha = 0.03f),
          Color.White.copy(alpha = 0.10f + glow * 0.35f),
        ))
        onDrawBehind {
          if (size.width < 1f || size.height < 1f) return@onDrawBehind
          val bounds = scene?.stripBounds
          val hasScene = scene != null && bounds != null && bounds.width > 0f && bounds.height > 0f &&
            GlassScenePolicy.canDrawRecordedScene(scene.isRecording, scene.hasValidDisplayList)
          if (hasScene) {
            lens.record {
              clipRect {
                translate(left = bounds!!.left - paneOrigin.x, top = bounds.top - paneOrigin.y) { drawLayer(scene!!.layer) }
              }
            }
            drawLayer(lens)
          }
          drawRoundRect(tint.copy(alpha = if (hasScene) film * (if (selected) 0.24f else 0.20f) else 0.70f), cornerRadius = CornerRadius(radius))
          drawRoundRect(Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (selected) 0.16f + glow * 0.24f else 0.06f + glow * 0.10f),
            Color.Transparent,
            Color.Black.copy(alpha = if (selected) 0.10f else 0.08f),
          )), cornerRadius = CornerRadius(radius))
          drawRoundRect(edge, cornerRadius = CornerRadius(radius), style = Stroke((1f + 0.5f * tuning.depth).dp.toPx()))
          if (selected && size.height > 4.dp.toPx() && size.width > 4.dp.toPx()) {
            drawRoundRect(Color.Black.copy(alpha = 0.10f), topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
              size = androidx.compose.ui.geometry.Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
              cornerRadius = CornerRadius((radius - 1.dp.toPx()).coerceAtLeast(0f)), style = Stroke(1.dp.toPx()))
          }
        }
      })
    }
    content()
  }
}

private const val BROWSER_LENS = """
uniform shader scene;
uniform float2 pane;
uniform float radius;
uniform float bend;
uniform float motion;
uniform float thickness;
uniform float highlight;
half4 main(float2 p) {
  float2 c = p - pane * 0.5;
  float2 q = abs(c) - (pane * 0.5 - radius);
  float distance = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
  float edge = clamp(1.0 + distance / max(radius, 1.0), 0.0, 1.0);
  // Analytic rounded-rectangle edge normal: flat sides stay flat; only corners curve.
  float2 outer = max(q, 0.0);
  float2 axis = q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
  float2 normal = (length(outer) > 0.0001 ? outer / max(length(outer), 0.0001) : axis) * sign(c);
  float2 bent = p - normal * bend * edge * edge * (1.0 + motion * 0.25);
  half4 color = scene.eval(bent);
  color.r = scene.eval(bent + normal * edge * 0.45).r;
  color.b = scene.eval(bent - normal * edge * 0.45).b;
  float light = dot(normal, float2(-0.7071068, -0.7071068));
  float bevel = edge * edge * clamp(thickness, 0.0, 2.0);
  float specular = pow(max(light, 0.0), 10.0) * bevel * clamp(highlight, 0.0, 1.0) * 0.24;
  float shade = max(-light, 0.0) * bevel * 0.07;
  color.rgb = clamp(color.rgb + half3(specular - shade) * color.a, half3(0.0), half3(color.a));
  return color;
}
"""
