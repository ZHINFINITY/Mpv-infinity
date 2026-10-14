/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.domain.media.model

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class Video(
  val id: Long,
  val title: String,
  val displayName: String,
  val path: String,
  val uri: Uri,
  val duration: Long,
  val durationFormatted: String,
  val size: Long,
  val sizeFormatted: String,
  val dateModified: Long,
  val dateAdded: Long,
  val mimeType: String,
  val bucketId: String,
  val bucketDisplayName: String,
  val width: Int,
  val height: Int,
  val fps: Float,
  val resolution: String,
  val hasEmbeddedSubtitles: Boolean = false,
  val subtitleCodec: String = "",
  val videoCodec: String = "",
  val videoCodecMimeType: String = "",
  val isAudio: Boolean = false,
)
