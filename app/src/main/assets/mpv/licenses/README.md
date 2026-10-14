# MPV Android runtime licenses

These notices apply to the Standard and No-Vulkan MPV AARs (`app/libs/mpvlib.aar` and `app/libs/mpvlib-no-vulkun.aar`) and their native libraries. They do not change the licenses of Mpv∞-authored code or of the separate FFmpeg helper executables under `app/src/main/jniLibs/arm64-v8a/`.

| Component | Version/source | License used for this build | Notice |
|---|---|---|---|
| FFmpeg native libraries | FFmpeg n9.0 | LGPL-3.0-or-later; built with `--disable-gpl --disable-nonfree --enable-version3` | `COPYING.LGPLv3` |
| mpv/libmpv | `f5bcfb195412e0ca733eac2e850879cd3b1ded18` | LGPL-2.1-or-later; built with `-Dgpl=false` | `MPV-LICENSE.LGPL` and `MPV-Copyright` |
| libplacebo | `3330a515d62139259c26239014f286e233bd3a5c` | LGPL-2.1-or-later | `libplacebo-LICENSE` |
| Mbed TLS | 3.6.7 | Apache-2.0 selected from its Apache-2.0 OR GPL-2.0-or-later dual license | `mbedtls-LICENSE` |
| mpvlibAndroid wrapper | Pinned source profile | MIT | `mpvlibAndroid-LICENSE-MIT` |

The complete LGPL texts are included alongside these notices. The separate arm64 FFmpeg helpers are documented in `app/src/main/assets/ffmpeg/README.txt` and `FFMPEG-LICENSE.md`; their LGPLv2.1-or-later mode is distinct from the MPV AAR's LGPLv3-or-later FFmpeg libraries.

The third-party libraries retain their own upstream licenses. This notice does not relicense them under the application license. The GPL Rubber Band dependency and DVDnav feature, and the FongMi-specific decoder build, are not included in the next AAR set; DVD menu/navigation support is therefore unavailable in these profiles.

This directory contains notices and license texts, not corresponding source code. For binary redistribution, make the corresponding source and any required relinkable application materials available as required by each applicable LGPL version; these notices alone do not satisfy those obligations. The reproducible build helper and pinned upstream source revisions are documented in `scripts/build-mpv-lgpl-aar.sh`.
