/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.theme

import android.graphics.BitmapShader
import android.os.Build
import androidx.annotation.RequiresApi

/** Retains the browser lens effect and exposes refreshed effects for animated motion. */
internal class BrowserLensEffectHandle internal constructor(
  val effect: androidx.compose.ui.graphics.RenderEffect,
  private val motionUpdater: (Float) -> androidx.compose.ui.graphics.RenderEffect?,
) {
  fun updateMotion(value: Float): androidx.compose.ui.graphics.RenderEffect? = motionUpdater(value)
}

/**
 * Trampoline that never mentions [android.graphics.RuntimeShader]. ART verifies a
 * method when it first runs, so the API 33 class is only referenced from methods that
 * older devices never execute.
 */
internal object RuntimeShaderSupport {
  fun available(): Boolean = Build.VERSION.SDK_INT >= 33

  fun glassOpticsOrNull(): Any? {
    if (Build.VERSION.SDK_INT < 33) return null
    return glassOpticsApi33()
  }

  fun browserLensShaderOrNull(): Any? {
    if (Build.VERSION.SDK_INT < 33) return null
    return browserLensShaderApi33()
  }

  fun browserLensEffectOrNull(
    shader: Any?,
    paneWidth: Float,
    paneHeight: Float,
    radius: Float,
    bend: Float,
    motion: Float,
    frostPx: Float,
  ): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < 33 || shader == null) return null
    return browserLensEffectApi33(shader, paneWidth, paneHeight, radius, bend, motion, frostPx)
  }

  fun browserLensHandleOrNull(
    paneWidth: Float,
    paneHeight: Float,
    radius: Float,
    bend: Float,
    frostPx: Float,
  ): BrowserLensEffectHandle? {
    if (Build.VERSION.SDK_INT < 33) return null
    return browserLensHandleApi33(paneWidth, paneHeight, radius, bend, frostPx)
  }

  fun bindGlassOpticsOrNull(
    shader: Any?,
    sourceShader: BitmapShader,
    paint: android.graphics.Paint,
    paneWidth: Float,
    paneHeight: Float,
    originX: Float,
    originY: Float,
    sceneLeft: Float,
    sceneTop: Float,
    sceneWidth: Float,
    sceneHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
    radius: Float,
    bevel: Float,
    lightX: Float,
    lightY: Float,
    specular: Float,
    fresnel: Float,
    strength: Float,
    frostPx: Float,
  ): Boolean {
    if (Build.VERSION.SDK_INT < 33 || shader == null) return false
    return bindGlassOpticsApi33(
      shader, sourceShader, paint, paneWidth, paneHeight, originX, originY,
      sceneLeft, sceneTop, sceneWidth, sceneHeight, imageWidth, imageHeight,
      radius, bevel, lightX, lightY, specular, fresnel, strength, frostPx,
    )
  }

  @RequiresApi(33)
  private fun glassOpticsApi33(): Any? = AgslRuntimeShaders.glassOptics()

  @RequiresApi(33)
  private fun browserLensShaderApi33(): Any? = AgslRuntimeShaders.browserLens()

  @RequiresApi(33)
  private fun browserLensEffectApi33(
    shader: Any,
    paneWidth: Float,
    paneHeight: Float,
    radius: Float,
    bend: Float,
    motion: Float,
    frostPx: Float,
  ): androidx.compose.ui.graphics.RenderEffect? =
    AgslRuntimeShaders.browserLensEffect(shader, paneWidth, paneHeight, radius, bend, motion, frostPx)

  @RequiresApi(33)
  private fun browserLensHandleApi33(
    paneWidth: Float,
    paneHeight: Float,
    radius: Float,
    bend: Float,
    frostPx: Float,
  ): BrowserLensEffectHandle? =
    AgslRuntimeShaders.browserLensHandle(paneWidth, paneHeight, radius, bend, frostPx)

  @RequiresApi(33)
  private fun bindGlassOpticsApi33(
    shader: Any,
    sourceShader: BitmapShader,
    paint: android.graphics.Paint,
    paneWidth: Float,
    paneHeight: Float,
    originX: Float,
    originY: Float,
    sceneLeft: Float,
    sceneTop: Float,
    sceneWidth: Float,
    sceneHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
    radius: Float,
    bevel: Float,
    lightX: Float,
    lightY: Float,
    specular: Float,
    fresnel: Float,
    strength: Float,
    frostPx: Float,
  ): Boolean =
    AgslRuntimeShaders.bindGlassOptics(
      shader, sourceShader, paint, paneWidth, paneHeight, originX, originY,
      sceneLeft, sceneTop, sceneWidth, sceneHeight, imageWidth, imageHeight,
      radius, bevel, lightX, lightY, specular, fresnel, strength, frostPx,
    )
}
