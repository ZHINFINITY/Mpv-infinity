/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

val LocalAppWallpaperActive = compositionLocalOf { false }

enum class WallpaperScaleMode {
  Fit,
  Fill,
}

@Composable
fun AppWallpaperHost(content: @Composable () -> Unit) {
  val context = LocalContext.current
  val preferences = koinInject<AppearancePreferences>()
  val wallpaperUri by preferences.customWallpaperUri.collectAsState()
  val wallpaperZoom by preferences.customWallpaperZoom.collectAsState()
  val wallpaperOffsetX by preferences.customWallpaperOffsetX.collectAsState()
  val wallpaperOffsetY by preferences.customWallpaperOffsetY.collectAsState()
  val wallpaperScaleMode by preferences.customWallpaperScaleMode.collectAsState()
  val wallpaperBlur by preferences.customWallpaperBlur.collectAsState()
  val wallpaperAlpha by preferences.customWallpaperAlpha.collectAsState()
  val wallpaper =
    produceState<Bitmap?>(initialValue = null, wallpaperUri) {
      val loadedWallpaper =
        if (wallpaperUri.isBlank()) {
          null
        } else {
          withContext(Dispatchers.IO) { loadWallpaperBitmap(context, wallpaperUri) }
        }
      value = loadedWallpaper
    }.value
  DisposableEffect(wallpaper) {
    val displayedWallpaper = wallpaper
    onDispose {
      if (displayedWallpaper != null) {
        Handler(Looper.getMainLooper()).postDelayed(
          { if (!displayedWallpaper.isRecycled) displayedWallpaper.recycle() },
          WALLPAPER_RECYCLE_DELAY_MS,
        )
      }
    }
  }
  val wallpaperActive = wallpaper != null

  Box(
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    wallpaper?.let { bitmap ->
      WallpaperImage(
        bitmap = bitmap,
        zoom = wallpaperZoom,
        offsetX = wallpaperOffsetX,
        offsetY = wallpaperOffsetY,
        scaleMode = wallpaperScaleMode,
        blurRadius = wallpaperBlur,
        imageAlpha = wallpaperAlpha,
        modifier = Modifier.fillMaxSize(),
      )
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(
              if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                Color.Black.copy(alpha = 0.18f)
              } else {
                Color.White.copy(alpha = 0.30f)
              },
            ),
      )
    }
    CompositionLocalProvider(LocalAppWallpaperActive provides wallpaperActive) {
      content()
    }
  }
}

@Composable
fun wallpaperAwareBackgroundColor(): Color =
  if (LocalAppWallpaperActive.current) Color.Transparent else MaterialTheme.colorScheme.background

@Composable
fun WallpaperImage(
  bitmap: Bitmap,
  zoom: Float,
  offsetX: Float,
  offsetY: Float,
  scaleMode: WallpaperScaleMode,
  blurRadius: Float = 0f,
  imageAlpha: Float = 1f,
  modifier: Modifier = Modifier,
) {
  val safeBlur = blurRadius.coerceIn(0f, MAX_WALLPAPER_BLUR_DP)
  val safeAlpha = imageAlpha.coerceIn(0f, 1f)
  Box(modifier = modifier.clipToBounds()) {
    if (scaleMode == WallpaperScaleMode.Fit) {
      Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
          Modifier
            .fillMaxSize()
            .graphicsLayer {
              scaleX = 1.12f
              scaleY = 1.12f
              alpha = 0.72f * safeAlpha
            }.blur(28.dp),
      )
    }
    Image(
      bitmap = bitmap.asImageBitmap(),
      contentDescription = null,
      contentScale = if (scaleMode == WallpaperScaleMode.Fit) ContentScale.Fit else ContentScale.Crop,
      modifier =
        Modifier
          .fillMaxSize()
          .graphicsLayer {
            val safeZoom = zoom.coerceIn(1f, 3f)
            scaleX = safeZoom
            scaleY = safeZoom
            translationX = offsetX.coerceIn(-1f, 1f) * size.width * 0.35f
            translationY = offsetY.coerceIn(-1f, 1f) * size.height * 0.35f
            alpha = safeAlpha
          }.then(if (safeBlur > 0f) Modifier.blur(safeBlur.dp) else Modifier),
    )
  }
}

private const val MAX_WALLPAPER_BLUR_DP = 40f

suspend fun saveWallpaperCopy(
  context: android.content.Context,
  sourceUri: String,
): String = withContext(Dispatchers.IO) {
  val bitmap = requireNotNull(loadWallpaperBitmap(context, sourceUri)) {
    context.getString(app.infinity.mpvz.R.string.wallpaper_save_failed)
  }
  try {
    if (sourceUri.startsWith(WALLPAPER_DATA_PREFIX)) return@withContext sourceUri
    val output = java.io.ByteArrayOutputStream()
    android.util.Base64OutputStream(output, android.util.Base64.NO_WRAP).use { encodedStream ->
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encodedStream)) {
        context.getString(app.infinity.mpvz.R.string.wallpaper_save_failed)
      }
    }
    require(output.size() <= MAX_WALLPAPER_ENCODED_LENGTH) {
      context.getString(app.infinity.mpvz.R.string.wallpaper_save_failed)
    }
    WALLPAPER_DATA_PREFIX + output.toString(Charsets.US_ASCII.name())
  } finally {
    bitmap.recycle()
  }
}

fun loadWallpaperBitmap(
  context: android.content.Context,
  wallpaperUri: String,
): Bitmap? =
  runCatching {
    val uri = Uri.parse(wallpaperUri)
    if (uri.scheme.equals("file", ignoreCase = true)) {
      val file = uri.path?.let { java.io.File(it) }?.takeIf { it.isFile && it.canRead() } ?: return@runCatching null
      if (file.length() == 0L) return@runCatching null
    }
    val imageBytes = if (uri.scheme.equals("data", ignoreCase = true)) {
      val header = wallpaperUri.substringBefore(',')
      if (!header.startsWith("data:image/", ignoreCase = true) || !header.endsWith(";base64", ignoreCase = true)) {
        return@runCatching null
      }
      val encoded = wallpaperUri.substringAfter(',', "")
      if (encoded.isEmpty() || encoded.length > MAX_WALLPAPER_ENCODED_LENGTH) return@runCatching null
      android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
    } else null
    fun decode(options: BitmapFactory.Options): Bitmap? =
      if (imageBytes != null) {
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
      } else if (uri.scheme.equals("file", ignoreCase = true)) {
        BitmapFactory.decodeFile(uri.path, options)
      } else {
        context.contentResolver.openInputStream(uri)?.use { stream ->
          BitmapFactory.decodeStream(stream, null, options)
        }
      }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_WALLPAPER_DIMENSION_PX) {
      sampleSize *= 2
    }
    decode(BitmapFactory.Options().apply { inSampleSize = sampleSize })
  }.getOrNull()

private const val WALLPAPER_DATA_PREFIX = "data:image/png;base64,"
private const val MAX_WALLPAPER_ENCODED_LENGTH = 40 * 1024 * 1024
private const val MAX_WALLPAPER_DIMENSION_PX = 2560
private const val WALLPAPER_RECYCLE_DELAY_MS = 120L
