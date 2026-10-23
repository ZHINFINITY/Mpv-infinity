#!/usr/bin/env python3
"""Structural checks for the alternate search and settings switch migration."""
from pathlib import Path
import re
from settings_options_glass_contract_test import arguments, masked

ROOT = Path(__file__).parents[2]
SRC = ROOT / "app/src/main/java/app/infinity/mpvz"
AUDIO = SRC / "ui/browser/audiobooks/AudiobookLibraryScreen.kt"
NAV = SRC / "ui/browser/navidrome/NavidromeContent.kt"
SETTINGS = [SRC / "ui/preferences/AboutScreen.kt", SRC / "ui/preferences/CatalogAddonsPreferences.kt", SRC / "ui/preferences/NuvioAddonsPreferences.kt"]

def functions(text):
    # Balanced brace extraction is enough to ensure checks inspect real call scopes.
    out=[]
    for m in re.finditer(r"(?:fun|private fun)\s+\w+[^\{]*\{", text):
        i=m.end()-1; depth=0
        for j in range(i,len(text)):
            depth += text[j]=='{'; depth -= text[j]=='}'
            if depth==0:
                out.append(text[m.start():j+1]); break
    return out

def calls(text, name):
    return [arguments(text, text.index('(', m.start()))
            for m in re.finditer(rf"\b{name}\s*\(", masked(text))]

def main():
    a=AUDIO.read_text(); n=NAV.read_text()
    assert "import app.infinity.mpvz.ui.components.InlineSearchBar" in a
    assert "import app.infinity.mpvz.ui.components.InlineSearchBar" in n
    af=functions(a); nf=functions(n)
    ac=calls(a, "InlineSearchBar"); nc=calls(n, "InlineSearchBar")
    assert len(ac) == 2 and len(nc) == 1
    for args in ac + nc:
        for required in ("query =", "onQueryChange =", "onSearch =", "WindowInsets(0.dp)"):
            assert required in args, (required, args)
    assert "onQueryChange = { query = it }" in ac[0]
    assert "onSearch = { query = it }" in ac[0]
    assert "onQueryChange = { searchQuery = it }" in ac[1]
    assert "onSearch = ::performSearch" in ac[1]
    assert "onQueryChange = viewModel::onSearchQueryChanged" in nc[0]
    assert "onSearch = viewModel::onSearchQueryChanged" in nc[0]
    # Verify each actual search component call carries both query and callback args.
    assert any("InlineSearchBar(" in f and "query = query" in f and "onQueryChange = { query = it }" in f and "onSearch = { query = it }" in f for f in af)
    assert any("InlineSearchBar(" in f and "query = searchQuery" in f and "onQueryChange = { searchQuery = it }" in f and "onSearch = ::performSearch" in f for f in af)
    assert any("InlineSearchBar(" in f and "query = uiState.searchQuery" in f and "onQueryChange = viewModel::onSearchQueryChanged" in f and "onSearch = viewModel::onSearchQueryChanged" in f for f in nf)
    assert "OutlinedTextField(" not in a[a.find("if (contentMode"):a.find("PrimaryScrollableTabRow")]
    assert "OutlinedTextField(" not in n[n.find("if (isSearching)"):n.find("} else {", n.find("if (isSearching)"))]
    preferences = SRC / "ui/preferences"
    for p in SETTINGS:
        s=p.read_text(); fs=functions(s)
        assert "import app.infinity.mpvz.ui.player.controls.components.GlassSwitch" in s
        assert not re.search(r"(?<!Glass)\bSwitch\s*\(", s)
        for args in calls(s, "GlassSwitch"):
            assert "checked =" in args and "onCheckedChange =" in args, args
        assert any("GlassSwitch(" in f and "checked =" in f and "onCheckedChange =" in f for f in fs)
    # No preference screen may bypass the shared glass-aware switch wrapper.
    for p in preferences.rglob("*.kt"):
        s=p.read_text()
        assert not re.search(r"(?<!Glass)\bSwitch\s*\(", s), p
    print("alternate search and GlassSwitch checks passed")

if __name__ == "__main__": main()
