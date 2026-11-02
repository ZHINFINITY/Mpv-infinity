/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.cast

import android.util.TypedValue
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastPlayerButtonTest {
  private val source: String by lazy {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    File(root, "app/infinity/mpvz/ui/cast/CastPlayerButton.kt").readText()
  }

  @Test
  fun chooserFragmentIsPublicTopLevelAndHasPublicNoArgumentConstructor() {
    val fragmentClass = GlassMediaRouteChooserDialogFragment::class.java

    assertTrue(Modifier.isPublic(fragmentClass.modifiers))
    assertNull(fragmentClass.enclosingClass)
    assertFalse(fragmentClass.isAnonymousClass)
    assertFalse(fragmentClass.isLocalClass)
    assertNotNull(fragmentClass.getConstructor())
  }

  @Test
  fun castButtonKeepsGoogleRouteSetupAndUsesRestorableGlassChooser() {
    assertTrue(source.contains("CastButtonFactory.setUpMediaRouteButton(context.applicationContext, this)"))
    assertTrue(source.contains("GlassMediaRouteDialogFactory(glassOpacity, glassHighlight)"))
    assertTrue(source.contains("GlassMediaRouteChooserDialogFragment().apply"))
    assertTrue(source.contains("putFloat(ARG_GLASS_OPACITY, opacity)"))
    assertTrue(source.contains("putFloat(ARG_GLASS_HIGHLIGHT, highlight)"))
    assertFalse(source.contains("object : MediaRouteChooserDialogFragment()"))
  }

  @Test
  fun chooserBackgroundSupportsResourceAndDirectThemeColorsWithFallback() {
    val fallback = 0xFF20242C.toInt()
    val directColor = 0xFF112233.toInt()
    val resourceColor = 0xFF445566.toInt()

    assertEquals(
      directColor,
      resolveDialogThemeColor(0, TypedValue.TYPE_INT_COLOR_ARGB8, directColor, fallback) {
        error("A direct color must not resolve a resource")
      },
    )
    assertEquals(
      resourceColor,
      resolveDialogThemeColor(42, TypedValue.TYPE_NULL, 0, fallback) { resourceId ->
        assertEquals(42, resourceId)
        resourceColor
      },
    )
    assertEquals(fallback, resolveDialogThemeColor(0, TypedValue.TYPE_INT_DEC, 7, fallback) { error("not a color") })
    assertEquals(fallback, resolveDialogThemeColor(99, TypedValue.TYPE_NULL, 0, fallback) { error("missing resource") })
  }

  @Test
  fun chooserGlassFillUsesDialogThemeBackgroundAndConfiguredOpacity() {
    assertTrue(source.contains("context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)"))
    assertTrue(source.contains("ContextCompat.getColor(context, resourceId)"))
    assertTrue(source.contains("type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT"))
    assertTrue(source.contains("GlassSurfacePolicy.opacity(glassOpacity)"))
    assertTrue(source.contains("Color.argb(alpha, Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor))"))
  }
}
