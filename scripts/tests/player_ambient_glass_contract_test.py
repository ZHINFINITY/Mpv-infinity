"""Contract for the player Liquid Glass ambient-colour fallback."""
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/theme/LiquidGlassBackdrop.kt"


def test_player_glass_does_not_paint_theme_film_without_sampled_scene():
    source = SOURCE.read_text()
    assert "val scene = backdrop?.value" in source
    assert "if (scene != null || backdrop == null) {" in source
    assert "drawRoundRect(PlayerAmbientTintPolicy.filmColor(themedTint, PlayerAmbientTint.color,\n            isPlayerHost = backdrop != null).copy(alpha = filmAlpha)" in source
    # Browser/settings fallbacks never receive a player backdrop and must keep their film.
    assert "LocalLiquidGlassBackdrop provides" not in (ROOT / "app/src/main/java/app/infinity/mpvz/ui/theme/BrowserGlassScene.kt").read_text()
    # The sampled-scene path remains intact; this is deliberately not a global
    # disable of the Liquid Glass material.
    assert "paint.shader = shader" in source
    assert "let the ambient colour show" in source


if __name__ == "__main__":
    test_player_glass_does_not_paint_theme_film_without_sampled_scene()
    print("player ambient glass contract: PASS")
