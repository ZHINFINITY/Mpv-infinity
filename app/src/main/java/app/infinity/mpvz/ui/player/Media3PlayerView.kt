/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** A Media3 video surface with Mpv∞'s black, control-free presentation defaults. */
class Media3PlayerView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : PlayerView(context, attrs) {
  private var subtitleSafeForCrop = false
  private var subtitleOriginalParent: ViewGroup? = null
  private var subtitleOriginalIndex = -1
  private var subtitleOriginalLayoutParams: ViewGroup.LayoutParams? = null

  init {
    useController = false
    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    setKeepContentOnPlayerReset(true)
    setShutterBackgroundColor(Color.BLACK)
    setBackgroundColor(Color.BLACK)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (subtitleSafeForCrop) {
      post { updateSubtitleHost() }
    }
  }

  /**
   * Keeps ordinary subtitles readable when Crop mode zooms the video content.
   *
   * PlayerView places SubtitleView inside its AspectRatioFrameLayout. In RESIZE_MODE_ZOOM that
   * frame is the same cropped/zoomed content viewport, so subtitles at the video edges can be
   * clipped along with the video. For Crop only, move the existing SubtitleView to the surrounding
   * player root. The controller still owns and updates the same SubtitleView, while the Activity's
   * zoom/translation remains limited to this video view. Fit and Stretch restore the original
   * PlayerView hierarchy so positioned subtitles retain their normal behavior there.
   */
  fun setSubtitleSafeForCrop(enabled: Boolean) {
    if (subtitleSafeForCrop == enabled) return
    subtitleSafeForCrop = enabled
    post { updateSubtitleHost() }
  }

  private fun updateSubtitleHost() {
    val subtitle = subtitleView ?: return
    if (!isAttachedToWindow) return
    if (subtitleSafeForCrop) {
      moveSubtitleOutsideContentFrame(subtitle)
    } else {
      restoreSubtitleToContentFrame(subtitle)
    }
  }

  private fun moveSubtitleOutsideContentFrame(subtitle: View) {
    val outerParent = parent as? ViewGroup ?: return
    val currentParent = subtitle.parent as? ViewGroup ?: return
    if (currentParent === outerParent) return

    subtitleOriginalParent = currentParent
    subtitleOriginalIndex = currentParent.indexOfChild(subtitle)
    subtitleOriginalLayoutParams = subtitle.layoutParams
    currentParent.removeView(subtitle)

    val insertIndex = (outerParent.indexOfChild(this) + 1).coerceAtLeast(0)
    val params = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT,
    )
    outerParent.addView(subtitle, insertIndex.coerceAtMost(outerParent.childCount), params)
    subtitle.clipToOutline = false
  }

  private fun restoreSubtitleToContentFrame(subtitle: View) {
    val originalParent = subtitleOriginalParent ?: return
    if (subtitle.parent === originalParent) return
    (subtitle.parent as? ViewGroup)?.removeView(subtitle)
    val params = subtitleOriginalLayoutParams ?: FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT,
    )
    val index = subtitleOriginalIndex.coerceIn(0, originalParent.childCount)
    originalParent.addView(subtitle, index, params)
  }
}
