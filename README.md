<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="250" height="250" />
</p>

<h1 align="center">Mpv∞</h1>

<p align="center">
  <b>Powerful and efficient Android video player with Native Media3 and MPV engines.</b>
  <br>
  <i>High-quality playback, thoughtful controls, and a focused media library without distractions.</i>
  <br>
  <a href="https://t.me/Infinityzlabs">Join the Mpv∞ Telegram channel</a>
</p>

**Mpv∞ (Mpv-infinity) is an ad-free, open-source Android media player for high-quality video and audio playback, combining MPV/libmpv with AndroidX Media3/ExoPlayer engines, dual subtitles, HDR, Dolby Vision, HDR10+, chapters, and customizable playback controls.**

### Jellyfin Client and Seerr Support
Mpv∞ includes a native **Jellyfin Client** for connecting to Jellyfin media servers, browsing libraries, searching movies and TV shows, and playing available media directly in Mpv∞. It also includes native **Seerr support** for Seerr and Overseerr-compatible media request servers, including discovery, search, availability status, season requests, and direct playback of available Jellyfin content. Relevant search terms include **Jellyfin Android client**, **Jellyfin media player**, **Seerr client**, **Overseerr client**, **Android MPV player**, and **Jellyfin video playback**.

<p align="center">
  <a href="https://t.me/Infinityzlabs"><b>Join InfinityZ Lab on Telegram for app feedback and feature requests</b></a>
</p>

> [!IMPORTANT]
> **Mpv∞ is actively developed by ZHINFINITY.**
>
> The current hotfix release focuses on reliable Native-to-MPV fallback for difficult Dolby Vision files while preserving the existing MPV playback path.


<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-brightgreen.svg" />
  <img src="https://img.shields.io/badge/License-AGPL_v3-blue.svg" />
  <img src="https://img.shields.io/github/v/release/ZHINFINITY/Mpv-infinity.svg?logo=github&label=Release&cacheSeconds=3600" />
  <img src="https://img.shields.io/github/downloads/ZHINFINITY/Mpv-infinity/total?logo=github&cacheSeconds=3600" />
</p>

---

## Showcase

The screenshots below present the Mpv∞ player surface, video library, About page, Live Wallpaper library, Native engine selector, and chapter navigation.

<div align="center">
  <img src="docs/showcase/player-glass.jpg" width="90%" alt="Mpv∞ player with translucent controls and seekbar">
</div>

<br>

<div align="center">
  <img src="docs/showcase/video-library.jpg" width="31%" alt="Mpv∞ video library">
  <img src="docs/showcase/about-mpv-infinity.jpg" width="31%" alt="Mpv∞ About page">
  <img src="docs/showcase/live-wallpapers.jpg" width="31%" alt="Mpv∞ Live Wallpaper library">
</div>

<br>

<div align="center">
  <img src="docs/showcase/decoder-native.jpg" width="46%" alt="Mpv∞ Playback engine selector showing MPV and Native">
  <img src="docs/showcase/chapters.jpg" width="46%" alt="Mpv∞ chapter navigation sheet">
</div>

---

## Features

Mpv∞ is a feature-rich Android media player combining the MPV and AndroidX Media3 playback engines with customizable controls, advanced video rendering, subtitle tools, audio playback, and practical library features.

<details close>
<summary><b>⚙️ Dual Playback Engines: MPV + Media3</b></summary>

| Feature | Description |
|---|---|
| **MPV playback engine** | Full libmpv playback for broad format support, advanced rendering, shaders, scripting, and detailed MPV controls. |
| **Media3 playback engine** | AndroidX Media3 playback with platform-integrated video, audio, codec, timeline, and device support. |
| **Automatic engine routing** | Auto mode can choose Media3 for supported Dolby Vision items and HLS/DASH streams while keeping ordinary files on MPV. |
| **Per-video engine selection** | Switch the current item between MPV and Media3 from the decoder controls without changing the global preference. |
| **Engine-aware playback controls** | Seeking, playback position, chapters, segment actions, and subtitle selection are available through the active engine. |
| **Media3 audio compatibility** | Media3 playback includes the FFmpeg audio extension for additional audio-format compatibility where supported by the device. |
| **Media3 dual subtitles** | Selectable dialogue and sign subtitle tracks can render together as layered subtitle tracks. |
| **Media3 chapters and skippable segments** | Media3 exposes chapter-aware timeline information and playable segment actions such as intro, recap, outro, and credits skipping. |
| **Playback information** | Statistics and decoder controls show the active engine and available output details when reported by the platform. |

