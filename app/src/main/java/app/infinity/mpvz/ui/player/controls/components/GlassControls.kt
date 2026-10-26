/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls.components

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.theme.lens.LocalLensGlassEnabled
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import app.infinity.mpvz.ui.theme.lens.lensControlThumb
import app.infinity.mpvz.ui.theme.lens.videoLensChip
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.theme.GlassDeviceSupport
import app.infinity.mpvz.ui.theme.GlassWaterRippleShader
import app.infinity.mpvz.ui.theme.LocalGlassCustomization
import app.infinity.mpvz.ui.theme.SwitchContrastPolicy
import app.infinity.mpvz.ui.theme.glassRimLight
import app.infinity.mpvz.ui.theme.liquidGlassPane
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

// Build 1910: retain a named inset token for shared settings/control sizing contracts.
private val horizontalInset = 16.dp

val LocalPlayerGlassSurfaceOwner = staticCompositionLocalOf { false }

/** Pure wave geometry used by the glass touch feedback and JVM contracts. */
object GlassWaterRipplePolicy {
  const val DurationMs = 560

  /** Ring radius as a fraction of the element's larger side: starts small, ends past the edge. */
  fun radiusFraction(progress: Float): Float = .10f + progress.coerceIn(0f, 1f) * 1.15f

  /** Quadratic decay: strong at the touch, settled to zero when the wave finishes. */
  fun amplitude(progress: Float): Float {
    val remaining = 1f - progress.coerceIn(0f, 1f)
    return remaining * remaining
  }

  /** The ring widens a little as it travels, like a real water wave. */
  fun ringWidthPx(maxDimensionPx: Float, progress: Float): Float =
    (maxDimensionPx * (.10f + .08f * progress.coerceIn(0f, 1f))).coerceAtLeast(6f)

  /** Peak displacement in px: follows the global refraction tuning, capped for small chips. */
  fun displacementPx(densityPx: Float, refraction: Float, amplitude: Float): Float =
    5f * densityPx * refraction.coerceIn(0f, 2f) * amplitude.coerceIn(0f, 1f)

  /** Crest highlight: follows the global rim/highlight tuning, subtler on true-black AMOLED. */
  fun crestLight(rimStrength: Float, amplitude: Float, amoled: Boolean): Float =
    (if (amoled) .10f else .18f) * rimStrength.coerceIn(0f, 2f) * amplitude.coerceIn(0f, 1f)

  fun useRefraction(glassEnabled: Boolean, deviceAllowsGlass: Boolean, sdkInt: Int): Boolean =
    glassEnabled && deviceAllowsGlass && sdkInt >= 33

  /** Only an active wave installs a RenderEffect; idle or finished buttons hold none. */
  fun isActive(progress: Float): Boolean = progress < 1f
}

/** Pure slider geometry used by rendering and unit tests. */
object GlassSliderPolicy {
  fun stretch(pressed: Boolean, reducedMotion: Boolean = false): Float =
    if (pressed && !reducedMotion) 1.2f else 1f
  fun squash(pressed: Boolean, reducedMotion: Boolean = false): Float =
    if (pressed && !reducedMotion) 0.92f else 1f
  fun tickPositions(steps: Int): List<Float> =
    if (steps <= 0) emptyList() else (0..steps + 1).map { it.toFloat() / (steps + 1) }
  fun hapticBucket(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Int {
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val buckets = if (steps > 0) steps + 1 else 20
    return (fraction * buckets).toInt().coerceIn(0, buckets)
  }
}

/** Shared control geometry: modestly larger than Material's original glass treatment. */
object GlassControlGeometryPolicy {
  const val switchThumbWidthDp = 34f
  const val switchThumbHeightDp = 25f
  const val switchTrackHeightDp = 34f
  const val sliderThumbWidthDp = 30f
  const val sliderThumbHeightDp = 24f
  const val sliderTrackHeightDp = 6f
  const val oldSliderTrackHeightDp = 5f

  /** Material places a custom switch thumb's centre 16dp from either end of its 52dp track. */
  const val materialSwitchThumbCentreInsetDp = 16f
  /** Clear glass visible between the capsule thumb and the end of its groove. */
  const val switchThumbEndGapDp = 2f

