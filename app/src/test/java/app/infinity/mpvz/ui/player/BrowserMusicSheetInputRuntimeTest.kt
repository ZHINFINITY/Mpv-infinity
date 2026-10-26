/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner report (build 1906): after minimizing the music, the app froze. Music kept playing but no
 * touch was accepted. The collapsed sheet kept its full-screen player composed and only made it
 * invisible, so it sat above the library and the mini player and took every touch.
 *
 * These tests click the "library" through real Compose hit testing with a collapsed, pre-composed
 * sheet on top, exactly like MainActivity's layering (BrowserMusicSheet after MiniPlayer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserMusicSheetInputRuntimeTest {
  @get:Rule val composeRule = createComposeRule()

  private var libraryTaps = 0
  private var sheetTaps = 0
  private lateinit var state: BrowserMusicSheetState

  private fun setLayers(sheetModifier: (BrowserMusicSheetState) -> Modifier) {
    composeRule.setContent {
      val scope = rememberCoroutineScope()
      state = remember { BrowserMusicSheetState(scope) }
      Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().testTag("library").clickable { libraryTaps++ })
        if (state.isContentComposed) {
          Box(Modifier.fillMaxSize().then(sheetModifier(state)).clickable { sheetTaps++ })
        }
      }
    }
    composeRule.runOnIdle {
      state.updateAvailability(true)
      // Finger-down on the mini player composes the full player early.
      state.prewarm()
    }
    composeRule.waitForIdle()
    assertTrue("the sheet content must be pre-composed for this scenario", state.isContentComposed)
    assertFalse(state.isShowing)
  }

  @Test
  fun collapsedPrewarmedSheetLetsTouchesReachTheLibrary() {
    setLayers { Modifier.browserMusicSheetPresence(it) }
    composeRule.onNodeWithTag("library").performClick()
    assertEquals(1, libraryTaps)
    assertEquals(0, sheetTaps)
  }

  @Test
  fun openSheetTakesTouchesAndCollapsingHandsThemBack() {
    setLayers { Modifier.browserMusicSheetPresence(it) }
    composeRule.runOnIdle { assertTrue(state.expand()) }
    composeRule.waitForIdle()
    assertTrue(state.coversScreen)
    composeRule.onNodeWithTag("library").performClick()
    assertEquals("an open sheet covers the library", 1, sheetTaps)
    assertEquals(0, libraryTaps)

    composeRule.runOnIdle { assertTrue(state.collapse()) }
    composeRule.waitForIdle()
    assertFalse(state.isShowing)
    // Still composed for a quick reopen, but no longer in the way.
    assertTrue(state.isContentComposed)
    composeRule.onNodeWithTag("library").performClick()
    assertEquals(1, libraryTaps)
    assertEquals(1, sheetTaps)
  }

  @Test
  fun fullPageDragCannotStartOnACollapsedSheet() {
    setLayers { Modifier.browserMusicSheetPresence(it) }
    composeRule.runOnIdle {
      // A list scroll that reached the old invisible page started a drag here, which re-armed the
      // pre-composed player and kept the freeze going.
      assertFalse(state.beginDrag(fromMiniPlayer = false))
      assertEquals(MusicSheetPhase.Collapsed, state.phase)
      assertTrue("the mini player itself may still open the sheet", state.beginDrag(fromMiniPlayer = true))
    }
  }

  /** Control: the old alpha-only hiding is exactly what swallowed the touches. */
  @Test
  fun alphaOnlyHidingStillSwallowsTouches() {
    setLayers { s -> Modifier.graphicsLayer { alpha = if (s.progress <= 0f) 0f else 1f } }
    composeRule.onNodeWithTag("library").performClick()
    assertEquals(0, libraryTaps)
    assertEquals(1, sheetTaps)
  }
}
