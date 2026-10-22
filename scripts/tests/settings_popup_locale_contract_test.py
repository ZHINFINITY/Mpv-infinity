#!/usr/bin/env python3
"""Build-free guards for localized settings and popup UI paths.

These tests intentionally inspect source rather than claiming runtime locale coverage;
Android/Compose instrumentation is required to exercise actual dialogs.
"""
from pathlib import Path
import json
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
CUSTOM = ROOT / "app/src/main/java/app/infinity/mpvz/ui/preferences/components/CustomThemeSettings.kt"
ACTIVITY = ROOT / "app/src/main/java/app/infinity/mpvz/MainActivity.kt"
KEYS = ROOT.parent / "output/settings-popup-new-localization-keys.json"


class SettingsPopupLocaleContractTest(unittest.TestCase):
    def test_custom_theme_settings_has_no_hardcoded_user_visible_labels(self):
        source = CUSTOM.read_text()
        # Placeholder is intentionally an input-format example, not UI copy.
        labels = re.findall(r'Text\("|contentDescription\s*=\s*"|ThemeAdjustmentSlider\("', source)
        self.assertEqual(labels, [], "custom-theme settings labels must use stringResource")
        self.assertIn("stringResource(R.string.pref_custom_theme_title)", source)
        self.assertIn("stringResource(R.string.pref_custom_theme_selected, selected.name)", source)
        self.assertIn("stringResource(R.string.generic_cancel)", source)

    def test_activity_dialog_reads_recomposing_compose_resources(self):
        source = ACTIVITY.read_text()
        dialog = source[source.index("AlertDialog(", source.index("showRendererBuildNotice")):]
        self.assertIn("stringResource(R.string.renderer_build_notice_title)", dialog)
        self.assertIn("stringResource(R.string.generic_ok)", dialog)
        self.assertNotIn("getString(", dialog[:1200])

    def test_new_keys_are_explicitly_handed_off_to_resource_owner(self):
        payload = json.loads(KEYS.read_text())
        self.assertGreaterEqual(len(payload), 30)
        self.assertTrue(all(key.startswith("pref_custom_theme_") or key == "generic_save" for key in payload))
        self.assertEqual(payload["pref_custom_theme_selected"], "Selected in theme rail: %1$s")


if __name__ == "__main__":
    unittest.main()
