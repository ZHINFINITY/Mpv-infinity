#!/usr/bin/env python3
"""Build-free guards for complete, registered, genuinely translated new language packs."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'app/src/main/res'
LANGS = {'id': 'Indonesian', 'vi': 'Vietnamese', 'fa': 'Persian', 'pl': 'Polish', 'ta': 'Tamil'}
TOKEN = re.compile(r'%%|%(?:\d+\$)?[+#\- 0,.(\d]*[a-zA-Z]|<[^>]+>|\{[^}]+\}|@[A-Za-z0-9_./:-]+')


def entries(path):
    output = {}
    for element in ET.parse(path).getroot():
        name = element.get('name')
        assert name not in output, (path, name)
        output[name] = element
    return output


class LocaleResourcesTest(unittest.TestCase):
    def test_picker_registers_the_actual_new_resource_qualifiers(self):
        source = (ROOT / 'app/src/main/java/app/infinity/mpvz/ui/preferences/AdvancedPreferencesScreen.kt').read_text()
        for code, name in LANGS.items():
            self.assertRegex(source, rf'\b{name}\("{code}"\)')
            self.assertTrue((RES / f"values-{'in' if code == 'id' else code}/strings.xml").is_file())

    def test_each_pack_covers_every_canonical_key_and_plural_quantity(self):
        base = entries(RES / 'values/strings.xml')
        for code in LANGS:
            actual = entries(RES / f"values-{'in' if code == 'id' else code}/strings.xml")
            self.assertEqual(set(base), set(actual), code)
            for name, element in base.items():
                translated = actual[name]
                self.assertEqual(element.tag, translated.tag, (code, name))
                if element.tag == 'plurals':
                    quantities = {e.get('quantity'): e for e in translated}
                    sources = {e.get('quantity'): e for e in element}
                    expected = set(sources) | {'few', 'many'} if code == 'pl' else set(sources)
                    self.assertEqual(expected, set(quantities))
                    for quantity, value in quantities.items():
                        source = sources.get(quantity, sources['other'])
                        self.assertEqual(sorted(TOKEN.findall(source.text or '')),
                            sorted(TOKEN.findall(value.text or '')), (code, name))
                elif element.get('formatted') == 'false':
                    structure = re.compile(r'<[^>]+>|\{[^}]+\}|@[A-Za-z0-9_./:-]+')
                    self.assertEqual(sorted(structure.findall(element.text or '')),
                        sorted(structure.findall(translated.text or '')), (code, name))
                    self.assertEqual((element.text or '').count('%'), (translated.text or '').count('%'), (code, name))
                else:
                    self.assertEqual(sorted(TOKEN.findall(''.join(element.itertext()))),
                        sorted(TOKEN.findall(''.join(translated.itertext()))), (code, name))

    def test_core_settings_and_popup_labels_are_not_english_fallback(self):
        base = entries(RES / 'values/strings.xml')
        required = ['pref_preferences', 'pref_player', 'generic_reset',
            'pref_custom_theme_title', 'pref_custom_theme_summary',
            'pref_custom_theme_create', 'pref_custom_theme_mute_video']
        for code in LANGS:
            actual = entries(RES / f"values-{'in' if code == 'id' else code}/strings.xml")
            for name in required:
                self.assertTrue((actual[name].text or '').strip(), (code, name))
                self.assertNotEqual(base[name].text, actual[name].text, (code, name))
            human = [name for name,e in base.items() if e.tag == 'string' and e.get('translatable') != 'false']
            changed = sum(actual[name].text != base[name].text for name in human)
            self.assertGreaterEqual(changed, len(human) * 0.85, code)

    def test_indonesian_uses_supported_resource_qualifier_and_modern_selector_tag(self):
        self.assertTrue((RES / 'values-in/strings.xml').is_file())
        self.assertFalse((RES / 'values-id/strings.xml').exists())
        selector = (ROOT / 'app/src/main/java/app/infinity/mpvz/ui/preferences/AdvancedPreferencesScreen.kt').read_text()
        self.assertIn('Indonesian("id")', selector)

if __name__ == '__main__':
    unittest.main()
