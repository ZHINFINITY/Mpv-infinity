/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser.dialogs

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import app.infinity.mpvz.R
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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runner.RunWith

/** Exercises the actual option tree, shared host, callbacks and scroll reachability. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SortDialogGlassRuntimeTest {
  @get:Rule val composeRule = createComposeRule()
  private lateinit var appearance: AppearancePreferences

  @Before fun installPreferences() {
    val context = RuntimeEnvironment.getApplication()
    val store = context.getSharedPreferences("sort-dialog-glass-runtime", Context.MODE_PRIVATE)
    store.edit().clear().commit()
    appearance = AppearancePreferences(AndroidPreferenceStore(context, store))
    startKoin { modules(module { single { appearance } }) }
  }

  @After fun closeKoin() { stopKoin() }

  @Test fun realSortViewFieldsAndSlidersRemainReachableAndClickableAcrossMasterChanges() {
    var sorts = 0
    var orders = 0
    var trees = 0
    var grids = 0
    var checks = 0
    var fields = 0
    val folderColumns = mutableListOf<Int>()
    val videoColumns = mutableListOf<Int>()
    val pairedColumns = mutableStateOf(false)
    val fieldsLabel = RuntimeEnvironment.getApplication().getString(R.string.ui_fields)
    composeRule.setContent {
      MpvInfinityTheme {
        SortDialog(
          isOpen = true,
          onDismiss = {},
          title = "View options",
          sortType = "Title",
          onSortTypeChange = { if (it == "Date") sorts++ },
          sortOrderAsc = true,
          onSortOrderChange = { if (!it) orders++ },
          types = listOf("Title", "Date"),
          icons = listOf(Icons.RoundedFilled.Title, Icons.RoundedFilled.CalendarToday),
          getLabelForType = { _, _ -> "A-Z" to "Z-A" },
          viewModeSelector = MultiViewModeSelector(
            label = "View Mode",
            options = listOf(
              ViewModeOption("Folder", Icons.RoundedFilled.ViewModule, true, {}),
              ViewModeOption("Tree", Icons.RoundedFilled.AccountTree, false, { trees++ }),
            ),
          ),
          layoutModeSelector = ViewModeSelector(
            label = "Layout",
            firstOptionLabel = "List",
            secondOptionLabel = "Grid",
            firstOptionIcon = Icons.RoundedFilled.ViewList,
            secondOptionIcon = Icons.RoundedFilled.GridView,
            isFirstOptionSelected = true,
            onViewModeChange = { if (!it) grids++ },
            checkboxLabel = "Center titles",
            onCheckboxChange = { checks++ },
          ),
          folderGridColumnSelector = GridColumnSelector("Folder columns", 2, { folderColumns += it }),
          videoGridColumnSelector = if (pairedColumns.value) GridColumnSelector("Video columns", 2, { videoColumns += it }) else null,
          visibilityToggles = listOf(VisibilityToggle("Duration field", false, { fields++ })),
        )
      }
    }
    composeRule.onNodeWithText("View options").assertIsDisplayed()
    for ((index, enabled) in listOf(false, true, false).withIndex()) {
      println("SortDialog interaction pass=$index liquidGlass=$enabled")
      composeRule.runOnIdle {
        appearance.liquidGlassSurfaces.set(enabled)
        pairedColumns.value = enabled
      }
      composeRule.waitForIdle()
      // performScrollTo handles the nearest scrolling ancestor only. Sort types
      // live in a horizontal strip inside the vertical popup, so reveal that
      // strip vertically before asking it to reveal one of its own children.
      composeRule.onNodeWithText("Title").onParent().performScrollTo()
      composeRule.onNodeWithText("Title").performScrollTo().assertIsDisplayed()
      for (label in listOf("Folder", "List")) {
        composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
      }
      composeRule.onNodeWithText("Date").onParent().performScrollTo()
      composeRule.onNodeWithText("Date").performScrollTo().assertIsDisplayed().performClick()
      composeRule.onNodeWithText("Z-A").performScrollTo().performClick()
      composeRule.onNodeWithText("Tree").performScrollTo().performClick()
      composeRule.onNodeWithText("Grid").performScrollTo().performClick()
      composeRule.onNodeWithText("Center titles").performScrollTo().performClick()
      composeRule.onNodeWithContentDescription("Folder columns").performScrollTo()
        .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> assertTrue(setProgress(3f)) }
      if (enabled) {
        composeRule.onNodeWithContentDescription("Video columns").performScrollTo()
          .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> assertTrue(setProgress(3f)) }
      }
      composeRule.onNodeWithText(fieldsLabel).performScrollTo().performClick()
      composeRule.onNodeWithText("Duration field").performScrollTo().performClick()
      composeRule.onNodeWithText(fieldsLabel).performScrollTo().performClick()
      composeRule.runOnIdle {
        assertEquals(index + 1, sorts)
        assertEquals(index + 1, orders)
        assertEquals(index + 1, trees)
        assertEquals(index + 1, grids)
        assertEquals(index + 1, checks)
        assertEquals(index + 1, fields)
        assertEquals(List(index + 1) { 3 }, folderColumns)
      }
    }
    assertEquals(listOf(3), videoColumns)
  }
}
