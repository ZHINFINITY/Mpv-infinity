#!/usr/bin/env python3
"""
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
See LICENSE for terms.

Verify license notices on the folder-blacklist merge files.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OWNED = [
    ROOT / "app/src/main/java/app/infinity/mpvz/preferences/FolderBlacklistMatcher.kt",
    ROOT / "app/src/main/java/app/infinity/mpvz/ui/browser/folderlist/FolderListBlacklistFilter.kt",
    ROOT / "app/src/test/java/app/infinity/mpvz/ui/browser/folderlist/FolderBlacklistInversionTest.kt",
    ROOT / "app/src/test/java/app/infinity/mpvz/ui/browser/folderlist/FolderListBlacklistFilterTest.kt",
]
POLYFORM = "SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0"
AGPL = "SPDX-License-Identifier: AGPL"


def main() -> None:
    """Keep project notices and independently licensed runtime notices intact."""
    missing = [str(path.relative_to(ROOT)) for path in OWNED if POLYFORM not in path.read_text()]
    if missing:
        raise AssertionError(f"missing PolyForm header: {', '.join(missing)}")

    production = OWNED[:2]
    mislabeled = [str(path.relative_to(ROOT)) for path in production if AGPL in path.read_text()]
    if mislabeled:
        raise AssertionError(f"new production AGPL label: {', '.join(mislabeled)}")

    notices = {
        "Python-3.13.12.txt": "PYTHON SOFTWARE FOUNDATION LICENSE",
        "Go-Noto-OFL-1.1.txt": "SIL OPEN FONT LICENSE",
        "Mozilla-CA-MPL-2.0.txt": "Mozilla Public License",
    }
    for filename, marker in notices.items():
        repository = ROOT / "THIRD_PARTY_LICENSES" / filename
        packaged = ROOT / "app/src/main/assets/licenses/third-party-runtime" / filename
        text = repository.read_text(encoding="utf-8")
        assert len(text) > 1000 and marker in text, filename
        assert packaged.read_text(encoding="utf-8") == text, filename

    print("project headers and packaged third-party license notices OK")


if __name__ == "__main__":
    main()
