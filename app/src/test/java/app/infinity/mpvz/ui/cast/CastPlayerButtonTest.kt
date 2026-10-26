/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.cast

import java.io.File
import java.lang.reflect.Modifier
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
}