</details>

<details close>
<summary><b>🎨 Theme & Visual System</b></summary>

| Feature | Description |
|---|---|
| **25+ Color Themes** | Default, Dynamic (Material You), Catppuccin, Nord, Tokyo Night, Rose Pine, Gruvbox, Dracula, and many more |
| **AMOLED Pure Black Mode** | Every theme has a dedicated variant with pure black backgrounds |
| **Player Controls Animation** | 5 animation styles: Default, Elastic Bounce, Cinematic Scale, Slide Up, Minimal Fade |
| **Always Dark Mode** | Option to keep player controls in dark theme regardless of app theme |
| **Themed Player Controls** | Adaptive controls that match your app theme or system accent |
| **Material 3 Expressive UI** | Expressive components, spring-based navigation, responsive grids, and polished predictive-back motion |

</details>

<details close>
<summary><b>🖐️ Gesture System</b></summary>

| Feature | Description |
|---|---|
| **Refined Tap Logic** | Configurable double-tap seek zones (left/center/right) with independently assignable actions |
| **Multi-Tap Continuous Seeking** | Triple/quadruple tap to keep seeking further without lifting |
| **Horizontal Swipe to Seek** | Swipe across video to seek with live time/delta overlay |
| **Long-Press Dynamic Speed** | Long-press activates configurable speed boost; swipe left/right to adjust across 8 presets |
| **Subtitle Drag Gesture** | Long-press center screen to drag subtitles vertically when active |
| **Subtitle Zoom & Dialogue Seek** | Pinch subtitle text to resize it and swipe across subtitles to seek between dialogue lines |
| **Pinch-to-Zoom with Pan** | Pinch to zoom (-1x to 3x) with simultaneous pan and single-finger pan after zoom |
| **Volume Boost via Gesture** | Vertical swipe volume can exceed 100% into configurable boost range |
| **Swap Volume/Brightness Sides** | Option to swap which screen side controls volume vs brightness |

</details>

<details close>
<summary><b>📺 HDR, Codec & Video Pipeline</b></summary>

