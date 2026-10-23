/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.preferences.components.settingsGlassContainerColor
import app.infinity.mpvz.ui.preferences.components.settingsGlassSurface

/** The sheet never grows taller than this share of the screen, so it always clears the status bar. */
internal const val GlassOptionSheetMaxHeightFraction = 0.9f

private val GlassSheetTopShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * Frosted modal bottom sheet shared by option pickers and the server-add sheets.
 *
 * Material3 applies the sheet's slide offset *inside* the modifier passed to [ModalBottomSheet].
 * A glass pane (clip + backdrop draw) placed on that modifier therefore sits at the un-offset
 * position near the top of the window, while the sheet content is drawn lower down. Short sheets
 * got clipped to nothing and the frost appeared in the wrong place (build 1902). Here the sheet's
 * own surface is transparent and the pane is drawn by the content column, which moves with the
 * sheet. The pane also owns the drag handle and the single bottom inset (navigation bar + IME),
 * so the frost reaches the screen edge and nothing is padded twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassModalBottomSheet(
  onDismissRequest: () -> Unit,
  sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
  paneModifier: Modifier = glassOptionSheetModifier(),
  containerColor: Color = glassOptionSheetContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
  content: @Composable ColumnScope.() -> Unit,
) {
  val maxHeight = (LocalConfiguration.current.screenHeightDp * GlassOptionSheetMaxHeightFraction).dp
  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
    shape = GlassSheetTopShape,
    containerColor = Color.Transparent,
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 0.dp,
    dragHandle = null,
    contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .heightIn(max = maxHeight)
          .then(paneModifier)
          .background(containerColor, GlassSheetTopShape)
          .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
    ) {
      BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
      content()
    }
  }
}

/**
 * Shared bottom sheet for option pickers and small settings forms (Sort & View, list/options
 * pickers, text fields), opened from the bottom like the Jellyfin server-add sheet.
 *
 * Layout contract (fixes the build-1902 open crash): the title and buttons stay pinned while
 * [content] gets a bounded, weighted area. Content brings its own scrolling (a `verticalScroll`
 * column or a lazy list) and is always measured with a finite max height, never inside another
 * vertical scroll. [modifier] styles the frosted pane, which [GlassModalBottomSheet] draws inside
 * the moving sheet content.
 */
@Composable
fun GlassOptionSheet(
  onDismissRequest: () -> Unit,
  title: @Composable () -> Unit,
  modifier: Modifier = glassOptionSheetModifier(),
  containerColor: Color = glassOptionSheetContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
  confirmButton: (@Composable () -> Unit)? = null,
  dismissButton: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  GlassModalBottomSheet(
    onDismissRequest = onDismissRequest,
    paneModifier = modifier,
    containerColor = containerColor,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
      Box(Modifier.fillMaxWidth().weight(1f, fill = false)) { content() }
      if (confirmButton != null || dismissButton != null) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
          dismissButton?.invoke()
          confirmButton?.invoke()
        }
      }
    }
  }
}

/** String-title convenience overload. */
@Composable
fun GlassOptionSheet(
  onDismissRequest: () -> Unit,
  title: String,
  modifier: Modifier = glassOptionSheetModifier(),
  containerColor: Color = glassOptionSheetContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
  confirmButton: (@Composable () -> Unit)? = null,
  dismissButton: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) = GlassOptionSheet(
  onDismissRequest = onDismissRequest,
  title = { Text(title) },
  modifier = modifier,
  containerColor = containerColor,
  confirmButton = confirmButton,
  dismissButton = dismissButton,
  content = content,
)

/** The one shared frosted pane for option and server sheets (same material as Sort & View). */
@Composable
fun glassOptionSheetModifier(cornerRadius: Dp = 28.dp): Modifier =
  Modifier.settingsGlassSurface(cornerRadius = cornerRadius, independent = true)

/** Transparent over Liquid Glass; the caller's opaque Material fill when glass is off. */
@Composable
fun glassOptionSheetContainerColor(fallback: Color = MaterialTheme.colorScheme.surfaceContainerLow): Color =
  settingsGlassContainerColor(fallback)
