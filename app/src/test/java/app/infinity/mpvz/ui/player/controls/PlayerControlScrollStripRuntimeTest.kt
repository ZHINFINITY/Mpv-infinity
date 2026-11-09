/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerControlScrollStripRuntimeTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun fittingActionsStayCenteredAndOverflowScrollsAcrossTheViewport() {
    var itemCount by mutableIntStateOf(2)
    lateinit var state: ScrollState
    composeRule.setContent {
      state = remember { ScrollState(0) }
      PlayerControlScrollStrip(
        modifier = Modifier.width(180.dp).height(60.dp).testTag("control-strip"),
        scrollState = state,
        gap = 8.dp,
        alignment = Alignment.CenterHorizontally,
      ) {
        repeat(itemCount) { index ->
          Box(Modifier.size(44.dp).testTag("action-$index"))
        }
      }
    }
    composeRule.waitForIdle()

    val viewport = composeRule.onNodeWithTag("control-strip").fetchSemanticsNode().boundsInRoot
    val first = composeRule.onNodeWithTag("action-0").fetchSemanticsNode().boundsInRoot
    val last = composeRule.onNodeWithTag("action-1").fetchSemanticsNode().boundsInRoot
    assertEquals(viewport.center.x, (first.left + last.right) / 2f, 1.5f)
    assertEquals(0, state.maxValue)

    composeRule.runOnIdle { itemCount = 6 }
    composeRule.waitForIdle()
    assertTrue("overflow must create a scroll range", state.maxValue > 0)

    composeRule.onNodeWithTag("control-strip").performTouchInput {
      swipe(
        start = Offset(size.width * 0.82f, size.height * 0.5f),
        end = Offset(size.width * 0.18f, size.height * 0.5f),
        durationMillis = 350,
      )
    }
    composeRule.waitForIdle()
    val afterLeftDrag = state.value
    assertTrue("dragging left must reveal later buttons", afterLeftDrag > 0)

    composeRule.onNodeWithTag("control-strip").performTouchInput {
      swipe(
        start = Offset(size.width * 0.18f, size.height * 0.5f),
        end = Offset(size.width * 0.82f, size.height * 0.5f),
        durationMillis = 350,
      )
    }
    composeRule.waitForIdle()
    assertTrue("dragging right must return toward the first buttons", state.value < afterLeftDrag)
  }

  @Test
  fun reversedLandscapeActionsAlsoRespondToHorizontalDrag() {
    lateinit var state: ScrollState
    composeRule.setContent {
      state = remember { ScrollState(0) }
      PlayerControlScrollStrip(
        modifier = Modifier.width(180.dp).height(60.dp).testTag("landscape-strip"),
        scrollState = state,
        gap = 8.dp,
        alignment = Alignment.End,
        reverseScrolling = true,
        showEdgeFades = true,
      ) {
        repeat(6) { index -> Box(Modifier.size(44.dp).testTag("landscape-action-$index")) }
      }
    }
    composeRule.waitForIdle()
    assertTrue("reversed overflow must have a scroll range", state.maxValue > 0)
    val initial = state.value

    composeRule.onNodeWithTag("landscape-strip").performTouchInput {
      swipe(
        start = Offset(size.width * 0.18f, size.height * 0.5f),
        end = Offset(size.width * 0.82f, size.height * 0.5f),
        durationMillis = 350,
      )
    }
    composeRule.waitForIdle()
    if (state.value == initial) {
      composeRule.onNodeWithTag("landscape-strip").performTouchInput {
        swipe(
          start = Offset(size.width * 0.82f, size.height * 0.5f),
          end = Offset(size.width * 0.18f, size.height * 0.5f),
          durationMillis = 350,
        )
      }
      composeRule.waitForIdle()
    }
    assertNotEquals("at least one horizontal drag direction must scroll", initial, state.value)
  }
}