  /**
   * How far the drawn switch groove extends past Material's 52dp track on each side so the
   * capsule thumb sits fully inside it, with [switchThumbEndGapDp] to spare, when on and off.
   */
  fun switchTrackOverhangDp(
    thumbWidthDp: Float = switchThumbWidthDp,
    centreInsetDp: Float = materialSwitchThumbCentreInsetDp,
    endGapDp: Float = switchThumbEndGapDp,
  ): Float = (thumbWidthDp / 2f - centreInsetDp + endGapDp).coerceAtLeast(0f)
  const val oldSwitchThumbWidthDp = 30f
  const val oldSwitchThumbHeightDp = 22f
  const val oldSliderThumbWidthDp = 28f
  const val oldSliderThumbHeightDp = 22f

  fun isSlightlyLarger(value: Float, oldValue: Float): Boolean =
    value > oldValue && value <= oldValue * 1.16f
}

/**
 * Shared press feedback. Values are consumed in graphicsLayer/draw lambdas only.
 *
 * [glass] is the caller's Liquid Glass state: with glass on (and a supported device on
 * API 33+) the press also sends a refractive water wave across the element; otherwise a
 * soft, shape-clipped fade is drawn instead.
 */
@Composable
fun Modifier.glassPressFeedback(
  interactionSource: MutableInteractionSource,
  enabled: Boolean = true,
  reducedMotion: Boolean = false,
  glass: Boolean = false,
): Modifier {
  var pressed by remember { mutableStateOf(false) }
  LaunchedEffect(interactionSource, enabled) {
    interactionSource.interactions.collect { interaction: Interaction ->
      when (interaction) {
        is PressInteraction.Press -> pressed = enabled
        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
      }
    }
  }
  val scale by animateFloatAsState(
    if (pressed && !reducedMotion) 0.96f else 1f,
    spring(stiffness = Spring.StiffnessMediumLow), label = "glassPress",
  )
  return graphicsLayer { scaleX = scale; scaleY = scale }
    .glassWaterRipple(interactionSource, enabled, glass = glass)
}

/**
 * Touch-origin water wave. Callers place their shape clip before this modifier, so both the
 * refraction and the fade stay inside the button's own shape.
 *
 * Cost model: one Animatable per element, read only inside graphicsLayer/draw lambdas (no
 * recomposition per frame), a RenderEffect only while the wave is running, and no loops
 * or polling when idle. A new press restarts the wave from the new touch point.
 */
@Composable
fun Modifier.glassWaterRipple(
  interactionSource: MutableInteractionSource,
  enabled: Boolean = true,
  intensity: Float = 1f,
  glass: Boolean = false,
): Modifier {
  val context = LocalContext.current
  val tuning = LocalGlassCustomization.current
  val amoled = MaterialTheme.colorScheme.surface == Color.Black
  val refraction = remember(glass, context) {
    GlassWaterRipplePolicy.useRefraction(
      glassEnabled = glass,
      deviceAllowsGlass = glass && GlassDeviceSupport.allowsGlass(context),
      sdkInt = Build.VERSION.SDK_INT,
    )
  }
  val progress = remember { Animatable(1f) }
  var origin by remember { mutableStateOf(Offset.Unspecified) }
  LaunchedEffect(interactionSource, enabled) {
    interactionSource.interactions.collect { interaction ->
      if (interaction is PressInteraction.Press && enabled) {
        origin = interaction.pressPosition
        // launch so the collector keeps receiving presses; Animatable cancels the old wave.
        launch {
          progress.snapTo(0f)
          progress.animateTo(1f, tween(GlassWaterRipplePolicy.DurationMs, easing = LinearOutSlowInEasing))
        }
      }
    }
  }
  val refractive =
    if (refraction && Build.VERSION.SDK_INT >= 33) {
      graphicsLayer {
        val p = progress.value
        val o = origin
        renderEffect =
          if (GlassWaterRipplePolicy.isActive(p) && o != Offset.Unspecified) {
            val amplitude = GlassWaterRipplePolicy.amplitude(p) * intensity
            GlassWaterRippleEffects.create(
              width = size.width,
              height = size.height,
              origin = o,
              radiusPx = size.maxDimension * GlassWaterRipplePolicy.radiusFraction(p),
              ringWidthPx = GlassWaterRipplePolicy.ringWidthPx(size.maxDimension, p),
              displacementPx = GlassWaterRipplePolicy.displacementPx(density, tuning.refraction, amplitude),
              light = GlassWaterRipplePolicy.crestLight(tuning.rimStrength, amplitude, amoled),
            )
          } else {
            null
          }
      }
    } else {
      // Keep the caller's chain (tags, clip, glass surface, size); never restart from Modifier.
      this
    }
  return refractive.drawWithCache {
    onDrawWithContent {
      drawContent()
      val p = progress.value
      val o = origin
      // The fade is the whole effect on fallback devices, and a faint companion glow when the
      // shader is unavailable at runtime (e.g. a driver failure after start-up).
      // Idle draws never touch the shader; it is only looked up while a wave is running.
      if (GlassWaterRipplePolicy.isActive(p) && o != Offset.Unspecified &&
        !(refraction && GlassWaterRippleEffects.available())
      ) {
        val radius = size.maxDimension * GlassWaterRipplePolicy.radiusFraction(p)
        drawCircle(
          brush = Brush.radialGradient(
            listOf(
              Color.White.copy(alpha = GlassWaterRipplePolicy.amplitude(p) * (if (amoled) .12f else .20f) * intensity),
              Color.Transparent,
            ),
            center = o,
            radius = radius,
          ),
          radius = radius,
          center = o,
        )
      }
    }
  }
}

/** Keeps every reference to the API 33 shader class behind an SDK check. */
private object GlassWaterRippleEffects {
  fun available(): Boolean = Build.VERSION.SDK_INT >= 33 && GlassWaterRippleShader.available

