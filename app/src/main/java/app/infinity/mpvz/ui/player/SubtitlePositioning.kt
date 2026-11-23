/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import app.infinity.mpvz.preferences.SubtitlesPreferences
import org.koin.core.context.GlobalContext
import kotlin.math.roundToInt

private const val MIN_SUBTITLE_POSITION = 0
private const val MAX_SUBTITLE_POSITION = 150
private const val SECONDARY_SUBTITLE_POSITION_OFFSET = 10

internal enum class SubtitleLane {
  Primary,
  Secondary,
}

private val subtitlesPreferences by lazy {
  GlobalContext.get().get<SubtitlesPreferences>()
}

private val HTML_TAG_REGEX = Regex("<[^>]*>")
private val ASS_TAG_REGEX = Regex("[{][^}]*[}]")

fun clampSubtitlePosition(position: Int): Int = position.coerceIn(MIN_SUBTITLE_POSITION, MAX_SUBTITLE_POSITION)

/**
 * Estimates subtitle hitbox bounds (lowerBound, upperBound) relative to subtitleScreenY.
 * Accounts for sub-text content, font size, sub-scale, and screen width to handle
 * multi-line wrapping in both portrait and landscape.
 */
internal fun getSubtitleHitboxBounds(
  screenWidth: Float,
  screenHeight: Float,
  lane: SubtitleLane = SubtitleLane.Primary,
): Pair<Float, Float> {
  val isSecondary = lane == SubtitleLane.Secondary
  val subScaleProperty = if (isSecondary) "secondary-sub-scale" else "sub-scale"
  val subScale = PlaybackSession.getPropertyFloat(subScaleProperty)
    ?: if (isSecondary) {
      subtitlesPreferences.secondarySubScale.get().takeIf { it > 0f } ?: subtitlesPreferences.subScale.get()
    } else {
      subtitlesPreferences.subScale.get()
    }
  val fontSize = (PlaybackSession.getPropertyInt("sub-font-size") ?: subtitlesPreferences.fontSize.get()).toFloat()
  val scaleMultiplier = subScale.coerceIn(0.4f, 3.0f)

  // Estimate per-line height in screen pixels.
  // sub-font-size is in "arbitrary" units scaled relative to screen height (720 reference).
  val lineHeightPx = (fontSize / 720f) * screenHeight * scaleMultiplier * 1.3f

  // Estimate how many lines the subtitle actually occupies
  val subTextProperty = if (isSecondary) "secondary-sub-text" else "sub-text"
  val subText = PlaybackSession.getPropertyString(subTextProperty) ?: ""
  val estimatedLines =
    if (subText.isNotEmpty()) {
      // Count explicit newlines first
      val explicitLines = subText.split("\n")

      // Estimate wrapping per explicit line based on available width
      // Subtitles typically use ~80% of screen width (sub-margin-x on each side)
      val marginProperty = if (isSecondary) "secondary-sub-margin-x" else "sub-margin-x"
      val subMarginX =
        (PlaybackSession.getPropertyInt(marginProperty) ?: PlaybackSession.getPropertyInt("sub-margin-x") ?: 25).toFloat()
      val availableWidth = screenWidth * (1f - 2f * subMarginX / screenWidth.coerceAtLeast(1f))

      // Estimate character width: roughly fontSize * scale * 0.55 (typical char width ratio)
      val charWidthPx = (fontSize / 720f) * screenHeight * scaleMultiplier * 0.55f
      val charsPerLine = if (charWidthPx > 0f) (availableWidth / charWidthPx).toInt().coerceAtLeast(1) else 40

      var totalLines = 0
      for (line in explicitLines) {
        val stripped = line.replace(HTML_TAG_REGEX, "").replace(ASS_TAG_REGEX, "")
        totalLines +=
          if (stripped.isEmpty()) 1 else ((stripped.length + charsPerLine - 1) / charsPerLine).coerceAtLeast(1)
      }
      totalLines.coerceAtLeast(1)
    } else {
      // No text available, assume a reasonable default
      2
    }

  // Subtitle text grows upward from the sub-pos anchor point (bottom-anchored primary
  // subs). Secondary subs are top-anchored in mpv, so their text grows downward; give
  // the secondary lane a symmetric hitbox so touches above and below the anchor both hit.
  val estimatedSubtitleHeight = lineHeightPx * estimatedLines
  val lowerBound: Float
  val upperBound: Float
  if (isSecondary) {
    lowerBound = -(estimatedSubtitleHeight + 80f * scaleMultiplier).coerceAtLeast(200f * scaleMultiplier)
    upperBound = -lowerBound
  } else {
    // Lower bound: small region below the anchor (padding for touch imprecision)
    lowerBound = -50f * scaleMultiplier
    // Upper bound: covers the full estimated subtitle height + padding
    upperBound = (estimatedSubtitleHeight + 80f * scaleMultiplier).coerceAtLeast(200f * scaleMultiplier)
  }

  return Pair(lowerBound, upperBound)
}

