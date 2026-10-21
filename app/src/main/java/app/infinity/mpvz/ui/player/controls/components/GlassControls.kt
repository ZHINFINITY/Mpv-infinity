/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.theme.liquidGlassPane
import app.infinity.mpvz.ui.theme.LocalGlassCustomization
import org.koin.compose.koinInject

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
  val depth = LocalGlassCustomization.current.depth
  val accent = MaterialTheme.colorScheme.primary
  Switch(
    checked = checked,
    onCheckedChange = onCheckedChange,
    enabled = enabled,
    colors = if (!glass) colors else SwitchDefaults.colors(
      checkedThumbColor = Color.Transparent,
      uncheckedThumbColor = Color.Transparent,
      disabledCheckedThumbColor = Color.Transparent,
      disabledUncheckedThumbColor = Color.Transparent,
      checkedTrackColor = accent.copy(alpha = if (enabled) 0.32f else 0.10f),
      uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
      checkedBorderColor = Color.Transparent,
      uncheckedBorderColor = Color.Transparent,
    ),
    thumbContent = if (!glass) thumbContent else ({
      Box(Modifier.size(24.dp)
        .shadow((1.dp + depth.coerceIn(0f, 2f).dp), RoundedCornerShape(12.dp), clip = false)
        .liquidGlassPane(true, opacity, highlight, 12.dp)
        .border(1.dp, if (enabled) accent.copy(alpha = if (checked) 0.75f else 0.35f)
          else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center) { thumbContent?.invoke() }
    }),
    // Material keeps its minimum interaction box and semantics; optics cover only the track.
    modifier = modifier.then(if (glass) Modifier.glassTrackOptics(opacity, highlight, 16.dp) else Modifier),
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
  val depth = LocalGlassCustomization.current.depth
  val accent = MaterialTheme.colorScheme.primary
  if (!glass) {
    Slider(value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
      onValueChangeFinished = onValueChangeFinished, valueRange = valueRange, steps = steps,
      colors = colors, interactionSource = interactionSource)
  } else {
    Slider(
      value = value,
      onValueChange = onValueChange,
      // Never lens the full Material slider touch box; optics are limited to track/thumb.
      modifier = modifier.then(if (glass) Modifier.glassTrackOptics(opacity, highlight, 2.dp, horizontalInset = 12.dp, height = 4.dp) else Modifier),
      enabled = enabled,
      onValueChangeFinished = onValueChangeFinished,
      valueRange = valueRange,
      steps = steps,
      interactionSource = interactionSource,
      colors = SliderDefaults.colors(
        thumbColor = Color.Transparent,
        activeTrackColor = accent.copy(alpha = 0.52f),
        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        disabledActiveTrackColor = accent.copy(alpha = 0.16f),
        disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
      ),
      thumb = {
        Box(Modifier.size(width = 20.dp, height = 28.dp)
          .shadow((1.dp + depth.coerceIn(0f, 2f).dp), RoundedCornerShape(10.dp), clip = false)
          .liquidGlassPane(true, opacity, highlight, 10.dp)
          .border(1.dp, accent.copy(alpha = if (enabled) 0.75f else 0.25f), RoundedCornerShape(10.dp)))
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
): Modifier {
  val tint = MaterialTheme.colorScheme.surfaceContainerHigh
  return drawWithCache {
  val trackHeight = height.toPx().coerceAtMost(size.height)
  val top = (size.height - trackHeight) / 2f
  val inset = horizontalInset.toPx().coerceAtMost(size.width / 2f)
  val corner = radius.toPx().coerceAtMost(trackHeight / 2f)
  onDrawBehind {
    drawRoundRect(
      color = tint.copy(alpha = opacity * 0.22f),
      topLeft = androidx.compose.ui.geometry.Offset(inset, top),
      size = androidx.compose.ui.geometry.Size(size.width - inset * 2f, trackHeight),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
    )
    drawRoundRect(
      color = Color.White.copy(alpha = highlight * 0.42f),
      topLeft = androidx.compose.ui.geometry.Offset(inset, top),
      size = androidx.compose.ui.geometry.Size(size.width - inset * 2f, trackHeight),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
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
  val appearance = koinInject<AppearancePreferences>()
  val preferenceEnabled by appearance.liquidGlassSurfaces.collectAsState()
  val preferredOpacity by appearance.glassSurfaceOpacity.collectAsState()
  val preferredHighlight by appearance.glassSurfaceHighlight.collectAsState()
  return liquidGlassPane(enabled && preferenceEnabled, opacity ?: preferredOpacity,
    highlight ?: preferredHighlight, cornerRadius)
}
