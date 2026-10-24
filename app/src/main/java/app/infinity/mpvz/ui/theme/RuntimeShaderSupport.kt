/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.theme

import android.graphics.BitmapShader
import android.os.Build
import androidx.annotation.RequiresApi

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

  fun browserLensEffectOrNull(
    paneWidth: Float,
    paneHeight: Float,
    radius: Float,
    bend: Float,
    motion: Float,
    frostPx: Float,
  ): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < 33) return null
    return browserLensEffectApi33(paneWidth, paneHeight, radius, bend, motion, frostPx)
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
  private fun browserLensEffectApi33(
    paneWidth: Float,
    paneHeight: Float,
    radius: Float,
    bend: Float,
    motion: Float,
    frostPx: Float,
  ): androidx.compose.ui.graphics.RenderEffect? =
    AgslRuntimeShaders.browserLensEffect(paneWidth, paneHeight, radius, bend, motion, frostPx)

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
