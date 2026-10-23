#!/usr/bin/env python3
"""Source contract for the compressor and crop-editor conditional glass paths."""
from pathlib import Path
import re
from media_info_playlist_glass_paths_test import calls as named_calls
ROOT = Path(__file__).resolve().parents[2]
COMP = ROOT / "app/src/main/java/app/infinity/mpvz/ui/browser/dialogs/VideoCompressorOverlay.kt"
CLIP = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/clip/ClipOverlayView.kt"

def source(path):
    return path.read_text()

def calls(text, name):
    """Return balanced argument scopes for actual Kotlin calls."""
    out=[]
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", text):
        start=m.end()-1; depth=0; quote=None; escape=False
        for i in range(start, len(text)):
            c=text[i]
            if quote:
                if escape: escape=False
                elif c=='\\': escape=True
                elif c==quote: quote=None
            elif c in "\"'": quote=c
            elif c=='(': depth+=1
            elif c==')':
                depth-=1
                if depth==0:
                    out.append(text[start+1:i]); break
    return out

def main():
    comp, clip = source(COMP), source(CLIP)
    # Three reachable state screens must use conditional fallback, not an opaque Scaffold.
    scaffolds=calls(comp,"Scaffold")
    assert len(scaffolds)==3
    assert all("settingsGlassContainerColor" in a for a in scaffolds)
    for scaffold in named_calls(comp, 'Scaffold'):
        assert scaffold['containerColor'].startswith('settingsGlassContainerColor(')
    # Every internal card, including preset/default cards, owns one bounded pane and fallback.
    cards=calls(comp,"ElevatedCard") + calls(comp,"OutlinedCard")
    assert len(cards)==11, len(cards)
    assert all("settingsGlassSurface" in a and "settingsGlassContainerColor" in a for a in cards)
    for card in named_calls(comp, 'ElevatedCard') + named_calls(comp, 'OutlinedCard'):
        assert 'settingsGlassSurface(' in card['modifier']
        assert 'settingsGlassContainerColor(' in card['colors']
    for card in named_calls(comp, 'ElevatedCard'):
        assert card['elevation'] == 'compressorGlassCardElevation()'
        radius = 28 if card.get('shape') == 'AppShapeScale.extraLarge' else 12
        assert f'cornerRadius = {radius}.dp' in card['modifier']
    for card in named_calls(comp, 'OutlinedCard'):
        assert card['shape'] == 'AppShapeScale.largeIncreased'
        assert 'cornerRadius = 20.dp' in card['modifier']
    elevation = comp.split('private fun compressorGlassCardElevation()', 1)[1]
    assert 'if (settingsGlassEnabled())' in elevation
    for state in ('default', 'pressed', 'focused', 'hovered', 'dragged', 'disabled'):
        assert f'{state}Elevation = 0.dp' in elevation
    assert 'else CardDefaults.elevatedCardElevation()' in elevation
    # Progress/result/error named surfaces are covered by the card calls and stage functions.
    for fn in ("CompressorProgressSurface", "CompressorResultSurface", "CompressorIssueSurface"):
        assert fn in comp
    # Settings sheet and all five dialog entry points must own conditional material.
    # The pane moves with the sheet content (GlassModalBottomSheet), never on the raw sheet modifier.
    assert not calls(comp,"ModalBottomSheet")
    sheet=calls(comp,"GlassModalBottomSheet")
    dialogs=calls(comp,"AlertDialog")
    assert len(sheet)==1 and "settingsGlassSurface" in sheet[0] and "settingsGlassContainerColor" in sheet[0]
    assert len(dialogs)==5
    assert all("settingsGlassSurface" in a and "settingsGlassContainerColor" in a for a in dialogs)
    for popup in named_calls(comp, 'GlassModalBottomSheet'):
        assert 'settingsGlassSurface(' in popup['paneModifier']
        assert popup['containerColor'].startswith('settingsGlassContainerColor(')
    for popup in named_calls(comp, 'AlertDialog'):
        assert 'settingsGlassSurface(' in popup['modifier']
        assert popup['containerColor'].startswith('settingsGlassContainerColor(')
    # Shared switch optics are used for every actual compressor toggle.
    assert comp.count("GlassSwitch(") >= 7
    assert "IconSwitch(" not in comp
    # Crop toolbar is the only ClipOverlayView container and cannot paint its old opaque fill.
    crop=calls(clip,"Surface")
    assert len(crop)==1
    assert "settingsGlassSurface" in crop[0]
    assert "settingsGlassContainerColor" in crop[0]
    assert "surfaceContainerHigh.copy(alpha = 0.96f)" in crop[0]
    assert "color = MaterialTheme.colorScheme.surfaceContainerHigh.copy" not in crop[0]
    crop_args = named_calls(clip, 'Surface')[0]
    assert crop_args['color'].startswith('settingsGlassContainerColor(')
    assert crop_args['shape'] == 'CircleShape'
    assert 'cornerRadius = 999.dp' in crop_args['modifier']
    assert crop_args['tonalElevation'] == 'if (glass) 0.dp else 6.dp'
    assert crop_args['shadowElevation'] == 'if (glass) 0.dp else 8.dp'
    # Guard against reintroducing unconditional opaque Material fills in audited call scopes.
    for a in scaffolds + cards + sheet + dialogs + crop:
        assert not re.search(r"containerColor\s*=\s*MaterialTheme\.colorScheme\.", a)

if __name__ == "__main__":
    main()
