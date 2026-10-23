/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.preferences

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.AndroidPreferenceStore
import app.infinity.mpvz.ui.preferences.components.SettingsScaffold
import app.infinity.mpvz.ui.preferences.components.SettingsTopAppBar
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRootGlassRuntimeTest {
  @get:Rule val composeRule = createComposeRule()
  private lateinit var appearance: AppearancePreferences

  @Before fun installPreferences() {
    val context = RuntimeEnvironment.getApplication()
    val values = context.getSharedPreferences("settings-root-runtime", Context.MODE_PRIVATE)
    values.edit().clear().commit()
    appearance = AppearancePreferences(AndroidPreferenceStore(context, values))
    appearance.liquidGlassSurfaces.set(false)
    startKoin { modules(module { single { appearance } }) }
  }

  @After fun closePreferences() = stopKoin()

  @OptIn(ExperimentalMaterial3Api::class)
  @Test fun realRootRevealsBackdropAndKeepsBoundsAndCallbacksAcrossMasterChanges() {
    var navigationClicks = 0
    var bodyClicks = 0
    composeRule.setContent {
      MpvInfinityTheme {
        Box(Modifier.fillMaxSize().background(Color.Blue)) {
          SettingsScaffold(
            modifier = Modifier.fillMaxSize().testTag("settings-root"),
            containerColor = Color.Red,
            topBar = {
              SettingsTopAppBar(
                modifier = Modifier.testTag("settings-header"),
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { navigationClicks++ }) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Magenta),
              )
            },
          ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
              Text("Body action", Modifier.testTag("settings-body-action").clickable { bodyClicks++ }.padding(12.dp))
            }
          }
        }
      }
    }
    val header = composeRule.onNodeWithTag("settings-header").fetchSemanticsNode().boundsInRoot
    val body = composeRule.onNodeWithTag("settings-body-action").fetchSemanticsNode().boundsInRoot
    for ((index, enabled) in listOf(false, true, false, true).withIndex()) {
      composeRule.runOnIdle { appearance.liquidGlassSurfaces.set(enabled) }
      composeRule.waitForIdle()
      assertEquals(header, composeRule.onNodeWithTag("settings-header").fetchSemanticsNode().boundsInRoot)
      assertEquals(body, composeRule.onNodeWithTag("settings-body-action").fetchSemanticsNode().boundsInRoot)
      val pixels = composeRule.onNodeWithTag("settings-root").captureToImage().toPixelMap()
      val background = pixels[2, pixels.height - 3]
      if (enabled) {
        assertTrue("glass settings must reveal the parent, not a solid Scaffold", background.blue > 0.95f && background.red < 0.05f)
      } else {
        assertTrue("classic must keep the supplied native Scaffold fill", background.red > 0.95f && background.blue < 0.05f)
      }
      composeRule.onNodeWithText("Back").performClick()
      composeRule.onNodeWithTag("settings-body-action").performClick()
      composeRule.runOnIdle {
        assertEquals(index + 1, navigationClicks)
        assertEquals(index + 1, bodyClicks)
      }
    }
  }
}
