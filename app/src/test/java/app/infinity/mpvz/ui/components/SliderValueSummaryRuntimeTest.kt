/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.components

import android.app.Application
import androidx.compose.foundation.layout.width
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

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

  @Test fun glassTickDotsAreSkippedBeforeDensePositionsAreBuilt() {
    val policy = app.infinity.mpvz.ui.player.controls.components.GlassSliderPolicy
    assertTrue(policy.tickDotsFit(96f, 15, 6f))
    assertFalse(policy.tickDotsFit(95.9f, 15, 6f))
    assertFalse(policy.tickDotsFit(100f, 16, 6f))
    assertFalse(policy.tickDotsFit(100f, Int.MAX_VALUE, 6f))
    assertFalse(policy.tickDotsFit(100f, 0, 6f))
    assertFalse(policy.tickDotsFit(100f, -1, 6f))
    assertFalse(policy.tickDotsFit(Float.NaN, 4, 6f))
    assertFalse(policy.tickDotsFit(100f, 4, Float.NaN))
    val controls = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/GlassControls.kt").readText()
    val tickDots = controls.substringAfter("private fun Modifier.glassTickDots")
      .substringBefore("private fun Modifier.glassSliderGroove")
    assertTrue(tickDots.contains("GlassSliderPolicy.tickDotsFit(size.width, steps, 6.dp.toPx())"))
    assertTrue(tickDots.contains("val positions = if (dotsFit)"))
    assertTrue(tickDots.contains("if (dotsFit) positions.forEach"))
  }

  @Test fun badgeMeasuresACurrentValueWiderThanItsEndpoints() {
    val value = "duration 120 minutes"
    val expectedBadgeWidthPx = mutableFloatStateOf(0f)
    compose.setContent {
      MaterialTheme {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
        SideEffect {
          expectedBadgeWidthPx.floatValue = with(density) {
            measurer.measure(value, style).size.width.toDp().toPx() + 16.dp.toPx()
          }
        }
        SliderValueSummary(value, "0", "60", "duration", Modifier.width(320.dp))
      }
    }
    compose.waitForIdle()
    val badgeWidth = compose.onNodeWithTag("slider_value_badge", useUnmergedTree = true)
      .fetchSemanticsNode().boundsInRoot.width
    assertTrue("current value must fit inside the measured badge", badgeWidth >= expectedBadgeWidthPx.floatValue - 0.5f)
  }

  @Test fun horizontalSliderItemsDoNotDuplicateGlassSliderTickHaptics() {
    val sliderItem = File(root, "app/src/main/java/app/infinity/mpvz/presentation/components/SliderItem.kt").readText()
    val horizontalSliders = sliderItem.substringAfter("fun SliderItem(").substringBefore("fun VerticalSliderItem(")
    assertTrue(horizontalSliders.contains("GlassSlider("))
    assertFalse(horizontalSliders.contains("LocalHapticFeedback.current"))
    assertFalse(horizontalSliders.contains("HapticFeedbackType.TextHandleMove"))
    val glassControls =
      File(root, "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/GlassControls.kt")
        .readText()
    assertTrue(glassControls.contains("HapticFeedbackType.SegmentFrequentTick"))
  }
}
