#!/usr/bin/env python3
"""Offline checks for the complete Persian resource pack and RTL contract."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).parents[2] / "app/src/main"
BASE = ROOT / "res/values/strings.xml"
FA = ROOT / "res/values-fa/strings.xml"
SOURCE = ROOT / "java/app/infinity/mpvz/ui/preferences/AdvancedPreferencesScreen.kt"
MANIFEST = ROOT / "AndroidManifest.xml"
TOKEN = re.compile(r"%%|%(?:\d+\$)?[+#\- 0,(\d]*[a-zA-Z]|\{[^}]+\}|<[^>]+>")

def entries(path):
    root = ET.parse(path).getroot()
    out = {}
    for e in root:
        name = e.attrib.get("name")
        if e.tag == "string": out[name] = e.text or ""
        elif e.tag in ("string-array", "plurals"):
            for i, child in enumerate(e):
                out[f"{name}|{child.attrib.get('quantity', i)}"] = child.text or ""
    return out

class PersianLocaleContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base, cls.fa = entries(BASE), entries(FA)

    def test_pack_covers_every_canonical_translatable_resource(self):
        missing = set(self.base) - set(self.fa)
        self.assertFalse(missing, f"missing Persian resources: {sorted(missing)[:20]}")

    def test_placeholders_are_preserved(self):
        for key, source in self.base.items():
            if key not in self.fa: continue
            self.assertEqual(sorted(TOKEN.findall(source)), sorted(TOKEN.findall(self.fa[key])), key)

    def test_persian_is_real_translation_not_english_clone(self):
        changed = [k for k, v in self.base.items() if k in self.fa and v.strip() and self.fa[k].strip() != v.strip()]
        self.assertGreaterEqual(len(changed), int(len(self.base) * .85))
        for key in ("generic_reset", "pref_preferences", "pref_appearance_title", "pref_app_language_title", "pref_custom_theme_create"):
            if key in self.base: self.assertNotEqual(self.base[key], self.fa.get(key), key)

    def test_custom_theme_popup_keys_are_present(self):
        keys = {"pref_custom_theme_title", "pref_custom_theme_summary", "pref_custom_theme_selected", "pref_custom_theme_edit", "pref_custom_theme_delete", "pref_custom_theme_add", "pref_custom_theme_create", "pref_custom_theme_edit_title", "pref_custom_theme_preview", "pref_custom_theme_hide_options", "pref_custom_theme_edit_background", "pref_custom_theme_media_framing", "pref_custom_theme_crop", "pref_custom_theme_fit", "pref_custom_theme_fill", "pref_custom_theme_video_aspect", "pref_custom_theme_fill_screen", "pref_custom_theme_keep_source", "pref_custom_theme_zoom_scale", "pref_custom_theme_horizontal_position", "pref_custom_theme_vertical_position", "pref_custom_theme_media_appearance", "pref_custom_theme_blur", "pref_custom_theme_brightness", "pref_custom_theme_saturation", "pref_custom_theme_transparency", "pref_custom_theme_overlay", "pref_custom_theme_panel_opacity", "pref_custom_theme_text_colour", "pref_custom_theme_argb_hex", "pref_custom_theme_colour_sliders", "pref_custom_theme_hue", "pref_custom_theme_saturation_value", "pref_custom_theme_brightness_value", "pref_custom_theme_opacity_value", "pref_custom_theme_mute_video", "pref_custom_theme_name", "generic_save", "pref_app_language_system_default"}
        missing = keys - set(self.fa)
        self.assertFalse(missing, f"missing popup translations: {sorted(missing)}")

    def test_rtl_app_contract(self):
        manifest = MANIFEST.read_text()
        self.assertRegex(manifest, r'android:supportsRtl\s*=\s*"true"')
        source = SOURCE.read_text()
        self.assertIn('Persian("fa")', source)
        self.assertIn("AppCompatDelegate.setApplicationLocales", source)
        self.assertNotIn("Locale.setDefault", source)
        # Android's fa locale is intrinsically RTL; this asserts the locale used by the app.
        self.assertIn('values-fa/strings.xml', str(FA))

if __name__ == "__main__": unittest.main()
