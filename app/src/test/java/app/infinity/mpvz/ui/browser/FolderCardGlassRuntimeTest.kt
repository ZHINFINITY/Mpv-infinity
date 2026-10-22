/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.domain.media.model.VideoFolder
import app.infinity.mpvz.domain.thumbnail.ThumbnailRepository
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.BrowserPreferences
import app.infinity.mpvz.preferences.preference.AndroidPreferenceStore
import app.infinity.mpvz.ui.browser.cards.FolderCard
import java.io.File
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

/** Actual folder seam on Compose/native Skia; this does not verify a device GPU lens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderCardGlassRuntimeTest {
  @get:Rule val composeRule = createComposeRule()
  private lateinit var appearance: AppearancePreferences

  @Before fun installActualPreferences() {
    val context = RuntimeEnvironment.getApplication()
    val preferences = context.getSharedPreferences("folder-card-runtime", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    val store = AndroidPreferenceStore(context, preferences)
    appearance = AppearancePreferences(store)
    val browser = BrowserPreferences(store, context)
    appearance.liquidGlassSurfaces.set(true)
    browser.showFolderThumbnails.set(false)
    startKoin {
      modules(module {
        single { appearance }
        single { browser }
        single { ThumbnailRepository(context) }
      })
    }
  }

  @After fun closePreferences() = stopKoin()

  @Test fun actualFolderCardKeepsMeasuredBoundsAndClickAcrossGlassToggle() {
    var clicks = 0
    composeRule.setContent {
      MaterialTheme {
        FolderCard(
          folder = VideoFolder("bucket", "Folder", "/Folder", 3),
          onClick = { clicks++ },
          modifier = Modifier.testTag("folder-card"),
          isGridMode = false,
        )
      }
    }
    val initial = composeRule.onNodeWithTag("folder-card").fetchSemanticsNode().boundsInRoot
    composeRule.onNodeWithText("Folder").assertIsDisplayed()
    composeRule.onNodeWithText("Folder").performClick()
    assertEquals(1, clicks)
    for (enabled in listOf(false, true, false, true)) {
      composeRule.runOnIdle { appearance.liquidGlassSurfaces.set(enabled) }
      composeRule.waitForIdle()
      val actual = composeRule.onNodeWithTag("folder-card").fetchSemanticsNode().boundsInRoot
      assertEquals(initial.left, actual.left, 0.5f)
      assertEquals(initial.top, actual.top, 0.5f)
      assertEquals(initial.right, actual.right, 0.5f)
      assertEquals(initial.bottom, actual.bottom, 0.5f)
      composeRule.onNodeWithText("Folder").performClick()
    }
    assertEquals(5, clicks)
  }

  @Test fun actualFolderPixelsKeepRoundedCornersAndTranslucentBodyUnlikeClassicFill() {
    val background = Color(0xFF102030)
    composeRule.setContent {
      MaterialTheme {
        Box(Modifier.fillMaxSize().background(background)) {
          FolderCard(
            folder = VideoFolder("bucket", "Folder", "/Folder", 3),
            onClick = {},
            modifier = Modifier.width(300.dp).testTag("folder-pixels"),
            isGridMode = false,
          )
        }
      }
    }
    val glass = composeRule.onNodeWithTag("folder-pixels").captureToImage().toPixelMap()
    val corner = glass[0, 0]
    // Above text/artwork, below the thin rim: this is the actual measured card body.
    val bodyY = (glass.height * 0.08f).toInt().coerceAtLeast(2)
    val body = glass[glass.width / 2, bodyY]
    assertTrue("rounded corner must expose the real backdrop", colorDistance(background, corner) < 0.06f)
    assertTrue("glass must have visible body depth over a dark backdrop", colorDistance(corner, body) > 0.08f)
    composeRule.runOnIdle { appearance.liquidGlassSurfaces.set(false) }
    composeRule.waitForIdle()
    val classic = composeRule.onNodeWithTag("folder-pixels").captureToImage().toPixelMap()
    val classicBody = classic[classic.width / 2, bodyY]
    assertTrue("glass must not use the opaque native card fill", classicBody.red > body.red + 0.15f)
    assertTrue("classic keeps exactly the same rounded geometry", colorDistance(background, classic[0, 0]) < 0.06f)
  }

  @Test fun actualHomeCallersKeepOneMeasuredPaneAndTwelveDpListSeam() {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val card = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/cards/FolderCard.kt").readText()
    val screen = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/folderlist/FolderListScreen.kt").readText()
    val swipe = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/cards/SwipeableFolderActions.kt").readText()
    assertTrue(card.contains("BrowserGlassSurface(") && card.contains("modifier = Modifier.fillMaxWidth()"))
    assertTrue(card.contains("enabled = liquidGlass"))
    assertTrue(!card.contains("BrowserGlassSurface(\n          modifier = Modifier.matchParentSize()"))
    assertTrue(screen.contains("verticalArrangement = Arrangement.spacedBy(12.dp)"))
    assertTrue("only the revealed swipe action owns another pane", swipe.indexOf("BrowserGlassSurface(") > swipe.indexOf("if (offsetX > 0f)"))
    assertTrue("glass icon has no square fill", card.contains("background(if (liquidGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh)"))
  }

  private fun colorDistance(a: Color, b: Color): Float =
    kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue) + kotlin.math.abs(a.alpha - b.alpha)
}
