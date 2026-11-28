/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.imageviewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import kotlin.math.max

private const val MAX_ZOOM = 5f

@Composable
fun ZoomableImage(
  bitmap: Bitmap?,
  placeholder: Bitmap?,
  isLoading: Boolean,
  error: Throwable?,
  onRetry: () -> Unit,
  rotationDegrees: Float,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
) {
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
  val transformState =
    rememberTransformableState { zoomChange, _, _ ->
      val next = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
      scale = next
      if (next <= 1f) offset = Offset.Zero
    }
  BoxWithConstraints(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    // A quarter turn swaps the axes, so the box an image must fit into is the screen seen sideways.
    // Sizing against the unswapped axes is what pushed a rotated image past the screen edges and
    // cropped it — a rotated full-body portrait lost its head and feet.
    val quarterTurned = rotationDegrees.toInt() % 180 != 0
    val fitWidth = if (quarterTurned) maxHeight else maxWidth
    val fitHeight = if (quarterTurned) maxWidth else maxHeight

    when {
      bitmap != null -> {
        val imageAspect = remember(bitmap) { bitmap.width.toFloat() / bitmap.height.toFloat() }
        val fitted = fitInto(imageAspect, fitWidth, fitHeight)
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .onSizeChanged { surfaceSize = it }
              .pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() }, onDoubleTap = {
                  scale = if (scale > 1f) 1f else MAX_ZOOM / 2
                  if (scale <= 1f) offset = Offset.Zero
                })
              }
              .transformable(state = transformState),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier =
              Modifier
                .size(fitted.width, fitted.height)
                .rotate(rotationDegrees)
                .graphicsLayer {
                  scaleX = scale
                  scaleY = scale
                  translationX = offset.x
                  translationY = offset.y
                },
          ) {
            Image(
              bitmap = bitmap.asImageBitmap(),
              contentDescription = null,
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Fit,
            )
          }
        }
      }

      // Also true when the thumbnail is ready but the full-size load has not reported in yet, so the
      // placeholder shows immediately instead of flashing the "no image" glyph first. An error wins
      // so a failed page still offers its retry button.
      placeholder != null && error == null -> {
        val placeholderAspect =
          remember(placeholder) { placeholder.width.toFloat() / placeholder.height.toFloat() }
        val fitted = fitInto(placeholderAspect, fitWidth, fitHeight)
        Image(
          bitmap = remember(placeholder) { placeholder.asImageBitmap() },
          contentDescription = null,
          modifier =
            Modifier
              .size(fitted.width, fitted.height)
              .rotate(rotationDegrees)
              .background(Color.Black),
          contentScale = ContentScale.Fit,
          alpha = 0.5f,
        )
      }

      isLoading -> {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = MaterialTheme.colorScheme.primary,
        )
      }

      error != null -> {
        Column(
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Text(
            text = stringResource(R.string.ui_image_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(12.dp))
          FilledTonalButton(onClick = onRetry) {
            Icon(
              imageVector = Icons.RoundedFilled.Refresh,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.ui_retry))
          }
        }
      }

      else -> {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Image,
            contentDescription = "No image",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
          )
        }
      }
    }
  }
}

/** Largest `w x h` with [aspect] that fits inside [maxWidth] x [maxHeight]. */
private fun fitInto(aspect: Float, maxWidth: Dp, maxHeight: Dp): DpSize {
  val widthFromHeight = maxHeight * aspect
  return if (widthFromHeight <= maxWidth) {
    DpSize(widthFromHeight, maxHeight)
  } else {
    DpSize(maxWidth, maxWidth / aspect)
  }
}

private fun clampOffset(offset: Offset, scale: Float, surface: IntSize): Offset {
  if (surface == IntSize.Zero || scale <= 1f) return Offset.Zero
  val maxX = max(0f, surface.width * (scale - 1f) / 2f)
  val maxY = max(0f, surface.height * (scale - 1f) / 2f)
  return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}