  fun create(
    width: Float,
    height: Float,
    origin: Offset,
    radiusPx: Float,
    ringWidthPx: Float,
    displacementPx: Float,
    light: Float,
  ): androidx.compose.ui.graphics.RenderEffect? =
    if (Build.VERSION.SDK_INT >= 33) {
      GlassWaterRippleShader.effect(width, height, origin.x, origin.y, radiusPx, ringWidthPx, displacementPx, light)
    } else {
      null
    }
}

@Composable
fun GlassSwitch(
  checked: Boolean,
  onCheckedChange: ((Boolean) -> Unit)?,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  glassEnabled: Boolean = true,
  colors: SwitchColors = SwitchDefaults.colors(),
  thumbContent: (@Composable () -> Unit)? = null,
) {
  val appearance = koinInject<AppearancePreferences>()
  val preferenceEnabled by appearance.liquidGlassSurfaces.collectAsState()
  val opacity by appearance.glassSurfaceOpacity.collectAsState()
  val highlight by appearance.glassSurfaceHighlight.collectAsState()
  val glass = preferenceEnabled && glassEnabled
  val scheme = MaterialTheme.colorScheme
  // Lens thumb: needs the shader engine (API 33+, supported device, glass on). The track is
  // recorded by a SIBLING box under the Switch, so the thumb never samples its own layer.
  val lensEnabled = glass && LocalLensGlassEnabled.current
  val contrast = SwitchContrastPolicy.forSurfaceLuminance(scheme.surface.luminance())
  val tuned = SwitchDefaults.colors(
    checkedTrackColor = contrast.checkedTrack, checkedThumbColor = contrast.checkedThumb,
    uncheckedTrackColor = contrast.uncheckedTrack, uncheckedThumbColor = contrast.uncheckedThumb,
    checkedBorderColor = contrast.checkedBorder, uncheckedBorderColor = contrast.uncheckedBorder,
    disabledCheckedTrackColor = contrast.checkedTrack.copy(alpha = .45f),
    disabledCheckedThumbColor = contrast.checkedThumb.copy(alpha = .70f),
    disabledUncheckedTrackColor = contrast.uncheckedTrack.copy(alpha = .45f),
    disabledUncheckedThumbColor = contrast.uncheckedThumb.copy(alpha = .52f),
  )
  val transparent = SwitchDefaults.colors(
    checkedThumbColor = Color.Transparent, uncheckedThumbColor = Color.Transparent,
    disabledCheckedThumbColor = Color.Transparent, disabledUncheckedThumbColor = Color.Transparent,
    checkedTrackColor = Color.Transparent, uncheckedTrackColor = Color.Transparent,
    disabledCheckedTrackColor = Color.Transparent, disabledUncheckedTrackColor = Color.Transparent,
    checkedBorderColor = Color.Transparent, uncheckedBorderColor = Color.Transparent,
  )
  val switchInteraction = remember { MutableInteractionSource() }
  val switchPressed by switchInteraction.collectIsPressedAsState()
  val switchDragged by switchInteraction.collectIsDraggedAsState()
  val lensProgress by animateFloatAsState(
    if (lensEnabled && (switchPressed || switchDragged)) 1f else 0f,
    spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow), label = "switchLens",
  )
  val trackBackdrop = rememberLayerBackdrop()
  val trackOptics = Modifier.glassTrackOptics(opacity, highlight, 16.dp, height = GlassControlGeometryPolicy.switchTrackHeightDp.dp,
    checked = checked, overhang = GlassControlGeometryPolicy.switchTrackOverhangDp().dp)
  val glassThumb: @Composable () -> Unit = {
    val thumbShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val base = Modifier.size(width = GlassControlGeometryPolicy.switchThumbWidthDp.dp, height = GlassControlGeometryPolicy.switchThumbHeightDp.dp)
    Box(
      if (lensEnabled) base
        .lensControlThumb(trackBackdrop, thumbShape, if (checked) contrast.checkedThumb else contrast.uncheckedThumb) { lensProgress }
        .glassRimLight(thumbShape, ambient = contrast.checkedTrack, highContrast = true)
      else base
        .glassRimLight(thumbShape, ambient = contrast.checkedTrack, highContrast = true)
        .glassCapsuleBody(contrast.checkedThumb, enabled, checked, base = contrast.uncheckedThumb),
      contentAlignment = Alignment.Center,
    ) { thumbContent?.invoke() }
  }
  if (lensEnabled) {
    Box(modifier, contentAlignment = Alignment.Center) {
      Box(Modifier.matchParentSize().layerBackdrop(trackBackdrop).then(trackOptics))
      Switch(
        checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        colors = transparent, thumbContent = glassThumb, interactionSource = switchInteraction,
      )
    }
  } else {
    Switch(
      checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
      colors = if (glass) transparent else tuned,
      thumbContent = if (!glass) thumbContent else glassThumb,
      interactionSource = switchInteraction,
      // Material keeps its minimum interaction box while the custom capsule is drawn inside it.
      modifier = modifier.then(if (glass) trackOptics else Modifier),
    )
  }
}

