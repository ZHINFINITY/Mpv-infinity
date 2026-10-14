/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.visualizer

import android.content.Context
import android.view.MotionEvent

internal class GalaxyVisualizerView(
  context: Context,
  features: AudioFeatures,
  palette: VisualizerPalette,
  reducedMotion: Boolean = false,
) : VisualizerTextureView(context),
  PaletteConsumer {
  private val galaxyRenderer =
    GalaxyRenderer(
      context.applicationContext,
      features,
      palette,
      reducedMotion,
    )

  private var previousX = 0f
  private var previousY = 0f

  init {
    setRenderer(galaxyRenderer)
    renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
    isClickable = true
  }

  override fun updatePalette(value: VisualizerPalette) {
    galaxyRenderer.updatePalette(value)
  }

  fun setReducedMotion(reducedMotion: Boolean) {
    galaxyRenderer.setReducedMotion(reducedMotion)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        previousX = event.x
        previousY = event.y
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
      }

      MotionEvent.ACTION_MOVE -> {
        if (event.pointerCount == 1) {
          val dx = event.x - previousX
          val dy = event.y - previousY
          previousX = event.x
          previousY = event.y
          galaxyRenderer.addTouchRotation(
            normalizedDx = dx / width.coerceAtLeast(1),
            normalizedDy = dy / height.coerceAtLeast(1),
          )
        }
        return true
      }

      MotionEvent.ACTION_UP -> {
        parent?.requestDisallowInterceptTouchEvent(false)
        performClick()
        return true
      }

      MotionEvent.ACTION_CANCEL -> {
        parent?.requestDisallowInterceptTouchEvent(false)
        return true
      }
    }
    return super.onTouchEvent(event)
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }
}
