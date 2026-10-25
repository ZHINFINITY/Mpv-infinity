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
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Stable
private class BrowserScene(val layer: GraphicsLayer, val contentLens: Boolean = false) {
  var stripBounds by mutableStateOf(Rect.Zero)
  // Render-thread reentrancy guard, not snapshot state: drawing must not invalidate itself.
  var isRecording = false
  // A GraphicsLayer retains its last display list when the source is measured at
  // zero or leaves composition. Never let a lens consume that detached list.
  var hasValidDisplayList = false

  fun invalidate() {
    hasValidDisplayList = false
  }
  /** View of the window that records this scene; panes in other windows (dialogs, menus) differ. */
  var ownerView: android.view.View? = null
  /** Popup windows currently sampling this scene; recording runs only while > 0. */
  var popupConsumers by mutableStateOf(0)
  /** Observable once-per-session flag so a popup window redraws after the first recording. */
  var popupReady by mutableStateOf(false)
}

private data class BrowserGlassCapabilities(val capture: Boolean, val refraction: Boolean, val motion: Boolean)
private val LocalBrowserScene = compositionLocalOf<BrowserScene?> { null }
/**
 * Full route content (folders, thumbnails, text) for popup glass. Only panes drawn in a
 * separate window (dialogs, dropdown menus) may sample it: they are never part of the
 * recorded route tree, so there is no producer -> consumer cycle.
 */
private val LocalBrowserPopupScene = compositionLocalOf<BrowserScene?> { null }
private val LocalBrowserCapabilities = compositionLocalOf { BrowserGlassCapabilities(false, false, false) }

/** App-owned background producer, a sibling of all controls/routes. Its subtree
 * is isolated from the consumer scene. Never capture the route tree or player UI.
 */
@Composable
fun BrowserGlassSceneHost(
  enabled: Boolean,
  background: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val hostView = LocalView.current
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
      val candidate = PowerManager.OnThermalStatusChangedListener { thermal = it }
      try {
        // The default overload delivers on this Context's main executor. Thermal
        // monitoring is optional: a missing/restarting system service must never
        // prevent composing a browser or opening a popup.
        power.addThermalStatusListener(candidate)
        candidate
      } catch (_: RuntimeException) {
        thermal = PowerManager.THERMAL_STATUS_SEVERE
        null
      }
    } else null
    onDispose {
      context.contentResolver.unregisterContentObserver(observer)
      context.unregisterReceiver(receiver)
      if (Build.VERSION.SDK_INT >= 29 && listener != null) {
        try { power?.removeThermalStatusListener(listener) } catch (_: RuntimeException) {
          // The service may disappear between registration and disposal.
        }
      }
    }
  }
  val allowed = enabled && !lowRam && !powerSave && thermal < 3 &&
    GlassDeviceSupport.allowsGlass(context)
  GlassStartupWatch(active = allowed && LocalBrowserScene.current == null)
  val layer = rememberGraphicsLayer()
  // Nested hosts inherit the root display-list instead of shadowing it.
  val inheritedScene = LocalBrowserScene.current
  val ownsScene = inheritedScene == null
  val scene = inheritedScene ?: remember(layer) { BrowserScene(layer) }
  val amoledBackdrop = MaterialTheme.colorScheme.background == Color.Black
  val producerTop = if (amoledBackdrop) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
  val producerBottom = if (amoledBackdrop) Color.Transparent else MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f)
  val producerBrush = remember(producerTop, producerBottom) {
    Brush.verticalGradient(listOf(producerTop, producerBottom))
  }
  DisposableEffect(scene, allowed, ownsScene) {
    onDispose {
      if (ownsScene) scene.invalidate()
    }
  }
  CompositionLocalProvider(
    LocalBrowserScene provides scene,
    LocalBrowserCapabilities provides BrowserGlassCapabilities(allowed, allowed && motion && Build.VERSION.SDK_INT >= 33, motion && !lowRam && !powerSave && thermal < 3),
  ) {
    Box(Modifier.fillMaxSize()) {
      if (ownsScene && enabled) {
        // Only this sibling owns the backdrop. Its subtree has no access to the
        // consumer scene, so even a misplaced glass child cannot sample itself.
        CompositionLocalProvider(
          LocalBrowserScene provides null,
          LocalBrowserCapabilities provides BrowserGlassCapabilities(false, false, false),
        ) {
          Box(Modifier.matchParentSize().onGloballyPositioned { coordinates ->
            if (coordinates.isAttached) {
              val origin = coordinates.localToWindow(Offset.Zero) + windowScreenOffset(hostView)
              scene.stripBounds = Rect(origin.x, origin.y,
                origin.x + coordinates.size.width, origin.y + coordinates.size.height)
            } else scene.invalidate()
          }.drawWithContent {
            if (!allowed || !GlassScenePolicy.validCaptureSize(size.width, size.height)) {
              scene.invalidate()
              drawContent()
            } else {
              scene.invalidate()
              scene.isRecording = true
              try {
                scene.layer.record(size = IntSize(size.width.toInt(), size.height.toInt())) {
                  // This draws the isolated BACKGROUND sibling, never content().
                  this@drawWithContent.drawContent()
                }
                scene.hasValidDisplayList = true
              } finally {
                scene.isRecording = false
              }
              drawLayer(scene.layer)
            }
          }) {
            if (background != null) background()
            else Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background).background(producerBrush))
          }
        }
      }
      // A single call site in both modes: never dispose/recreate the browser tree
      // merely to change its material or enter a low-power fallback.
      content()
    }
  }
}

