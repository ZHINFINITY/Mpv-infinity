/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real pointer input through the pill's layering: drag gesture outside, horizontalScroll
 * inside it, clickable tabs inside that. Build 1942 lost every drag to the inner scrollable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-xxhdpi")
class NavPillDragGestureRuntimeTest {
  @get:Rule val composeRule = createComposeRule()

  private val clicks = mutableStateListOf<Int>()
  private val releases = mutableStateListOf<Int>()
  private val positions = mutableStateListOf<Float?>()

  @Composable
  private fun Pill(tabWidthDp: Int, selected: Int = 0, barWidthDp: Int? = null) {
    val tabPx = with(LocalDensity.current) { tabWidthDp.dp.toPx() }
    val centers = List(4) { tabPx / 2f + it * tabPx }
    Box(
      Modifier
        .testTag("pill")
        .then(if (barWidthDp != null) Modifier.width(barWidthDp.dp) else Modifier)
        .navPillDragGesture(
          tabCount = 4,
          selectedIndex = selected,
          isRtl = false,
          tabCenters = { centers },
          onDragPosition = { positions += it },
          onHoverChanged = {},
          onRelease = { releases += it },
        )
        .horizontalScroll(rememberScrollState()),
    ) {
      Row {
        repeat(4) { index ->
          Box(Modifier.testTag("tab$index").width(tabWidthDp.dp).height(44.dp).clickable { clicks += index })
        }
      }
    }
  }

  @Test fun draggingThePillAcrossTabsSelectsTheTabUnderTheFinger() {
    composeRule.setContent { Pill(tabWidthDp = 80) }
    composeRule.onNodeWithTag("pill").performTouchInput {
      swipe(start = centerLeft.copy(x = 40.dp.toPx()), end = centerLeft.copy(x = 200.dp.toPx()), durationMillis = 1200)
    }
    composeRule.waitForIdle()
    assertEquals(listOf(2), releases.toList())
    assertTrue("a drag must not also click a tab", clicks.isEmpty())
    assertTrue("the lens must follow the finger", positions.filterNotNull().any { it > 1f })
    assertEquals("drag state is cleared on release", null, positions.last())
  }

  @Test fun dragStillWinsWhenThePillOverflowsAndCanScroll() {
    composeRule.setContent { Pill(tabWidthDp = 120, barWidthDp = 300) }
    composeRule.onNodeWithTag("pill").performTouchInput {
      swipe(start = centerLeft.copy(x = 60.dp.toPx()), end = centerLeft.copy(x = 190.dp.toPx()), durationMillis = 1200)
    }
    composeRule.waitForIdle()
    assertEquals(1, releases.size)
    assertTrue(releases.single() >= 1)
    assertTrue(clicks.isEmpty())
  }

  @Test fun tappingATabStillClicksIt() {
    composeRule.setContent { Pill(tabWidthDp = 80) }
    composeRule.onNodeWithTag("tab2").performClick()
    composeRule.waitForIdle()
    assertEquals(listOf(2), clicks.toList())
    assertTrue("a tap is not a drag", releases.isEmpty())
  }

  @Test fun verticalMovesAreLeftAlone() {
    composeRule.setContent { Pill(tabWidthDp = 80) }
    composeRule.onNodeWithTag("pill").performTouchInput {
      swipe(start = center, end = center.copy(y = center.y - 300f), durationMillis = 300)
    }
    composeRule.waitForIdle()
    assertTrue(releases.isEmpty())
    assertTrue(positions.isEmpty())
  }
}
