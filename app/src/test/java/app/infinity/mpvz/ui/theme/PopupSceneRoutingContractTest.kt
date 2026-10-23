/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Build 1902: dialogs opened from Settings and other pushed routes sample the live route like the
 * browser's Sort & View popup, instead of the flat root background (the opaque AI-provider dialog).
 */
class PopupSceneRoutingContractTest {
  private val root = generateSequence(File("").absoluteFile) { it.parentFile }
    .first { File(it, "app/src/main/java").isDirectory }
  private fun read(path: String) = File(root, path).readText()

  @Test fun everyNonBrowserRouteHasAPopupSceneProducer() {
    val activity = read("app/src/main/java/app/infinity/mpvz/MainActivity.kt")
    val entry = activity.substringAfter("entryProvider = { route ->").substringBefore("sizeTransform")
    assertTrue(entry.contains("if (route == MainScreen)"))
    assertTrue(entry.contains("BrowserGlassPopupSceneHost(enabled = popupGlassEnabled)"))
    assertTrue(activity.contains("val popupGlassEnabled by appearancePreferences.liquidGlassSurfaces.collectAsState()"))
  }

  @Test fun popupProducerRecordsOnlyWhilePopupsAreAttached() {
    val scene = read("app/src/main/java/app/infinity/mpvz/ui/theme/BrowserGlassScene.kt")
    val host = scene.substringAfter("fun BrowserGlassPopupSceneHost(").substringBefore("\n/**")
    assertTrue(host.contains("LocalBrowserPopupScene provides popupScene"))
    assertTrue(host.contains("popupScene.ownerView = hostView"))
    assertTrue(host.contains("canCapture && popupsOpen && !popupScene.isRecording"))
    assertTrue(host.contains("GlassScenePolicy.validCaptureSize"))
    assertTrue(host.contains("popupScene.invalidate()"))
    // Never provides the in-window route scene: no producer -> consumer cycle.
    assertFalse(host.contains("LocalBrowserScene provides"))
  }

  @Test fun preferenceDialogsKeepAnOpaqueFillWhenGlassIsOff() {
    val wrappers = read("app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsPreferenceWrappers.kt")
    assertFalse(wrappers.contains("containerColor = Color.Transparent"))
    assertTrue(Regex("containerColor = settingsGlassContainerColor\\(MaterialTheme\\.colorScheme\\.surfaceContainer").findAll(wrappers).count() >= 3)
  }
}
