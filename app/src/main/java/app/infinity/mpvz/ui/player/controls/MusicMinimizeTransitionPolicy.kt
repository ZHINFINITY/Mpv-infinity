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
  /**
   * Settle physics. Every settle (finger release, tap, Back) is the same critically damped
   * spring, so the motion never bounces and a release keeps the finger's speed instead of
   * restarting from rest. 650 lands a full open or close in about a quarter second.
   */
  const val SETTLE_DAMPING_RATIO = 1f
  const val SETTLE_STIFFNESS = 650f
  /** Programmatic runs (tap, Back, minimize button) start moving on their very first frame. */
  const val PROGRAMMATIC_KICK_PROGRESS_PER_SECOND = 3.5f
  /** Fast flicks are capped so a stray 20 000 px/s sample cannot teleport the page. */
  const val MAX_SETTLE_PROGRESS_PER_SECOND = 12f
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

  /**
   * Distance the page's top edge travels between full and mini. Dragging by exactly this many
   * pixels moves the edge by the same amount, so the page stays locked under the finger.
   * Falls back to the page height when the mini player has not been measured.
   */
  fun dragTravelPx(pageTop: Float, miniTop: Float?, pageHeight: Float): Float {
    val fallback = if (pageHeight.isFinite() && pageHeight > 0f) pageHeight else 1f
    if (miniTop == null || !miniTop.isFinite() || !pageTop.isFinite()) return fallback
    val travel = miniTop - pageTop
    return if (travel >= fallback * .3f) travel else fallback
  }

  /**
   * Initial spring velocity (miniProgress per second) for a settle from [start] to [target].
   * A finger release carries its own velocity; a programmatic run gets a small kick toward the
   * target so it never starts with a slow ease-in. A release velocity pointing away from the
   * target (a flick that the position rule overruled) is dropped rather than fought.
   */
  fun settleInitialVelocity(
    start: Float,
    target: Float,
    programmatic: Boolean,
    releaseVelocityYPxPerSecond: Float,
    travelPx: Float,
  ): Float {
    val direction = when {
      target > start -> 1f
      target < start -> -1f
      else -> return 0f
    }
    if (programmatic) return direction * PROGRAMMATIC_KICK_PROGRESS_PER_SECOND
    if (!releaseVelocityYPxPerSecond.isFinite() || !travelPx.isFinite() || travelPx <= 0f) return 0f
    val v = (releaseVelocityYPxPerSecond / travelPx)
      .coerceIn(-MAX_SETTLE_PROGRESS_PER_SECOND, MAX_SETTLE_PROGRESS_PER_SECOND)
    return if (v * direction > 0f) v else 0f
  }

  private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  /**
   * Text, buttons and the seek bar leave early, so mid-flight the card is just colour and
   * cover instead of a shrinking copy of every control.
   */
  fun chromeAlpha(minimizeProgress: Float): Float =
    if (!minimizeProgress.isFinite()) 1f else 1f - smoothstep(0f, .4f, minimizeProgress)

  /**
   * The palette card itself stays solid for most of the flight and dissolves only in the last
   * quarter, cross-fading with the real mini player that sits at the same rectangle.
   */
  fun surfaceAlpha(minimizeProgress: Float): Float =
    if (!minimizeProgress.isFinite()) 1f else 1f - smoothstep(.75f, 1f, minimizeProgress)

  const val FULL_COVER_RADIUS_DP = 32f
  const val MINI_COVER_RADIUS_DP = 8f

  /** Corner radius of the flying cover, in on-screen dp. */
  fun coverRadiusDp(minimizeProgress: Float): Float {
    val p = if (minimizeProgress.isFinite()) minimizeProgress.coerceIn(0f, 1f) else 0f
    return FULL_COVER_RADIUS_DP + (MINI_COVER_RADIUS_DP - FULL_COVER_RADIUS_DP) * p
  }

  /**
   * Layer for the single flying cover, drawn above the page (not inside its scaled layer).
   * [measuredFull] is the cover's unwarped window rectangle; width and height scale separately
   * only if the target is not the same shape, which for square covers never happens.
   */
  fun flyingCoverLayer(frame: Frame, measuredFull: Rect): Layer {
    if (measuredFull.width <= 0f || measuredFull.height <= 0f) return Layer(0f, 0f, 1f, 1f)
    return Layer(
      frame.artwork.left - measuredFull.left,
      frame.artwork.top - measuredFull.top,
      frame.artwork.width / measuredFull.width,
      frame.artwork.height / measuredFull.height,
    )
  }

  fun backgroundAlpha(settledOpen: Boolean, inTransition: Boolean): Float = if (settledOpen && !inTransition) 1f else 0f

  /**
   * After every finger lifts, a page left strictly between full and mini with no settle in
   * flight is a dropped gesture (cancelled nested child). It must settle, never stay frozen.
   */
  fun needsReleaseSettle(settleInFlight: Boolean, miniProgress: Float): Boolean =
    !settleInFlight && miniProgress.isFinite() && miniProgress > 0f && miniProgress < 1f

  fun shouldTranslucent(isAudio: Boolean, api: Int): Boolean = isAudio && api >= 30

  fun useSlideFallback(anchor: MiniPlayerTarget?, currentWindowWidth: Float, currentWindowHeight: Float, translucencyAvailable: Boolean = true): Boolean =
    !translucencyAvailable || anchor == null || !currentWindowWidth.isFinite() || !currentWindowHeight.isFinite() ||
      abs(anchor.viewportWidth - currentWindowWidth) > currentWindowWidth * .05f ||
      (anchor.viewportHeight > 0f && abs(anchor.viewportHeight - currentWindowHeight) > currentWindowHeight * .05f)

  enum class RootBackgroundAction { Clear, Restore, Keep }

  /**
   * What to do with PlayerActivity's opaque root background when window translucency changes.
   * See-through clears it once (remembering it); opaque restores it once; otherwise nothing.
   */
  fun rootBackgroundAction(seeThrough: Boolean, currentlyCleared: Boolean): RootBackgroundAction =
    when {
      seeThrough && !currentlyCleared -> RootBackgroundAction.Clear
      !seeThrough && currentlyCleared -> RootBackgroundAction.Restore
      else -> RootBackgroundAction.Keep
    }
}