@Composable
fun GlassSlider(
  value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
  enabled: Boolean = true, glassEnabled: Boolean = true,
  onValueChangeFinished: (() -> Unit)? = null,
  valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0,
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  colors: SliderColors = SliderDefaults.colors(),
) {
  val appearance = koinInject<AppearancePreferences>()
  val preferenceEnabled by appearance.liquidGlassSurfaces.collectAsState()
  val opacity by appearance.glassSurfaceOpacity.collectAsState()
  val highlight by appearance.glassSurfaceHighlight.collectAsState()
  val glass = preferenceEnabled && glassEnabled
  val accent = MaterialTheme.colorScheme.primary
  val lensEnabled = glass && LocalLensGlassEnabled.current
  val haptics = LocalHapticFeedback.current
  var lastBucket by remember { mutableStateOf<Int?>(null) }
  val report: (Float) -> Unit = { next ->
    val bucket = GlassSliderPolicy.hapticBucket(next, valueRange, steps)
    if (bucket != lastBucket) {
      if (lastBucket != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
      lastBucket = bucket
    }
    onValueChange(next)
  }
  if (!glass) {
    Slider(value = value, onValueChange = report, modifier = modifier, enabled = enabled,
      onValueChangeFinished = onValueChangeFinished, valueRange = valueRange, steps = steps,
      interactionSource = interactionSource, colors = colors)
  } else {
    val glassColors = SliderDefaults.colors(
      thumbColor = Color.Transparent, activeTrackColor = Color.Transparent,
      inactiveTrackColor = Color.Transparent,
      disabledActiveTrackColor = accent.copy(alpha = .18f), disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f),
    )
    val pressed by interactionSource.collectIsDraggedAsState()
    val touched by interactionSource.collectIsPressedAsState()
    val lensProgress by animateFloatAsState(
      if (lensEnabled && (pressed || touched)) 1f else 0f,
      spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow), label = "sliderLens",
    )
    // Recorded by the track (a sibling of the thumb in Slider's layout), never by the thumb.
    val trackBackdrop = rememberLayerBackdrop()
    Slider(
      value = value, onValueChange = report, modifier = modifier, enabled = enabled,
      onValueChangeFinished = { lastBucket = null; onValueChangeFinished?.invoke() },
      valueRange = valueRange, steps = steps, interactionSource = interactionSource, colors = glassColors,
      track = { sliderState ->
        SliderDefaults.Track(
          sliderState = sliderState, enabled = enabled, colors = glassColors,
          modifier = Modifier.height(GlassControlGeometryPolicy.sliderTrackHeightDp.dp)
            .then(if (lensEnabled) Modifier.layerBackdrop(trackBackdrop) else Modifier)
            .glassSliderGroove(sliderState.coercedValueAsFraction, accent, opacity, highlight)
            .then(Modifier.glassTickDots(if (steps > 0) steps else 4, accent)),
        )
      },
      thumb = {
        val sx by animateFloatAsState(GlassSliderPolicy.stretch(pressed), spring(stiffness = Spring.StiffnessMediumLow), label = "sliderStretch")
        val sy by animateFloatAsState(GlassSliderPolicy.squash(pressed), spring(stiffness = Spring.StiffnessMediumLow), label = "sliderSquash")
        val thumbShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        val base = Modifier.size(width = GlassControlGeometryPolicy.sliderThumbWidthDp.dp, height = GlassControlGeometryPolicy.sliderThumbHeightDp.dp)
          .graphicsLayer { scaleX = sx; scaleY = sy }
        Box(
          if (lensEnabled) base
            .lensControlThumb(trackBackdrop, thumbShape, Color.White) { lensProgress }
            .glassRimLight(thumbShape, ambient = accent, highContrast = true)
          else base
            .glassRimLight(thumbShape, ambient = accent, highContrast = true)
            .glassCapsuleBody(accent, enabled, value > valueRange.start),
        )
      },
    )
  }
}

