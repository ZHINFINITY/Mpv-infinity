/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.domain.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.File

object EmbeddedArtworkCandidates {
  private val artworkExtensions = listOf("jpg", "jpeg", "png", "webp")
  private val genericArtworkNames = listOf("cover", "folder", "poster", "thumbnail")

  fun forVideoPath(path: String): List<String> {
    if (path.isBlank() || path.isRemoteOrOpaqueUri()) return emptyList()
    val normalizedPath = path.replace('\\', '/')
    val parent =
      normalizedPath.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotBlank() }
        ?: return emptyList()
    val fileName = normalizedPath.substringAfterLast('/')
    val baseName =
      fileName.substringBeforeLast('.', missingDelimiterValue = fileName).takeIf { it.isNotBlank() }
        ?: return emptyList()

    return buildList {
      artworkExtensions.forEach { extension ->
        add("$parent/$baseName.$extension")
      }
      artworkExtensions.forEach { extension ->
        add("$parent/$baseName.cover.$extension")
        add("$parent/$baseName-cover.$extension")
      }
      genericArtworkNames.forEach { name ->
        artworkExtensions.forEach { extension ->
          add("$parent/$name.$extension")
        }
      }
    }.distinct()
  }

  private fun String.isRemoteOrOpaqueUri(): Boolean =
    startsWith("http://", ignoreCase = true) ||
      startsWith("https://", ignoreCase = true) ||
      startsWith("rtmp://", ignoreCase = true) ||
      startsWith("rtsp://", ignoreCase = true) ||
      startsWith("ftp://", ignoreCase = true) ||
      startsWith("sftp://", ignoreCase = true) ||
      startsWith("smb://", ignoreCase = true) ||
      startsWith("content://", ignoreCase = true)
}

internal object EmbeddedArtworkResolver {
  private const val ArtworkMaxEdge = 1024

  private fun decodeBytesSampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
      inSampleSize = ThumbnailDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight, ArtworkMaxEdge)
      inPreferredConfig = Bitmap.Config.ARGB_8888
    })
  }

  private fun decodeFileSampled(path: String): Bitmap? =
    runCatching { java.io.File(path).inputStream().use(ThumbnailDecodePolicy::readEncodedImage) }
      .getOrNull()?.let(::decodeBytesSampled)

  private fun decodeStreamSampled(input: java.io.InputStream): Bitmap? =
    ThumbnailDecodePolicy.readEncodedImage(input)?.let(::decodeBytesSampled)

  fun decodeArtworkUri(
    context: Context,
    artworkUri: String?,
  ): Bitmap? {
    if (artworkUri.isNullOrBlank()) return null
    app.infinity.mpvz.presentation.components.RemoteImageLoader.getFromMemory(artworkUri)?.let { return it }
    val uri = Uri.parse(artworkUri)
    return runCatching {
      val decoded =
        when (uri.scheme?.lowercase()) {
          null, "" -> decodeFileSampled(artworkUri)
          "file" -> uri.path?.let(::decodeFileSampled)
          "content", "android.resource" ->
            context.contentResolver.openInputStream(uri)?.use(::decodeStreamSampled)
          "http", "https" -> {
            val connection = (java.net.URL(artworkUri).openConnection() as java.net.HttpURLConnection).apply {
              connectTimeout = 8000
              readTimeout = 8000
              instanceFollowRedirects = true
              setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MpvInfinity")
            }
            connection.inputStream.use(::decodeStreamSampled)
          }
          else -> null
        }
      decoded?.also {
        app.infinity.mpvz.presentation.components.RemoteImageLoader.putInMemory(artworkUri, it)
      }
    }.getOrNull()
  }

  fun decodeEmbeddedArtwork(
    videoPath: String?,
    retriever: MediaMetadataRetriever,
  ): Bitmap? =
    decodeRetrieverArtwork(retriever)
      ?: MatroskaEmbeddedArtworkExtractor.decode(videoPath)
      ?: decodeSidecar(videoPath)

  fun decodeSidecar(videoPath: String?): Bitmap? =
    videoPath
      ?.let(EmbeddedArtworkCandidates::forVideoPath)
      ?.asSequence()
      ?.map(::File)
      ?.firstNotNullOfOrNull { candidate ->
        candidate
          .takeIf { it.isFile && it.canRead() }
          ?.let { decodeFileSampled(it.path) }
      }

  fun decodeRetrieverArtwork(retriever: MediaMetadataRetriever): Bitmap? {
    retriever.embeddedPicture
      ?.takeIf { it.isNotEmpty() }
      ?.let { bytes -> decodeBytesSampled(bytes) }
      ?.let { return it }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      runCatching { retriever.getPrimaryImage() }
        .getOrNull()
        ?.let { return it }
    }

    return null
  }
}
