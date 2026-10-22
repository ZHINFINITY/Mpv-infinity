#!/usr/bin/env python3
"""Portable source/resource guards; actual Android resolution is tested in Robolectric."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
CUSTOM = ROOT / 'app/src/main/java/app/infinity/mpvz/ui/preferences/components/CustomThemeSettings.kt'
ACTIVITY = ROOT / 'app/src/main/java/app/infinity/mpvz/MainActivity.kt'
RES = ROOT / 'app/src/main/res'


class SettingsPopupLocaleContractTest(unittest.TestCase):
    def test_custom_theme_settings_has_no_hardcoded_user_visible_labels(self):
        source = CUSTOM.read_text()
        labels = re.findall(r'Text\("|contentDescription\s*=\s*"|ThemeAdjustmentSlider\("', source)
        self.assertEqual(labels, [], 'custom-theme settings labels must use stringResource')
        self.assertIn('stringResource(R.string.pref_custom_theme_title)', source)
        self.assertIn('stringResource(R.string.pref_custom_theme_selected, selected.name)', source)
        self.assertIn('stringResource(R.string.generic_cancel)', source)

    def test_activity_dialog_reads_recomposing_compose_resources(self):
        source = ACTIVITY.read_text()
        dialog = source[source.index('AlertDialog(', source.index('showRendererBuildNotice')):]
        self.assertIn('stringResource(R.string.renderer_build_notice_title)', dialog)
        self.assertIn('stringResource(R.string.generic_ok)', dialog)
        self.assertNotIn('getString(', dialog[:1200])

    def test_every_popup_key_is_committed_and_available_in_each_locale(self):
        canonical = {e.get('name'):e for e in ET.parse(RES / 'values/strings.xml').getroot()}
        keys = {name for name in canonical if name.startswith('pref_custom_theme_')} | {'generic_save'}
        self.assertGreaterEqual(len(keys), 30)
        self.assertEqual(canonical['pref_custom_theme_selected'].text, 'Selected in theme rail: %1$s')
        used = set(re.findall(r'R\.string\.(pref_custom_theme_\w+|generic_save)', CUSTOM.read_text()))
        self.assertTrue(used <= keys)
        for code in ['ar','de','es','fr','hi','ja','pt-rBR','ru','zh-rCN','id','vi','fa','pl','ta']:
            translated = {e.get('name'):e for e in ET.parse(RES / f'values-{code}/strings.xml').getroot()}
            self.assertTrue(keys <= set(translated), code)
            self.assertTrue(all((translated[name].text or '').strip() for name in keys), code)


if __name__ == '__main__':
    unittest.main()