private fun Modifier.glassTickDots(steps: Int, accent: Color): Modifier = drawWithCache {
  val positions = GlassSliderPolicy.tickPositions(steps)
  onDrawWithContent {
    drawContent()
    positions.forEach { x -> drawCircle(accent.copy(alpha = .72f), radius = 1.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * x, size.height / 2f)) }
  }
}

private fun Modifier.glassSliderGroove(fraction: Float, accent: Color, opacity: Float, highlight: Float): Modifier = drawWithCache {
  val radius = size.height / 2f
  onDrawBehind {
    val fillWidth = size.width * fraction.coerceIn(0f, 1f)
    val corner = androidx.compose.ui.geometry.CornerRadius(radius, radius)
    // Sunken groove: shaded under its top lip, lit along its lower lip.
    drawRoundRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .34f), Color.Black.copy(alpha = .10f), Color.White.copy(alpha = .18f))), cornerRadius = corner)
    drawRoundRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .30f), Color.Transparent, Color.White.copy(alpha = .20f))), cornerRadius = corner, style = Stroke(1.dp.toPx().coerceAtMost(radius)))
    if (fillWidth > 0f) {
      val fill = androidx.compose.ui.geometry.Size(fillWidth.coerceAtLeast(size.height), size.height)
      // Raised accent fill: glossy crest on top, deeper accent underneath.
      drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = (.34f * highlight).coerceIn(.12f, .40f)), accent.copy(alpha = (.62f + opacity * .18f).coerceAtMost(.86f)), accent.copy(alpha = .92f))), size = fill, cornerRadius = corner)
      val crestInset = size.height * .22f
      if (fill.width > crestInset * 2f) {
        drawLine(Color.White.copy(alpha = (.42f * highlight).coerceIn(.16f, .48f)), androidx.compose.ui.geometry.Offset(crestInset + radius * .5f, crestInset), androidx.compose.ui.geometry.Offset(fill.width - crestInset - radius * .5f, crestInset), strokeWidth = (size.height * .16f).coerceAtLeast(.5f), cap = androidx.compose.ui.graphics.StrokeCap.Round)
      }
    }
  }
}