/**
 * Popup-only producer for routes outside [BrowserGlassRouteContentHost] (Settings and the other
 * pushed screens). Panes drawn in dialog/menu windows above such a route sample its live content,
 * frosted by the global Liquid Glass settings, exactly like the browser's Sort & View popup,
 * instead of the flat root background. Recording runs only while a popup consumer is attached.
 */
@Composable
fun BrowserGlassPopupSceneHost(
  enabled: Boolean,
  content: @Composable () -> Unit,
) {
  val hostView = LocalView.current
  val rootCapabilities = LocalBrowserCapabilities.current
  val popupLayer = rememberGraphicsLayer()
  val popupScene = remember(popupLayer) { BrowserScene(popupLayer, contentLens = true) }
  popupScene.ownerView = hostView
  val canCapture = enabled && rootCapabilities.capture
  DisposableEffect(popupScene) { onDispose { popupScene.invalidate() } }
  CompositionLocalProvider(LocalBrowserPopupScene provides popupScene) {
    Box(
      Modifier
        .fillMaxSize()
        .onGloballyPositioned { coordinates ->
          if (coordinates.isAttached && coordinates.size.width > 0 && coordinates.size.height > 0) {
            val origin = coordinates.localToWindow(Offset.Zero) + windowScreenOffset(hostView)
            popupScene.stripBounds = Rect(origin.x, origin.y,
              origin.x + coordinates.size.width, origin.y + coordinates.size.height)
          } else {
            popupScene.invalidate()
          }
        }
        .drawWithContent {
          // Read in draw scope: attaching the first popup re-runs this draw to start recording.
          val popupsOpen = popupScene.popupConsumers > 0
          if (canCapture && popupsOpen && !popupScene.isRecording &&
            GlassScenePolicy.validCaptureSize(size.width, size.height)
          ) {
            popupScene.isRecording = true
            try {
              popupLayer.record(size = IntSize(size.width.toInt(), size.height.toInt())) {
                this@drawWithContent.drawContent()
              }
              popupScene.hasValidDisplayList = true
              if (!popupScene.popupReady) popupScene.popupReady = true
            } finally {
              popupScene.isRecording = false
            }
          } else if (!popupsOpen || !canCapture) {
            popupScene.invalidate()
          }
          drawContent()
        },
    ) { content() }
  }
}

/**
 * Captures only the ordinary route display list for the floating navigation lens.
 * The route producer is a sibling of the navigation consumer: it never records the
 * consumer itself, and route glass continues to sample the independent root backdrop.
 * A detached or zero-sized layer is invalidated rather than reusing stale pixels.
 */
