# MPV Android runtime licenses

These notices cover the checked-in Standard and No-Vulkan MPV AARs (`app/libs/mpvlib.aar` and `app/libs/mpvlib-no-vulkun.aar`) and the experimental arm64 runtime overlay used by the MPVFlow Debug build. The checked-in AARs come from the separate `mpv-player/mpv` source pin; they are not FongMi builds. The experimental Standard arm64 overlay is rebuilt from the pinned FongMi source and replaces only the arm64 MPV runtime libraries at build time. Neither changes the license of Mpv∞-authored code or the separate FFmpeg helper executables under `app/src/main/jniLibs/arm64-v8a/`.

| Component | Version/source | License used for this build | Notice |
|---|---|---|---|
| FFmpeg native libraries | FFmpeg n9.0 | LGPL-3.0-or-later; built with `--disable-gpl --disable-nonfree --enable-version3` | `COPYING.LGPLv3` |
| mpv/libmpv (checked-in AARs) | `mpv-player/mpv` `f5bcfb195412e0ca733eac2e850879cd3b1ded18` | LGPL-2.1-or-later build with `-Dgpl=false` | `MPV-LICENSE.LGPL` and `MPV-Copyright` |
| mpv/libmpv (experimental arm64 overlay) | FongMi/mpv `14b3999a1a3e322c217bc90ee4df5a8f1bc88f69` | LGPL-2.1-or-later build with `-Dgpl=false`; retain FongMi's GPL-2.0 source attribution and upstream notices | `MPV-LICENSE.LGPL`, `MPV-Copyright`, and `LICENSE.GPL` |
| libplacebo | API-374 in checked-in AARs; `v7.387.0` (`275f7dea27924cdd34ab2e6c20e448b6bc4fab65`) in the experimental arm64 overlay | LGPL-2.1-or-later | `libplacebo-LICENSE` |
| libbluray (static dependency of the arm64 runtime) | 1.4.1 | LGPL-2.1-or-later | `libbluray-NOTICE.txt` and `COPYING.LGPLv2.1` |
| libudfread | 1.2.0 | LGPL-2.1-or-later | `libudfread-NOTICE.txt` and `COPYING.LGPLv2.1` |
| Mbed TLS | 3.6.7 | Apache-2.0 selected from its Apache-2.0 OR GPL-2.0-or-later dual license | `mbedtls-LICENSE` |
| mpvlibAndroid wrapper | Pinned source profile | MIT | `mpvlibAndroid-LICENSE-MIT` |

The complete LGPL texts and the upstream GPLv2 license text are included alongside these notices. The separate arm64 FFmpeg helpers are documented in `app/src/main/assets/ffmpeg/README.txt` and `FFMPEG-LICENSE.md`; their LGPLv2.1-or-later mode is distinct from the MPV AAR's LGPLv3-or-later FFmpeg libraries.

The third-party libraries retain their own upstream licenses. This notice does not relicense them under the application license. The GPL Rubber Band dependency and DVDnav feature are not included in these profiles; DVD menu/navigation is unavailable, while LGPL Blu-ray stream support remains enabled.

This directory contains notices and license texts, not corresponding source code. For LGPL-covered binary redistribution, make the corresponding source and any required relinkable application materials available as required by each applicable LGPL version; these notices alone do not satisfy those obligations. The reproducible build helpers and pinned upstream source revisions are documented in `scripts/build-mpv-lgpl-aar.sh` and `THIRD_PARTY_SOURCE.md`.
