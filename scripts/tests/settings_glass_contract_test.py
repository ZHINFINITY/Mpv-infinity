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
    assert "import androidx.compose.material3.Card" not in card and "Individual preference wrappers own their surfaces" in card
    assert "settingsGlassPreferenceRow" in rows and "GlassSwitch" in rows
    assert "SettingsGlassPolicy.geometry(enabled)" in glass
    assert "geometry.cornerRadiusDp.dp" in glass
    assert "if (settingsGlassEnabled())" not in rows
    assert "settingsGlassPreferenceRow" in switch and "onValueChange" in switch
    assert "onCheckedChange" in controls and "onValueChangeFinished" in controls
    assert "horizontalInset" in controls
    print("settings_glass_contract_test: PASS")

if __name__ == "__main__":
    main()
