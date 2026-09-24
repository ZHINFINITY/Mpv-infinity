The arm64-v8a/ffmpeg executable is FFmpeg 9.0, built for Android by:
https://github.com/hzw1199/Android-FFmpeg-Prebuilt

The bundled libffmpeg.so is stripped for production size while retaining the
complete muxing and demuxing feature set used by yt-dlp.

The ffmpeg and ffprobe launcher files set LD_LIBRARY_PATH before starting the
corresponding Android binaries so the companion library is loaded reliably.

FFmpeg is licensed under LGPL-2.1-or-later for this build. The corresponding
source and build information are available from the project above and from:
https://ffmpeg.org/download.html
