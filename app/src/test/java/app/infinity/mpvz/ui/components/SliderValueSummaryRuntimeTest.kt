/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.components

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Owner: "make sure the text fits properly and isn't overlapping": the description must not move with the value. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SliderValueSummaryRuntimeTest {
  @get:Rule val compose = createComposeRule()

  @Test fun descriptionKeepsItsPositionAndWidthForEveryValue() {
    val value = mutableStateOf("0%")
    compose.setContent {
      MaterialTheme {
        SliderValueSummary(value.value, "0%", "200%", "edge reflections; text and icons stay sharp",
          Modifier.width(320.dp))
      }
    }
    fun bounds() = compose.onNodeWithTag("slider_value_description", useUnmergedTree = true)
      .fetchSemanticsNode().boundsInRoot
    val first = bounds()
    for (next in listOf("5%", "85%", "100%", "200%")) {
      compose.runOnIdle { value.value = next }
      compose.waitForIdle()
      val b = bounds()
      assertEquals("left moved for $next", first.left, b.left, 0.01f)
      assertEquals("width changed for $next", first.width, b.width, 0.01f)
      assertEquals("top moved for $next", first.top, b.top, 0.01f)
    }
  }
}
