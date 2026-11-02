/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

@file:Suppress("ktlint:standard:no-wildcard-imports")

package app.infinity.mpvz.ui.player.controls.components.panels

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.player.controls.panelCardsColors
import app.infinity.mpvz.ui.player.controls.PlayerControlGridPolicy
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.theme.glassRimLight
import app.infinity.mpvz.ui.theme.liquidGlassPane
import app.infinity.mpvz.ui.theme.AppShapeScale
import kotlin.math.roundToInt

/**
 * A draggable panel with an optional fixed header and scrollable content.
 *
 * @param modifier Modifier for the panel
 * @param header Optional composable for the fixed header that stays constant during scroll
 * @param attachToScreenEdges Build 1933: in landscape, attach the panel flush to the top, bottom
 *   and end display edges (grid More drawer and Subtitles settings only). Others keep 1909 placement.
 * @param content The scrollable content of the panel
 */
@Composable
fun DraggablePanel(
  modifier: Modifier = Modifier,
  header: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  containerColor: Color? = null,
  tonalElevation: Dp = 0.dp,
  shadowElevation: Dp = 0.dp,
  border: BorderStroke? = null,
  attachToScreenEdges: Boolean = false,
  content: @Composable () -> Unit,
) {
  var offsetX by remember { mutableFloatStateOf(0f) }
  var panelWidth by remember { mutableIntStateOf(0) }
  val appearance = org.koin.compose.koinInject<AppearancePreferences>()
  val glass by appearance.liquidGlassSurfaces.collectAsState()
  val opacity by appearance.glassSurfaceOpacity.collectAsState()
  val highlight by appearance.glassSurfaceHighlight.collectAsState()

  val configuration = LocalConfiguration.current
  val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
  val attached = EdgeAttachedPanelPolicy.attaches(attachToScreenEdges, isPortrait)
  val layoutDirection = LocalLayoutDirection.current
  val insetDensity = LocalDensity.current
  val safeInsets = WindowInsets.safeDrawing
  val insetKey = listOf(
    safeInsets.getTop(insetDensity),
    safeInsets.getBottom(insetDensity),
    safeInsets.getLeft(insetDensity, layoutDirection),
    safeInsets.getRight(insetDensity, layoutDirection),
  )
  // Keyed on window geometry and safe insets so rotation, resizing and system bars appearing
  // or hiding re-measure the distance to the edges instead of keeping a stale value.
  var edgeBleed by remember(attached, configuration.screenWidthDp, configuration.screenHeightDp, insetKey) {
    mutableStateOf(EdgeBleed.Zero)
  }
  // Last measured host size; not state, so tracking it never triggers recomposition.
  val lastHostSize = remember(attached) { IntArray(2) { -1 } }

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxSize()
        .then(
          if (attached) {
            Modifier.onGloballyPositioned { coordinates ->
              val root = coordinates.findRootCoordinates()
              val topLeft = root.localPositionOf(coordinates, Offset.Zero)
              val measured =
                EdgeAttachedPanelPolicy.bleed(
                  hostLeft = topLeft.x.roundToInt(),
                  hostTop = topLeft.y.roundToInt(),
                  hostRight = (topLeft.x + coordinates.size.width).roundToInt(),
                  hostBottom = (topLeft.y + coordinates.size.height).roundToInt(),
                  rootWidth = root.size.width,
                  rootHeight = root.size.height,
                  layoutDirection = layoutDirection,
                )
              val hostChanged = lastHostSize[0] != coordinates.size.width || lastHostSize[1] != coordinates.size.height
              lastHostSize[0] = coordinates.size.width
              lastHostSize[1] = coordinates.size.height
              val settled = EdgeAttachedPanelPolicy.settle(edgeBleed, measured, hostGeometryChanged = hostChanged)
              if (settled != edgeBleed) edgeBleed = settled
            }
          } else {
            Modifier
          },
        ),
    contentAlignment = if (isPortrait) Alignment.Center else Alignment.CenterEnd,
  ) {
    val density = LocalDensity.current
    val parentWidthPx = with(density) { maxWidth.toPx() }

    // Calculate bounds for horizontal drag
    // Panel is aligned to CenterEnd (Right), so offset 0 is the default rightmost position.
    val freeSpace = (parentWidthPx - panelWidth).coerceAtLeast(0f)
    val dragRange = EdgeAttachedPanelPolicy.dragRange(attached, freeSpace)
    val maxOffset = dragRange.endInclusive
    val minOffset = dragRange.start

    // In portrait, cap panel height to 50% of available height
    val panelMaxHeight = if (isPortrait) maxHeight * 0.5f else maxHeight

    val colors = panelCardsColors()
    val panelWidthDp = PlayerControlGridPolicy.panelWidthDp(maxWidth.value).dp
    val panelShape =
      if (attached) EdgeAttachedPanelPolicy.shape(layoutDirection) else shape ?: MaterialTheme.shapes.extraLarge
    val safeTop = WindowInsets.safeDrawing.getTop(density)
    val safeBottom = WindowInsets.safeDrawing.getBottom(density)
    val safeEnd =
      if (layoutDirection == LayoutDirection.Ltr) {
        WindowInsets.safeDrawing.getRight(density, layoutDirection)
      } else {
        WindowInsets.safeDrawing.getLeft(density, layoutDirection)
      }
    Surface(
      modifier =
        Modifier
          .offset { IntOffset(offsetX.roundToInt(), 0) }
          .onSizeChanged { panelWidth = it.width }
          .then(
            if (attached) {
              // The glass extends past the host by the measured distance to the display
              // edges, while the panel still occupies its original slot in the layout.
              val panelWidthPx = with(density) { panelWidthDp.roundToPx() }
              Modifier.layout { measurable, constraints ->
                val bleed = edgeBleed
                val hostHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
                val placeable =
                  measurable.measure(
                    Constraints.fixed(
                      width = panelWidthPx + bleed.end,
                      height = hostHeight + bleed.top + bleed.bottom,
                    ),
                  )
                layout(panelWidthPx, hostHeight) { placeable.placeRelative(0, -bleed.top) }
              }
            } else {
              Modifier
                // An outer fillMaxWidth makes the constraints tight before widthIn,
                // defeating its cap. Resolve one exact width shared by header/body.
                .width(panelWidthDp)
                // Establish a finite viewport so the scroll body cannot paint beyond
                // the panel's rounded root when cards are taller than the sheet.
                .heightIn(max = panelMaxHeight)
                .fillMaxHeight()
            },
          )
          .liquidGlassPane(glass, opacity, highlight, 24.dp, drawRim = !attached)
          .glassRimLight(shape = panelShape, enabled = attached && glass),
      shape = panelShape,
      color = if (glass) Color.Transparent else containerColor ?: colors.containerColor,
      contentColor = colors.contentColor,
      tonalElevation = if (glass) 0.dp else tonalElevation,
      shadowElevation = if (glass) 0.dp else shadowElevation,
      border = if (glass) null else border,
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .then(
              if (attached) {
                with(density) {
                  Modifier.padding(
                    top = EdgeAttachedPanelPolicy.contentInset(edgeBleed.top, safeTop).toDp(),
                    bottom = EdgeAttachedPanelPolicy.contentInset(edgeBleed.bottom, safeBottom).toDp(),
                    end = EdgeAttachedPanelPolicy.contentInset(edgeBleed.end, safeEnd).toDp(),
                  )
                }
              } else {
                Modifier
              },
            ),
      ) {
        // Drag Handle & Indicator
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(18.dp) // Good touch target size
              .pointerInput(maxOffset, minOffset) {
                detectDragGestures { change, dragAmount ->
                  change.consume()
                  val newOffset = offsetX + dragAmount.x
                  offsetX = newOffset.coerceIn(minOffset, maxOffset)
                }
              },
          contentAlignment = Alignment.Center,
        ) {
          // An edge-attached panel is part of the screen and cannot be dragged away,
          // so it keeps the spacing but not the drag affordance.
          if (!attached) Box(
            modifier =
              Modifier
                .width(32.dp)
                .height(4.dp)
                .background(
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                  shape = AppShapeScale.extraSmall,
                ),
          )
        }

        // Fixed header (if provided) - stays constant
        if (header != null) {
          header()
        }

        // Scrollable content
        Column(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        ) {
          content()
        }
      }
    }
  }
}
