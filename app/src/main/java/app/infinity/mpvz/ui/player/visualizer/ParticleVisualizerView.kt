/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.visualizer

import android.content.Context
import android.view.MotionEvent

internal class ParticleVisualizerView(
  context: Context,
  features: AudioFeatures,
  palette: VisualizerPalette,
  reducedMotion: Boolean = false,
) : VisualizerTextureView(context), PaletteConsumer {
  private val renderer = ParticleFeedbackRenderer(
    context.applicationContext,
    features,
    palette,
    reducedMotion,
  )

  init {
    setRenderer(renderer)
    renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
    isClickable = true
  }

  override fun updatePalette(value: VisualizerPalette) {
    renderer.updatePalette(value)
  }

  fun setReducedMotion(reducedMotion: Boolean) {
    renderer.setReducedMotion(reducedMotion)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        parent?.requestDisallowInterceptTouchEvent(true)
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
