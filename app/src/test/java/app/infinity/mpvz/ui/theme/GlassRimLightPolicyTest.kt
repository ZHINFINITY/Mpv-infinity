/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassRimLightPolicyTest {
  @Test
  fun `tuning values are clamped and non-finite values fall back to defaults`() {
    assertEquals(2f, GlassRimLightPolicy.strength(9f), 0f)
    assertEquals(0f, GlassRimLightPolicy.strength(-1f), 0f)
    assertEquals(GlassRimLightPolicy.DefaultStrength, GlassRimLightPolicy.strength(Float.NaN), 0f)
    assertEquals(0.5f, GlassRimLightPolicy.widthDp(0.1f), 0f)
    assertEquals(3f, GlassRimLightPolicy.widthDp(40f), 0f)
    assertEquals(GlassRimLightPolicy.DefaultWidthDp, GlassRimLightPolicy.widthDp(Float.POSITIVE_INFINITY), 0f)
    assertEquals(1f, GlassRimLightPolicy.softness(3f), 0f)
    assertEquals(GlassRimLightPolicy.DefaultSoftness, GlassRimLightPolicy.softness(Float.NaN), 0f)
  }

  @Test
  fun `zero strength draws nothing and stronger tuning is brighter`() {
    assertFalse(GlassRimLightPolicy.rim(0f, 0.5f, darkTheme = true).visible)
    val normal = GlassRimLightPolicy.rim(1f, 0.5f, darkTheme = true)
    val strong = GlassRimLightPolicy.rim(2f, 0.5f, darkTheme = true)
    assertTrue(normal.visible)
    assertTrue(strong.highlightAlpha > normal.highlightAlpha)
    assertTrue(strong.shadeAlpha > normal.shadeAlpha)
    assertTrue(strong.highlightAlpha <= 0.9f && strong.shadeAlpha <= 0.45f)
  }

  @Test
  fun `softness spreads the band and halo without changing peak light`() {
    val crisp = GlassRimLightPolicy.rim(1f, 0f, darkTheme = true)
    val soft = GlassRimLightPolicy.rim(1f, 1f, darkTheme = true)
    assertEquals(crisp.highlightAlpha, soft.highlightAlpha, 0f)
    assertTrue(soft.highlightStop > crisp.highlightStop)
    assertTrue(soft.highlightStop <= 0.5f)
    assertEquals(0f, crisp.glowAlpha, 0f)
    assertTrue(soft.glowAlpha > 0f && soft.glowWidthScale > crisp.glowWidthScale)
  }

  @Test
  fun `press boost brightens, high contrast keeps a visible outline`() {
    val rest = GlassRimLightPolicy.rim(1f, 0.5f, darkTheme = false)
    val pressed = GlassRimLightPolicy.rim(1f, 0.5f, darkTheme = false, boost = 1f)
    assertTrue(pressed.highlightAlpha > rest.highlightAlpha)
    val hc = GlassRimLightPolicy.rim(0.2f, 0.5f, darkTheme = true, highContrast = true)
    assertTrue(hc.shadeAlpha >= 0.32f)
  }

  @Test
  fun `ambient tint is subtle and absent colour stays white`() {
    assertEquals(Color.White, GlassRimLightPolicy.lightColor(null))
    assertEquals(Color.White, GlassRimLightPolicy.lightColor(Color.Unspecified))
    val tinted = GlassRimLightPolicy.lightColor(Color(0xFF0000FF))
    assertTrue(tinted.red > 0.7f && tinted.green > 0.7f && tinted.blue == 1f)
  }
}
