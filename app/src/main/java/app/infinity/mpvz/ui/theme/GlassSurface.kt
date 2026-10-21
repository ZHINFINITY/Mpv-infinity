/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared scene optics for video, or caller-owned artwork blur for audio. Foreground is never blurred. */
@Composable
fun GlassSurface(
  modifier: Modifier = Modifier,
  enabled: Boolean,
  opacity: Float = 0.88f,
  highlight: Float = 0.18f,
  cornerRadius: Dp = 24.dp,
  border: Boolean = true,
  backdrop: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
  val tuning = LocalGlassCustomization.current
  val hasSharedScene = LocalLiquidGlassBackdrop.current != null
  val alpha = GlassSurfacePolicy.opacity(if (tuning.overrideMaterial) tuning.opacity else opacity)
  val glow = GlassSurfacePolicy.highlight(if (tuning.overrideMaterial) tuning.highlight else highlight)
  val artworkBlur = remember(enabled, backdrop != null, tuning.frost) {
    if (enabled && backdrop != null && Build.VERSION.SDK_INT >= 31 && tuning.frost > 0f) {
      RenderEffect.createBlurEffect(24f * tuning.frost, 24f * tuning.frost, Shader.TileMode.CLAMP).asComposeRenderEffect()
    } else null
  }
  Box(
    modifier = modifier.clip(shape)
      .liquidGlassPane(enabled, alpha, glow, cornerRadius, drawRim = border)
      .then(if (!enabled) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape) else Modifier)
      .then(if (border && !enabled) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), shape) else Modifier),
  ) {
    if (enabled && backdrop != null && !hasSharedScene) {
      Box(Modifier.matchParentSize().graphicsLayer { renderEffect = artworkBlur }) { backdrop.invoke() }
      Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha * 0.58f)))
    }
    content()
  }
}

@Composable
fun GlassControlSurface(
  modifier: Modifier = Modifier,
  enabled: Boolean,
  opacity: Float,
  highlight: Float,
  cornerRadius: Dp = 20.dp,
  border: Boolean = true,
  backdrop: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) = GlassSurface(modifier, enabled, opacity, highlight, cornerRadius, border, backdrop, content)
