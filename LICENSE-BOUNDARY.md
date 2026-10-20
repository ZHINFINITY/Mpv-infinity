# Upcoming-release licensing boundary and ownership gaps

## Owner-approved source policy; no tagged release published

The [Mpv∞ Permission-Only Terms 1.0](LICENSE) are the licence offer for
all project material in the covered source snapshot and upcoming release that ZHINFINITY owns or has express
authority to offer under those exact terms. **This includes carried-forward,
unchanged user-owned frame-interpolation code and feature updates; it is not
limited to newly authored additions.** Independently licensed third-party,
upstream, and contributor material keeps its applicable terms and notices.
Authority for a different licence is not automatically authority for this one.

Everyone may download, install, update, and run official releases without
asking, including for commercial work and necessary installation/backup copies.
GitHub's built-in public-repository viewing and forking are expressly acceptable
platform activities, including a fork under another user's account. A public
fork can be visible on GitHub; this acknowledgement is not a general licence
for external code reuse, APK rehosting, app-store release, or republication.
Sharing official download links is allowed.

This is a custom licence offer, not PolyForm Noncommercial or an open-source
licence. The owner authorized merging this policy after reviewing the draft.
Qualified legal review is recommended before a tagged/binary release.
Automated review is not a legal opinion or a compliance certificate.

## Effective source boundary and upcoming release

