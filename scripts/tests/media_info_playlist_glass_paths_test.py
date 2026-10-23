#!/usr/bin/env python3
"""Actual Kotlin call-argument contracts; not device/rendering tests."""
from pathlib import Path
import re
from settings_options_glass_contract_test import arguments, masked

BASE = Path(__file__).resolve().parents[2] / 'app/src/main/java/app/infinity/mpvz'

def function(text, name):
    match = re.search(r'\bfun\s+(?:Modifier\.)?' + name + r'\s*\(', masked(text))
    assert match, name
    signature_start = text.index('(', match.start())
    signature_end = signature_start + len(arguments(text, signature_start)) + 2
    start = text.index('{', signature_end)
    clean = masked(text)
    depth = 0
    for end in range(start, len(clean)):
        depth += (clean[end] == '{') - (clean[end] == '}')
        if depth == 0:
            return text[start + 1:end]
    raise AssertionError('Unclosed function: ' + name)

def named(args):
    clean = masked(args)
    depth = 0
    start = 0
    result = {}
    for i, char in enumerate(clean + ','):
        depth += (char in '([{') - (char in ')]}')
        if char == ',' and depth == 0:
            part = args[start:i]
            match = re.match(r'\s*(\w+)\s*=\s*', masked(part))
            if match:
                result[match.group(1)] = part[match.end():].strip()
            start = i + 1
    return result

def calls(text, name):
    return [named(arguments(text, text.index('(', m.start())))
            for m in re.finditer(r'\b' + name + r'\s*\(', masked(text))]

def main():
    info = (BASE / 'ui/mediainfo/MediaInfoActivity.kt').read_text()
    screen = function(info, 'MediaInfoScreen')
    scaffold = calls(screen, 'Scaffold')
    assert len(scaffold) == 1
    assert scaffold[0]['containerColor'] == 'settingsGlassContainerColor(MaterialTheme.colorScheme.background)'
    actions = calls(screen, 'FilledTonalIconButton')
    assert len(actions) == 2
    for action in actions:
        assert 'settingsGlassSurface(cornerRadius = 20.dp)' in action['modifier']
        assert 'settingsGlassContainerColor(MaterialTheme.colorScheme.secondaryContainer)' in action['colors']
        assert 'if (glass) MaterialTheme.colorScheme.onSurface' in action['colors']
        assert 'scope.launch' in action['onClick']
    error = function(info, 'ErrorContent')
    card = calls(error, 'Card')
    assert len(card) == 1
    assert 'settingsGlassSurface' in card[0]['modifier']
    assert 'settingsGlassContainerColor(MaterialTheme.colorScheme.errorContainer)' in card[0]['colors']
    assert 'if (glass) MaterialTheme.colorScheme.error else' in error
    dialog = calls(function(info, 'ValueDetailDialog'), 'AlertDialog')
    assert len(dialog) == 1
    assert 'settingsGlassSurface(cornerRadius = 28.dp, independent = true)' in dialog[0]['modifier']
    assert dialog[0]['containerColor'] == 'settingsGlassContainerColor(androidx.compose.material3.AlertDialogDefaults.containerColor)'
    assert 'settingsGlassContainerColor(MaterialTheme.colorScheme.background)' in function(info, 'MediaInfoContent')

    playlist = (BASE / 'ui/player/controls/components/sheets/PlaylistSheet.kt').read_text()
    for component in ('PlaylistTrackListItem', 'PlaylistTrackGridItem'):
        rows = calls(function(playlist, component), 'Surface')
        assert len(rows) >= 1
        assert '.playlistItemGlass()' in rows[0]['modifier'], component
        assert 'RoundedCornerShape(12.dp)' in rows[0]['shape']
        assert '.clickable(onClick = onClick)' in rows[0]['modifier']
    header = calls(function(playlist, 'PlaylistSheet'), 'IconButton')
    assert len(header) == 2
    assert all('.playlistItemGlass(999.dp)' in action['modifier'] for action in header)
    helper = function(playlist, 'playlistItemGlass')
    panes = calls(helper, 'playerGlassPane')
    assert len(panes) == 1
    assert panes[0]['enabled'] == 'enabled'
    assert 0 < float(panes[0]['opacity'].removesuffix('f')) <= 0.45
    assert 'appearance.liquidGlassSurfaces.collectAsState()' in helper
    assert 'LocalLiquidGlassBackdrop provides' not in helper
    assert 'Bitmap' not in helper
    assert 'getThumbnailFromMemory(video, thumbWidth, thumbHeight)' in playlist
    print('media-info and bounded playlist actual-path checks passed')

if __name__ == '__main__':
    main()
