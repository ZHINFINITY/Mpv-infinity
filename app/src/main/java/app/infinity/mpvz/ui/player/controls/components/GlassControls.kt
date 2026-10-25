/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.theme.liquidGlassPane
import app.infinity.mpvz.ui.theme.LocalGlassCustomization
import app.infinity.mpvz.ui.theme.SwitchContrastPolicy
import org.koin.compose.koinInject

/** A tile may own the glass material while its embedded button retains input/semantics only. */
val LocalPlayerGlassSurfaceOwner = staticCompositionLocalOf { false }

/** Material owns input, state animation, focus, accessibility and RTL; optics only draw behind it. */
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
  val depth = GlassControlPolicy.depth(LocalGlassCustomization.current.depth)
  val scheme = MaterialTheme.colorScheme
  val accent = scheme.primary
  val contrast = SwitchContrastPolicy.forSurfaceLuminance(scheme.surface.luminance())
  val themedColors = SwitchDefaults.colors(
    checkedTrackColor = contrast.checkedTrack, checkedThumbColor = contrast.checkedThumb,
    uncheckedTrackColor = contrast.uncheckedTrack, uncheckedThumbColor = contrast.uncheckedThumb,
    checkedBorderColor = contrast.checkedBorder, uncheckedBorderColor = contrast.uncheckedBorder,
    disabledCheckedTrackColor = contrast.checkedTrack.copy(alpha = .45f),
    disabledCheckedThumbColor = contrast.checkedThumb.copy(alpha = .70f),
    disabledUncheckedTrackColor = contrast.uncheckedTrack.copy(alpha = .45f),
    disabledUncheckedThumbColor = contrast.uncheckedThumb.copy(alpha = .52f),
    disabledCheckedBorderColor = contrast.checkedBorder.copy(alpha = .45f),
    disabledUncheckedBorderColor = contrast.uncheckedBorder.copy(alpha = .45f),
  )
  Switch(
    checked = checked,
    onCheckedChange = onCheckedChange,
    enabled = enabled,
    colors = if (!glass) themedColors else SwitchDefaults.colors(
      checkedThumbColor = Color.Transparent,
      uncheckedThumbColor = Color.Transparent,
      disabledCheckedThumbColor = Color.Transparent,
      disabledUncheckedThumbColor = Color.Transparent,
      checkedTrackColor = Color.Transparent,
      uncheckedTrackColor = Color.Transparent,
      disabledCheckedTrackColor = Color.Transparent,
      disabledUncheckedTrackColor = Color.Transparent,
      checkedBorderColor = Color.Transparent,
      uncheckedBorderColor = Color.Transparent,
      disabledCheckedBorderColor = Color.Transparent,
      disabledUncheckedBorderColor = Color.Transparent,
    ),
    thumbContent = if (!glass) thumbContent else ({
      Box(
        Modifier.size(24.dp)
          .glassThumbOptics(
            accent = contrast.checkedTrack,
            checked = checked,
            highlight = GlassControlPolicy.highlightAlpha(highlight, enabled, checked),
            shadow = GlassControlPolicy.shadowAlpha(depth, enabled),
            border = GlassControlPolicy.thumbBorderAlpha(enabled, checked),
          ),
        contentAlignment = Alignment.Center,
      ) { thumbContent?.invoke() }
    }),
    // Material keeps its minimum interaction box and semantics; optics cover only the track.
    modifier = modifier.then(if (glass) Modifier.glassTrackOptics(opacity, highlight, 16.dp, height = 32.dp, checked = checked) else Modifier),
  )
}

@Composable
fun GlassSlider(
  value: Float,
  onValueChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  glassEnabled: Boolean = true,
  onValueChangeFinished: (() -> Unit)? = null,
  valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
  steps: Int = 0,
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  colors: SliderColors = SliderDefaults.colors(),
) {
  val appearance = koinInject<AppearancePreferences>()
  val preferenceEnabled by appearance.liquidGlassSurfaces.collectAsState()
  val opacity by appearance.glassSurfaceOpacity.collectAsState()
  val highlight by appearance.glassSurfaceHighlight.collectAsState()
  val glass = preferenceEnabled && glassEnabled
  val depth = GlassControlPolicy.depth(LocalGlassCustomization.current.depth)
  val accent = MaterialTheme.colorScheme.primary
  if (!glass) {
    Slider(value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
      onValueChangeFinished = onValueChangeFinished, valueRange = valueRange, steps = steps,
      colors = colors, interactionSource = interactionSource)
  } else {
    val glassColors = SliderDefaults.colors(
      thumbColor = Color.Transparent,
      activeTrackColor = accent.copy(alpha = 0.52f),
      inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
      disabledActiveTrackColor = accent.copy(alpha = 0.16f),
      disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    )
    Slider(
      value = value,
      onValueChange = onValueChange,
      // Never lens the full Material slider touch box; optics are limited to track/thumb.
      modifier = modifier,
      enabled = enabled,
      onValueChangeFinished = onValueChangeFinished,
      valueRange = valueRange,
      steps = steps,
      interactionSource = interactionSource,
      colors = glassColors,
      track = { sliderState ->
        SliderDefaults.Track(
          sliderState = sliderState,
          enabled = enabled,
          colors = glassColors,
          modifier = Modifier.height(4.dp)
            .glassTrackOptics(opacity, highlight, 2.dp, height = 4.dp),
        )
      },
        thumb = {
        Box(Modifier.size(width = 18.dp, height = 24.dp)
          .glassThumbOptics(
            accent = accent,
            highlight = GlassControlPolicy.highlightAlpha(highlight, enabled, value > valueRange.start),
            shadow = GlassControlPolicy.shadowAlpha(depth, enabled),
            border = if (enabled) 0.62f else 0.22f,
          ))
      },
    )
  }
}

