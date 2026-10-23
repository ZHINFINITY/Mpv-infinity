/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls

import kotlin.math.abs

/** Pure, device-free mechanics for the audio full-player/miniplayer handoff. */
object MusicMinimizeTransitionPolicy {
  const val SETTLE_OPEN_DURATION_MS = 220
  const val CLOSE_DURATION_MS = 300
  const val OPEN_DURATION_MS = 400
  const val CLOSE_VELOCITY_PAGE_PER_SECOND = 1f
  const val CLOSE_VALUE = 0.7f

  /** transitionValue is 1=open and 0=minimized; downward drag decreases it linearly. */
  fun dragValue(start: Float, dragDeltaY: Float, pageHeight: Float): Float {
    if (!start.isFinite() || !dragDeltaY.isFinite() || !pageHeight.isFinite() || pageHeight <= 0f) return start
    return (start - dragDeltaY / pageHeight).coerceIn(0f, 1f)
  }

  fun settleTarget(value: Float, velocityPageHeightsPerSecond: Float, movingDown: Boolean): Float {
    if (!value.isFinite() || !velocityPageHeightsPerSecond.isFinite()) return 1f
    val close = movingDown && (velocityPageHeightsPerSecond > CLOSE_VELOCITY_PAGE_PER_SECOND || value < CLOSE_VALUE)
    return if (close) 0f else 1f
  }

  fun easeOutCubic(start: Float, end: Float, fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    return start + (end - start) * (1f - (1f - t) * (1f - t) * (1f - t))
  }

  fun easeInOutCubic(fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    return if (t < .5f) 4f * t * t * t else 1f - ((-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f)) / 2f
  }

  fun currentAnchor(viewportWidth: Float, currentWindowWidth: Float, currentWindowHeight: Float = 0f): MiniPlayerTarget? {
    if (!viewportWidth.isFinite() || !currentWindowWidth.isFinite() || currentWindowWidth <= 0f) return null
    val anchor = MiniPlayerTransitionAnchor.forViewport(currentWindowWidth) ?: return null
    val widthMismatch = abs(anchor.viewportWidth - currentWindowWidth) > .05f * currentWindowWidth
    val heightMismatch = currentWindowHeight > 0f && anchor.viewportHeight > 0f &&
      abs(anchor.viewportHeight - currentWindowHeight) > .05f * currentWindowHeight
    return if (widthMismatch || heightMismatch) null else anchor
  }

  data class Rect(val left: Float, val top: Float, val width: Float, val height: Float)

  /**
   * One frame of the page/artwork handoff. [minimizeProgress] is 0 = fully open, 1 = landed
   * on the mini player; the mapping is linear so a drag never jumps away from the finger
   * (programmatic runs put their easing in the animation's time curve instead).
   */
  data class Frame(
    /** Page panel rectangle in window coordinates. */
    val page: Rect,
    val cornerRadiusDp: Float,
    /** Uniform content scale applied from the page's top-left (page.width / screen.width). */
    val contentScale: Float,
    val contentAlpha: Float,
    /** Clip size in the page layer's own (pre-scale) coordinates, so after scaling it is [page]. */
    val clipWidth: Float,
    val clipHeight: Float,
    /** Corner radius in pre-scale layer coordinates (dp), so the drawn radius equals [cornerRadiusDp]. */
    val clipCornerRadiusDp: Float,
    /** Cover artwork rectangle in window coordinates and its corner radius. */
    val artwork: Rect,
    val artworkRadiusDp: Float,
  )

  const val PAGE_END_RADIUS_DP = 28f
  const val ARTWORK_START_RADIUS_DP = 12f
  const val ARTWORK_END_RADIUS_DP = 6f