internal fun estimateSubtitleHorizontalHitboxBounds(
  text: String,
  fontSize: Float,
  subScale: Float,
  screenWidth: Float,
  screenHeight: Float,
  marginX: Float,
): Pair<Float, Float> {
  if (screenWidth <= 0f || screenHeight <= 0f) return 0f to screenWidth.coerceAtLeast(0f)
  val scale = subScale.coerceIn(0.4f, 3.0f)
  val availableWidth = (screenWidth - 2f * marginX.coerceAtLeast(0f)).coerceIn(screenWidth * 0.25f, screenWidth)
  val characterWidth = (fontSize / 720f) * screenHeight * scale * 0.55f
  val charsPerLine = if (characterWidth > 0f) (availableWidth / characterWidth).toInt().coerceAtLeast(1) else 40
  val strippedLines = text.split("\n").map { it.replace(HTML_TAG_REGEX, "").replace(ASS_TAG_REGEX, "") }
  val longestVisibleLine = strippedLines.maxOfOrNull { it.length } ?: 0
  val visibleCharacters = minOf(longestVisibleLine, charsPerLine)
  val minimumWidth = screenWidth * 0.2f
  val estimatedTextWidth = if (text.isBlank()) screenWidth * 0.6f else visibleCharacters * characterWidth
  val hitboxWidth = (estimatedTextWidth + 48f * scale).coerceIn(minimumWidth, availableWidth)
  val left = (screenWidth - hitboxWidth) / 2f
  return left to (left + hitboxWidth)
}

internal fun getSubtitleHorizontalHitboxBounds(
  screenWidth: Float,
  screenHeight: Float,
  lane: SubtitleLane,
): Pair<Float, Float> {
  val isSecondary = lane == SubtitleLane.Secondary
  val scaleProperty = if (isSecondary) "secondary-sub-scale" else "sub-scale"
  val scale =
    PlaybackSession.getPropertyFloat(scaleProperty)
      ?: if (isSecondary) {
        subtitlesPreferences.secondarySubScale.get().takeIf { it > 0f } ?: subtitlesPreferences.subScale.get()
      } else {
        subtitlesPreferences.subScale.get()
      }
  val fontSize = (PlaybackSession.getPropertyInt("sub-font-size") ?: subtitlesPreferences.fontSize.get()).toFloat()
  val textProperty = if (isSecondary) "secondary-sub-text" else "sub-text"
  val text = PlaybackSession.getPropertyString(textProperty).orEmpty()
  val marginProperty = if (isSecondary) "secondary-sub-margin-x" else "sub-margin-x"
  val marginX =
    (PlaybackSession.getPropertyInt(marginProperty) ?: PlaybackSession.getPropertyInt("sub-margin-x") ?: 25).toFloat()
  return estimateSubtitleHorizontalHitboxBounds(text, fontSize, scale, screenWidth, screenHeight, marginX)
}

internal fun isWithinSubtitleTouchBounds(
  touchX: Float,
  touchY: Float,
  subtitleAnchorY: Float,
  horizontalBounds: Pair<Float, Float>,
  verticalBounds: Pair<Float, Float>,
): Boolean =
  touchX in horizontalBounds.first..horizontalBounds.second &&
    (subtitleAnchorY - touchY) in verticalBounds.first..verticalBounds.second

