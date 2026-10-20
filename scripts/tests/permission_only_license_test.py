#!/usr/bin/env python3
"""Check licensing boundaries; not a legal-enforceability or ownership opinion.

SPDX-License-Identifier: LicenseRef-MpvInfinity-Permission-Only-1.0
Copyright (c) 2026 ZHINFINITY. See LICENSE and LICENSE-BOUNDARY.md.
"""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASELINE = "7015805f6831cbf8a9fc2e6d5dd5c70af209b638"


def main() -> None:
    historical = ROOT / "HISTORICAL-LICENSE-POLYFORM-BASELINE.md"
    original = subprocess.check_output(
        ["git", "show", f"{BASELINE}:LICENSE"], cwd=ROOT
    )
    assert historical.read_bytes() == original, "baseline licence was altered"
    agpl = ROOT / "HISTORICAL-LICENSE-AGPL-3.0-or-later.txt"
    assert agpl.read_bytes() == subprocess.check_output(
        ["git", "show", f"{BASELINE}:{agpl.name}"], cwd=ROOT
    ), "historical AGPL text was altered"

    terms = (ROOT / "LICENSE").read_text(encoding="utf-8")
    for required in (
        "# Mpv∞ Permission-Only Terms 1.0",
        "prior written permission from ZHINFINITY",
        "commercial and noncommercial activities alike",
        "No general personal-use or noncommercial-use permission is granted",
        "must not falsely",
        "do not withdraw, narrow, replace, or amend",
        "GitHub's rights to host",
        "relinking, or reverse engineering",
        "only new, original copyrightable",
        "have not been\napproved by legal counsel",
    ):
        assert required in terms, required
    assert "## Noncommercial Purposes" not in terms
    assert "LicenseRef-MpvInfinity-Permission-Only-1.0" in (
        ROOT / "LICENSE-BOUNDARY.md"
    ).read_text(encoding="utf-8")
    boundary = (ROOT / "LICENSE-BOUNDARY.md").read_text(encoding="utf-8")
    for required in (BASELINE, "cannot make", "no new playback feature",
                     "transitive", "AGPL", "mpvRx", "legal counsel"):
        assert required in boundary, required
    for relative in (
        "THIRD_PARTY_SOURCE.md", "THIRD_PARTY_LICENSES",
        "app/src/main/assets/mpv/licenses",
        "app/src/main/assets/licenses/third-party-runtime",
        "media3-decoder-ffmpeg/LICENSE", "media3-decoder-ffmpeg/NOTICE",
        "app/src/main/cpp/third_party/quickjs/LICENSE",
        "app/src/main/cpp/mpvflow/mediacodec-vulkan/LICENSE.LGPL",
    ):
        assert (ROOT / relative).exists(), relative
    print("prospective-only licence boundaries and historical texts verified")


if __name__ == "__main__":
    main()
