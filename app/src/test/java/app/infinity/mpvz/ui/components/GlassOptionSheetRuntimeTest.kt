/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.components

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.AndroidPreferenceStore
import app.infinity.mpvz.ui.preferences.components.ListPreference
import app.infinity.mpvz.ui.preferences.components.OptionsDialog
import app.infinity.mpvz.ui.preferences.components.TextFieldPreference
import app.infinity.mpvz.ui.theme.MpvInfinityTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import me.zhanghai.compose.preference.ProvidePreferenceLocals

/**
 * Opens every shared bottom sheet for real (build 1902: the first sheet conversion crashed on open
 * with an infinite-height nested scroll). Runs with Liquid Glass off and on.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassOptionSheetRuntimeTest(private val glass: Boolean) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "glass={0}")
    fun params(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }

  @get:Rule val composeRule = createComposeRule()
  private lateinit var appearance: AppearancePreferences

  @Before fun installPreferences() {
    val context = RuntimeEnvironment.getApplication()
    val store = context.getSharedPreferences("glass-option-sheet-runtime", Context.MODE_PRIVATE)
    store.edit().clear().commit()
    appearance = AppearancePreferences(AndroidPreferenceStore(context, store))
    appearance.liquidGlassSurfaces.set(glass)
    startKoin { modules(module { single { appearance } }) }
  }

  @After fun closeKoin() { stopKoin() }

  @Test fun tallScrollingContentOpensWithoutCrashAndStaysReachable() {
    var done = 0
    composeRule.setContent {
      MpvInfinityTheme {
        GlassOptionSheet(
          onDismissRequest = {},
          title = "Tall sheet",
          confirmButton = { TextButton(onClick = { done++ }) { Text("Done") } },
        ) {
          // The exact shape that crashed: the content scrolls itself inside the sheet.
          Column(Modifier.verticalScroll(rememberScrollState())) {
            repeat(60) { Text("Row $it") }
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Tall sheet").assertIsDisplayed()
    composeRule.onNodeWithText("Row 59").performScrollTo().assertIsDisplayed()
    // Buttons are pinned outside the scroll area, so they never scroll away.
    composeRule.onNodeWithText("Done").assertIsDisplayed().performClick()
    composeRule.waitForIdle()
    assertEquals(1, done)
  }

  @Test fun optionsSheetReachesAndSelectsTheLastOption() {
    var picked: Int? = null
    composeRule.setContent {
      MpvInfinityTheme {
        OptionsDialog(
          title = "Pick one",
          options = (0 until 40).toList(),
          selectedOption = 0,
          onOptionSelected = { picked = it },
          onDismiss = {},
          optionLabel = { "Option $it" },
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Pick one").assertIsDisplayed()
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Option 39"))
    composeRule.onNodeWithText("Option 39").assertIsDisplayed().performClick()
    composeRule.waitForIdle()
    assertEquals(39, picked)
  }

  @Test fun listPreferenceOpensAsSheetAndClosesOnPick() {
    var value by mutableStateOf("Google Translate")
    composeRule.setContent {
      MpvInfinityTheme { ProvidePreferenceLocals {
        ListPreference(
          value = value,
          onValueChange = { value = it },
          values = listOf("Google Translate", "OpenAI-compatible", "DeepL", "Gemini"),
          title = { Text("Translation provider") },
          valueToText = { AnnotatedString(it) },
        )
      } }
    }
    composeRule.onNodeWithText("Translation provider").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Gemini").assertIsDisplayed().performClick()
    composeRule.waitForIdle()
    assertEquals("Gemini", value)
    assertTrue(composeRule.onAllNodes(hasText("DeepL")).fetchSemanticsNodes().isEmpty())
  }

  @Test fun textFieldPreferenceSheetAcceptsInputAndConfirms() {
    var value by mutableStateOf(10)
    composeRule.setContent {
      MpvInfinityTheme { ProvidePreferenceLocals {
        TextFieldPreference(
          value = value,
          onValueChange = { value = it },
          title = { Text("Seek seconds") },
          textToValue = { it.toIntOrNull() },
        )
      } }
    }
    composeRule.onNodeWithText("Seek seconds").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("10").performTextReplacement("25")
    val ok = RuntimeEnvironment.getApplication().getString(android.R.string.ok)
    // Short sheets used to be clipped to nothing under glass (pane on the offset-free sheet modifier).
    composeRule.onNode(hasText(ok) and hasClickAction() and !hasSetTextAction()).assertIsDisplayed().performClick()
    composeRule.waitForIdle()
    assertEquals(25, value)
  }
}

/** Every settings option/form popup opens as the shared frosted bottom sheet; true alerts stay dialogs. */
class GlassOptionSheetConversionContractTest {
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "app/src/main/java").isDirectory }
  private val pref = "app/src/main/java/app/infinity/mpvz/ui/preferences"

  private fun sheetCalls(path: String): List<String> {
    val s = File(root, path).readText()
    return Regex("GlassOptionSheet\\(").findAll(s).map { s.substring(it.range.first, minOf(s.length, it.range.first + 400)) }.toList()
  }

  @Test fun convertedSettingsPopupsUseTheFrostedSheet() {
    val expected = mapOf(
      "$pref/SubtitlesPreferencesScreen.kt" to 2, "$pref/FoldersPreferencesScreen.kt" to 2,
      "$pref/GesturePreferencesScreen.kt" to 1, "$pref/ModelSearchDialog.kt" to 1,
      "$pref/AiIntegrationScreen.kt" to 1, "$pref/NuvioAddonsPreferences.kt" to 1,
      "$pref/AudioPreferencesScreen.kt" to 1, "$pref/PlayerControlsPreferencesScreen.kt" to 1,
      "$pref/PlayerPreferencesScreen.kt" to 1, "$pref/components/OptionsDialog.kt" to 1,
      "$pref/components/SettingsPreferenceWrappers.kt" to 2,
      "app/src/main/java/app/infinity/mpvz/ui/browser/dialogs/SortDialog.kt" to 1,
    )
    for ((path, count) in expected) {
      val calls = sheetCalls(path)
      assertEquals(path, count, calls.size)
      for (call in calls) {
        assertTrue("$path keeps the frosted pane", call.contains("settingsGlassSurface(cornerRadius = 28.dp, independent = true)"))
        assertTrue("$path keeps an opaque fallback", call.contains("containerColor = settingsGlassContainerColor("))
        assertFalse("$path has no AlertDialog-only args", call.contains("properties =") || call.contains("tonalElevation ="))
      }
    }
  }

  @Test fun glassIsNeverPlacedOnARawModalBottomSheetModifier() {
    val main = File(root, "app/src/main/java")
    val offenders = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "GlassOptionSheet.kt" }
      .filter { f ->
        val s = f.readText()
        Regex("(?<![A-Za-z])ModalBottomSheet\\(").findAll(s).any { m ->
          val args = s.substring(m.range.last, minOf(s.length, m.range.last + 600)).substringBefore(") {")
          args.contains("glassOptionSheetModifier()") || args.contains("settingsGlassSurface(")
        }
      }.map { it.name }.toList()
    assertTrue("glass pane must move with the sheet content: $offenders", offenders.isEmpty())
    for (f in listOf("ui/browser/jellyfin/AddJellyfinServerDialog.kt", "ui/browser/jellyfin/seerr/SeerrConnectionDialog.kt",
      "ui/browser/dialogs/AddConnectionDialog.kt", "ui/browser/dialogs/SharedServerDialogs.kt")) {
      val s = File(main, "app/infinity/mpvz/$f").readText()
      assertTrue(f, s.contains("GlassModalBottomSheet("))
      assertFalse("$f pads the nav bar twice", s.contains(".navigationBarsPadding()"))
    }
  }

  @Test fun sheetAppliesOneBottomInsetAndBoundsItsContent() {
    // Code only: KDoc may name the APIs it warns against.
    val s = File(root, "app/src/main/java/app/infinity/mpvz/ui/components/GlassOptionSheet.kt").readLines()
      .filterNot { it.trimStart().let { l -> l.startsWith("*") || l.startsWith("/*") || l.startsWith("//") } }
      .joinToString("\n")
    assertTrue(s.contains("contentWindowInsets = { WindowInsets(0, 0, 0, 0) }"))
    assertEquals(1, Regex("windowInsetsPadding\\(").findAll(s).count())
    assertFalse(s.contains("navigationBarsPadding"))
    assertTrue(s.contains(".heightIn(max = maxHeight)"))
    assertTrue(s.contains("Box(Modifier.fillMaxWidth().weight(1f, fill = false)) { content() }"))
    // The pane is drawn by the content column (moves with the sheet), not the sheet's own surface.
    assertTrue(s.contains(".then(paneModifier)"))
    assertTrue(s.contains("containerColor = Color.Transparent,"))
    assertTrue(s.contains("dragHandle = null,"))
    // The sheet itself must not wrap content in another vertical scroll (the open crash).
    assertFalse(s.substringAfter("ModalBottomSheet(").contains("verticalScroll"))
  }
}