private fun Modifier.glassCapsuleBody(accent: Color, enabled: Boolean, checked: Boolean, base: Color? = null): Modifier = drawWithCache {
  val shadowOffset = 1.5.dp.toPx()
  val shadowSpread = 1.dp.toPx()
  onDrawBehind {
    val radius = size.height / 2f
    // Contact shadow: the thumb floats just above its groove.
    drawRoundRect(
      color = Color.Black.copy(alpha = if (enabled) .26f else .12f),
      topLeft = androidx.compose.ui.geometry.Offset(-shadowSpread * .5f, shadowOffset),
      size = androidx.compose.ui.geometry.Size(size.width + shadowSpread, size.height + shadowSpread * .5f),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius + shadowSpread, radius + shadowSpread),
    )
    if (base != null) {
      drawRoundRect(base.copy(alpha = if (enabled) .94f else .48f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
    }
    drawRoundRect(
      brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (base != null) (if (enabled) .40f else .18f) else if (enabled) .72f else .30f), if (checked && base == null) accent.copy(alpha = .28f) else Color.Transparent, Color.Black.copy(alpha = if (enabled) .28f else .10f))),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
    )
    drawRoundRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .44f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * .18f), radius = size.width), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
  }
}

@Composable
private fun Modifier.glassTrackOptics(opacity: Float, highlight: Float, radius: Dp, height: Dp, checked: Boolean = true, overhang: Dp = 0.dp): Modifier {
  val scheme = MaterialTheme.colorScheme
  val tint = if (checked) scheme.primary else scheme.onSurface
  return drawWithCache {
    val trackHeight = height.toPx().coerceAtMost(size.height)
    val top = (size.height - trackHeight) / 2f
    val corner = radius.toPx().coerceAtMost(trackHeight / 2f)
    val extra = overhang.toPx().coerceAtLeast(0f)
    val left = -extra
    val trackWidth = size.width + extra * 2f
    onDrawBehind {
      drawRoundRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .28f), tint.copy(alpha = if (checked) (.38f + opacity * .30f).coerceAtMost(.76f) else .22f), Color.White.copy(alpha = .14f))), topLeft = androidx.compose.ui.geometry.Offset(left, top), size = androidx.compose.ui.geometry.Size(trackWidth, trackHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner))
      drawRoundRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .22f), Color.Transparent, Color.White.copy(alpha = .22f))), topLeft = androidx.compose.ui.geometry.Offset(left, top), size = androidx.compose.ui.geometry.Size(trackWidth, trackHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner), style = Stroke(1.dp.toPx()))
    }
  }
}

@Composable
fun Modifier.playerGlassPane(enabled: Boolean, opacity: Float? = null, highlight: Float? = null, cornerRadius: Dp = 20.dp): Modifier {
  val shape = remember(cornerRadius) {
    if (cornerRadius.value >= 999f) CircleShape else RoundedCornerShape(cornerRadius)
  }
  // Append the clip after the caller's lift/shadow layer so the glass and ripple stay round
  // without clipping away the visible press shadow.
  val clipped = this.clip(shape)
  if (LocalPlayerGlassSurfaceOwner.current) return clipped
  val appearance = koinInject<AppearancePreferences>()
  val preferenceEnabled by appearance.liquidGlassSurfaces.collectAsState()
  val preferredOpacity by appearance.glassSurfaceOpacity.collectAsState()
  val preferredHighlight by appearance.glassSurfaceHighlight.collectAsState()
  val frame = app.infinity.mpvz.ui.theme.LocalLiquidGlassBackdrop.current
  val on = enabled && preferenceEnabled
  // Pill/circle chips refract the player's single existing video sample through the lens engine
  // (API 33+, supported device). Sheets/panels and every fallback keep the frosted pane below.
  if (on && frame != null && LocalLensGlassEnabled.current && app.infinity.mpvz.ui.theme.lens.VideoLensPolicy.isLensChip(cornerRadius.value)) {
    return clipped.videoLensChip(frame, cornerRadius.value,
      app.infinity.mpvz.ui.theme.GlassSurfacePolicy.opacity(opacity ?: preferredOpacity),
      app.infinity.mpvz.ui.theme.GlassSurfacePolicy.highlight(highlight ?: preferredHighlight))
  }
  return clipped.liquidGlassPane(on, opacity ?: preferredOpacity,
    highlight ?: preferredHighlight, cornerRadius, thickSurface = true)
}
