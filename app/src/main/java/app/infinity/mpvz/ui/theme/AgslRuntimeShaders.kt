/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.theme

import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * All [RuntimeShader] construction lives in this API 33 class.
 *
 * ART resolves classes referenced by a method's constant pool when that method first
 * runs. Keeping RuntimeShader out of [BrowserGlassScene] and [LiquidGlassBackdrop]
 * prevents a `NoClassDefFoundError` on Android 10 (Xiaomi Redmi 8, API 29).
 */
@RequiresApi(33)
internal object AgslRuntimeShaders {
  fun glassOptics(): RuntimeShader? =
    GlassShaderCache.shared(GLASS_OPTICS) { RuntimeShader(GLASS_OPTICS) }

  fun browserLens(): RuntimeShader? =
    runCatching { RuntimeShader(BROWSER_LENS) }.getOrNull()

  fun browserLensEffect(
    shader: Any?,
    paneWidth: Float,
    paneHeight: Float,
    radius: Float,
    bend: Float,
    motion: Float,
    frostPx: Float,
  ): androidx.compose.ui.graphics.RenderEffect? = runCatching {
    // Any driver/uniform failure falls back to the frosted pane instead of crashing the draw.
    val runtimeShader = shader as? RuntimeShader ?: return@runCatching null
    browserLensEffectUnchecked(runtimeShader, paneWidth, paneHeight, radius, bend, motion, frostPx)
  }.getOrNull()

  private fun browserLensEffectUnchecked(
    shader: RuntimeShader,
    paneWidth: Float,
    paneHeight: Float,
    radius: Float,
    bend: Float,
    motion: Float,
    frostPx: Float,
  ): androidx.compose.ui.graphics.RenderEffect? {
    shader.setFloatUniform("pane", paneWidth, paneHeight)
    shader.setFloatUniform("radius", radius)
    shader.setFloatUniform("bend", bend)
    shader.setFloatUniform("motion", motion)
    return createBrowserLensComposeEffect(shader, frostPx)
  }

  private fun createBrowserLensComposeEffect(
    shader: RuntimeShader,
    frostPx: Float,
  ): androidx.compose.ui.graphics.RenderEffect {
    val lensEffect = RenderEffect.createRuntimeShaderEffect(shader, "scene")
    val chained =
      if (frostPx > 0f) {
        RenderEffect.createChainEffect(
          lensEffect,
          RenderEffect.createBlurEffect(frostPx, frostPx, Shader.TileMode.CLAMP),
        )
      } else {
        lensEffect
      }
    return chained.asComposeRenderEffect()
  }

  fun browserLensHandle(
    paneWidth: Float,
    paneHeight: Float,
    radius: Float,
    bend: Float,
    frostPx: Float,
  ): BrowserLensEffectHandle? = runCatching {
    val shader = browserLens() ?: return@runCatching null
    shader.setFloatUniform("pane", paneWidth, paneHeight)
    shader.setFloatUniform("radius", radius)
    shader.setFloatUniform("bend", bend)
    shader.setFloatUniform("motion", 0f)
    val initialEffect = createBrowserLensComposeEffect(shader, frostPx)
    BrowserLensEffectHandle(initialEffect) { motion ->
      runCatching {
        shader.setFloatUniform("motion", motion.coerceIn(0f, 1f))
        createBrowserLensComposeEffect(shader, frostPx)
      }.getOrNull()
    }
  }.getOrNull()

  fun bindGlassOptics(
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
    runCatching {
      val runtime = shader as RuntimeShader
      sourceShader.setLocalMatrix(null)
      runtime.setInputShader("scene", sourceShader)
      runtime.setFloatUniform("pane", paneWidth, paneHeight)
      runtime.setFloatUniform("origin", originX, originY)
      runtime.setFloatUniform("sceneOrigin", sceneLeft, sceneTop)
      runtime.setFloatUniform("sceneSize", sceneWidth, sceneHeight)
      runtime.setFloatUniform("imageSize", imageWidth, imageHeight)
      runtime.setFloatUniform("radius", radius)
      runtime.setFloatUniform("bevel", bevel)
      runtime.setFloatUniform("light", lightX, lightY)
      runtime.setFloatUniform("specular", specular)
      runtime.setFloatUniform("fresnel", fresnel)
      runtime.setFloatUniform("strength", strength)
      runtime.setFloatUniform("frost", frostPx)
      paint.shader = runtime
    }.isSuccess
}
