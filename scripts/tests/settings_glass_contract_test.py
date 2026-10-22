#!/usr/bin/env python3
"""Static contract checks for the shared settings Liquid Glass surface."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def read(rel: str) -> str:
    return (ROOT / rel).read_text()

def main() -> None:
    glass = read("app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsGlass.kt")
    card = read("app/src/main/java/app/infinity/mpvz/ui/preferences/CardPreferences.kt")
    rows = read("app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsComponents.kt")
    switch = read("app/src/main/java/app/infinity/mpvz/ui/preferences/components/SwitchPreference.kt")
    controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/GlassControls.kt")
    assert "fun Modifier.settingsGlassSurface" in glass
    assert "liquidGlassSurfaces.collectAsState()" in glass
    assert "liquidGlassPane(" in glass
    assert "import androidx.compose.material3.Card" in card
    assert "LocalSettingsGlassOwner provides true" in card
    assert ".settingsGlassSurface(cornerRadius = 28.dp)" in card
    assert "GlassSwitch" in rows
    assert "settingsGlassPreferenceRow" not in glass + rows + switch
    assert "LocalSettingsGlassOwner.current && !independent" in glass
    assert "if (settingsGlassEnabled())" not in rows
    assert "onValueChange" in switch
    assert "onCheckedChange" in controls and "onValueChangeFinished" in controls
    assert "horizontalInset" in controls
    print("settings_glass_contract_test: PASS")

if __name__ == "__main__":
    main()
