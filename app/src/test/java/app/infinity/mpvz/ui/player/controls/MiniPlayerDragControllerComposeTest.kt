/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.remember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiniPlayerDragControllerComposeTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun lazyColumnScrollsNormallyThenTopEdgeSwipeCollapsesAndSettlesOnce() {
    lateinit var harness: Harness
    composeRule.setContent {
      harness = remember { Harness() }
      val listState = rememberLazyListState()
      Box(
        Modifier.width(300.dp).height(500.dp)
          .testTag("gesture-root")
          .nestedScroll(harness.controller.nestedScrollConnection),
      ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
          items((0..40).toList()) { item -> androidx.compose.material3.Text("row $item") }
        }
      }
    }
    composeRule.onNodeWithTag("gesture-root").assertExists().performTouchInput {
      swipe(start = androidx.compose.ui.geometry.Offset(150f, 450f), end = androidx.compose.ui.geometry.Offset(150f, 50f))
    }
    composeRule.runOnIdle { assertEquals(0f, harness.state.progress, 0f) }
    composeRule.onNodeWithTag("gesture-root").performTouchInput {
      swipe(start = androidx.compose.ui.geometry.Offset(150f, 50f), end = androidx.compose.ui.geometry.Offset(150f, 450f))
    }
    composeRule.runOnIdle {
      assertTrue(harness.state.progress > 0f)
      assertEquals(1, harness.settleCount)
      assertTrue(harness.controller.onRootDragCancel())
    }
  }

  private class Harness {
    var state = MiniPlayerGestureState()
    var settleCount = 0
    val controller = MiniPlayerDragController(
      state = { state },
      setState = { state = it },
      extentPx = { 500f },
      settle = { settleCount++ },
    )
  }
}
