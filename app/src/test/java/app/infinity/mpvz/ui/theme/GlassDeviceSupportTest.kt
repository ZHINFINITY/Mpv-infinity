/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.theme

import app.infinity.mpvz.ui.theme.GlassDeviceSupport.DeviceFacts
import app.infinity.mpvz.ui.theme.GlassDeviceSupport.Verdict
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner request: unsupported devices must skip Liquid Glass instead of crashing. */
class GlassDeviceSupportTest {
  private val pocoF7 = DeviceFacts(sdkInt = 36, isLowRamDevice = false, glEsVersion = 0x30002)

  @Test
  fun capableDevicesKeepFullGlass() {
    assertEquals(Verdict.Supported, GlassDeviceSupport.evaluate(pocoF7, crashedBefore = false))
    assertEquals(Verdict.Supported, GlassDeviceSupport.evaluate(pocoF7.copy(sdkInt = 31), false))
    // An unknown GLES version (0) is not treated as a missing GPU.
    assertEquals(Verdict.Supported, GlassDeviceSupport.evaluate(pocoF7.copy(glEsVersion = 0), false))
  }

  @Test
  fun unsupportedDevicesSkipGlass() {
    assertEquals(Verdict.OldAndroid, GlassDeviceSupport.evaluate(pocoF7.copy(sdkInt = 30), false))
    assertEquals(Verdict.OldAndroid, GlassDeviceSupport.evaluate(pocoF7.copy(sdkInt = 26), false))
    assertEquals(Verdict.LowRam, GlassDeviceSupport.evaluate(pocoF7.copy(isLowRamDevice = true), false))
    assertEquals(Verdict.NoGles3, GlassDeviceSupport.evaluate(pocoF7.copy(glEsVersion = 0x20000), false))
    assertEquals(Verdict.CrashedBefore, GlassDeviceSupport.evaluate(pocoF7, crashedBefore = true))
    listOf(Verdict.OldAndroid, Verdict.LowRam, Verdict.NoGles3).forEach { assertTrue("$it locks the switch", it.hard) }
    assertFalse("the user may retry after a crash", Verdict.CrashedBefore.hard)
  }

  @Test
  fun glassCrashesAreRecognisedThroughCauses() {
    val glass = IllegalStateException("shader").apply {
      stackTrace = arrayOf(StackTraceElement("android.graphics.RuntimeShader", "<init>", "RuntimeShader.java", 1))
    }
    assertTrue(GlassDeviceSupport.isGlassCrash(glass))
    assertTrue(GlassDeviceSupport.isGlassCrash(RuntimeException("wrapped", glass).apply {
      stackTrace = arrayOf(StackTraceElement("app.infinity.mpvz.MainActivity", "onCreate", "MainActivity.kt", 1))
    }))
    val scene = IllegalArgumentException().apply {
      stackTrace = arrayOf(StackTraceElement("app.infinity.mpvz.ui.theme.BrowserGlassSceneKt", "draw", "BrowserGlassScene.kt", 1))
    }
    assertTrue(GlassDeviceSupport.isGlassCrash(scene))
    val unrelated = NullPointerException().apply {
      stackTrace = arrayOf(StackTraceElement("app.infinity.mpvz.ui.player.PlayerActivity", "onCreate", "PlayerActivity.kt", 1))
    }
    assertFalse("an unrelated crash must not take glass away", GlassDeviceSupport.isGlassCrash(unrelated))
  }

  @Test
  fun onlyANativeCrashOfTheProcessThatWasInitialisingGlassCounts() {
    assertTrue(GlassDeviceSupport.isNativeCrashDuringGlassInit(pendingPid = 4242, lastExitPid = 4242, lastExitWasNativeCrash = true))
    assertFalse("swipe-away or low-memory kill", GlassDeviceSupport.isNativeCrashDuringGlassInit(4242, 4242, false))
    assertFalse("a different process", GlassDeviceSupport.isNativeCrashDuringGlassInit(4242, 5151, true))
    assertFalse("sentinel was cleared", GlassDeviceSupport.isNativeCrashDuringGlassInit(0, 0, true))
  }

  @Test
  fun everySettingsSummaryExplainsTheVerdict() {
    Verdict.entries.forEach { verdict ->
      val text = GlassDeviceSupportText.summary(verdict)
      assertTrue(text.isNotBlank())
      if (verdict.hard) assertTrue(text.startsWith("Not supported on this device"))
    }
  }

  @Test
  fun gateIsWiredIntoStartupCrashHandlerAndBothGlassHosts() {
    val root = generateSequence(File("").absoluteFile) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz/App.kt").isFile }
    fun src(path: String) = File(root, "app/src/main/java/app/infinity/mpvz/$path").readText()
    val app = src("App.kt")
    val enforce = app.indexOf("GlassDeviceSupport.enforceAtStartup(this)")
    assertTrue("startup enforcement present", enforce > 0)
    assertTrue("enforced after Koin starts", enforce > app.indexOf("startKoin"))
    assertTrue("glass preference is forced off", "liquidGlassSurfaces.set(false)" in app.substring(enforce, enforce + 300))
    assertTrue(
      "crash handler records glass crashes before exiting",
      src("presentation/crash/GlobalExceptionHandler.kt").let { it.indexOf("GlassDeviceSupport.recordCrash") in 1 until it.indexOf("exitProcess(0)") },
    )
    val browser = src("ui/theme/BrowserGlassScene.kt")
    assertTrue("GlassDeviceSupport.allowsGlass(context)" in browser.substringAfter("val allowed = enabled").substringBefore("\n  val layer"))
    assertTrue("GlassStartupWatch(" in browser)
    val player = src("ui/theme/LiquidGlassBackdrop.kt")
    assertTrue("enabled && GlassDeviceSupport.allowsGlass(context)" in player)
    assertTrue("GlassStartupWatch(active = allowed)" in player)
    assertTrue(
      "lens effect creation cannot throw into draw",
      "runCatching {\n    // Any driver/uniform failure" in src("ui/theme/AgslRuntimeShaders.kt"),
    )
  }
}
