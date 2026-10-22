/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.preferences

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
  fun realDestinationsRenderAsSeparateCardsWithSpacingAndCallbacks() {
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
    // The merged clickable row inherits its children's text but reports the entire
    // card bounds. Measure the unmerged Text nodes to test real content clearance.
    val firstBounds = appearanceText.fetchSemanticsNode().boundsInRoot
    val secondBounds = playerText.fetchSemanticsNode().boundsInRoot
    val firstCard = composeRule.onNodeWithTag("settings-destination-card:Appearance")
      .fetchSemanticsNode().boundsInRoot
    val secondCard = composeRule.onNodeWithTag("settings-destination-card:Player")
      .fetchSemanticsNode().boundsInRoot
    val firstIcon = composeRule.onNodeWithTag("settings-destination-icon:Appearance", useUnmergedTree = true)
      .fetchSemanticsNode().boundsInRoot
    assertTrue("icon must clear rounded leading edge", firstIcon.left >= firstCard.left + 16f)
    assertTrue("icon must clear rounded top edge", firstIcon.top >= firstCard.top + 12f)
    assertTrue("destination cards must be vertically separated", secondCard.top > firstCard.bottom)
    assertTrue("destination cards must have the shared minimum height", firstCard.height >= 72f)
    assertTrue("destination card must clear its icon/text content", firstBounds.left > firstCard.left + 16f)
    assertTrue("destination card must clear its trailing content", firstBounds.right < firstCard.right - 16f)
    assertTrue("destination text must be inside its card", firstBounds.top > firstCard.top && firstBounds.bottom < firstCard.bottom)
    assertTrue("destination text must be inside its card", secondBounds.top > secondCard.top && secondBounds.bottom < secondCard.bottom)

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
    val initialAppearanceCard = composeRule.onNodeWithTag("settings-destination-card:Appearance")
      .fetchSemanticsNode().boundsInRoot
    val initialPlayerCard = composeRule.onNodeWithTag("settings-destination-card:Player")
      .fetchSemanticsNode().boundsInRoot
    for (enabled in listOf(false, true, false, true)) {
      composeRule.runOnIdle { appearance.liquidGlassSurfaces.set(enabled) }
      composeRule.waitForIdle()
      assertSameBounds(initialAppearance, composeRule.onNodeWithText("Appearance", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
      assertSameBounds(initialPlayer, composeRule.onNodeWithText("Player", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
      assertSameBounds(initialAppearanceCard, composeRule.onNodeWithTag("settings-destination-card:Appearance").fetchSemanticsNode().boundsInRoot)
      assertSameBounds(initialPlayerCard, composeRule.onNodeWithTag("settings-destination-card:Player").fetchSemanticsNode().boundsInRoot)
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
