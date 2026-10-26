/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.preferences.components

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.AndroidPreferenceStore
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Reset/Cancel/Save row at sheet widths from a 320dp phone (280dp after the
 * editor's 20dp sides) up to a Poco F7-class phone, in the locales with the longest labels,
 * with Liquid Glass off and on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeEditorActionRowRuntimeTest {
  @get:Rule val composeRule = createComposeRule()
  private lateinit var appearance: AppearancePreferences

  @Before fun installPreferences() {
    val context = RuntimeEnvironment.getApplication()
    val store = context.getSharedPreferences("theme-editor-actions-runtime", Context.MODE_PRIVATE)
    store.edit().clear().commit()
    appearance = AppearancePreferences(AndroidPreferenceStore(context, store))
    startKoin { modules(module { single { appearance } }) }
  }

  @After fun closeKoin() { stopKoin() }

  @Test @Config(qualifiers = "in-w480dp-h900dp-xxhdpi") fun indonesianHasTheLongestResetAndCancel() =
    assertOneEqualRow(expectedReset = "Mengatur ulang")

  @Test @Config(qualifiers = "fr-w480dp-h900dp-xxhdpi") fun frenchHasTheLongestSave() = assertOneEqualRow()
  @Test @Config(qualifiers = "de-w480dp-h900dp-xxhdpi") fun german() = assertOneEqualRow()
  @Test @Config(qualifiers = "hi-w480dp-h900dp-xxhdpi") fun hindi() = assertOneEqualRow()
  @Test @Config(qualifiers = "ta-w480dp-h900dp-xxhdpi") fun tamil() = assertOneEqualRow()
  @Test @Config(qualifiers = "ru-w480dp-h900dp-xxhdpi") fun russian() = assertOneEqualRow()
  @Test @Config(qualifiers = "w480dp-h900dp-xxhdpi") fun english() = assertOneEqualRow(expectedReset = "Reset")

  private fun assertOneEqualRow(expectedReset: String? = null) {
    val context = RuntimeEnvironment.getApplication()
    val labels = listOf(R.string.generic_reset, R.string.generic_cancel, R.string.generic_save)
      .map(context::getString)
    expectedReset?.let { assertEquals("locale qualifier must load the translation", it, labels[0]) }
    var resets = 0
    var cancels = 0
    var saves = 0
    val width = mutableIntStateOf(425)
    composeRule.setContent {
      MpvInfinityTheme {
        Box(Modifier.width(width.intValue.dp)) {
          ThemeEditorActionRow(
            saveEnabled = true,
            onReset = { resets++ },
            onCancel = { cancels++ },
            onSave = { saves++ },
          )
        }
      }
    }
    for (w in listOf(425, 353, 320, 280)) for (glass in listOf(false, true)) {
      composeRule.runOnIdle { width.intValue = w; appearance.liquidGlassSurfaces.set(glass) }
      composeRule.waitForIdle()
      val bounds = listOf(ThemeEditorResetTag, ThemeEditorCancelTag, ThemeEditorSaveTag)
        .map { composeRule.onNodeWithTag(it).getUnclippedBoundsInRoot() }
      val widths = bounds.map { (it.right - it.left).value }
      val heights = bounds.map { (it.bottom - it.top).value }
      bounds.forEach { assertEquals("w=$w glass=$glass same row", bounds[0].top.value, it.top.value, 0.5f) }
      assertTrue(bounds[0].right <= bounds[1].left && bounds[1].right <= bounds[2].left)
      widths.forEach { assertEquals("w=$w glass=$glass equal widths $widths", widths[0], it, 0.5f) }
      heights.forEach { assertEquals("w=$w glass=$glass equal heights $heights", heights[0], it, 0.5f) }
      assertTrue("min touch height", heights[0] >= 52f - 0.5f)
      val icon = composeRule.onAllNodesWithTag(ThemeEditorResetIconTag, useUnmergedTree = true)
        .fetchSemanticsNodes().size
      // Phone-class sheets (Poco F7 and typical 393dp+ phones) keep the Reset icon.
      if (w >= 425) assertEquals("w=$w Reset keeps its icon", 1, icon)
      for ((index, label) in labels.withIndex()) {
        val layout = textLayout(label)
        assertEquals("$label one line", 1, layout.lineCount)
        assertTrue(layout.layoutInput.style.fontSize.value >= ThemeEditorLayoutPolicy.MinLabelSp - 0.01f)
        // Only on a 320dp phone (280dp sheet) may Indonesian "Mengatur ulang" fall back to the
        // ellipsis; its full text stays in semantics for TalkBack.
        val ellipsisAllowed = w < 320 && index == 0 && label == "Mengatur ulang"
        if (!ellipsisAllowed) {
          assertFalse("w=$w $label (glass=$glass) is ellipsized", layout.isLineEllipsized(0))
          assertFalse("w=$w $label (glass=$glass) overflows", layout.didOverflowWidth)
        }
      }
    }
    composeRule.onNodeWithTag(ThemeEditorResetTag).performClick()
    composeRule.onNodeWithTag(ThemeEditorCancelTag).performClick()
    composeRule.onNodeWithTag(ThemeEditorSaveTag).performClick()
    composeRule.runOnIdle { assertEquals(listOf(1, 1, 1), listOf(resets, cancels, saves)) }
  }

  private fun textLayout(label: String): TextLayoutResult {
    val node = composeRule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
    val results = mutableListOf<TextLayoutResult>()
    node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
    return results.single()
  }
}
