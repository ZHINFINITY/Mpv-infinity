#!/usr/bin/env python3
"""Bounded licence wording/history checks, not a legal or ownership opinion.

SPDX-License-Identifier: LicenseRef-MpvInfinity-Permission-Only-1.0
Copyright (c) 2026 ZHINFINITY. See LICENSE and LICENSE-BOUNDARY.md.
"""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASELINE = "7015805f6831cbf8a9fc2e6d5dd5c70af209b638"


def text(relative: str) -> str:
    """Normalize wrapping without changing words or legal meaning."""
    return " ".join((ROOT / relative).read_text(encoding="utf-8").split())


def main() -> None:
    available = subprocess.run(
        ["git", "cat-file", "-e", f"{BASELINE}:LICENSE"], cwd=ROOT,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
    )
    if available.returncode:
        raise SystemExit(
            "Baseline licence object missing: fetch full repository history "
            "(CI requires actions/checkout fetch-depth: 0) and retry."
        )
    assert (ROOT / "HISTORICAL-LICENSE-POLYFORM-BASELINE.md").read_bytes() == (
        subprocess.check_output(["git", "show", f"{BASELINE}:LICENSE"], cwd=ROOT)
    ), "baseline licence was altered"
    agpl = "HISTORICAL-LICENSE-AGPL-3.0-or-later.txt"
    assert (ROOT / agpl).read_bytes() == subprocess.check_output(
        ["git", "show", f"{BASELINE}:{agpl}"], cwd=ROOT
    ), "historical AGPL text was altered"

    terms = text("LICENSE")
    for required in (
        "# Mpv∞ Permission-Only Terms 1.0",
        "all copyrightable project code",
        "owns or has express authority",
        "frame-interpolation code and feature updates carried forward unchanged",
        "Coverage is not limited to material newly written",
        "prior written permission from ZHINFINITY",
        "commercial and noncommercial activities alike",
        "Permission for everyone to use official app releases",
        "to download, install, update, and run official, unmodified",
        "No individual permission request is required",
        "commercial purposes",
        "Sharing a link to",
        "general source-code reuse or republication rights",
        "must not falsely",
        "do not withdraw, narrow, replace, or amend",
        "Earlier grants are not limited to people who already made a fork",
        "GitHub's rights to host",
        "expressly acceptable to ZHINFINITY",
        "not a separate general source-reuse or publication licence",
        "relinking, or reverse engineering",
        "have not been approved by legal counsel",
    ):
        assert required in terms, required
    for obsolete in (
        "only new, original copyrightable",
        "No general personal-use or noncommercial-use permission is granted",
        "## Noncommercial Purposes",
    ):
        assert obsolete not in terms, obsolete

    boundary = text("LICENSE-BOUNDARY.md")
    for required in (
        BASELINE, "no new playback feature", "transitive", "AGPL", "mpvRx",
        "legal review", "LicenseRef-MpvInfinity-Permission-Only-1.0",
        "not an authorship cutoff", "Both are **unassigned**",
        "unchanged user-owned frame-interpolation code", "authority",
        "not limited to people who already forked",
    ):
        assert required in boundary, required
    readme = text("README.md")
    for required in (
        "retains its existing grants",
        "Everyone may download, install, update, and use official",
        "including unchanged owned/authorized frame-interpolation code",
        "forking are expressly acceptable",
    ):
        assert required in readme, required
    contributing = text("CONTRIBUTING.md")
    assert "no general contribution-workflow exception" in contributing
    assert "not an assignment of copyright" in contributing
    for relative in (
        "THIRD_PARTY_SOURCE.md", "THIRD_PARTY_LICENSES",
        "app/src/main/assets/mpv/licenses",
        "app/src/main/assets/licenses/third-party-runtime",
        "media3-decoder-ffmpeg/LICENSE", "media3-decoder-ffmpeg/NOTICE",
        "app/src/main/cpp/third_party/quickjs/LICENSE",
        "app/src/main/cpp/mpvflow/mediacodec-vulkan/LICENSE.LGPL",
    ):
        path = ROOT / relative
        assert path.exists(), relative
        if path.is_file():
            assert path.stat().st_size > 0, relative
        else:
            assert any(p.is_file() and p.stat().st_size > 0
                       for p in path.rglob("*")), relative
    # Run the draft's implementation/no-notice-change gate separately; making
    # it permanent here would prohibit legitimate future implementation edits.
    print("historical licence bytes, release-scope/app-use/fork wording and notice presence verified")


if __name__ == "__main__":
    main()
