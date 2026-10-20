# Next-source-release licensing boundary

## Draft only: do not merge or publish a release yet

The proposed [Mpv∞ Permission-Only Terms 1.0](LICENSE) require prior written
permission for copying, use, reuse, modification, and publication of qualifying
new ZHINFINITY-owned material, whether commercial or noncommercial. This is
not PolyForm Noncommercial, and not an open-source licence. The exact text must
be approved by the owner and reviewed by qualified legal counsel before
publication. Automated review is not a legal opinion.

## Exact source boundary

- Baseline: [`7015805f6831cbf8a9fc2e6d5dd5c70af209b638`](https://github.com/ZHINFINITY/Mpv-infinity/commit/7015805f6831cbf8a9fc2e6d5dd5c70af209b638), the public merge of PR #85.
- The baseline's complete root licence is preserved byte-for-byte in
  [HISTORICAL-LICENSE-POLYFORM-BASELINE.md](HISTORICAL-LICENSE-POLYFORM-BASELINE.md).
- Earlier tagged snapshots retain the licences distributed with them. For
  example, tag `v2.1.5` (`71cc7d0ad7e83e1a9f2097d1f3ce0e583aaf156f`) has an
  AGPL-3.0-or-later root licence. No existing tag or history is changed here.
- The proposed boundary is the first commit descended from that baseline that
  introduces this document together with the root title
  `Mpv∞ Permission-Only Terms 1.0`. Its commit SHA identifies the boundary,
  regardless of the eventual release name. No next release number is assigned
  by this PR and the application version is not changed.
- New material in this proposal is limited to licensing policy/documentation
  and the permission-only regression test, its CI invocation, and a preview
  footer licence label. There is no new playback feature.
  Existing source headers and every third-party notice remain unchanged.
- Future original, exclusively owned contributions intended to use the new
  terms should be explicitly marked
  `SPDX-License-Identifier: LicenseRef-MpvInfinity-Permission-Only-1.0`,
  with `Copyright (c) <year> ZHINFINITY` and `See LICENSE and LICENSE-BOUNDARY.md`.
  Do not replace older notices. In mixed files, identify the particular new
  contribution and its introduction commit; a file-wide new header must not
  purport to cancel earlier or third-party rights.

**The interpolation and feature updates already present at the baseline were
public under the baseline's licence. This documentation-only change cannot make
that existing code exclusively permission-only, even in a new release.** Older
AGPL grants also remain effective where applicable. Merely changing a header,
release name, or licence file does not erase previous grants. The existing
`PolyForm-Noncommercial-1.0.0` headers intentionally remain in place for
previously published material; consult the historical baseline licence for them.

## Third-party and ownership inventory

This is an index, not a claim of ownership or an exhaustive dependency audit.
All component notices, including ones not listed here, take precedence for
their own material. Keep the source/replacement manifest and all notice files
intact when assembling a release.

| Material / location | Applicable terms and scope |
| --- | --- |
| Previously published Mpv∞ source | Its original AGPL, PolyForm, or other grants remain available; see the history and both historical licence files. |
| mpvRx / mpvEx / mpv-android lineage, other contributors | Excluded from the new ZHINFINITY-owned scope. Separately documented permissions do not themselves transfer ownership; any future ownership-based scope change needs its own review. A previous authorization to use PolyForm does not establish authorization for these different terms. |
| MPV/libmpv, libplacebo, libbluray, libudfread, FFmpeg | Independent component licences; see `app/src/main/assets/mpv/licenses/`, `app/src/main/assets/ffmpeg/FFMPEG-LICENSE.md`, `THIRD_PARTY_LICENSES/`, and [THIRD_PARTY_SOURCE.md](THIRD_PARTY_SOURCE.md) for source/relink obligations and differing LGPL versions. |
| Media3, AndroidX, native decoder module | See `media3-decoder-ffmpeg/LICENSE`, `media3-decoder-ffmpeg/NOTICE`, `app/src/main/assets/licenses/media3/`, and each dependency's own terms. |
| QuickJS, QuickJS-KT, Mbed TLS, wrappers and native imported code | See component-specific notices, including `app/src/main/cpp/third_party/quickjs/LICENSE`, `app/src/main/cpp/mpvflow/mediacodec-vulkan/LICENSE.LGPL`, and MPV licence assets. Not relicensed here. |
| Python, yt-dlp and bundled runtime data | See runtime notices under `THIRD_PARTY_LICENSES/` and `app/src/main/assets/licenses/third-party-runtime/`; the source manifest records acquisition and reproducibility limitations. |
| Fonts, Noto, Mozilla CA data, Anime4K / HDR shaders and other assets | Retain OFL, MPL, and each asset's independent upstream terms; no ownership or blanket custom restriction is asserted here. |
| Maven, JitPack and other transitive dependencies | Each dependency keeps its own terms. Produce a release-specific dependency/notice inventory before claiming completeness. |

## Required before approval and release

1. The owner reviews and approves the exact root `LICENSE`; obtain qualified
   legal advice on enforceability, attribution, the permission mechanism, and
   the intended distribution model. `ZHINFINITY` is the supported repository
   identity, not a verified legal name or evidence of an assignment.
2. Establish provenance of any code proposed for new coverage. Retain actual
   upstream/contributor permissions or assignments; repository history and
   replaced AGPL libraries alone do not prove authority to relicense derived
   application code. Exclude material with unresolved ownership.
3. Keep interpolation source/settings and all existing notices unchanged in
   this PR. Check with `git diff --exit-code
   7015805f6831cbf8a9fc2e6d5dd5c70af209b638 -- app subtitle-libass
   media3-decoder-ffmpeg THIRD_PARTY_LICENSES THIRD_PARTY_SOURCE.md
   HISTORICAL-LICENSE-AGPL-3.0-or-later.txt` (join as one shell command).
4. For any binary release, separately verify the actual packaged notices,
   corresponding LGPL source, modifications, build instructions, and any
   required relinkable application materials. Existing build checks and source
   URLs are not a legal compliance certificate. The standalone FFmpeg helper
   recipe and a complete transitive notice inventory still need verification.
5. Record the approved boundary commit in the eventual release notes and
   distinguish genuinely new permission-only contributions from earlier code.
   Do not retag older releases or label the entire mixed repository exclusively
   permission-only. This PR neither creates nor publishes a tagged release.

A public GitHub repository still allows viewing and GitHub forking under
[GitHub's platform terms](https://docs.github.com/articles/licensing-a-repository).
Permission-only terms cannot technically prevent copying or guarantee that
another infringement will not occur. No repository visibility change is made
by this proposal.
