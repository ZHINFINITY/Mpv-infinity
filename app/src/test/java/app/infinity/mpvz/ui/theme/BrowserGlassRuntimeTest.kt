/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.CustomThemeData
import app.infinity.mpvz.preferences.preference.AndroidPreferenceStore
import app.infinity.mpvz.ui.player.controls.components.GlassSlider
import app.infinity.mpvz.ui.preferences.components.CustomThemeEditor
import app.infinity.mpvz.ui.preferences.components.settingsGlassEnabled
import app.infinity.mpvz.ui.preferences.components.settingsGlassPreferenceRow
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose on simulated Android/software Skia, NOT an emulator or native-player test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserGlassRuntimeTest {
  @get:Rule val composeRule = createComposeRule()
  private lateinit var appearance: AppearancePreferences

  @Before fun installActualPreferences() {
    val context = RuntimeEnvironment.getApplication()
    val preferences = context.getSharedPreferences("glass-runtime", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    appearance = AppearancePreferences(AndroidPreferenceStore(context, preferences))
    appearance.liquidGlassSurfaces.set(true)
    startKoin { modules(module { single { appearance } }) }
  }

  @After fun closePreferences() { stopKoin() }

  @Test fun actualThermalListenerRegistersAndDisposesWithScene() {
    var visible by mutableStateOf(true)
    val power = RuntimeEnvironment.getApplication().getSystemService(PowerManager::class.java)
    composeRule.setContent {
      MaterialTheme {
        if (visible) BrowserGlassSceneHost(enabled = true) { Text("Thermal scene") }
      }
    }
    composeRule.runOnIdle { assertEquals(1, shadowOf(power).thermalStatusListeners.size) }
    composeRule.runOnUiThread { visible = false }
    composeRule.waitForIdle()
    composeRule.runOnIdle { assertTrue(shadowOf(power).thermalStatusListeners.isEmpty()) }
  }

  @Test fun modeSwitchKeepsBoundsCallbacksAndRememberedContent() {
    var enabled by mutableStateOf(true)
    var taps = 0
    var contentInstances = 0
    composeRule.setContent {
      MaterialTheme {
        BrowserGlassSceneHost(enabled = enabled) {
          val identity = remember { ++contentInstances }
          BrowserGlassSurface(
            modifier = Modifier.size(220.dp, 96.dp).testTag("surface").clickable { taps++ },
            enabled = enabled, opacity = 0.7f, highlight = 0.4f,
          ) { Text("Instance $identity") }
        }
      }
    }
    val initial = node("surface").fetchSemanticsNode().boundsInRoot
    repeat(20) {
      composeRule.runOnUiThread { enabled = !enabled }
      composeRule.waitForIdle()
      node("surface").assertIsDisplayed().performClick()
      assertSameBounds(initial, node("surface").fetchSemanticsNode().boundsInRoot)
      composeRule.onNodeWithText("Instance 1").assertIsDisplayed()
    }
    composeRule.runOnIdle { assertEquals(20, taps); assertEquals(1, contentInstances) }
  }

  @Test fun repeatedLazyScrollAndTapAcrossBothMaterialModes() {
    var enabled by mutableStateOf(true)
    var selected = -1
    composeRule.setContent {
      MaterialTheme {
        BrowserGlassSceneHost(enabled = enabled) {
          LazyColumn(Modifier.testTag("list")) {
            items((0 until 40).toList(), key = { it }) { index ->
              BrowserGlassSurface(
                modifier = Modifier.size(260.dp, 64.dp).testTag("row-$index").clickable { selected = index },
                enabled = enabled, opacity = 0.65f, highlight = 0.2f,
              ) { Text("Row $index") }
            }
          }
        }
      }
    }
    repeat(20) { cycle ->
      composeRule.runOnUiThread { enabled = cycle % 2 == 0 }
      composeRule.waitForIdle()
      node("list").performScrollToIndex(39)
      node("row-39").assertIsDisplayed().performClick()
      composeRule.runOnIdle { assertEquals(39, selected) }
      node("list").performScrollToIndex(0)
      node("row-0").assertIsDisplayed().performClick()
      composeRule.runOnIdle { assertEquals(0, selected) }
    }
  }

  @Test fun actualSettingsCardsKeepBoundsAndSpacingWhenMasterChanges() {
    composeRule.setContent {
      MaterialTheme {
        BrowserGlassSceneHost(enabled = true) {
          Column(Modifier.width(320.dp)) {
            repeat(2) { index ->
              Box(Modifier.fillMaxWidth().settingsGlassPreferenceRow().testTag("setting-$index")) {
                Text("Setting $index")
              }
            }
            Text("Material", Modifier.testTag("material-${settingsGlassEnabled()}"))
          }
        }
      }
    }
    val first = node("setting-0").fetchSemanticsNode().boundsInRoot
    val second = node("setting-1").fetchSemanticsNode().boundsInRoot
    assertTrue(second.top > first.bottom)
    repeat(10) { cycle ->
      val enabled = cycle % 2 == 0
      composeRule.runOnIdle { appearance.liquidGlassSurfaces.set(enabled) }
      composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodes(hasTestTag("material-$enabled")).fetchSemanticsNodes().isNotEmpty()
      }
      assertSameBounds(first, node("setting-0").fetchSemanticsNode().boundsInRoot)
      assertSameBounds(second, node("setting-1").fetchSemanticsNode().boundsInRoot)
    }
  }

  @Test @Config(qualifiers = "w320dp-h480dp")
  fun actualThemeEditorKeepsSaveAndResetReachableInNarrowWindow() {
    exerciseActualThemeEditor()
  }

  @Test @Config(qualifiers = "w640dp-h360dp-land")
  fun actualThemeEditorKeepsSaveAndResetReachableInLandscape() {
    exerciseActualThemeEditor()
  }

  private fun exerciseActualThemeEditor() {
    var visible by mutableStateOf(true)
    var savedName: String? = null
    val initial = CustomThemeData(name = "Virtual test", mediaPath = "/missing-test-theme.jpg", isVideo = false)
    composeRule.setContent {
      MaterialTheme {
        BrowserGlassSceneHost(enabled = true) {
          if (visible) {
            CustomThemeEditor(
              initial = initial,
              isNew = true,
              onDismiss = { visible = false },
              onSave = { savedName = it.name; visible = false },
            )
          }
        }
      }
    }
    composeRule.onNodeWithText("Save").assertIsDisplayed()
    composeRule.onNodeWithText("Edit theme").performClick()
    composeRule.onNodeWithText("Custom text colour").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("Reset").assertIsDisplayed().performClick()
    composeRule.onNodeWithText("Save").assertIsDisplayed().performClick()
    composeRule.runOnIdle { assertEquals("Virtual test", savedName) }
    composeRule.runOnUiThread { visible = true }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Cancel").assertIsDisplayed().performClick()
    composeRule.waitForIdle()
    composeRule.runOnIdle { assertEquals(false, visible) }
  }

  @Test fun actualGlassSliderHasOneProgressActionAndKeepsCallbackInBothModes() {
    var value by mutableStateOf(0.25f)
    composeRule.setContent {
      MaterialTheme {
        Box(Modifier.fillMaxSize()) {
          GlassSlider(value = value, onValueChange = { value = it }, valueRange = 0f..1f)
          Text("Material", Modifier.testTag("slider-material-${settingsGlassEnabled()}"))
        }
      }
    }
    val progress = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
    for (enabled in listOf(true, false, true)) {
      composeRule.runOnIdle { appearance.liquidGlassSurfaces.set(enabled) }
      composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodes(hasTestTag("slider-material-$enabled")).fetchSemanticsNodes().isNotEmpty()
      }
      assertEquals(1, composeRule.onAllNodes(progress).fetchSemanticsNodes().size)
      composeRule.onNode(progress).performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
      composeRule.runOnIdle { assertEquals(0.75f, value, 0.001f) }
      composeRule.runOnIdle { value = 0.25f }
    }
  }

  private fun node(tag: String): SemanticsNodeInteraction = composeRule.onNode(hasTestTag(tag))
  private fun assertSameBounds(expected: Rect, actual: Rect) {
    assertEquals(expected.left, actual.left, 0.5f)
    assertEquals(expected.top, actual.top, 0.5f)
    assertEquals(expected.right, actual.right, 0.5f)
    assertEquals(expected.bottom, actual.bottom, 0.5f)
  }
}