  fun frame(minimizeProgress: Float, screen: Rect, mini: Rect, fullArtwork: Rect, miniArtwork: Rect): Frame {
    val p = if (minimizeProgress.isFinite()) minimizeProgress.coerceIn(0f, 1f) else 0f
    fun lerp(a: Float, b: Float) = a + (b - a) * p
    fun lerp(a: Rect, b: Rect) = Rect(lerp(a.left, b.left), lerp(a.top, b.top), lerp(a.width, b.width), lerp(a.height, b.height))
    val page = lerp(screen, mini)
    val scale = if (screen.width > 0f) (page.width / screen.width).coerceIn(0.01f, 1f) else 1f
    val radius = PAGE_END_RADIUS_DP * p
    val fade = ((p - .15f) / .70f).coerceIn(0f, 1f)
    return Frame(
      page = page,
      cornerRadiusDp = radius,
      contentScale = scale,
      contentAlpha = 1f - fade,
      clipWidth = page.width / scale,
      clipHeight = page.height / scale,
      clipCornerRadiusDp = radius / scale,
      artwork = lerp(fullArtwork, miniArtwork),
      artworkRadiusDp = lerp(ARTWORK_START_RADIUS_DP, ARTWORK_END_RADIUS_DP),
    )
  }

  /** graphicsLayer values (top-left origin) for the page whose untransformed origin is [origin]. */
  data class Layer(val translationX: Float, val translationY: Float, val scaleX: Float, val scaleY: Float)

  fun pageLayer(frame: Frame, originLeft: Float, originTop: Float): Layer =
    Layer(frame.page.left - originLeft, frame.page.top - originTop, frame.contentScale, frame.contentScale)

  /**
   * Child layer for the cover inside the scaled page: lands the cover on [Frame.artwork] in window
   * coordinates despite the parent's uniform scale. [measuredFull] is the cover's unwarped window
   * rectangle measured while the player was fully open.
   */
  fun artworkLayer(frame: Frame, originLeft: Float, originTop: Float, measuredFull: Rect): Layer {
    val parent = pageLayer(frame, originLeft, originTop)
    val s = parent.scaleX
    if (s <= 0f || measuredFull.width <= 0f || measuredFull.height <= 0f) return Layer(0f, 0f, 1f, 1f)
    val tx = (frame.artwork.left - originLeft - parent.translationX) / s - (measuredFull.left - originLeft)
    val ty = (frame.artwork.top - originTop - parent.translationY) / s - (measuredFull.top - originTop)
    return Layer(tx, ty, frame.artwork.width / (measuredFull.width * s), frame.artwork.height / (measuredFull.height * s))
  }

  /** Release decision in the controls' miniProgress convention (0 open, 1 minimized). */
  fun releaseTargetMiniProgress(miniProgress: Float, velocityYPxPerSecond: Float, pageHeightPx: Float, movingDown: Boolean): Float {
    val pageVelocity = if (pageHeightPx > 0f && velocityYPxPerSecond.isFinite()) velocityYPxPerSecond / pageHeightPx else 0f
    return 1f - settleTarget(1f - miniProgress, pageVelocity, movingDown)
  }

  /** Animation duration for a settle toward [targetMiniProgress]. */
  fun settleDurationMs(targetMiniProgress: Float, programmatic: Boolean): Int = when {
    targetMiniProgress >= 1f -> CLOSE_DURATION_MS
    programmatic -> OPEN_DURATION_MS
    else -> SETTLE_OPEN_DURATION_MS
  }

  fun backgroundAlpha(settledOpen: Boolean, inTransition: Boolean): Float = if (settledOpen && !inTransition) 1f else 0f

  fun shouldTranslucent(isAudio: Boolean, api: Int): Boolean = isAudio && api >= 30

  fun useSlideFallback(anchor: MiniPlayerTarget?, currentWindowWidth: Float, currentWindowHeight: Float, translucencyAvailable: Boolean = true): Boolean =
    !translucencyAvailable || anchor == null || !currentWindowWidth.isFinite() || !currentWindowHeight.isFinite() ||
      abs(anchor.viewportWidth - currentWindowWidth) > currentWindowWidth * .05f ||
      (anchor.viewportHeight > 0f && abs(anchor.viewportHeight - currentWindowHeight) > currentWindowHeight * .05f)
}
