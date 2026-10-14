The arm64-v8a FFmpeg helper executables are packaged as
`libffmpeg_exec.so` and `libffprobe_exec.so` under
`app/src/main/jniLibs/arm64-v8a`. They are FFmpeg 9.0, built for Android by:
https://github.com/hzw1199/Android-FFmpeg-Prebuilt

The helpers are stripped for production size while retaining the muxing and
demuxing features used by yt-dlp. Embedded configure strings include
`--disable-gpl --disable-nonfree`, and the FFmpeg libraries report LGPL version
2.1 or later.

The app configures `LD_LIBRARY_PATH` before starting the Android helpers so the
companion libraries load reliably.

FFmpeg is licensed under LGPL-2.1-or-later for this build. The corresponding
source and build information are available from the project above and from:
https://ffmpeg.org/download.html
The complete LGPLv2.1 license text is included in COPYING.LGPLv2.1.
