/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.theme

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Build 1933 water-ripple refraction for glass buttons, kept in its own API 33 class so
 * [RuntimeShader] is never resolved on older Android releases (see [AgslRuntimeShaders]).
 *
 * The shader displaces the button's own rendered content (glass pane, icon, label) along
 * a travelling, damped ring centred on the touch point, and adds a soft specular crest.
 * It is only installed while a ripple is running; idle buttons carry no RenderEffect.
 */
@RequiresApi(33)
internal object GlassWaterRippleShader {
  /** Set after any driver or uniform failure, so the session falls back to the fade. */
  @Volatile private var failed = false

  val available: Boolean
    get() = !failed && shader() != null

  private fun shader(): RuntimeShader? =
    GlassShaderCache.shared(WATER_RIPPLE) { RuntimeShader(WATER_RIPPLE) }

  /**
   * Creates the effect for one frame. Uniforms are snapshotted by
   * [RenderEffect.createRuntimeShaderEffect], so the shared shader can be reused by
   * several buttons on the main thread.
   */
  fun effect(
    width: Float,
    height: Float,
    originX: Float,
    originY: Float,
    radiusPx: Float,
    ringWidthPx: Float,
    displacementPx: Float,
    light: Float,
  ): androidx.compose.ui.graphics.RenderEffect? {
    if (failed || width <= 0f || height <= 0f) return null
    return runCatching {
      val shader = shader() ?: return null
      shader.setFloatUniform("size", width, height)
      shader.setFloatUniform("origin", originX, originY)
      shader.setFloatUniform("radius", radiusPx)
      shader.setFloatUniform("ringWidth", ringWidthPx.coerceAtLeast(1f))
      shader.setFloatUniform("displacement", displacementPx)
      shader.setFloatUniform("light", light)
      RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }.getOrElse {
      failed = true
      null
    }
  }

  private const val WATER_RIPPLE = """
uniform shader content;
uniform float2 size;
uniform float2 origin;
uniform float radius;
uniform float ringWidth;
uniform float displacement;
uniform float light;

half4 main(float2 p) {
  float2 v = p - origin;
  float d = length(v);
  float x = (d - radius) / ringWidth;
  // A damped wave packet: a bright crest at the ring and a shallow trough behind it.
  float envelope = exp(-x * x);
  float wave = envelope * cos(x * 2.4);
  float2 dir = d > 0.001 ? v / d : float2(0.0, 0.0);
  float2 q = clamp(p - dir * (wave * displacement), float2(0.5, 0.5), size - float2(0.5, 0.5));
  half4 c = content.eval(q);
  // Premultiplied colour: lift the crest towards white without exceeding alpha.
  half crest = half(max(wave, 0.0) * light);
  return half4(min(c.rgb + half3(crest) * c.a, half3(c.a)), c.a);
}
"""
}