The historical reference snapshot is public PR #85's merge,
[`7015805f6831cbf8a9fc2e6d5dd5c70af209b638`](https://github.com/ZHINFINITY/Mpv-infinity/commit/7015805f6831cbf8a9fc2e6d5dd5c70af209b638).
Its root licence is preserved byte-for-byte in
[HISTORICAL-LICENSE-POLYFORM-BASELINE.md](HISTORICAL-LICENSE-POLYFORM-BASELINE.md).
That reference is **not an authorship cutoff** and does not exclude older
ZHINFINITY-owned interpolation or feature code from the new release's offer.

The effective source boundary is the merge commit of
[PR #87](https://github.com/ZHINFINITY/Mpv-infinity/pull/87) that first places
these owner-approved terms on protected `main`. The PR's merge-commit SHA
uniquely identifies that snapshot without a self-referential commit hash in
this file. Before that merge this branch is preparatory; after it the offer
covers the entire owned/expressly authorized portions of that source snapshot,
not merely the documentation diff. Later source snapshots may retain the offer.

The next tagged release's version and source SHA remain **unassigned**. Record
both in the release notes and release documentation before publication. This
policy merge does not bump the application version, create a tag, publish a
release, rewrite history, or change repository visibility.

This PR changes licensing/documentation, the bounded licence test and its CI
invocation, and a preview footer label only. There is no new playback feature.
All application source, interpolation settings, binaries, and existing
third-party notices remain unchanged from the historical reference snapshot.
The broader licence offer does not require changing the playback implementation.

## Earlier rights remain usable, not just existing forks

The same owned code can be offered under these new terms while licences
previously granted for that code remain available. In particular, the
interpolation and feature code already public at the reference snapshot retains
its existing grants even when included unchanged in the upcoming release.
The proposal cannot make those portions exclusively permission-only or require
permission for activities already authorized by an earlier applicable licence.

This protection is not limited to people who already forked or downloaded.
Others may also obtain and use earlier-licensed material wherever those grants
permit, including from other recipients. They must comply with the earlier
licence. Earlier grants do not automatically cover later additions absent from
those snapshots. Tag `v2.1.5` (`71cc7d0ad7e83e1a9f2097d1f3ce0e583aaf156f`)
and other historical releases retain their original licences; the historical
AGPL text remains in `HISTORICAL-LICENSE-AGPL-3.0-or-later.txt`.

Existing `PolyForm-Noncommercial-1.0.0` source headers are retained as evidence
of the earlier offer, not as an assertion that all such files are wholly owned
by ZHINFINITY. The root `LICENSE` states the separate upcoming-release offer
for owned/expressly authorized portions without deleting those earlier grants.
For future exclusively covered material, the local identifier is
`SPDX-License-Identifier: LicenseRef-MpvInfinity-Permission-Only-1.0`.
In mixed files, identify the covered portions and their provenance instead of
relabeling the entire file or removing existing copyright/licence notices.

## Scope inventory and gaps that prevent whole-codebase coverage

This is a source index, not an ownership certificate or exhaustive dependency
audit. No in-tree path-by-path assignment/authorization register was found.
The following candidate areas do not prove exclusive ownership merely by their
location, contributor identity, or existing SPDX header.

| Material / source location | Proposed treatment and unresolved evidence |
| --- | --- |
| ZHINFINITY-owned application changes, including original interpolation/feature code in `app/src/main/cpp/mpvflow/`, `app/src/main/cpp/media3flow_vulkan_jni.cpp`, and Kotlin UI/engine code | Included in the new-release offer to the extent actually owned, including unchanged portions. Mixed upstream material must be identified separately; the directory or header alone is not proof. |
| mpvRx / mpvEx / mpv-android lineage and other contributors' code | Keep applicable upstream/contributor terms. Including any portion in a separate custom offer requires documented express authority for those exact portions and these terms; prior PolyForm consent or a library replacement is not sufficient evidence. No such complete authorization register is established by this PR. |
| Contributor/agent-labelled history | Commit author names do not establish copyright assignments or the provenance of generated/copied material. Confirm human contributions, upstream sources, and actual authority; do not claim unknown material. |
| MPV/libmpv, libplacebo, libbluray, libudfread, FFmpeg | Not relicensed. See `app/src/main/assets/mpv/licenses/`, `app/src/main/assets/ffmpeg/FFMPEG-LICENSE.md`, `THIRD_PARTY_LICENSES/`, and [THIRD_PARTY_SOURCE.md](THIRD_PARTY_SOURCE.md). Preserve component-specific LGPL versions and required debugging, source, replacement, and relink rights/materials. |
| Media3, AndroidX and native decoder module | Not relicensed. See `media3-decoder-ffmpeg/LICENSE`, `media3-decoder-ffmpeg/NOTICE`, `app/src/main/assets/licenses/media3/`, and each dependency's own terms. |
| QuickJS, QuickJS-KT, Mbed TLS, wrappers and imported native code | Not relicensed. Retain component notices, including `app/src/main/cpp/third_party/quickjs/LICENSE`, `app/src/main/cpp/mpvflow/mediacodec-vulkan/LICENSE.LGPL`, and MPV licence assets. |
| Python, yt-dlp, fonts, Noto, Mozilla CA data, Anime4K/HDR shaders and bundled assets | Not blanket-covered. Retain their runtime, PSF, OFL, MPL, and independent upstream notices in component paths and packaged assets. |
| Maven/JitPack and transitive dependencies | Not relicensed. A complete release-specific dependency/notice inventory remains to be verified. |

**Consequently, the offer is broader than new additions but cannot honestly
label the full mixed codebase exclusively permission-only.** Ownership and
specific authorizations determine actual coverage, and earlier licence grants
remain independently usable even for owned code. No complete ownership audit
or blanket upstream approval is asserted here.

## Approval and distribution gates

1. The owner has authorized the policy merge. Qualified legal advice on scope,
   enforceability, attribution, and intended binary distribution is recommended.
   `ZHINFINITY` is the supported repository identity, not a verified legal name.
2. Establish a path/portion-level provenance and authority record before
   claiming coverage of disputed or mixed-source material. Retain the actual
   upstream/contributor permissions or assignments. Leave unresolved material
   under its applicable existing terms rather than asserting new authority.
3. Preserve implementation and all notices in this PR. Verify with one command:
   `git diff --exit-code 7015805f6831cbf8a9fc2e6d5dd5c70af209b638 -- app subtitle-libass media3-decoder-ffmpeg THIRD_PARTY_LICENSES THIRD_PARTY_SOURCE.md HISTORICAL-LICENSE-AGPL-3.0-or-later.txt`.
4. For any binary distribution, verify packaged notices, corresponding LGPL
   source, modifications, build instructions, and any required relinkable
   application materials. The standalone FFmpeg helper's exact source/build
   recipe and complete transitive notice inventory still need verification.
   Existing build checks are not a legal compliance certificate.
5. Record the approved release commit and version, summarize the release-wide
   offer and preserved grants accurately, and do not retag old releases or
   imply that prior recipients' or downstream recipients' rights were revoked.

[GitHub documents its public-repository viewing and forking rights](https://docs.github.com/articles/licensing-a-repository).
Those platform rights are acknowledged, not expanded into an external reuse
licence. Permission requirements cannot technically prevent infringement or
guarantee against future unauthorized publication.