@Composable
fun BrowserGlassRouteContentHost(
  enabled: Boolean,
  routeContent: @Composable () -> Unit,
  navigationContent: @Composable BoxScope.() -> Unit,
) {
  val hostView = LocalView.current
  val inheritedRootScene = LocalBrowserScene.current
  val rootCapabilities = LocalBrowserCapabilities.current
  val navLayer = rememberGraphicsLayer()
  val navContentScene = remember(navLayer) { BrowserScene(navLayer, contentLens = true) }
  val popupLayer = rememberGraphicsLayer()
  val popupScene = remember(popupLayer) { BrowserScene(popupLayer, contentLens = true) }
  popupScene.ownerView = hostView
  val canCapture = enabled && rootCapabilities.capture
  DisposableEffect(navContentScene, canCapture) {
    onDispose {
      navContentScene.invalidate()
      popupScene.invalidate()
    }
  }
  Box(Modifier.fillMaxSize()) {
    // This producer owns route content only. It is intentionally not given
    // navContentScene, preventing a producer -> consumer -> producer cycle.
    CompositionLocalProvider(LocalBrowserScene provides inheritedRootScene, LocalBrowserPopupScene provides popupScene) {
      Box(
        Modifier
          .fillMaxSize()
          .onGloballyPositioned { coordinates ->
            if (coordinates.isAttached && coordinates.size.width > 0 && coordinates.size.height > 0) {
              val origin = coordinates.localToWindow(Offset.Zero) + windowScreenOffset(hostView)
              val stripHeight = coordinates.size.height.coerceAtMost(BrowserGlassStripHeight)
              val stripTop = coordinates.size.height - stripHeight
              navContentScene.stripBounds = Rect(origin.x, origin.y + stripTop,
                origin.x + coordinates.size.width, origin.y + coordinates.size.height)
              popupScene.stripBounds = Rect(origin.x, origin.y,
                origin.x + coordinates.size.width, origin.y + coordinates.size.height)
            } else {
              navContentScene.invalidate()
              popupScene.invalidate()
            }
          }
          .drawWithContent {
            // Read in draw scope: opening the first popup re-runs this draw to start recording.
            val popupsOpen = popupScene.popupConsumers > 0
            if (!canCapture || !GlassScenePolicy.validCaptureSize(size.width, size.height)) {
              navContentScene.invalidate()
              popupScene.invalidate()
              drawContent()
            } else {
              if (popupsOpen && !popupScene.isRecording) {
                popupScene.isRecording = true
                try {
                  popupLayer.record(size = IntSize(size.width.toInt(), size.height.toInt())) {
                    this@drawWithContent.drawContent()
                  }
                  popupScene.hasValidDisplayList = true
                  if (!popupScene.popupReady) popupScene.popupReady = true
                } finally {
                  popupScene.isRecording = false
                }
              } else if (!popupsOpen) popupScene.invalidate()
              navContentScene.isRecording = true
              try {
                // Match the reference pill's bottom-384-pixel source, but keep
                // its producer separate from every navigation glass consumer.
                val stripHeight = size.height.toInt().coerceAtMost(BrowserGlassStripHeight)
                val stripTop = size.height - stripHeight
                navLayer.record(size = IntSize(size.width.toInt(), stripHeight)) {
                  clipRect {
                    translate(top = -stripTop) { this@drawWithContent.drawContent() }
                  }
                }
                navContentScene.hasValidDisplayList = true
              } finally {
                navContentScene.isRecording = false
              }
              drawContent()
            }
          },
      ) { routeContent() }
    }
    // The consumer is outside the captured subtree and is the only owner of the
    // bounded route-content scene. It cannot capture or render itself.
    CompositionLocalProvider(
      LocalBrowserScene provides navContentScene,
      LocalBrowserPopupScene provides popupScene,
      LocalBrowserCapabilities provides rootCapabilities.copy(capture = canCapture, refraction = canCapture && rootCapabilities.refraction),
    ) { navigationContent() }
  }
}

