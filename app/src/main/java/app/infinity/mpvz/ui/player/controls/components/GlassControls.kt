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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.theme.LocalGlassCustomization
import app.infinity.mpvz.ui.theme.SwitchContrastPolicy
import app.infinity.mpvz.ui.theme.glassRimLight
import app.infinity.mpvz.ui.theme.liquidGlassPane
import org.koin.compose.koinInject

val LocalPlayerGlassSurfaceOwner = staticCompositionLocalOf { false }

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

/** Shared press feedback. Values are consumed in graphicsLayer/draw lambdas only. */
@Composable
fun Modifier.glassPressFeedback(
  interactionSource: MutableInteractionSource,
  enabled: Boolean = true,
  reducedMotion: Boolean = false,
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
  Switch(
    checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
    colors = if (glass) transparent else tuned,
    thumbContent = if (!glass) thumbContent else {
      {
        Box(
          Modifier.size(width = 30.dp, height = 22.dp)
            .glassRimLight(androidx.compose.foundation.shape.RoundedCornerShape(12.dp), ambient = contrast.checkedTrack, highContrast = true)
            .glassCapsuleBody(contrast.checkedTrack, enabled, checked),
          contentAlignment = Alignment.Center,
        ) { thumbContent?.invoke() }
      }
    },
    modifier = modifier.then(if (glass) Modifier.glassTrackOptics(opacity, highlight, 16.dp, 32.dp, checked) else Modifier),
  )
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
      thumbColor = Color.Transparent, activeTrackColor = accent.copy(alpha = .72f),
      inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .14f),
      disabledActiveTrackColor = accent.copy(alpha = .18f), disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f),
    )
    val pressed by interactionSource.collectIsDraggedAsState()
    Slider(
      value = value, onValueChange = report, modifier = modifier, enabled = enabled,
      onValueChangeFinished = { lastBucket = null; onValueChangeFinished?.invoke() },
      valueRange = valueRange, steps = steps, interactionSource = interactionSource, colors = glassColors,
      track = { state ->
        SliderDefaults.Track(
          sliderState = state, enabled = enabled, colors = glassColors,
          modifier = Modifier.height(4.dp).glassTrackOptics(opacity, highlight, 2.dp, 4.dp)
            .then(if (steps > 0) Modifier.glassTickDots(steps, accent) else Modifier),
        )
      },
      thumb = {
        val sx by animateFloatAsState(GlassSliderPolicy.stretch(pressed), spring(stiffness = Spring.StiffnessMediumLow), label = "sliderStretch")
        val sy by animateFloatAsState(GlassSliderPolicy.squash(pressed), spring(stiffness = Spring.StiffnessMediumLow), label = "sliderSquash")
        Box(
          Modifier.size(width = 28.dp, height = 22.dp).graphicsLayer { scaleX = sx; scaleY = sy }
            .glassRimLight(androidx.compose.foundation.shape.RoundedCornerShape(12.dp), ambient = accent, highContrast = true)
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

private fun Modifier.glassCapsuleBody(accent: Color, enabled: Boolean, checked: Boolean): Modifier = drawWithCache {
  val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
  onDrawBehind {
    val radius = size.height / 2f
    drawRoundRect(
      brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (enabled) .78f else .35f), if (checked) accent.copy(alpha = .28f) else Color.Transparent, Color.Black.copy(alpha = if (enabled) .18f else .08f))),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
    )
    drawRoundRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .32f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * .18f), radius = size.width), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
  }
}

@Composable
private fun Modifier.glassTrackOptics(opacity: Float, highlight: Float, radius: Dp, height: Dp, checked: Boolean = true): Modifier {
  val scheme = MaterialTheme.colorScheme
  val tint = if (checked) scheme.primary else scheme.onSurface
  return drawWithCache {
    val trackHeight = height.toPx().coerceAtMost(size.height)
    val top = (size.height - trackHeight) / 2f
    val corner = radius.toPx().coerceAtMost(trackHeight / 2f)
    onDrawBehind {
      drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = highlight * .28f), tint.copy(alpha = if (checked) (.42f + opacity * .28f).coerceAtMost(.72f) else .20f), Color.Black.copy(alpha = .14f))), topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, trackHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner))
      drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .18f), Color.Transparent, Color.Black.copy(alpha = .18f))), topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, trackHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner), style = Stroke(1.dp.toPx()))
    }
  }
}

@Composable
fun Modifier.playerGlassPane(enabled: Boolean, opacity: Float? = null, highlight: Float? = null, cornerRadius: Dp = 20.dp): Modifier {
  if (LocalPlayerGlassSurfaceOwner.current) return this
  val appearance = koinInject<AppearancePreferences>()
  val preferenceEnabled by appearance.liquidGlassSurfaces.collectAsState()
  val preferredOpacity by appearance.glassSurfaceOpacity.collectAsState()
  val preferredHighlight by appearance.glassSurfaceHighlight.collectAsState()
  return liquidGlassPane(enabled && preferenceEnabled, opacity ?: preferredOpacity, highlight ?: preferredHighlight, cornerRadius)
}