internal fun resolveSubtitleLaneForTouch(
  primaryHit: Boolean,
  secondaryHit: Boolean,
  primaryAnchorDistance: Float,
  secondaryAnchorDistance: Float,
): SubtitleLane? =
  when {
    primaryHit && secondaryHit ->
      if (secondaryAnchorDistance < primaryAnchorDistance) SubtitleLane.Secondary else SubtitleLane.Primary
    primaryHit -> SubtitleLane.Primary
    secondaryHit -> SubtitleLane.Secondary
    else -> null
  }

fun calculateSecondarySubtitlePosition(
  primaryPosition: Int,
  screenWidth: Float? = null,
  screenHeight: Float? = null,
): Int {
  val primary = clampSubtitlePosition(primaryPosition)

  val width =
    screenWidth ?: PlaybackSession.getPropertyInt("osd-width")?.toFloat()
      ?: GlobalContext
        .get()
        .get<android.content.Context>()
        .resources.displayMetrics.widthPixels
        .toFloat()
  val height =
    screenHeight ?: PlaybackSession.getPropertyInt("osd-height")?.toFloat()
      ?: GlobalContext
        .get()
        .get<android.content.Context>()
        .resources.displayMetrics.heightPixels
        .toFloat()

  // Calculate the hitbox of the primary subtitle
  val (_, upperBound) = getSubtitleHitboxBounds(width, height)

  // Convert the hitbox height (pixels) to a percentage of the screen/OSD height
  val offsetPercent = (upperBound / height) * 100f

  // Dynamic offset, clamped to a reasonable range
  val offset = offsetPercent.roundToInt().coerceIn(8, 50)

  val abovePrimary = primary - offset

  return if (abovePrimary >= MIN_SUBTITLE_POSITION) {
    abovePrimary
  } else {
    (primary + offset).coerceIn(MIN_SUBTITLE_POSITION, MAX_SUBTITLE_POSITION)
  }
}

internal fun resolveSecondarySubtitlePosition(
  automaticPosition: Int,
  manualPosition: Int,
): Int =
  if (manualPosition >= MIN_SUBTITLE_POSITION) {
    clampSubtitlePosition(manualPosition)
  } else {
    clampSubtitlePosition(automaticPosition)
  }

fun isSecondarySubtitleActive(): Boolean = getTrackSelectionId("secondary-sid") > 0

fun subtitleAssOverrideValue(forceAssOverride: Boolean): String = if (forceAssOverride) "force" else "no"

fun applySubtitleOverrides(forceAssOverride: Boolean) {
  val overrideValue = subtitleAssOverrideValue(forceAssOverride)
  PlaybackSession.setPropertyString("sub-ass-override", overrideValue)
  PlaybackSession.setPropertyString("secondary-sub-ass-override", overrideValue)
}

fun applySubtitlePositions(
  primaryPosition: Int,
  screenWidth: Float? = null,
  screenHeight: Float? = null,
  secondaryPositionOverride: Int? = null,
) {
  val primary = clampSubtitlePosition(primaryPosition)
  PlaybackSession.setPropertyInt("sub-pos", primary)

  // Retrieve OSD or display dimensions as fallbacks if null
  val width =
    screenWidth ?: PlaybackSession.getPropertyInt("osd-width")?.toFloat()
      ?: GlobalContext
        .get()
        .get<android.content.Context>()
        .resources.displayMetrics.widthPixels
        .toFloat()
  val height =
    screenHeight ?: PlaybackSession.getPropertyInt("osd-height")?.toFloat()
      ?: GlobalContext
        .get()
        .get<android.content.Context>()
        .resources.displayMetrics.heightPixels
        .toFloat()

  val automaticPosition = calculateSecondarySubtitlePosition(primary, width, height)
  val manualPosition = subtitlesPreferences.secondarySubPos.get()
  PlaybackSession.setPropertyInt(
    "secondary-sub-pos",
    secondaryPositionOverride?.let(::clampSubtitlePosition)
      ?: resolveSecondarySubtitlePosition(automaticPosition, manualPosition),
  )
}

fun applySubtitleLayout(
  primaryPosition: Int,
  forceAssOverride: Boolean,
  screenWidth: Float? = null,
  screenHeight: Float? = null,
) {
  applySubtitleOverrides(forceAssOverride)
  applySubtitlePositions(primaryPosition, screenWidth, screenHeight)
}