@Composable
private fun Modifier.glassTrackOptics(
  opacity: Float,
  highlight: Float,
  radius: Dp,
  horizontalInset: Dp = 0.dp,
  height: Dp = 32.dp,
  checked: Boolean = true,
): Modifier {
  val scheme = MaterialTheme.colorScheme
  val tint = if (checked) scheme.primary else scheme.onSurface
  val tintAlpha = if (checked) 0.72f else 0.20f
  val depth = GlassControlPolicy.depth(LocalGlassCustomization.current.depth)
  return drawWithCache {
    val trackHeight = height.toPx().coerceAtMost(size.height)
    val top = (size.height - trackHeight) / 2f
    val inset = horizontalInset.toPx().coerceAtMost(size.width / 2f)
    val corner = radius.toPx().coerceAtMost(trackHeight / 2f)
    val bounds = androidx.compose.ui.geometry.Size(size.width - inset * 2f, trackHeight)
    onDrawBehind {
      drawRoundRect(
        brush = Brush.verticalGradient(
          listOf(
            Color.White.copy(alpha = (highlight * 0.32f).coerceIn(0f, 0.32f)),
            tint.copy(alpha = if (checked) (0.42f + opacity * 0.30f).coerceAtMost(0.72f) else tintAlpha),
            Color.Black.copy(alpha = GlassControlPolicy.shadowAlpha(depth, true)),
          ),
          startY = top,
          endY = top + trackHeight,
        ),
        topLeft = androidx.compose.ui.geometry.Offset(inset, top),
        size = bounds,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
      )
      // One full contour, not a half-height capsule and a second inset outline.
      // Native Material retains thumb position, interaction semantics and RTL.
      drawRoundRect(
        brush = Brush.verticalGradient(listOf(
          Color.White.copy(alpha = (0.16f + highlight * 0.5f).coerceAtMost(1f)),
          Color.Transparent,
          Color.Black.copy(alpha = GlassControlPolicy.shadowAlpha(depth, true)),
        ), startY = top, endY = top + trackHeight),
        topLeft = androidx.compose.ui.geometry.Offset(inset, top),
        size = bounds,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
        style = Stroke(1.dp.toPx()),
      )
    }
  }
}

@Composable
private fun Modifier.glassThumbOptics(
  accent: Color,
  highlight: Float,
  shadow: Float,
  border: Float,
  checked: Boolean = true,
): Modifier {
  // A frosted body hides any native thumb-shadow footprint beneath the knob;
  // only this full, rounded contour and directional reflection remain visible.
  val scheme = MaterialTheme.colorScheme
  val body = if (checked) scheme.onPrimary else scheme.onSurfaceVariant
  return drawWithCache {
  val radius = size.minDimension / 2f
  onDrawBehind {
    drawRoundRect(body, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
    drawRoundRect(
      brush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = highlight * 0.72f), accent.copy(alpha = if (checked) 0.18f else 0.08f), Color.Black.copy(alpha = shadow)),
      ),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
    )
    // A single convex highlight follows the full knob outline. The old half-height
    // round-rect produced another tiny capsule inside it, making the thumb look faceted.
    drawRoundRect(
      brush = Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = highlight * 0.40f), Color.Transparent),
        center = androidx.compose.ui.geometry.Offset(size.width * 0.30f, size.height * 0.22f),
        radius = radius * 1.7f,
      ),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
    )
    drawRoundRect(
      brush = Brush.verticalGradient(listOf(
        Color.White.copy(alpha = highlight * 0.88f),
        accent.copy(alpha = border * 0.35f),
        Color.Black.copy(alpha = shadow),
      )),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
      style = Stroke(1.dp.toPx()),
    )
  }
  }
}

@Composable
fun Modifier.playerGlassPane(
  enabled: Boolean,
  opacity: Float? = null,
  highlight: Float? = null,
  cornerRadius: Dp = 20.dp,
): Modifier {
  if (LocalPlayerGlassSurfaceOwner.current) return this
  val appearance = koinInject<AppearancePreferences>()
  val preferenceEnabled by appearance.liquidGlassSurfaces.collectAsState()
  val preferredOpacity by appearance.glassSurfaceOpacity.collectAsState()
  val preferredHighlight by appearance.glassSurfaceHighlight.collectAsState()
  return liquidGlassPane(enabled && preferenceEnabled, opacity ?: preferredOpacity,
    highlight ?: preferredHighlight, cornerRadius, thickSurface = true)
}