| Feature | Description |
|---|---|
| **Native Dolby Vision & HDR10+** | The Media3 Native engine supports device-dependent Dolby Vision and HDR10+ playback through Android’s available hardware codec stack. |
| **Device Codec Support** | Uses codecs exposed by the device for compatible video formats, HDR profiles, and hardware-accelerated playback. |
| **Shader-Based HDR Pipeline** | Powered by [hdr-toys](https://github.com/natural-harmonia-gropius/hdr-toys) — 77 bundled GLSL shaders |
| **Four HDR Modes** | BT.2100 PQ (HDR10), BT.2100 HLG, BT.2020 gamut mapping, Linear HDR |
| **SDR-to-HDR Boost** | Boost SDR content into HDR range when using Linear HDR pipeline |
| **GPU Deband** | CPU (gradfun) or GPU deband with configurable iterations, threshold, range, grain |
| **Smart Render Backend** | Auto-selects between OpenGL/Vulkan and gpu/gpu-next based on device support |

</details>

<details close>
<summary><b>🔥 Thermal & Battery Management</b></summary>

| Feature | Description |
|---|---|
| **ThermalMonitor** | Samples thermal headroom every 10s during playback |
| **Adaptive Shader Budget** | Ambient shader budget auto-capped based on thermal headroom |
| **Anime4K Proactive Throttling** | Auto-downgrades Anime4K quality when thermal headroom drops below 40% |
| **Background Poll Optimization** | Position poll interval doubles when controls are hidden, cutting JNI wake-ups 50% |
| **Stats Poll Backoff** | Stats page poll loop backs off from 1s to 2s when playback is paused |

</details>

<details close>
<summary><b>🧩 Anime4K & Upscaling</b></summary>

| Feature | Description |
|---|---|
| **7 Preset Quality Tiers** | Off, A, B, C, A+, B+, C+ with clean switching |
| **Quality Tiers in Decoder Settings** | Fast / Balanced / High quality choices |
| **4K/8K Safety Guard** | Auto-disables Anime4K for high-resolution content |
| **Thermal-Guarded Selection** | Auto-downgrades quality tier under thermal pressure before frame drops |

</details>

<details close>
<summary><b>💡 Ambient Mode</b></summary>

| Feature | Description |
|---|---|
| **Two Visual Modes** | GLOW and FRAME_EXTEND — both rendered via custom GLSL at runtime |
| **15+ Configurable Parameters** | Blur samples, glow intensity, saturation, warmth, vignette, dither noise, and more |
| **Shader Recompilation Caching** | Skips recompilation when parameters match last compiled version |

</details>

<details close>
<summary><b>📝 Subtitle System</b></summary>

| Feature | Description |
|---|---|
| **Dual Subtitle Support** | Primary + secondary with auto-offset to prevent overlap |
| **ASS Override Modes** | Smart force/scale handling for secondary subtitles |
| **Comprehensive Styling** | Font, size, bold, italic, border, shadow, colors, justification, scale by window |
| **Three Online Search Modes** | Wyzie, SubtitleHub (6 aggregated sources), and Hybrid (both merged) |
| **TMDB Integration** | Full media search with season/episode browsing for subtitles |
| **Subtitle Font Manager** | Choose a font directory, reload fonts, clear the cache, and select a default subtitle font |
| **Speech-to-Subtitle Generation** | Experimental subtitle generation from the active audio using supported cloud or offline Whisper providers |
| **Explicit Subtitle Off** | Disable subtitles directly without cycling through every available track |
| **Embedded and External Subtitle Tracks** | Select subtitle tracks from containers or automatically discovered same-name external subtitle files |

</details>

<details close>
<summary><b>🎮 Player Controls</b></summary>

| Feature | Description |
|---|---|
| **Fully Customizable Layout** | Four configurable zones (top-left/right, bottom-left/right) + portrait bottom row |
| **25+ Button Types** | Cast, Mirror, Vertical Flip, A-B Loop, Custom Skip, Background Playback, Ambient, and more |
| **Custom User Buttons** | Create arbitrary buttons executing Lua, JavaScript, or mpv commands |
| **Landscape/Portrait Adaptive Layouts** | Completely different control layouts per orientation |
| **"Slide to Unlock" Controls** | Slide mechanism when controls are locked |
| **Hide Button Backgrounds** | Transparent buttons with only icons visible |
| **Centralized "More Sheet"** | Quick access to all player buttons and custom controls |
| **In-Player Settings** | Toggle 10+ settings (gestures, PiP, UI behavior) without leaving playback |

</details>

<details close>
<summary><b>📺 Google Cast</b></summary>

| Feature | Description |
|---|---|
| **Native Cast Button** | Standard stateful Google Cast route icon in both portrait and landscape player controls |
| **Device Discovery** | Google Cast framework discovery and native device chooser for Chromecast and Cast-enabled TVs |
| **Position Handoff** | Transfers the current title, play state, duration, and playback position to the receiver |
| **Local File Casting** | Tokenized temporary LAN server exposes `file://` and `content://` media with byte-range seeking and CORS headers |
| **Remote Stream Casting** | Direct handoff for receiver-accessible HTTP and HTTPS media URLs |
| **Expanded Remote Controls** | Cast SDK controller, notification, lock-screen actions, reconnection, and receiver volume controls |
| **Return to Phone** | Restores local playback at the receiver's latest position when the Cast session ends |

> Cast uses Google's Default Media Receiver. The TV/Chromecast must support the media container and codecs; mpv-only formats are not transcoded automatically.

</details>

<details close>
<summary><b>🧭 Smart Orientation</b></summary>

| Feature | Description |
|---|---|
| **8 Orientation Modes** | Free, Video (auto aspect ratio), Portrait, Reverse Portrait, Sensor Portrait, Landscape, Reverse Landscape, Sensor Landscape |
| **Persistent Per-Video** | Orientation remembered per-video across sessions |

</details>

<details close>
<summary><b>🔍 File Browser & Navigation</b></summary>

| Feature | Description |
|---|---|
| **Dual Browser Modes** | Album View (folder grid) and Tree View (file manager hierarchy) |
| **Folder Pinning** | Pin frequently accessed folders to top |
| **Single-Child Auto-Flatten** | Folders with one subfolder auto-flatten for faster browsing |
| **Auto-Scroll to Last Played** | Opens to the last played video position |
| **Recursive File/Folder Counts** | Shows total video count, duration, size computed recursively |
| **"NEW" Badges** | Configurable threshold for new video indicators |
| **Grid/List Layout** | Per-orientation column count settings |
| **Multi-Protocol Network** | Built-in SMB, FTP, and WebDAV clients |
| **Syncplay Rooms** | Join a Syncplay server room to synchronize pause, resume, seeking, and playback position with other viewers |
| **Responsive & Dual-Pane Layouts** | Automatic grid sizing plus optional folder/settings dual-pane views on tablets |
| **Audio Library Mode** | MediaStore and filesystem audio browsing with square artwork, metadata titles, and mixed sibling playlists |
| **Folder Playlist Sheets** | Open playable folders, including nested series folders, as navigable episode or file playlists from the player |
| **Safer Folder Deletion** | Media-only folder deletion by default, with an explicit option to delete every contained file |
| **Settings Search Memory** | Search suggestions and recently used settings queries |

</details>

<details close>
<summary><b>🤖 AI Integration</b></summary>

| Feature | Description |
|---|---|
| **Provider Support** | OpenAI, Anthropic, Groq, OpenRouter, Together, and OpenCode Zen with provider-specific models and API protocols |
| **AI Subtitle Translation** | Translate subtitles with custom prompts |
| **AI Subtitle Formatting** | Reformat subtitle styling with custom prompts |
| **AI File Renaming** | Bulk rename video files with custom rename prompts |
| **Reasoning-Safe Parsing** | Removes reasoning blocks and code fences while accepting structured provider responses and citations |
| **Speech Providers** | Cloud transcription plus experimental offline Whisper subtitle generation |

</details>

<details close>
<summary><b>📜 Scripting & Editor</b></summary>

| Feature | Description |
|---|---|
| **Dual Language** | Lua (.lua) and JavaScript (.js) script support |
| **Sora Code Editor** | Built-in editor with TextMate syntax highlighting |
| **Runtime Script Loading** | Enable/disable scripts without restarting |
| **Lua Module Support** | Recursive `script-modules/` synchronization enables custom scripts to use `require()` helpers |
| **Config Editor** | Built-in editor for mpv.conf and input.conf |

</details>

<details close>
<summary><b>⚙️ Utilities</b></summary>

| Feature | Description |
|---|---|
| **Stats Page 6** | Live system monitor: FPS, dropped frames, codecs, network sparkline, battery |
| **Video Compressor** | Built-in FFmpeg-based compression with presets |
| **12 Video Filter Presets** | Vivid, Cinematic, Dramatic, Ghibli Style, Neon Pop, Deep Black, and more |
| **Custom Skip Segments** | Intro/outro/recap/credits/preview detection from IntroDB, TIDB, AniSkip, Anime Skip |
| **One-Tap Segment Skip** | Skip detected intro, outro, recap, credit, and preview segments during playback |
| **A-B Loop** | In-player looping with visual markers on seekbar |
| **Frame Navigation** | Frame-by-frame forward/backward with frame number display |
| **Sleep Timer** | Built-in with quick presets (15/30/45/60 min) |
| **Adaptive Background Playback** | Auto-PiP on Home, auto-resume after screen unlock |
| **Unified Background Playback** | One persistent audio/video switch; Back can return to browser lists without stopping the current media |
| **Notification Styles** | None, Media, or Progress with Chapters (Android 16+) |
| **Safe Area / Window Offset** | Prevents camera notch overlap |
| **Display Cutout Mode** | Full-bleed on notch devices |
| **Remember Brightness** | Persists brightness level set during playback |
| **M3U Playlist Support** | Parse and play local M3U playlists |
| **yt-dlp Integration** | High-performance streaming support for YouTube, Twitch, Bilibili, and more via a native Python bridge (SDK 29+ bypass) |
| **yt-dlp Quality Controls** | Independent codec, resolution, FPS, HDR, container, and audio-bitrate preferences |
| **Dynamic Refresh Rate** | Matches supported display refresh rates to the current video's frame rate for smoother motion |
| **Audio Blob Visualizer** | OpenGL ES 3.0 FFT-reactive blob with bloom, touch rotation, pinch zoom, and an Audio Settings toggle |
| **Screenshot Templates** | Filename placeholders for source name, playback position, and millisecond-accurate timestamps |

</details>

---

## 🔋 Battery Optimization Guide for Mpv∞

For efficient playback, begin with Mpv∞'s default configuration and enable additional rendering features only when you need them. The following choices usually reduce unnecessary GPU and battery work:

- **Use `gpu` for ordinary playback** when you do not need the Vulkan-based `gpu-next` renderer.
- **Use the built-in `fast` profile** for a lightweight starting point.
- **Enable Anime4K and other shaders selectively**, because they run on every rendered frame and can increase GPU load.
- **Check the statistics pages** during long playback to observe battery, thermal, frame, and rendering behavior on your device.
- **Keep custom mpv.conf settings focused**, since aggressive scaling, debanding, and shader chains can increase startup time, memory use, and power consumption.

Actual power use depends on the device, codec, resolution, refresh rate, renderer, and selected shaders. Mpv∞'s thermal-aware safeguards can reduce shader quality when the device is under sustained load.

---

<div align="center">
  <a href="https://github.com/ZHINFINITY/Mpv-infinity/releases">
    <img src="https://img.shields.io/badge/Download-Stable_Release-blue?style=for-the-badge&logo=github" alt="Stable Release">
  </a>
  <!-- <a href="https://ZHINFINITY.github.io/Mpv-/">
    <img src="https://img.shields.io/badge/Download-Preview_Build-orange?style=for-the-badge&logo=github" alt="Preview Build">
  </a> -->
</div>

<!-- <div align="center">
  <i>Note: Previews may be unstable and are intended for testing purposes only.</i>
</div> -->

If something breaks or feels inconsistent, please report it through the [Issues](https://github.com/ZHINFINITY/Mpv-infinity/issues) page with the app version, device model, engine selected, and relevant logs.

---

## Build

### Requirements

- JDK 17
- Android SDK with modern build tools installed
- Git

### Debug Build

```powershell
./gradlew.bat :app:assembleStandardDebug
```

### Release Variants

| Variant | Description |
|---|---|
| `standard` | Main release with in-app update support |

### APK Variants

| Variant | Description |
|---|---|
| `universal` | Works on all supported devices |
| `arm64-v8a` | Recommended for most current Android devices |
| `armeabi-v7a` | For older 32-bit ARM devices |
| `x86` | For 32-bit Intel and AMD Android devices |
| `x86_64` | For 64-bit Intel and AMD Android devices |

---

## Sponsor and Support
If you find Mpv∞ useful, you can support its continued development through Ko-fi or the UPI details below.

<div align="center">
  <a href="https://ko-fi.com/zhinfinity">
    <img src="https://img.shields.io/badge/Sponsor%20and%20Support%20on-Ko--fi-ff5e5b?logo=ko-fi&logoColor=white" alt="Sponsor and Support ZHINFINITY on Ko-fi">
  </a>
  <br>
  <br>

### UPI

`zhjjk001-1@oksbi`

<a href="upi://pay?pa=zhjjk001-1@oksbi&pn=ZHINFINITY&cu=INR">
  <img src="fastlane/metadata/android/en-US/images/upiqr-code.png" width="250" height="250" alt="UPI QR Code">
</a>

Scan with any UPI app (Google Pay, PhonePe, Paytm, BHIM)

</div>

---

## Release Notes For Maintainers

To cut a signed GitHub release through Actions, configure these repository secrets:

| Secret Name | Description |
|---|---|
| `SIGNING_KEYSTORE` | Base64-encoded keystore file (`.jks` or `.keystore`) |
| `SIGNING_KEY_ALIAS` | Key alias inside the keystore |
| `SIGNING_STORE_PASSWORD` | Password for the keystore |
| `KEY_PASSWORD` | Password for the signing key |

Then bump `versionCode` and `versionName` in `app/build.gradle.kts`, create a tag, and push it:

```bash
git tag -a v1.0.1 -m "Release version 1.0.1"
git push origin v1.0.1
```

Preview releases use the same flow with preview tags such as:

```bash
git tag -a v1.0.1-preview.1 -m "Preview release"
git push origin v1.0.1-preview.1
```

---

## Acknowledgments

Mpv∞ is maintained by **ZHINFINITY** and follows the same open-source lineage model as mpvRx: it is an independent modified Android player based on the open-source **[mpvRx](https://github.com/Riteshp2001/mpvRx)** project, with the earlier **[mpvExtended / mpvEx](https://github.com/marlboro-advance/mpvEx)** and **[mpv-android](https://github.com/mpv-android)** projects acknowledged as part of that lineage. Mpv∞-specific branding, Native Media3 integration, engine routing, fallback handling, controls, themes, and other changes are maintained separately. Mpv∞ is not affiliated with or endorsed by the upstream maintainers.

- [mpv-android](https://github.com/mpv-android)
- [AndroidX Media3](https://developer.android.com/jetpack/androidx/releases/media3) for the Native/Media3 playback, audio, UI, and effects stack (**Apache-2.0**).
- [Jellyfin Media3 FFmpeg Decoder](https://github.com/jellyfin/jellyfin-androidx-media) through `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` for additional codec and multichannel audio support (**GPL-3.0**).
- **Device-dependent 7.1 audio support** is Mpv∞ integration built on the Media3 audio pipeline, the Jellyfin FFmpeg decoder where selected, and codecs exposed by the user’s Android device; it is not a separate third-party library or a universal device-support guarantee.
- [mpvExtended / mpvEx](https://github.com/marlboro-advance/mpvEx)
- [mpvKt](https://github.com/abdallahmehiz/mpvKt)
- [PixelPlayer](https://github.com/theovilardo/PixelPlayer)
- [MpvRex](https://github.com/sfsakhawat999/mpvRex)
- [Next Player](https://github.com/anilbeesetti/nextplayer)
- [Gramophone](https://github.com/FoedusProgramme/Gramophone)
- [hdr-toys](https://github.com/natural-harmonia-gropius/hdr-toys)
- [AFinity](https://github.com/MakD/AFinity)
- [anacrolix/torrent](https://github.com/anacrolix/torrent)
- [Anime4K](https://github.com/bloc97/Anime4K)
- [**SunnyVishnu3**](https://github.com/SunnyVishnu3) for the `yt-dlp` native integration and SDK 29+ bypass logic.

For the detailed notice and redistribution guidance, see [`UPSTREAM_NOTICES.md`](UPSTREAM_NOTICES.md). Individual source files and bundled assets may contain their own copyright and license notices; those notices remain part of the distributed source tree.

---

## License

Distributed under the **GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)**. See [`LICENSE`](LICENSE) and [`UPSTREAM_NOTICES.md`](UPSTREAM_NOTICES.md) for the license and attribution information. Mpv∞-specific code, branding, and modifications are maintained by **ZHINFINITY**, while upstream copyrights remain with their respective holders.