/**
 * Compatibility marker. Source ownership lives in the independent host producer;
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
  materialRole: GlassSurfacePolicy.MaterialRole = GlassSurfacePolicy.MaterialRole.Clear,
  content: @Composable BoxScope.() -> Unit = {},
) {
  // Browser navigation intentionally keeps the original reference optics. Do not route
  // this surface through browserGlassPane: that path is the newer profile used by
  // settings/player/folder controls and has different depth/tint semantics.
  val scene = LocalBrowserScene.current
  val capabilities = LocalBrowserCapabilities.current
  val tuning = LocalGlassCustomization.current
  val hostView = LocalView.current
  val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
  val tint = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
  val film = GlassSurfacePolicy.opacity(opacity)
  val glow = GlassSurfacePolicy.highlight(highlight)
  var paneOrigin by remember { mutableStateOf(Offset.Zero) }
  val lens = rememberGraphicsLayer()
  Box(modifier
    .shadow(if (enabled) (if (selected) 7.dp else 12.dp) * tuning.depth else 0.dp, shape, clip = false)
    .onGloballyPositioned { if (it.isAttached) paneOrigin = it.localToWindow(Offset.Zero) + windowScreenOffset(hostView) }
    .clip(shape)
    .then(if (!enabled) Modifier.background(tint, shape) else Modifier)) {
    if (enabled) {
      Box(Modifier.matchParentSize().drawWithCache {
        val radius = cornerRadius.toPx().coerceAtMost(size.height / 2f).coerceAtMost(size.width / 2f)
        val refraction = capabilities.refraction && RuntimeShaderSupport.available()
        val effect = if (refraction) RuntimeShaderSupport.browserLensEffectOrNull(
          paneWidth = size.width,
          paneHeight = size.height,
          radius = radius,
          bend = (if (selected) 9f else 5f) * density * tuning.refraction,
          motion = deformation.coerceIn(0f, 1f),
          frostPx = if (tuning.frost > 0f) 2f * density * tuning.frost else 0f,
        ) else if (Build.VERSION.SDK_INT >= 31 && scene != null && tuning.frost > 0f) runCatching {
          RenderEffect.createBlurEffect(3f * density * tuning.frost, 3f * density * tuning.frost, Shader.TileMode.CLAMP).asComposeRenderEffect()
        }.getOrNull() else null
        lens.renderEffect = effect
        val edge = Brush.linearGradient(listOf(
          Color.White.copy(alpha = 0.20f + glow * 0.65f),
          Color.White.copy(alpha = 0.03f),
          Color.White.copy(alpha = 0.10f + glow * 0.35f),
        ))
        onDrawBehind {
          val bounds = scene?.stripBounds
          val hasScene = scene != null && bounds != null && bounds.width > 0f && bounds.height > 0f &&
            GlassScenePolicy.canDrawRecordedScene(scene.isRecording, scene.hasValidDisplayList)
          if (hasScene) {
            lens.record {
              clipRect { translate(left = bounds!!.left - paneOrigin.x, top = bounds.top - paneOrigin.y) { drawLayer(scene!!.layer) } }
            }
            drawLayer(lens)
          }
          drawRoundRect(tint.copy(alpha = if (hasScene) film * (if (selected) 0.24f else 0.20f) else 0.70f), cornerRadius = CornerRadius(radius))
          drawRoundRect(Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (selected) 0.16f + glow * 0.24f else 0.06f + glow * 0.10f),
            Color.Transparent,
            Color.Black.copy(alpha = if (selected) 0.10f else 0.08f),
          )), cornerRadius = CornerRadius(radius))
          drawRoundRect(edge, cornerRadius = CornerRadius(radius), style = Stroke(1.dp.toPx()))
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

/** The shared pane path used by folders, settings and Quick Play outside MainScreen. */
@Composable
internal fun hasBrowserGlassScene(): Boolean = LocalBrowserScene.current != null

/** Identity-only probe for runtime producer/consumer isolation; never exposes pixel data. */
@Composable
internal fun currentBrowserGlassSceneIdentity(): Any? = LocalBrowserScene.current

