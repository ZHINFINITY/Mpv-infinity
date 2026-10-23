package app.infinity.mpvz.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu as MaterialDropdownMenu
import androidx.compose.material3.ExposedDropdownMenu as MaterialExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import org.koin.compose.koinInject

/** Shared menu surface that preserves the native Material3 appearance when glass is disabled. */
@Composable
fun GlassDropdownMenu(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  offset: DpOffset = DpOffset.Zero,
  scrollState: ScrollState = androidx.compose.foundation.rememberScrollState(),
  properties: PopupProperties = PopupProperties(focusable = true),
  shape: Shape = RoundedCornerShape(4.dp),
  containerColor: Color = MenuDefaults.containerColor,
  tonalElevation: androidx.compose.ui.unit.Dp = MenuDefaults.TonalElevation,
  shadowElevation: androidx.compose.ui.unit.Dp = MenuDefaults.ShadowElevation,
  border: BorderStroke? = null,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
  val appearance = koinInject<AppearancePreferences>()
  val liquidGlass by appearance.liquidGlassSurfaces.collectAsState()
  if (liquidGlass) {
    MaterialDropdownMenu(
      expanded = expanded,
      onDismissRequest = onDismissRequest,
      modifier = modifier.liquidGlassPane(
        enabled = true,
        cornerRadius = 20.dp,
        materialRole = GlassSurfacePolicy.MaterialRole.Regular,
      ),
      offset = offset,
      scrollState = scrollState,
      properties = properties,
      shape = RoundedCornerShape(20.dp),
      containerColor = Color.Transparent,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border = null,
      content = {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
          content()
        }
      },
    )
  } else {
    MaterialDropdownMenu(
      expanded = expanded,
      onDismissRequest = onDismissRequest,
      modifier = modifier,
      offset = offset,
      scrollState = scrollState,
      properties = properties,
      shape = shape,
      containerColor = containerColor,
      tonalElevation = tonalElevation,
      shadowElevation = shadowElevation,
      border = border,
      content = content,
    )
  }
}

@Composable
fun ExposedDropdownMenuBoxScope.GlassExposedDropdownMenu(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  scrollState: ScrollState = androidx.compose.foundation.rememberScrollState(),
  matchAnchorWidth: Boolean = true,
  shape: Shape = RoundedCornerShape(4.dp),
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
  tonalElevation: androidx.compose.ui.unit.Dp = 0.dp,
  shadowElevation: androidx.compose.ui.unit.Dp = 0.dp,
  border: BorderStroke? = null,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
  val appearance = koinInject<AppearancePreferences>()
  val liquidGlass by appearance.liquidGlassSurfaces.collectAsState()
  if (liquidGlass) {
    MaterialExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = onDismissRequest,
      modifier = modifier.liquidGlassPane(
        enabled = true,
        cornerRadius = 20.dp,
        materialRole = GlassSurfacePolicy.MaterialRole.Regular,
      ),
      scrollState = scrollState,
      matchAnchorWidth = matchAnchorWidth,
      shape = RoundedCornerShape(20.dp),
      containerColor = Color.Transparent,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border = null,
      content = {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
          content()
        }
      },
    )
  } else {
    MaterialExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = onDismissRequest,
      modifier = modifier,
      scrollState = scrollState,
      matchAnchorWidth = matchAnchorWidth,
      shape = shape,
      containerColor = containerColor,
      tonalElevation = tonalElevation,
      shadowElevation = shadowElevation,
      border = border,
      content = content,
    )
  }
}
