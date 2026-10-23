#!/usr/bin/env python3
"""Offline coverage/format guards; completed pre-localization values remain immutable."""
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
ROOT = REPO / 'app/src/main/res'
BASELINE = '9f6e58127dfa08840ac314da85e4fdbcfdbe6429'
LOCALES = ['ar', 'de', 'es', 'fr', 'hi', 'ja', 'pt-rBR', 'ru', 'zh-rCN']
TOKEN = re.compile(r'%%|%(?:\d+\$)?[+#\- 0,.(\d]*[a-zA-Z]|<[^>]+>|\{[^}]+\}|@[A-Za-z0-9_./:-]+')

def tokens(element):
    pattern = TOKEN if element.get('formatted') != 'false' else re.compile(r'<[^>]+>|\{[^}]+\}|@[A-Za-z0-9_./:-]+')
    return sorted(pattern.findall(element.text or ''))


def mapping(root):
    result = {}
    for element in root:
        name = element.get('name')
        assert name not in result, f'duplicate resource {name}'
        result[name] = element
    return result


def signature(element):
    # ElementTree indentation/tails are not resource-value changes.
    value = element.text or '' if not list(element) else ''
    return element.tag, sorted(element.attrib.items()), value, tuple(signature(child) for child in element)


def main():
    canonical = mapping(ET.parse(ROOT / 'values/strings.xml').getroot())
    preserved = 0
    for code in LOCALES:
        got = mapping(ET.parse(ROOT / f'values-{code}/strings.xml').getroot())
        raw = subprocess.check_output(['git', 'show',
            f'{BASELINE}:app/src/main/res/values-{code}/strings.xml'], cwd=REPO)
        old = mapping(ET.fromstring(raw))
        assert set(canonical) <= set(got), (code, sorted(set(canonical)-set(got)))
        for name, element in old.items():
            assert name in got, (code, name)
            source = canonical.get(name)
            completed = element.tag != 'string' or source is None or (element.text or '') != (source.text or '')
            if completed:
                assert signature(element) == signature(got[name]), f'{code}: completed value changed: {name}'
                preserved += 1
        for name, source in canonical.items():
            actual = got[name]
            assert actual.tag == source.tag, (code, name, actual.tag, source.tag)
            if source.tag == 'string':
                assert tokens(source) == tokens(actual), (code, name)
                if source.get('formatted') == 'false':
                    assert (source.text or '').count('%') == (actual.text or '').count('%'), (code, name, 'literal percent')
            elif source.tag == 'plurals':
                quantities = {e.get('quantity') for e in actual}
                assert 'other' in quantities and quantities <= {'zero', 'one', 'two', 'few', 'many', 'other'}, (code, name)
        for name in ['pref_custom_theme_title', 'pref_custom_theme_summary', 'pref_custom_theme_mute_video']:
            assert (got[name].text or '').strip(), (code, name)
            assert got[name].text != canonical[name].text, f'{code}: popup still English: {name}'
        if code in {'hi', 'ru'}:
            english_fragments = re.compile(r'\b(?:photo|theme|themes|adjust|later|create|mute|selected|rail)\b', re.I)
            for name, element in got.items():
                if name.startswith('pref_custom_theme_'):
                    assert not english_fragments.search(element.text or ''), (code, name, 'mixed-English popup')
    print(f'PASS: {len(LOCALES)} existing locales cover {len(canonical)} canonical keys; {preserved} completed values preserved')


if __name__ == '__main__':
    main()