@Composable
internal fun Modifier.browserGlassPane(
  enabled: Boolean,
  opacity: Float,
  highlight: Float,
  cornerRadius: Dp,
  selected: Boolean = false,
  deformation: Float = 0f,
  drawRim: Boolean = true,
  materialRole: GlassSurfacePolicy.MaterialRole = GlassSurfacePolicy.MaterialRole.Clear,
  // Retained for source compatibility: every browser pane now uses the navigation
  // pill's reference material, so this flag no longer selects a different profile.
  @Suppress("UNUSED_PARAMETER") referenceOptics: Boolean = false,
): Modifier {
  val hostView = LocalView.current
  val routeScene = LocalBrowserScene.current
  val popupScene = LocalBrowserPopupScene.current
  // Popup panes (own window) sample the live route content, frosted and refracted by the
  // global settings, instead of the flat background; in-window panes never do (no cycle).
  val popupWindow = materialRole == GlassSurfacePolicy.MaterialRole.Regular &&
    GlassScenePolicy.samplesPopupContent(popupScene?.ownerView != null, popupScene?.ownerView === hostView)
  DisposableEffect(popupScene, popupWindow) {
    val target = popupScene
    if (popupWindow && target != null) target.popupConsumers += 1
    onDispose {
      if (popupWindow && target != null) {
        target.popupConsumers = (target.popupConsumers - 1).coerceAtLeast(0)
        if (target.popupConsumers == 0) target.popupReady = false
      }
    }
  }
  val scene = if (popupWindow) popupScene else routeScene
  val capabilities = LocalBrowserCapabilities.current
  val tuning = LocalGlassCustomization.current
  val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
  val regular = materialRole == GlassSurfacePolicy.MaterialRole.Regular
  // Edge-to-edge chrome (top bars, selection bar) has no floating outline: no rim,
  // shadow or body sheen, and it is tinted with the page background (true black on AMOLED).
  val edgeBar = GlassSurfacePolicy.isEdgeToEdgeChrome(cornerRadius.value)
  val tint = when {
    selected -> MaterialTheme.colorScheme.primaryContainer
    edgeBar -> MaterialTheme.colorScheme.background
    else -> MaterialTheme.colorScheme.surfaceContainerHigh
  }
  val themedTint = if (regular) GlassSurfacePolicy.regularTintColor(tint, MaterialTheme.colorScheme.onSurface) else tint
  val film = GlassSurfacePolicy.opacity(if (tuning.overrideMaterial) tuning.opacity else opacity)
  // Popups solve their film against the page behind them (a display list cannot be read back).
  val popupText = MaterialTheme.colorScheme.onSurface
  val popupUnderlay = GlassSurfacePolicy.browserUnderlayEstimate(MaterialTheme.colorScheme.background)
  val glow = GlassSurfacePolicy.highlight(if (tuning.overrideMaterial) tuning.highlight else highlight)
  // Text-bearing popups and menus: frost follows the global frost setting above a readable floor.
  val blurDp = if (regular) GlassSurfacePolicy.browserPopupFrostDp(tuning.frost)
    else GlassSurfacePolicy.navigationFrostDp(tuning.frost, refraction = capabilities.refraction)
  val floating = enabled && drawRim && !edgeBar
  var paneOrigin by remember { mutableStateOf(Offset.Zero) }
  // Runtime shader compilation is remembered, not repeated for every animated width/frame.
  // The navigation pill continues to use RuntimeShaderSupport.browserLensEffectOrNull(
  // unchanged; this pane uses a handle so deformation only updates a uniform.
  // Edge bars never bend: the lens edge term would draw a bright line along the bar's bottom.
  val lens = rememberGraphicsLayer()
  return this
    // Same elevation as the navigation pill (BrowserGlassSurface), scaled by the depth setting.
    .shadow(if (floating) (if (selected) 7.dp else 12.dp) * tuning.depth else 0.dp, shape, clip = false)
    .onGloballyPositioned { if (it.isAttached) paneOrigin = it.localToWindow(Offset.Zero) + windowScreenOffset(hostView) }
    .clip(shape)
    .then(if (!enabled) Modifier.background(tint, shape) else Modifier)
    .then(if (enabled) Modifier.drawWithCache {
        val radius = cornerRadius.toPx().coerceAtMost(size.height / 2f).coerceAtMost(size.width / 2f)
        val refraction = enabled && !edgeBar && capabilities.refraction && RuntimeShaderSupport.available()
        val lensHandle = if (refraction) RuntimeShaderSupport.browserLensHandleOrNull(
          paneWidth = size.width,
          paneHeight = size.height,
          radius = radius,
          bend = (if (selected) 9f else 5f) * density * tuning.refraction,
          frostPx = if (blurDp > 0f) blurDp * density else 0f,
        ) else null
        val effect = lensHandle?.effect ?: if (!refraction && Build.VERSION.SDK_INT >= 31 && scene != null && blurDp > 0f) runCatching {
          RenderEffect.createBlurEffect(blurDp * density, blurDp * density, Shader.TileMode.CLAMP).asComposeRenderEffect()
        }.getOrNull() else null
        lens.renderEffect = effect
        // Like the navigation pill, the refracted sample fully replaces what is behind the
        // pane, so content text under popups, menus and the selection pill never reads through.
        lens.alpha = 1f
        val edge = Brush.linearGradient(listOf(
          Color.White.copy(alpha = 0.20f + glow * 0.65f),
          Color.White.copy(alpha = 0.03f),
          Color.White.copy(alpha = 0.10f + glow * 0.35f),
        ))
        onDrawBehind {
          if (size.width < 1f || size.height < 1f) return@onDrawBehind
          lensHandle?.updateMotion(deformation.coerceIn(0f, 1f))
          // Observed state: the popup window redraws once the route window records its first frame.
          val popupSceneReady = !popupWindow || scene?.popupReady == true
          val bounds = scene?.stripBounds
          val hasScene = popupSceneReady && scene != null && bounds != null && bounds.width > 0f && bounds.height > 0f &&
            GlassScenePolicy.canDrawRecordedScene(scene.isRecording, scene.hasValidDisplayList)
          if (hasScene) {
            lens.record {
              clipRect {
                translate(left = bounds!!.left - paneOrigin.x, top = bounds.top - paneOrigin.y) { drawLayer(scene!!.layer) }
              }
            }
            drawLayer(lens)
          }
          val centerFilm = if (regular)
            GlassSurfacePolicy.regularAdaptiveFilmAlpha(film, popupUnderlay, themedTint, popupText, 0f, 0f)
          else film * (if (selected) 0.24f else 0.20f)
          // Without a scene the page behind is sharp, so keep a readable film (nav pill: 0.70).
          val fallbackFilm = when {
            regular -> GlassSurfacePolicy.RegularNoSceneFilmAlpha
            edgeBar -> GlassSurfacePolicy.EdgeChromeNoSceneFilmAlpha
            else -> GlassSurfacePolicy.NavigationNoSceneFilmAlpha
          }
          drawRoundRect(themedTint.copy(alpha = if (hasScene) centerFilm else fallbackFilm), cornerRadius = CornerRadius(radius))
          if (!edgeBar) {
            drawRoundRect(Brush.verticalGradient(listOf(
              Color.White.copy(alpha = if (selected) 0.16f + glow * 0.24f else 0.06f + glow * 0.10f),
              Color.Transparent,
              Color.Black.copy(alpha = if (selected) 0.10f else 0.08f),
            )), cornerRadius = CornerRadius(radius))
          }
          if (floating) drawRoundRect(edge, cornerRadius = CornerRadius(radius), style = Stroke(1.dp.toPx()))
          if (floating && selected && size.height > 4.dp.toPx() && size.width > 4.dp.toPx()) {
            drawRoundRect(Color.Black.copy(alpha = 0.10f), topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
              size = androidx.compose.ui.geometry.Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
              cornerRadius = CornerRadius((radius - 1.dp.toPx()).coerceAtLeast(0f)), style = Stroke(1.dp.toPx()))
          }
        }
      } else Modifier)
}

private fun windowScreenOffset(view: android.view.View): Offset {
  val window = IntArray(2)
  val screen = IntArray(2)
  view.getLocationInWindow(window)
  view.getLocationOnScreen(screen)
  return Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
}

private const val BrowserGlassStripHeight = 384

internal const val BROWSER_LENS = """
uniform shader scene;
uniform float2 pane;
uniform float radius;
uniform float bend;
uniform float motion;
half4 main(float2 p) {
  float2 c = p - pane * 0.5;
  float2 q = abs(c) - (pane * 0.5 - radius);
  float distance = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
  float edge = clamp(1.0 + distance / max(radius, 1.0), 0.0, 1.0);
  float2 normal = normalize(c / max(pane, float2(1.0)) + float2(0.0001));
  float2 bent = p - normal * bend * edge * edge * (1.0 + motion * 0.25);
  half4 color = scene.eval(bent);
  color.r = scene.eval(bent + normal * edge * 0.45).r;
  color.b = scene.eval(bent - normal * edge * 0.45).b;
  return color;
}
"""

private const val BROWSER_LATEST_LENS = """
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
