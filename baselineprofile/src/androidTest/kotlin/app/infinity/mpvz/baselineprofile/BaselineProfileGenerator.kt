/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class BaselineProfileGenerator {
  @get:Rule
  val baselineProfileRule = BaselineProfileRule()

  @Test
  fun criticalJourneys() {
    baselineProfileRule.collect(
      packageName = PACKAGE_NAME,
      stableIterations = 2,
      maxIterations = 3,
    ) {
      pressHome()
      startActivityAndWait()
      device.wait(Until.hasObject(By.pkg(PACKAGE_NAME)), 10_000)
      dismissPermissionPrompts(device)

      // Exercise each visible bottom-navigation destination, when present.
      listOf("Home", "Library", "Folders", "Music", "Settings").forEach { label ->
        device.findObject(By.text(label))?.click()
        device.waitForIdle()
      }

      // Empty libraries are valid: swipes simply become no-ops.
      repeat(3) { swipeScrollable(device) }
      device.findObject(By.text("Folders"))?.click()
      repeat(3) { swipeScrollable(device) }

      device.findObject(By.text("Settings"))?.click()
      device.waitForIdle()
      device.findObject(By.text("Appearance"))?.click()
      device.waitForIdle()
      repeat(3) { swipeScrollable(device) }
    }
  }

  private fun swipeScrollable(device: UiDevice) {
    val scrollable: UiObject2? = device.findObject(By.scrollable(true))
    if (scrollable != null) {
      scrollable.swipe(Direction.DOWN, 0.8f)
    } else {
      device.swipe(540, 1500, 540, 450, 12)
    }
    device.waitForIdle()
  }

  private fun dismissPermissionPrompts(device: UiDevice) {
    listOf("While using the app", "Allow", "Don’t allow", "Not now").forEach { text ->
      device.findObject(By.text(text))?.click()
    }
  }

  companion object {
    private const val PACKAGE_NAME = "app.infinity.mpvz"
  }
}
