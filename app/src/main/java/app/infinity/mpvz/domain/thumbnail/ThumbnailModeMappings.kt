/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.domain.thumbnail

import app.infinity.mpvz.preferences.ThumbnailMode
import kotlin.math.roundToInt

fun ThumbnailMode.toThumbnailStrategy(framePositionPercent: Float): ThumbnailStrategy =
  when (this) {
    ThumbnailMode.Smart -> ThumbnailStrategy.EmbeddedOrHybrid(0.33f)
    ThumbnailMode.FirstFrame -> ThumbnailStrategy.FirstFrame
    ThumbnailMode.FrameAtPosition ->
      ThumbnailStrategy.FrameAtPercentage((framePositionPercent / 100f).coerceIn(0f, 1f))
    ThumbnailMode.EmbeddedThumbnail -> ThumbnailStrategy.EmbeddedOrFirstFrame
  }

internal fun ThumbnailStrategy.prefersEmbeddedPicture(): Boolean =
  this is ThumbnailStrategy.EmbeddedOrFirstFrame || this is ThumbnailStrategy.EmbeddedOrHybrid

fun ThumbnailMode.thumbnailModeCacheKey(framePositionPercent: Float): String =
  when (this) {
    ThumbnailMode.Smart -> "Smart_embedded_v2"
    ThumbnailMode.FrameAtPosition ->
      "FrameAtPosition_${framePositionPercent.coerceIn(0f, 100f).roundToInt()}"
    ThumbnailMode.EmbeddedThumbnail -> "EmbeddedThumbnail_v2"
    else -> name
  }

sealed class ThumbnailStrategy {
  abstract val cacheKey: String

  data object FirstFrame : ThumbnailStrategy() {
    override val cacheKey: String = "first_frame"
  }

  data class FrameAtPercentage(
    val percentage: Float = 0.33f,
  ) : ThumbnailStrategy() {
    override val cacheKey: String = "frame_$percentage"
  }

  data class Hybrid(
    val percentage: Float = 0.33f,
  ) : ThumbnailStrategy() {
    override val cacheKey: String = "hybrid_$percentage"
  }

  data class EmbeddedOrHybrid(
    val percentage: Float = 0.33f,
  ) : ThumbnailStrategy() {
    override val cacheKey: String = "embedded_or_hybrid_$percentage"
  }

  data object EmbeddedOrFirstFrame : ThumbnailStrategy() {
    override val cacheKey: String = "embedded_or_first_frame"
  }
}
