# FongMi GPU motion interpolation sources

This directory retains the locally adapted FongMi motion core from [FongMi/mpv commit `f0ebca2a2c546444d9f9a62662b2f6e27614f7de`](https://github.com/FongMi/mpv/tree/f0ebca2a2c546444d9f9a62662b2f6e27614f7de/video/out/gpu_next). The upstream progress notes are at [`DOCS/memc-progress.md`](https://github.com/FongMi/mpv/blob/04e9d60470cfac3b407bc8bafcd54e2955e8f761/DOCS/memc-progress.md). Its files retain their LGPL-2.1-or-later SPDX notices; local changes added pre-analysis and caller-owned output-texture paths for the former Media3 integration.

This older helper snapshot is retained with its source attribution, but it is not linked into the restored Native Media3 engine and is not copied into the current MPV runtime. The experimental MPV runtime now checks out and builds native FongMi/mpv commit [`14b3999a1a3e322c217bc90ee4df5a8f1bc88f69`](https://github.com/FongMi/mpv/tree/14b3999a1a3e322c217bc90ee4df5a8f1bc88f69) directly; `scripts/prepare-mpvflow.py` validates that upstream source rather than applying a local MEMC patch. The FongMi GPL-2.0 attribution and full upstream `LICENSE.GPL` text are preserved in `app/src/main/assets/mpv/licenses/` alongside the per-file notices.

Keep this attribution and each source-file notice with the retained code. This branch remains experimental, unmerged, and unreleased.
