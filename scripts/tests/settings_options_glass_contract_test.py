from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SETTINGS = ROOT / 'app/src/main/java/app/infinity/mpvz/ui/preferences'
WRAPPERS = SETTINGS / 'components/SettingsPreferenceWrappers.kt'


def masked(text):
    pattern = r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])\''
    return re.sub(pattern, lambda m: ' ' * len(m.group()), text)


def arguments(text, begin):
    mask = masked(text)
    parens = braces = brackets = 0
    for index in range(begin + 1, len(text)):
        char = mask[index]
        if char == ')' and parens == braces == brackets == 0:
            return text[begin + 1:index]
        parens += (char == '(') - (char == ')')
        braces += (char == '{') - (char == '}')
        brackets += (char == '[') - (char == ']')
    raise AssertionError('Unclosed popup arguments')


class SettingsOptionsGlassContract(unittest.TestCase):
    def test_settings_use_compatible_app_owned_preference_rows(self):
        source = WRAPPERS.read_text()
        for signature in ('fun Preference(', 'fun <T> ListPreference(', 'fun SliderPreference(', 'fun <T> TextFieldPreference('):
            self.assertIn(signature, source)
        for path in SETTINGS.rglob('*.kt'):
            if path == WRAPPERS:
                continue
            self.assertIsNone(re.search(r'^import me\.zhanghai\.compose\.preference\.(Preference|ListPreference|SliderPreference|TextFieldPreference)\b', path.read_text(), re.M), str(path))

    def test_real_glass_slider_and_outer_option_surfaces_are_used(self):
        source = WRAPPERS.read_text()
        self.assertIn('GlassSlider(', source)
        self.assertNotIn('actualGlassSlider', source)
        self.assertGreaterEqual(source.count('containerColor = Color.Transparent'), 3)
        self.assertGreaterEqual(source.count('Modifier.settingsGlassSurface(cornerRadius ='), 3)
        for original in ('LibraryPreference(', 'LibraryListPreference(', 'LibrarySliderPreference(', 'LibraryTextFieldPreference('):
            self.assertIn(original, source)
        self.assertIn('onValueChangeFinished = { onValueChange(latest) }', source)
        self.assertIn('heightIn(min = 48.dp)', source)
        self.assertIn('Role.RadioButton', source)
        self.assertIn('TextFieldValue.Saver', source)

    def test_every_direct_settings_popup_uses_glass_and_conditional_fill(self):
        checked = 0
        for path in SETTINGS.rglob('*.kt'):
            if path == WRAPPERS:
                continue
            source = path.read_text()
            for match in re.finditer(r'\b(AlertDialog|DropdownMenu|ModalBottomSheet)\s*\(', masked(source)):
                begin = source.index('(', match.start())
                args = arguments(source, begin)
                self.assertIn('settingsGlassSurface(', args, str(path))
                self.assertIn('settingsGlassContainerColor(', args, str(path))
                checked += 1
        self.assertGreaterEqual(checked, 29)
        self.assertIn('Color.Transparent else fallback', (SETTINGS / 'components/SettingsGlass.kt').read_text())


if __name__ == '__main__':
    unittest.main()
