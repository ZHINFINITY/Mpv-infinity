/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.preferences

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.AndroidPreferenceStore
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.theme.MpvInfinityTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the actual PreferencesScreen destination row seam on software/native Robolectric Skia. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreferencesDestinationRuntimeTest {
  @get:Rule val composeRule = createComposeRule()
  private lateinit var appearance: AppearancePreferences

  private val section = SettingsSection(
    title = "Playback",
    tint = androidx.compose.ui.graphics.Color.DarkGray,
    items = listOf(
      SettingsDestination(
        title = "Appearance",
        summary = "Theme and glass surfaces",
        icon = Icons.RoundedFilled.Palette,
        screen = AppearancePreferencesScreen,
      ),
      SettingsDestination(
        title = "Player",
        summary = "Playback and controls",
        icon = Icons.RoundedFilled.Slideshow,
        screen = PlayerPreferencesScreen,
      ),
    ),
  )

  @Before
  fun installActualPreferences() {
    val context = RuntimeEnvironment.getApplication()
    val store = context.getSharedPreferences("preferences-destination-runtime", Context.MODE_PRIVATE)
    store.edit().clear().commit()
    appearance = AppearancePreferences(AndroidPreferenceStore(context, store))
    startKoin { modules(module { single { appearance } }) }
  }

  @After
  fun closePreferences() {
    stopKoin()
  }

  @Test
  fun realDestinationsRenderAsGroupedRowsWithCallbacks() {
    val clicks = mutableListOf<Screen>()
    composeRule.setContent {
      MpvInfinityTheme(showCustomThemeBackdrop = false) {
        MaterialTheme {
          SettingsDestinationGroup(
            section = section,
            selectedScreen = null,
            onItemClick = { clicks += it.screen },
          )
        }
      }
    }

    val appearanceText = composeRule.onNodeWithText("Appearance", useUnmergedTree = true).assertIsDisplayed()
    val playerText = composeRule.onNodeWithText("Player", useUnmergedTree = true).assertIsDisplayed()
    val firstBounds = appearanceText.fetchSemanticsNode().boundsInRoot
    val secondBounds = playerText.fetchSemanticsNode().boundsInRoot
    assertTrue("destination text must be displayed", firstBounds.height > 0f)
    assertTrue("destination rows must have stable ordering", secondBounds.top > firstBounds.top)

    appearanceText.performClick()
    playerText.performClick()
    composeRule.runOnIdle {
      assertEquals(listOf(AppearancePreferencesScreen, PlayerPreferencesScreen), clicks)
    }
  }

  @Test
  fun globalMasterOnAndOffKeepRealRowBoundsAndCallbacksStable() {
    val clicks = mutableListOf<Screen>()
    composeRule.setContent {
      MpvInfinityTheme(showCustomThemeBackdrop = false) {
        SettingsDestinationGroup(
          section = section,
          selectedScreen = null,
          onItemClick = { clicks += it.screen },
        )
      }
    }

    val initialAppearance = composeRule.onNodeWithText("Appearance", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    val initialPlayer = composeRule.onNodeWithText("Player", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    for (enabled in listOf(false, true, false, true)) {
      composeRule.runOnIdle { appearance.liquidGlassSurfaces.set(enabled) }
      composeRule.waitForIdle()
      assertSameBounds(initialAppearance, composeRule.onNodeWithText("Appearance", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
      assertSameBounds(initialPlayer, composeRule.onNodeWithText("Player", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
      composeRule.onNodeWithText("Appearance", useUnmergedTree = true).performClick()
    }
    composeRule.runOnIdle {
      assertEquals(4, clicks.size)
      assertTrue(clicks.all { it == AppearancePreferencesScreen })
    }
  }

  private fun assertSameBounds(expected: Rect, actual: Rect) {
    assertEquals(expected.left, actual.left, 0.5f)
    assertEquals(expected.top, actual.top, 0.5f)
    assertEquals(expected.right, actual.right, 0.5f)
    assertEquals(expected.bottom, actual.bottom, 0.5f)
  }
}
