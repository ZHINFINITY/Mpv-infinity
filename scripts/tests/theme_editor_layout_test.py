#!/usr/bin/env python3
"""Static guards for the production custom-theme editor layout contract."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"


def read(relative: str) -> str:
    return (JAVA / relative).read_text()


class ThemeEditorLayoutSourceTest(unittest.TestCase):
    def test_actual_editor_caller_remains_the_custom_theme_settings_root(self):
        screen = read("ui/preferences/AppearancePreferencesScreen.kt")
        editor = read("ui/preferences/components/CustomThemeSettings.kt")
        self.assertIn("CustomThemeSettings(", screen)
        self.assertIn("ModalBottomSheet(", editor)
        self.assertIn("ThemeEditorLayoutPolicy", editor)

    def test_editor_body_scrolls_while_actions_are_outside_scroll_container(self):
        editor = read("ui/preferences/components/CustomThemeSettings.kt")
        self.assertIn(".verticalScroll(rememberScrollState())", editor)
        self.assertIn("ThemeEditorActionRow(", editor)
        # The action row is after (outside) the weighted scroll column.
        self.assertLess(editor.index(".verticalScroll(rememberScrollState())"), editor.index("ThemeEditorActionRow("))
        self.assertIn(".imePadding()", editor)
        # GlassModalBottomSheet owns the single bottom inset; a second nav-bar pad doubled it.
        self.assertIn("GlassModalBottomSheet(", editor)
        self.assertNotIn(".navigationBarsPadding()", editor)
        # Owner request: Reset, Cancel and Save always share one row with equal size.
        actions = read("ui/preferences/components/ThemeEditorActions.kt")
        self.assertIn("heightIn(min = 52.dp)", actions)
        self.assertIn("Modifier.weight(1f).fillMaxHeight()", actions)
        self.assertIn("height(IntrinsicSize.Min)", actions)
        self.assertIn("Icons.RoundedFilled.Refresh", actions)
        self.assertIn("maxLines = 1", actions)
        self.assertIn("ThemeEditorLayoutPolicy.nextLabelSp(", actions)
        self.assertNotIn("Column(", actions)
        self.assertNotIn("stackActions", editor)

    def test_actual_actions_use_shared_material_without_changing_handlers(self):
        editor = read("ui/preferences/components/CustomThemeSettings.kt")
        actions = read("ui/preferences/components/ThemeGlassActions.kt")
        self.assertIn("ThemeGlassButton as Button", editor)
        self.assertIn("ThemeGlassOutlinedButton as OutlinedButton", editor)
        self.assertIn("onClick = onClick", actions)
        self.assertIn("enabled = enabled", actions)
        self.assertIn("settingsGlassSurface", actions)
        self.assertIn("onSave(edited.copy(name = name.trim()))", editor)
        self.assertIn("settingsGlassSurface", editor)
        self.assertNotIn("settingsGlassPreferenceRow().padding(12.dp)", editor)
        self.assertNotIn("Card(", editor)

    def test_editor_does_not_use_a_fixed_fraction_that_clips_bottom_actions(self):
        editor = read("ui/preferences/components/CustomThemeSettings.kt")
        self.assertNotIn("fillMaxHeight(0.94f)", editor)
        self.assertNotIn("padding(top = 8.dp, bottom = 12.dp)", editor)


if __name__ == "__main__":
    unittest.main()
