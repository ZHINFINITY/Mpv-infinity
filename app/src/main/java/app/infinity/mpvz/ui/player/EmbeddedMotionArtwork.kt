/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** Strictly local embedded motion art; static bitmap callers remain the fallback. */
internal object EmbeddedMotionArtwork {
  const val MaxEncodedBytes = 8 * 1024 * 1024
  const val MaxDecodedEdge = 1024

  fun animatedBytes(bytes: ByteArray?): ByteArray? =
    bytes?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasSupportedAnimationSignature(it) }

  internal fun hasSupportedAnimationSignature(bytes: ByteArray?): Boolean {
    if (bytes == null || bytes.size < 21 || bytes.size > MaxEncodedBytes) return false
    val gif = bytes.asciiAt(0, "GIF87a") || bytes.asciiAt(0, "GIF89a")
    val webp = bytes.asciiAt(0, "RIFF") && bytes.asciiAt(8, "WEBP") &&
      bytes.asciiAt(12, "VP8X") && (bytes[20].toInt() and 0x02) != 0
    return gif || webp
  }

  private fun ByteArray.asciiAt(offset: Int, value: String): Boolean =
    offset >= 0 && size >= offset + value.length &&
      value.indices.all { this[offset + it].toInt() == value[it].code }
}

@Composable
internal fun rememberMotionArtworkDrawable(bytes: ByteArray?): AnimatedImageDrawable? {
  val drawable by produceState<AnimatedImageDrawable?>(null, bytes) {
    // Do not keep the previous track's drawable alive while the next embedded cover decodes.
    value = null
    if (bytes != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      value = withContext(Dispatchers.Default) {
        runCatching {
          ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val edge = maxOf(info.size.width, info.size.height)
            if (edge > EmbeddedMotionArtwork.MaxDecodedEdge) {
              decoder.setTargetSampleSize(
                (edge + EmbeddedMotionArtwork.MaxDecodedEdge - 1) / EmbeddedMotionArtwork.MaxDecodedEdge,
              )
            }
          } as? AnimatedImageDrawable
        }.getOrNull()
      }
    }
  }
  return drawable
}

@Composable
internal fun MotionArtworkImage(
  drawable: AnimatedImageDrawable,
  isPlaying: Boolean,
  modifier: Modifier = Modifier,
  fit: Boolean = false,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  AndroidView(
    modifier = modifier,
    factory = { context ->
      ImageView(context).apply {
        scaleType = if (fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        setImageDrawable(drawable)
      }
    },
    update = { view ->
      view.scaleType = if (fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
      if (view.drawable !== drawable) view.setImageDrawable(drawable)
    },
    onRelease = { it.setImageDrawable(null) },
  )
  DisposableEffect(drawable, lifecycleOwner, isPlaying) {
    val lifecycle = lifecycleOwner.lifecycle
    fun sync() {
      if (isPlaying && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        drawable.start()
      } else {
        drawable.stop()
      }
    }
    val observer = LifecycleEventObserver { _, _ -> sync() }
    lifecycle.addObserver(observer)
    sync()
    onDispose {
      lifecycle.removeObserver(observer)
      drawable.stop()
    }
  }
}
