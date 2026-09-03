/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.effect.Crop
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

/**
 * Clip exporter backed by Media3 Transformer.
 *
 * The player itself remains libmpv, but Clip export deliberately does not create a second mpv core.
 * Media3 uses Android MediaCodec for decoding/encoding and OpenGL for crop effects, avoiding
 * libmpv builds that do not expose encoding mode and fail during mpv_initialize().
 */
@UnstableApi
internal object Media3ClipExporter {
  private const val PROGRESS_POLL_MS = 150L

  suspend fun export(
    context: Context,
    source: String,
    output: String,
    startSeconds: Double,
    endSeconds: Double,
    crop: ClipCrop?,
    cropFrameWidth: Int,
    cropFrameHeight: Int,
    headers: Map<String, String>,
    onProgress: (Double) -> Unit,
  ): String? =
    withContext(Dispatchers.Main.immediate) {
      coroutineScope {
        File(output).delete()

        val completion = CompletableDeferred<String?>()
        val transformer = buildTransformer(context, headers, completion)

        val mediaItem =
          MediaItem.Builder()
            .setUri(toMediaUri(source))
            .setClippingConfiguration(
              MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs((startSeconds.coerceAtLeast(0.0) * 1000.0).roundToLong())
                .setEndPositionMs((endSeconds.coerceAtLeast(startSeconds) * 1000.0).roundToLong())
                .build(),
            ).build()

        val editedMediaItem =
          EditedMediaItem.Builder(mediaItem)
            .setEffects(buildEffects(crop, cropFrameWidth, cropFrameHeight))
            .build()

        transformer.start(editedMediaItem, output)

        val progressJob =
          launch {
            val holder = ProgressHolder()
            while (completion.isActive) {
              if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress((holder.progress / 100.0).coerceIn(0.0, 1.0))
              }
              delay(PROGRESS_POLL_MS)
            }
          }

        try {
          completion.await()
        } finally {
          progressJob.cancelAndJoin()
          if (completion.isActive) transformer.cancel()
        }
      }
    }

  private fun buildTransformer(
    context: Context,
    headers: Map<String, String>,
    completion: CompletableDeferred<String?>,
  ): Transformer {
    val httpFactory = DefaultHttpDataSource.Factory()
    headers.entries
      .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
      ?.value
      ?.takeIf { it.isNotBlank() }
      ?.let(httpFactory::setUserAgent)

    val requestProperties = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
    if (requestProperties.isNotEmpty()) {
      httpFactory.setDefaultRequestProperties(requestProperties)
    }

    val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
    val decoderFactory =
      DefaultDecoderFactory.Builder(context)
        .setEnableDecoderFallback(true)
        .build()
    val assetLoaderFactory =
      ExoPlayerAssetLoader.Factory(
        context,
        decoderFactory,
        Clock.DEFAULT,
        mediaSourceFactory,
      )

    val listener =
      object : Transformer.Listener {
        override fun onCompleted(
          composition: Composition,
          exportResult: ExportResult,
        ) {
          completion.complete(null)
        }

        override fun onError(
          composition: Composition,
          exportResult: ExportResult,
          exportException: ExportException,
        ) {
          completion.complete(
            exportException.message?.takeIf { it.isNotBlank() }
              ?: "Media3 clip export failed",
          )
        }
      }

    return Transformer.Builder(context)
      .setAssetLoaderFactory(assetLoaderFactory)
      .setVideoMimeType(MimeTypes.VIDEO_H264)
      .setAudioMimeType(MimeTypes.AUDIO_AAC)
      .addListener(listener)
      .build()
  }

  private fun buildEffects(
    crop: ClipCrop?,
    frameWidth: Int,
    frameHeight: Int,
  ): Effects {
    crop ?: return Effects.EMPTY
    if (crop.width <= 0 || crop.height <= 0 || frameWidth <= 0 || frameHeight <= 0) {
      return Effects.EMPTY
    }

    // CropSelectionView reports coordinates in the same oriented frame the user sees. Media3
    // applies the container's rotation metadata before GL effects, so these normalized coordinates
    // can be applied directly without an additional rotation transform.
    val width = frameWidth.toFloat()
    val height = frameHeight.toFloat()
    val left = (-1f + 2f * crop.x / width).coerceIn(-1f, 1f)
    val right = (-1f + 2f * (crop.x + crop.width) / width).coerceIn(-1f, 1f)
    val top = (1f - 2f * crop.y / height).coerceIn(-1f, 1f)
    val bottom = (1f - 2f * (crop.y + crop.height) / height).coerceIn(-1f, 1f)
    if (right <= left || top <= bottom) return Effects.EMPTY

    val videoEffects: List<Effect> = listOf(Crop(left, right, bottom, top))
    return Effects(emptyList(), videoEffects)
  }

  private fun toMediaUri(source: String): Uri {
    val parsed = Uri.parse(source)
    if (!parsed.scheme.isNullOrBlank()) return parsed
    return if (source.startsWith('/')) Uri.fromFile(File(source)) else parsed
  }
}
