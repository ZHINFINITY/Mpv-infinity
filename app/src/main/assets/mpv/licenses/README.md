# MPV Android runtime licenses

These notices cover the checked-in Standard and No-Vulkan MPV AARs (`app/libs/mpvlib.aar` and `app/libs/mpvlib-no-vulkun.aar`) and the experimental arm64 runtime overlay used by the MPVFlow Debug build. The checked-in AARs come from the separate `mpv-player/mpv` source pin; they are not FongMi builds. The experimental Standard arm64 overlay is rebuilt from the pinned FongMi source and replaces the arm64 MPV and shared FFmpeg runtime libraries at build time; the shared FFmpeg libraries are also used by the existing Media3 DCA fallback. Neither changes the license of Mpv∞-authored code or the separate FFmpeg helper executables under `app/src/main/jniLibs/arm64-v8a/`.

| Component | Version/source | License used for this build | Notice |
|---|---|---|---|
| FFmpeg native libraries (checked-in AARs) | FFmpeg n9.0 | LGPL-3.0-or-later; built with `--disable-gpl --disable-nonfree --enable-version3` | `COPYING.LGPLv3` |
| FFmpeg native libraries (experimental arm64 overlay) | FongMi/FFmpeg [`release-9.1-fongmi` commit `f2c5162b7c4b3e8835de270ff78cecc524decad1`](https://github.com/FongMi/FFmpeg/tree/f2c5162b7c4b3e8835de270ff78cecc524decad1) | LGPL-3.0-or-later; built with `--disable-gpl --disable-nonfree --enable-version3` | `COPYING.LGPLv3` |
| mpv/libmpv (checked-in AARs) | `mpv-player/mpv` `f5bcfb195412e0ca733eac2e850879cd3b1ded18` | LGPL-2.1-or-later build with `-Dgpl=false` | `MPV-LICENSE.LGPL` and `MPV-Copyright` |
| mpv/libmpv (experimental arm64 overlay) | FongMi/mpv `95ec8e929f8ad2f97c8415c486971493a0b408df`; the MPVRx-provenance motion.c/h hashes are recorded in `THIRD_PARTY_SOURCE.md` | LGPL-2.1-or-later build with `-Dgpl=false`; retain FongMi's GPL-2.0 source attribution and upstream notices | `MPV-LICENSE.LGPL`, `MPV-Copyright`, and `LICENSE.GPL` |
| libplacebo | API-374 in checked-in AARs; FongMi/libplacebo `63659d96d1bff7d7a5a1368e9538056a193fc6fb` in the experimental arm64 overlay | LGPL-2.1-or-later | `libplacebo-LICENSE` |
| libbluray (static dependency of the arm64 runtime) | 1.4.1, with the FongMi compatibility patch set | LGPL-2.1-or-later | `libbluray-NOTICE.txt` and `COPYING.LGPLv2.1` |
| libudfread | 1.2.0 | LGPL-2.1-or-later | `libudfread-NOTICE.txt` and `COPYING.LGPLv2.1` |
| Mbed TLS | 3.6.7 | Apache-2.0 selected from its Apache-2.0 OR GPL-2.0-or-later dual license | `mbedtls-LICENSE` |
| mpvlibAndroid wrapper/build helpers | Standard/no-Vulkan profiles and FongMi AAR build source `d04217abe8e72e4a099269f045855bf69b87d53b` | MIT | `mpvlibAndroid-LICENSE-MIT` |

The experimental arm64 runtime uses the complete MPVRx-provenance FongMi motion core at `95ec8e929f8ad2f97c8415c486971493a0b408df`; exact `motion.c` and `motion.h` hashes and the 480px analysis cap are verified by `scripts/mpvrx_motion_source.py`. No local app-owned motion shader patch is applied. The upstream downsample shader samples once per analysis texel rather than averaging a 4×4 source footprint, so thin-contour aliasing remains an unverified device-test risk. FongMi's GPL-2.0 attribution, `LICENSE.GPL`, and all upstream per-file notices remain included.

The complete LGPL texts and the upstream GPLv2 license text are included alongside these notices. The separate arm64 FFmpeg helpers are documented in `app/src/main/assets/ffmpeg/README.txt` and `FFMPEG-LICENSE.md`; their LGPLv2.1-or-later mode is distinct from the checked-in AARs' and experimental overlay's LGPLv3-or-later FFmpeg libraries.

The FongMi patch hunks modify LGPL-licensed libbluray. The mpvlibAndroid MIT notice covers its wrapper/build-helper source; it does not relicense libbluray. Preserve libbluray's upstream notices and corresponding source as required by its license.

The third-party libraries retain their own upstream licenses. This notice does not relicense them under the application license. The GPL Rubber Band dependency and DVDnav feature are not included in these profiles; DVD menu/navigation is unavailable, while LGPL Blu-ray stream support remains enabled.

This directory contains notices and license texts, not corresponding source code. For LGPL-covered binary redistribution, make the corresponding source and any required relinkable application materials available as required by each applicable LGPL version; these notices alone do not satisfy those obligations. The reproducible build helpers and pinned upstream source revisions are documented in `scripts/build-mpv-lgpl-aar.sh` and `THIRD_PARTY_SOURCE.md`.
