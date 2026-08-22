# Changelog

These notes are written in plain English and focus on what changed for real use.

## 1.0.1 — Stable Release

This stable release consolidates the hybrid playback, mixed queue, audio-player, and usability improvements delivered after v1.0.0.

- **Hybrid playback engines:** MPV and Media3 are available for high-quality video and audio playback, with reliable per-item engine handoff and fallback behavior. HDR, Dolby Vision, HDR10+, hardware decoding, shader, chapter, subtitle, casting, scripting, and streaming capabilities remain available according to device and source support.
- **Temporary mixed-media queue:** Select songs and videos from different folders into an editable, non-persistent queue. The queue can be started from the Quick Play menus, keeps all selected items visible, supports reordering and removal, and advances across audio/video boundaries without replacing the queue with the first item’s folder playlist. Queue contents are preserved when another music view or media launch is opened, so video entries cannot silently disappear from a mixed queue.
- **Audio-player recovery:** Video-to-audio queue transitions now restore the audio player’s orientation, controls, metadata, minimize handoff, background playback, and playback notification state instead of retaining the preceding video session state.
- **Music metadata and artwork:** Artist information is preserved for music added to a temporary queue and used as a fallback when embedded metadata is incomplete. Album-art presentation and landscape metadata placement are improved.
- **Music-player UI:** Added swipe-down minimization, a cleaner landscape artwork-and-controls layout, visible landscape option controls, improved spacing, and a larger visualizer option target while preserving the existing portrait experience.
- **Media3 stability:** Position and playback-state polling uses the controller’s published state snapshot, avoiding unsafe cross-thread player access during playback and transitions.
- **Settings export identity:** New settings exports use the Mpv∞ filename format `mpv_infinity_settings_YYYYMMDD_HHMMSS.xml`; compatibility with existing settings files is retained.
- **Standard distribution:** Stable assets target the Standard flavor and include universal, arm64-v8a, armeabi-v7a, x86, and x86_64 APKs.

## 1.0.0 — Public Release

- **Foreground media playback:** The playback service promotes itself immediately with a minimal notification before media-session and playback initialization.
- **Public feature set:** MPV and Media3 playback engines, dual subtitle tracks, chapter-aware playback, folder playlist sheets, skip segments, adaptive controls, HDR and shader pipelines, casting, scripting, audio library mode, and the broader features listed in the README.
- **Distribution:** Standard APK variants only; no Fongmi or no-Vulkan variants.

## 1.1.26 — Media3 Subtitle Slots, Skip Intro, and Playlist Race Fixes (CI Candidate)

- **Dual subtitle renderer mapping:** The stale-cue guard now distinguishes custom text-renderer slots from global Media3 renderer indexes, restoring valid dialogue and sign cue delivery while still rejecting late callbacks from disabled slots.
- **Media3 skip intro:** Skip-segment detection now polls Media3 position and duration, and both manual and automatic skip actions seek through the Media3 controller instead of the unavailable MPV `time-pos` property.
- **Nested playlist race protection:** Folder discovery is marked in flight so asynchronous Media3 loading cannot overwrite a pending multi-item folder queue with a singleton.
- **Release scope:** CI-only validation candidate; repository remains private, only Standard APK variants are in scope, and v1.1.25 behavior is otherwise preserved.

## 1.1.25 — Media3 Subtitle Cue Guard and Playlist Discovery Logging

- **Subtitle stale-cue guard:** SubtitleTextOutput callbacks from disabled renderer slots are now rejected; any cached cues for that slot are cleared immediately so a disabled sign track cannot remain visible after deselection.
- **Playlist discovery always runs:** The playlist-sheet refresh no longer skips discovery based on the Activity playlist size; fresh discovery runs on every sheet open for external file-manager launches.
- **Diagnostic logging:** Added detailed logs to sibling resolution and folder generation to expose which guard aborts discovery for nested MKV folders such as Konosuba.
- **Release scope:** CI-only validation candidate; repository remains private, only Standard APK variants are in scope, and all v1.1.24 fixes are preserved.

## 1.1.24 — Media3 Subtitle Cue Reset and Playlist Sheet Refresh (Unreleased CI Candidate)

- **Subtitle deselection cleanup:** Explicit subtitle toggles now clear merged Media3 cue buffers and create a renderer disable boundary before applying the new selection, preventing a disabled sign track from remaining visible.
- **Playlist sheet refresh:** Opening the playlist sheet now retriggers folder discovery through the Activity’s preserved external URI, including direct and compact-control entry points, so a singleton file-manager launch can materialize its nested MKV sibling queue.
- **Race reduction:** Folder discovery is triggered from one authoritative sheet-open effect rather than duplicate control callbacks.
- **Release scope:** Unreleased CI validation candidate; repository remains private, no release is being published yet, and the v1.1.23 fixes are preserved.

## 1.1.23 — Media3 Dialogue+Sign Same-Group Fix and MKV Playlist Index Fix

- **Dialogue+sign same TrackGroup:** When dialogue and sign tracks share the same Media3 `TrackGroup`, both are now assigned to a single text renderer through a multi-index `SelectionOverride`, allowing both to render simultaneously without the toggle workaround.
- **MKV playlist index fallback:** The current-file lookup now falls back to filename matching when exact normalized-path comparison fails because content-URI and filesystem paths are represented differently.
- **Stable subtitle identity:** Track-map rebuilds preserve the desired subtitle selection using stable track identity keys rather than volatile synthetic IDs.
- **External launch session bypass:** File-manager `ACTION_VIEW` launches bypass stale saved playback sessions so fresh folder-queue discovery can run.
- **Release scope:** CI-only validation candidate; repository remains private, only Standard APK variants are in scope, and all v1.1.22 fixes are preserved.

## 1.1.22 — Media3 Dialogue and Nested MKV Playlist Hotfix (Unreleased CI Candidate)

- **First-load dialogue subtitles:** Dialogue and sign tracks from the same Media3 `TrackGroup` are now assigned one track index per text-renderer slot instead of being batched onto one renderer. This explicitly activates dialogue when both tracks are selected for the first time.
- **MPV-like sign readability:** Sign cues retain their embedded ASS/SSA position and alignment while receiving a compact opaque window and conservative cue-size normalization; dialogue sizing is unchanged.
- **File-manager MKV folders:** MediaStore folder discovery can recover a video row by display name and size when a SAF/content URI omits a numeric ID or `RELATIVE_PATH`, allowing ancestor-folder queue generation for nested season layouts.
- **Release scope:** Unreleased CI validation candidate; repository remains private, only Standard APK variants are in scope, and v1.1.21 optimizations are preserved.

## 1.1.21 — Playback Efficiency and Resource Cleanup (Unreleased CI Candidate)

- **Reduced duplicate UI work:** Media3 state callbacks now suppress identical snapshots while retaining the 250 ms live position updates needed by the seekbar and controls.
- **Lower subtitle overhead:** Sign-track detection reuses a compiled pattern and cached renderer classification instead of rescanning and lowercasing track titles for every cue batch.
- **Resource cleanup:** Artwork and embedded-lyrics metadata fallbacks now release `MediaMetadataRetriever` even when data-source or metadata extraction fails.
- **Quality preservation:** No video/audio resolution, decoder selection, bitrate, subtitle positioning, playback timing, or MPV music-player behavior was reduced or changed by this optimization pass.
- **Release scope:** Unreleased CI validation candidate; repository remains private, no release tag is created, and all v1.1.20 fixes are preserved.

## 1.1.20 — Media3 Subtitle First-Load and MKV Playlist Hotfix

- **First-load dual subtitles:** A bounded retry of up to four attempts at 120 ms intervals reapplies the explicit renderer-specific subtitle overrides after each track-map update, so selecting dialogue and signs works immediately without toggling one track off and back on.
- **Sign subtitle readability:** Sign-track cues receive a dark window treatment while preserving their embedded ASS/SSA position and alignment, preventing sign colors from blending with the underlying source sign.
- **MKV folder playlist:** The season-folder recursive sibling scan now runs for all launch sources, including file-manager launches, not only video-list launches. This fixes Konosuba and similar nested-season-folder playlist-sheet failures.
- **Release scope:** CI-only validation build; repository remains private, only Standard APK variants are in scope, and all v1.1.19 fixes are preserved.

## 1.1.19 — Media3 Subtitle and Nested MKV Playlist Hotfix

- **First-load simultaneous subtitles:** Text-renderer-specific overrides are cleared before each explicit Media3 subtitle assignment, so selecting dialogue and signs works immediately without toggling one track off and on.
- **Readable sign windows:** Embedded cue windows retain their ASS/SSA positioning while opaque backgrounds are reduced to adaptive semi-transparent colors, keeping signs readable without hiding the source image.
- **Nested MKV folders:** Folder discovery keeps the immediate season folder as the default and falls back to ancestor/descendant scanning or MediaStore ancestor paths when nested Anime/Show/Season layouts otherwise produce a singleton queue.
- **Release scope:** CI-only validation build; repository remains private, only Standard APK variants are in scope, and all v1.1.18 fixes are preserved.

## 1.1.18 — Media3 Subtitle and MKV Playlist Sheet Hotfix

- **Media3 simultaneous subtitles:** Dialogue and sign selections now remain selected across track-change callbacks and are assigned to separate text-renderer slots, so both cue streams can render continuously together.
- **MKV playlist sheet:** The playlist sheet now refreshes from the live queue when asynchronous folder discovery publishes sibling items.
- **Playlist interaction recovery:** An empty or still-loading Playlist state no longer blocks ordinary screen taps; it self-dismisses if no multi-item queue appears.
- **Release scope:** CI-only validation build; repository remains private, only Standard APK variants are in scope, and all v1.1.17 fixes are preserved.

## 1.1.17 — Media3 Double-Tap Feedback Hotfix

- **Double-tap seek indicator:** Media3 now uses its live position and duration when updating the shared double-tap seek state, so the visible `+10`/`-10` text and chevron indicator appear just as they do with MPV. The actual seek operation remains unchanged for both engines.
- **Release scope:** CI-only validation build; repository remains private, only Standard APK variants are in scope, and all v1.1.16 fixes are preserved.

## 1.1.16 — Media3 Subtitle, Chapter, Playlist, and Position Hotfix

- **ASS drawing artifact:** Broadened Media3’s ASS drawing-command detection to remove coordinate-only drawing cues such as `m x y l x y` without removing dialogue or sign cues.
- **Media3 dialogue and signs:** The merged cue list is now filtered after PlayerView’s own cue update, so selected dialogue and sign subtitles remain renderable together.
- **Media3 chapters:** Matroska Chapter entries are also extracted from track Format metadata, covering files where the global metadata callback does not receive chapters.
- **MKV folder playlist:** The asynchronous multi-item queue is no longer replaced by a singleton when Media3 starts loading before folder discovery finishes.
- **Queue position:** Automatic transitions to a different queued item now start at 00:00 instead of inheriting the previous Media3 item’s position.
- **Release scope:** CI-only validation build; repository remains private, only Standard APK variants are in scope, and all v1.1.15 fixes are preserved.

## 1.1.15 — Media3 Chapters, Subtitles, and Playlist Hotfix

- **Media3 chapters:** Matroska and other supported Media3 chapter metadata now feeds the existing chapter sheet; files without supported chapter metadata keep the chapter button hidden, while MPV chapter behavior is unchanged.
- **Media3 dialogue and signs:** Removed the controller-side cue replacement that could discard the dialogue stream when dialogue and sign subtitle tracks were selected together; PlayerView now retains Media3’s native merged cue handling.
- **ASS drawing artifact:** Corrected the raw-string whitespace and numeric-token regex used for ASS drawing-command detection.
- **Playlist controls:** The playlist sheet no longer triggers the playback auto-hide timer after opening, keeping the folder card and its controls visible while browsing a queue.
- **Release scope:** Unreleased CI validation build; repository remains private, only Standard APK variants are in scope, and all v1.1.14 fixes are preserved.

## 1.1.14 — Gesture, Playlist, and Media3 Subtitle Hotfix

- **Media3 hold speed:** Long-press speed gestures now read and restore the live Media3 playback state instead of stale MPV pause and speed properties.
- **Folder playlist sheet:** Multi-item folder queues are now exposed to the playlist card in both MPV and Media3, including ordinary folders outside the Reels path. The playlist swipe path also keeps controls visible when opening the sheet.
- **Media3 subtitle sheet refresh:** Selecting or removing a subtitle publishes the updated selection immediately so the open sheet reflects the change without being dismissed and reopened.
- **ASS drawing artifact:** Media3 filters malformed ASS vector drawing-command cues that were being displayed as numeric text in the video corner, while preserving positioned sign cues.
- **Release scope:** Unreleased validation build; repository remains private, only Standard APK variants are in scope, and all v1.1.13 fixes are preserved.

## 1.1.13 — Media3 Subtitle Rendering Hotfix

- **ASS/SSA sign positioning:** Native/Media3 subtitles now honor embedded alignment and position tags while retaining the app’s subtitle scale control, preventing sign subtitles from being stacked, oversized, or misplaced.
- **Simultaneous subtitle tracks:** Native/Media3 can keep dialogue and sign tracks selected together, and tapping an active track removes only that track instead of disabling every subtitle.
- **Subtitle Off action:** The subtitle sheet now disables the active engine correctly; MPV subtitle properties remain unchanged for MPV playback.
- **Release scope:** Unreleased CI validation build; repository remains private, only Standard APK variants are in scope, and all v1.1.12 fixes are preserved.

## 1.1.12 — Portrait Controls and Media3 Gesture Hotfix

- **Portrait bottom controls:** The vertical player now renders the same saved buttons, membership, and order configured in Edit Portrait Bottom, including added and removed controls.
- **Media3 hold speed:** The Hold for multi-x speed gesture now routes through the engine-aware playback-speed setter, so the configured speed applies to Media3 and returns to the prior speed after release.
- **Queue position:** Queued Media3/Native transitions keep the start-at-zero guard through asynchronous state restoration instead of reapplying the previous video’s position.
- **Folder playlist card:** The folder playlist button opens the existing Now Playing card without triggering the generic control-hide action.
- **Release scope:** Private repository; Standard APK variants only. MPV behavior and previously working music, pause-button, subtitle, and playback paths are preserved.

## 1.1.11 — Playlist Card and Portrait Pause Hotfix

- **Folder playlist card:** The portrait top-bar episode pill now opens the existing bottom “Now Playing” card for the videos in the loaded folder, including the item count, thumbnails, current-item highlight, metadata, and reorder handles.
- **Playlist button availability:** The episode pill remains tappable for an explicit folder queue even when the general playlist preference is disabled; other playlist controls and playback behavior are unchanged.
- **Circular pause indication:** Preserved the portrait pause/resume button’s circular clipping so the pressed state does not show a blue rectangular overlay.
- **Release scope:** Private repository; Standard APK variants only.

## 1.1.10 — Queue Episode Start Hotfix

- **Next and previous items start at 00:00:** Queue navigation and automatic playlist advancement no longer reuse the prior video’s saved or engine-handoff position.
- **Same-video resume preserved:** Reopening the same video directly can still restore that video’s own saved position.
- **Previous fixes preserved:** The working MPV-only music player and circular portrait pause-button indication remain unchanged.
- **Release scope:** Private repository; Standard APK variants only.

## 1.1.9 — Portrait Pause Button UI Hotfix

- **Circular pressed indication:** Clipped the portrait video pause/resume button’s interaction layer to its circular shape so the blue rectangular pressed overlay no longer appears when pausing or resuming.
- **Playback behavior preserved:** The pause/resume action, circular control appearance, and MPV music-player implementation are unchanged.
- **Release scope:** Private repository; Standard APK variants only.

## 1.1.8 — MPV-Only Music Ownership Hotfix

- **MPV-only audio routing:** Audio items are now forced through MPV before any global engine, Media3, or Dolby/Auto decision. Delayed video-track callbacks cannot hand the current song back to Media3.
- **Shared-container audio:** An explicit audio launch remains MPV-owned even when a shared container such as MKV reports a `video/*` MIME type.
- **Responsive controls:** MPV pause/resume no longer becomes a no-op because of a temporary Android audio-focus failure, and the current audio generation remains the command target for seeking and timeline updates.
- **Stale title prevention:** The current audio queue item and path now take precedence over a stale `media-title` left by the previous video.
- **Release scope:** This is a Standard APK hotfix; music remains MPV-based, the repository remains private, and upstream MPV/mpvRx attribution is preserved.

## 1.1.7 — MPV Music Player Metadata and Timeline Hotfix

- **Track information:** Audio controls now receive MPV’s live `media-title` and keep the title available during direct audio launches and queue transitions.
- **Cover art and embedded metadata:** Artwork and presentation metadata no longer become permanently blank when the audio screen appears before MPV emits `FILE_LOADED`; extraction retries after the load phase becomes ready.
- **Lyrics:** Lyrics lookup waits for a real MPV duration, retries when the loaded audio timeline becomes available, and ignores stale results from a previous track.
- **Seekbar:** The MPV audio timeline poller now recognizes the audio-load ownership hint, so direct music playback continuously updates position and duration instead of remaining at a placeholder.
- **Release scope:** This is a Standard APK hotfix; MPV remains the music engine, the repository remains private, and upstream MPV/mpvRx attribution is preserved.

## 1.1.6 — MPV Music Controls Hotfix

- **MPV music controls:** Audio-only transitions now explicitly retain MPV ownership, including audio containers such as MKV, so pause/resume, seek, speed, and track commands are not routed to a stale Native/Media3 player after returning from video playback.
- **Music timeline and metadata:** Audio loads now refresh MPV position and duration state, update the current title, and preserve the audio hint through queue navigation so the seekbar and song information remain synchronized.
- **Vertical pause button:** Removed the default rectangular pressed indication from the circular vertical video pause/resume control while preserving its touch behavior and circular shape.
- **Previous fixes preserved:** Includes the v1.1.5 portrait seekbar redesign and v1.1.4 video-startup crash protection. The repository remains private and only Standard APK variants are released.

## 1.1.4 — Video Startup Crash Hotfix

- **Video startup crash:** Prevented the MPV state poller and audio-routing check from dereferencing the player host before the Activity has attached it. This fixes the crash when opening a video after the v1.1.3 audio recovery changes.
- **Music-player behavior preserved:** Audio remains MPV-based, and the v1.1.3 music control/timeline recovery remains included.
- **Portrait controls preserved:** The v1.1.3 symmetric portrait seekbar-card redesign remains included.
- **Release scope:** This is a Standard APK corrective hotfix for v1.1.3; the repository remains private and upstream MPV/mpvRx attribution is preserved.

## 1.1.3 — Portrait Controls and Music Player Recovery Hotfix

- **Symmetric portrait seekbar card:** Rebuilt the vertical seekbar contents around one shared inner inset so both timestamps and the rail sit comfortably inside the same rounded card with balanced corner spacing. The existing glass/player-controls appearance is preserved.
- **Music-player recovery after video playback:** Audio commands are routed to MPV whenever the current item is audio, even if a stale Native engine flag remains from the preceding video. Audio timing is refreshed from MPV while the track is active, and audio scrubbing no longer gets rejected by a transient seekability state.
- **Video behavior preserved:** Native/Media3 routing remains unchanged for video items; this hotfix does not redesign the video engine or add a video surface to the music player.
- **Release scope:** This is a Standard APK hotfix for v1.1.2; the repository remains private and upstream MPV/mpvRx attribution is preserved.

## 1.1.2 — MPV Audio Recovery and Portrait Seekbar Hotfix

- **Rapid music skips:** MPV now releases any previous transition mute before arming the next load guard, preventing the audio output from remaining muted while the timeline continues after several fast song skips.
- **Video transition protection:** Each new MPV load still uses its own short transition guard, so the fix does not remove the protection against stale audio during video decoder replacement or engine handoff.
- **Portrait seekbar spacing:** Increased and equalized the outer portrait seekbar card’s vertical padding, while retaining the separated timestamp row and rail so elapsed and remaining duration stay visible.
- **Release scope:** This is a Standard APK hotfix for v1.1.1; the repository remains private and upstream MPV/mpvRx attribution is preserved.

## 1.1.1 — Native Subtitle Rendering Hotfix

- **Native caption style mapping:** Corrected the Media3 `CaptionStyleCompat` argument order so Native subtitles render with visible text, a transparent background/window, and the selected outline instead of black text and a small black cue rectangle.
- **Subtitle zoom preserved:** The existing Native pinch-scale and track-reapply behavior remains unchanged.
- **Release scope:** This is a Standard APK hotfix for v1.1.0; MPV subtitle rendering and the MPV-only music path are unchanged.

## 1.1.0 — Native Subtitle, Background Playback, and Portrait Control Fixes

- **Native subtitle rendering:** Native subtitles now use one configured Media3 subtitle renderer, preventing duplicate layers and visual artifacts when the size or track changes.
- **Transparent Native captions:** Native subtitle backgrounds and windows remain transparent instead of showing an unwanted black box. Supported style, border, color, and saved pinch-scale settings are reapplied after track updates.
- **Native background playback:** The Native player stays alive through the foreground service handoff when background playback is enabled, without incorrectly reading or controlling MPV state.
- **Music-player metadata and state:** Music playback remains MPV-based, while the music player derives its title from the current audio path or metadata instead of a stale video title.
- **Portrait controls:** The vertical player now has one transport row, compact icon-only actions, a speed/lock/audio header pill, and timestamps above the seek rail so elapsed and remaining duration stay visible.
- **Release scope:** This release contains the Standard APK variants only; the repository remains private and upstream MPV/mpvRx attribution is preserved.

## 1.0.11 — Native Playback and Portrait Controls Fixes

- **Native background playback:** When background playback is enabled, the Media3/Native player remains active as the Activity stops instead of being treated like the MPV-only service handoff.
- **Native subtitle styling:** Native captions now start with a transparent window and receive supported text, background, and edge settings from the subtitle panel while preserving the saved pinch scale across renderer and track updates.
- **Music-player synchronization:** The audio controls can use live Native state for play/pause, position, and duration instead of stale MPV properties.
- **Portrait controls:** The vertical player now uses a compact rounded control card with visible elapsed/remaining time, a centered transport row, labeled Subtitles/Zoom/Rotate/More actions, and a reference-style speed/lock/audio header pill.

## 1.0.10 — Native Subtitle and Music Player Fixes

- **Native subtitle persistence:** Native subtitle pinch zoom now keeps its scale in the active engine state and reapplies it when Media3 recreates the subtitle renderer or changes tracks.
- **Correct Native gesture baseline:** Each Native pinch starts from the last Native scale instead of MPV’s unrelated `sub-scale` value, which previously made Native appear to reset to 1.0.
- **Music-player launch routing:** Direct audio launches are identified before the first MPV track-list event, preventing the first song from briefly opening in the video-player surface.
- **Audio metadata recovery:** Embedded title, artist, album, artwork, codec, sample rate, bitrate, and channel information are used when transient MPV properties are unavailable, restoring the lossless details view for local files.

## 1.0.9 — MPV Subtitle Scale Application Fix

- **MPV rendering fix:** Removed the ASS subtitle layout path that reset `sub-scale` to `1.0` whenever subtitle positioning was reapplied.
- **Actual pinch effect:** MPV now retains the scale written by the pinch gesture or subtitle settings slider instead of showing a changed indicator while rendering the original size.
- **Native path preserved:** The v1.0.8 Media3 `SubtitleView` scaling bridge remains unchanged.
- **Private test release:** This build is for device validation; prior releases remain available and the planned public fresh-start reset is deferred.

## 1.0.8 — Shared Subtitle Pinch-Zoom Fix

- **Both engines supported:** Subtitle pinch gestures now detect selected Native subtitle tracks as well as MPV tracks.
- **Actual Native scaling:** Native pinch updates Media3’s attached `SubtitleView`; MPV continues to receive the existing `sub-scale` property update.
- **Private test release:** This build supersedes the v1.0.7 gesture-routing test and does not change the planned public fresh-start reset.

## 1.0.7 — Subtitle Pinch-Zoom Gesture Fix

- **Subtitle pinch zoom:** Pinching directly over visible subtitle text now routes to `sub-scale` instead of being misclassified as video zoom.
- **Gesture behavior preserved:** Video pinch zoom, pan, subtitle position gestures, and both Native and MPV playback paths remain unchanged outside the corrected subtitle hit test.
- **Private test release:** This build is for device validation; previous releases remain available and the planned public fresh-start reset is deferred.

## 1.0.6 — Native Multichannel Regression Fix

- **Wuthering With You regression fixed:** Native AutoSafe now preserves the six-channel layout used by the previously working DTS/DTS-HD path instead of forcing it through a stereo mixer.
- **Targeted 7.1 protection:** AutoSafe still downmixes seven- and eight-channel input, which is the narrower compatibility path needed for Tangled’s failing AudioTrack configuration.
- **Fallback preserved:** If Native genuinely fails on a device, the existing safe MPV fallback remains active and the repository stays on the private test-release track.

## 1.0.4 — Tangled Fallback and Stability Fix

- **Media3-error fallback protection:** Auto mode now records a Native/Media3 playback error as an automatic fallback for the affected item before switching to MPV, preventing the MPV Dolby Vision observer from immediately selecting Native again.
- **Tangled stability:** This covers the failure path where Media3 initializes but cannot create the required audio renderer for an 8-channel Dolby/TrueHD or E-AC-3 track, while MPV can continue playback.
- **Detached-callback crash protection:** Delayed player cleanup callbacks no longer call the detached `PlayerActivity` host after an engine transition or Activity teardown. Local cleanup state is still cleared safely.
- **Manual switching preserved:** The new protection applies to automatic fallback only; explicit decoder-sheet selection of Native or MPV remains available.

## 1.0.3 — Tangled Dolby Vision Handoff Fix

- **Observer-path fallback protection:** Auto mode now suppresses the MPV Dolby Vision track observer from immediately selecting Native again after the Native watchdog has already fallen back to MPV for the same item.
- **Tangled compatibility:** This specifically fixes Dolby Vision Profile 8.1 / HDR10-compatible files such as Tangled, where Native can initialize and decode but fail to produce usable visible output on some devices.
- **Manual switching preserved:** The guard applies only to automatic Auto-mode reselection. Choosing Native or MPV manually from the decoder sheet still works.

## 1.0.2 — Mpv∞ Update Feed Fix

- **Own release feed:** The in-app updater now checks `ZHINFINITY/Mpv-infinity` releases instead of the inherited mpvRx release feed, so it will no longer offer mpvRx versions such as 2.1.0.
- **Version alignment:** The corrective build is version `1.0.2` and downloads only the compatible Mpv∞ Standard APK from the Mpv∞ release assets.

## 1.0.0 — Feature & Experience Release

### 🧲 Native Torrent Streaming & Media Browsing
- **Built-in Torrent Streaming Engine**: Stream and play `.torrent` files, magnet links, and HTTP torrent URLs directly with background piece caching, sequential download prioritization, and integrated local streaming server.
- **Dedicated Torrent File & Episode Picker**: Interactive file selection screen showing all playable video and audio items within multi-file torrents, complete with individual file sizes, format tags, launch status, and episode labels.
- **Anime & Series Torrent Cards**: Redesigned Torrent history cards in Network Streaming that automatically group multi-episode series with expandable episode lists, poster art, synopsis, release year, and media type tags.
- **Smart TMDB Metadata & Artwork Parsing**: Multi-stage metadata engine that intelligently parses filenames, seasons, and episodes (supporting complex scene tags, colon separators like `S1:E1`, cross-format `1x01`, dashes, and anime numbering) with multi-candidate fallback search against TMDB to fetch high-resolution posters, backdrops, and overviews.
- **Inline Episode Search & Sorting**: Built-in search bar within expanded torrent cards to quickly filter large episode collections by title, file path, or episode number, alongside ascending/descending (1–N / A–Z) sorting toggles.
- **Torrent Playback History & Progress Memory**: Automatically tracks opened and watched torrent files with checkmark badges and one-tap resume capabilities.
- **Real-Time Torrent Buffering Status**: Live buffer duration indicator and demuxer cache percentage chip displayed directly beneath the player loading spinner during network cache buffering.

### 🎵 Music Library Revamp & Audio Player Experience
- **Dedicated Music Library Screen**: Completely redesigned Music tab with organized sub-tabs for **Songs**, **Albums**, **Artists**, **Playlists**, and **Folders**.
- **Audio Folder Browser & Sorting**: Integrated folder-based audio browser in the Music Library with folder sorting dialog (`FolderSortDialog`), instant text filtering, and clean audio-only traversal.
- **Customizable & Reorderable Music Sub-Tabs**: Preference setting to toggle visibility and customize the ordering of Music Library sub-tabs to match your workflow.
- **Interactive Cover Art Swipe Pager**: Horizontal swipe gestures on album art cards to fluidly navigate between previous and next tracks in active playlists with real-time drag physics.
- **Synchronized LRC Lyrics & In-Place Lyrics View**: Full support for synchronized LRC lyrics featuring white active-line highlighting, tap-to-restore fullscreen mode, auto-hiding controls, and embedded vs. external source toggling.
- **Audio Visualizers & Reactivity**: Full visualizer support across 4 distinct styles (Galaxy, Blob, 3D Cuboid Warptunnel, Particle) with spectrum capture lifecycle optimizations that suspend rendering behind sheets to save battery.
- **In-Place Lossless Specs & Audio Enhancements**:
  - Direct on-card Lossless badge with tap-to-expand details (`HI-RES LOSSLESS`, format, sample rate, bit depth, bitrate).
  - Quick-access Equalizer button in player controls, dedicated playback speed indicator next to A-B loop, and in-player playlist management.
  - Dynamic Range Compression (DRC) audio filter and non-overriding filter pipeline.

### 📦 Release Flavors & Device Compatibility
- **Standard Default Build**: The primary release featuring full `gpu-next` and Vulkan hardware acceleration, built for standard modern Android devices.
- **Non-Vulkan Compatibility Build**: A dedicated flavor with Vulkan disabled to eliminate startup and playback crashes on legacy GPU drivers and older devices without Vulkan support.
- **Fongmi High-Performance Build**: Advanced flavor supporting MediaCodec hardware decoding under Vulkan mode, aggressive rendering optimizations, and Dolby Vision (DV) playback capabilities.

### 🌐 Network Streaming, Proxies & Protocols
- **In-App HLS Proxy Engine**: Added dedicated local HLS proxy server with a settings toggle for optimized live stream and HLS media handling.
- **SMB Tree Ownership & Session Reliability**: Resolved DiskShare closing issues where session-cached SMB connections were prematurely closed during per-request streams.
- **Preserved HTTP Headers & Cookies**: Custom headers, cookies, and user-agent strings are now properly preserved across network stream resolvers and playback requests.
- **Recent Stream Cards & Quick Autofill**: Quick paste and autofill stream cards with one-tap playback launch.

### 📱 UI Polish, Themes & Controls
- **New Aurora Theme**: Added modern Aurora gradient color theme and refreshed navigation library icons.
- **Auto-Marquee for Long Media Titles**: Implemented continuous auto-marquee scrolling for long track names, torrent titles, and episode filenames across all list rows.
- **Expanded Video Sharpness Limits**: Expanded video sharpness adjustments to a full $\pm 10$ range (Issue #391).
- **Translucent Bottom Sheets & Normal Seekbar Polish**: Soft translucent sheet background styling (Issue #391) and fixed normal seekbar track coloring in dark themes (Issue #393).
- **Smooth Tab Pager & Navigation Inset Clearance**: Added pre-paging cache (`beyondViewportPageCount = 1`) and graphics-layer translation to prevent jank during tab swipes, with bottom padding clearance across all tabs to prevent navigation button overlap.
- **Video Mini-Player Control**: Option to toggle mini-player for video playback independently of audio, with subtitle suppression in mini-player mode.
- **Pulsing Search Highlights & Autoscroll**: Settings search now smoothly scrolls to and pulses the target preference with a high-contrast highlight indicator.
- **Unified Audio & Video Folder Blacklisting**: Manage blacklisted directories for audio and video media libraries independently or together.
- **Supported Codecs Inspection Screen**: Detailed hardware vs. software decoder capabilities list for all audio and video media codecs supported on device.
- **Full Multi-Language Localization**: Added complete Hindi language translation support alongside English, Arabic, German, Spanish, French, Japanese, Brazilian Portuguese, Russian, and Simplified Chinese.

### ⚡ Performance, Engine & Stability
- **Upgraded MPV Engine & FFmpeg 9.0**: Updated bundled `mpvlib` binaries with updated SSL certificates, Fongmi Vulkan / MediaCodec support, and universal non-Vulkan fallbacks for older devices.
- **Precise Network Seek Previews & Seek Guards**: Enhanced seekbar thumbnail scrubbing precision over network streams, non-blocking thumbnail extraction, and seek-guarding to prevent audio stuttering during rapid scrubbing.
- **Playback History Disambiguation**: Resolved history collisions for same-named files in different directories by keying history records with unique canonical paths (Issue #382).
- **Jetpack Compose Low-Power Tuning**: Phase-deferred layout and draw calls, memoized image bitmaps, optimized list recycling with `contentType`, and elimination of recomposition stalls during tab swiping.
- **Memory & Lifecycle Hardening**: Added LeakCanary detection in debug builds, persistent foreground playback notification services, and leak-free session teardowns.

## 2.0.0 — Major Release

### 🎵 Music Player & Audio Experience
- **Dedicated Music & Audio Player Interface**: Built a full-fledged audio player UI with responsive portrait/landscape layouts, dynamic theme backgrounds, transparent visualizers, cover art polish, and real-time metadata display.
- **Tablet Landscape Dual-Pane Music Player**: New tablet-optimized two-pane music player layout with smooth drag-and-drop playlist reordering.
- **Interactive Audio Visualizers**:
  - **3D Cuboid Warptunnel Visualizer**: Ported native Compose Canvas 3D tunnel visualizer featuring dynamic tunnel radius, touch-steering controls, 3D rotation gestures, pinch-zoom, screen-filling scale, dynamic theme palettes, and interactive reactivity.
  - **OpenGL Blob & Galaxy Visualizers**: High-rate spectrum capture, FFT audio-capture energy processing, tuned frequency envelopes, frame-time-aware interpolation, and responsive beat decay for smooth, jump-free rendering.
  - **PCM-based Visualizer Pipeline**: New per-bin FFT texture pipeline for enhanced audio spectrum analysis with dual waveform/FFT capture.
- **Separate Background Playback Controls**: Introduced independent background playback settings for audio vs. video media with notification permission prompts, system brightness restriction to valid ranges, and automatic service cleanup.
- **Smarter Media Notifications**: Audio notifications now strip file extensions from track titles and fall back to embedded album art when MPV reports no thumbnail.
- **Uninterrupted Background Music**: Swiping back during audio playback keeps the song playing, the playback service stays alive when reopening the player from a notification, and toggling the background playback setting off no longer pauses active music.
- **Equalizer & Audio Filters**: Added a built-in equalizer with debounced MPV audio filter updates for smooth slider movement, A-B looping, and persistent pitch correction controls.
- **Audio Playlist Management**: Added drag-and-drop playlist item reordering, M3U/IPTV `tvg-logo` thumbnail fallbacks, square 1:1 artwork cards, and automatic sibling audio file list population.
- **Audio Player Orientation & Controls**: Added configurable audio player orientation settings, centered play/pause controls in portrait mode, and dedicated audio background playback toggle.
- **Audio Mode UI Fixes**: System bars no longer flicker when opening sheets in audio mode, and seekbar timer colors adapt to the active theme.

### 📺 Google Cast & Remote Controls
- **Native Cast Integration**: Added stateful Google Cast buttons to both portrait and landscape player control bars with layout migration support for existing configurations.
- **Local & Remote Media Handoff**: Casts remote HTTP(S) streams directly, while local `file://` and `content://` media are served over a secure, tokenized local HTTP server with byte-range seeking and CORS support.
- **Remote Cast Controller**: Full-featured remote control dialog supporting play/pause, volume adjustment, seeking, playback speed selection, and media track switching.
- **Playback Continuity**: Seamlessly synchronizes title, play state, duration, and position when transferring playback between local device and Cast receivers, restoring state on disconnect.
- **Graceful Cast Degradation**: The player stays fully functional when the Google Cast module is unavailable on a device.

### ⚡ Performance & Video Player Polish
- **GPU View Transformations**: Implemented hardware-accelerated GPU view transformations for ultra-smooth video panning and zoom.
- **Refined Touch Gestures**: Restricted video pan gestures to two-finger operations to prevent accidental screen shifts while scrubbing or adjusting volume/brightness. Decoupled video and subtitle gesture toggles.
- **Snap-to-Preset Hold-Speed**: Hold-speed gesture now snaps to fixed presets (0.5x, 1x, 1.5x, 2x, 2.5x, 3x, 3.5x, 4x) displayed as a clean text pill, replacing the old overlay slider.
- **Native Ported Thumbnail Pipeline**: Integrated fast native video thumbnail extraction pipeline (ported from mpvRex) for instant seekbar scrubbing previews and refreshed visible thumbnail updates.
- **YouTube-Style Ambient Lighting**: Added ambient background mode that dynamically projects matching video color highlights around the player.
- **Display Refresh Rate Auto-Matching**: Automatically adjusts the display refresh rate to match video source frame rates for smooth, tear-free video output.
- **Anime4K & Vulkan Upscaling**: Added standalone Anime4K Ultra upscaling mode with `gpu-next` Vulkan requirement checks and optimized baseline profiles.
- **HDR Mode Hardening**: HDR modes are now properly gated by renderer support (GPU Next + Vulkan), colors are restored correctly when HDR is disabled, and your last selected HDR mode is remembered between sessions.
- **Linear HDR Restored**: Reverted Linear HDR to use mpv-native pipeline without hdr-toys shaders, fixing brightness issues on supported devices.
- **Robust Player Sessions**: Added an mpv session coordinator that reliably tears down and recovers sessions (no ghost players after crashes), sanitizes the mpv config, and collects player diagnostics.
- **Negative Brightness Control**: Support for negative brightness adjustment to dim the display below system default minimums.
- **Instant Video Launch & Startup Optimization**: Offloaded file loading to `Dispatchers.Default` to eliminate UI thread blocking. Deferred cold-start DB init, grammar pre-load, and auto-update checks to cut first-frame time significantly.
- **Screenshot Timestamps & Templates**: Re-worked screenshot templates (`%F`, `%P`, `%p`, `%wH`, `%wM`, `%wS`, `%wT`) to use exact video playback position instead of wall-clock time.
- **Audio Decoder Fallback**: Added audio decoder check and fallback for unsupported audio codecs in compressor.

### 📱 UI & Modern Tablet Dual-Pane Design
- **Telegram-Style Floating Pill Navbar**: Redesigned bottom navigation bar with a modern floating pill design, smooth sliding indicator animations, and gesture-synced pill motion that follows finger swipes across tabs.
- **Horizontal Pager Tab Navigation**: Implemented swipeable horizontal pager transitions across main navigation tabs (`MainScreen`).
- **Tablet & Foldable Dual-Pane Layouts**: Full dual-pane interface support for Folder List, File System Browser, and Settings screens with active folder card highlights and animated navigation pills.
- **Dynamic Grid & Column Layouts**: Independent grid/list view toggles for folders and video lists with custom column counts, side-by-side column sliders, and haptic feedback ticks on snap.
- **Quick Play FAB & Direct Play Toggle**: Added Quick Play Floating Action Button (FAB) with action menu (Open File, Recently Played, Open Link) and a new **Direct Quick Play** setting to immediately launch recent media without showing the menu.
- **Tree View Path Compression**: Configurable single-child folder flattening (`Off`, 1–5, `Unlimited`) applied per navigation step.
- **FAB Alignment & Exit Animations**: Aligned FAB Y-positions across Home, Recents, and File System screens, and preserved floating action bar buttons during exit animations.
- **Navbar & Toolbar Polish**: Fixed floating navbar bottom inset padding, added a smooth animated selection toolbar, and fixed the bottom navbar hiding in the audio library.
- **Unified Blur Theme Transitions**: Consistent blur-based theme transitions throughout the app.
- **Rounded Material Symbol Icons**: All app icons unified onto rounded Material Symbols with a cleaner icon pipeline.
- **Header Theme Toggle**: Single tap on app name/screen title now toggles dark/light theme with circular reveal animation (always enabled).

### 🌐 Syncplay & Network Streaming
- **Synchronized Room Playback**: Complete Syncplay client implementation featuring server connections, room creation/joining, MD5 password authentication, latency compensation, protocol version handshakes, and user list sync with background reconnection fixes.
- **Native HLS/DASH Streaming**: Direct media URLs (`.m3u8`, `.mpd`, `.mp4`, `.ts`) bypass yt-dlp to use MPV's native ffmpeg demuxers for faster, crash-free playback.
- **Expanded Protocol Support**: Added native stream detection and intent filter handling for `gopher://`, `sctp://`, `data://`, and MIME-only external player intents.
- **WebDAV Connectivity Enhancements**: Depth-zero `PROPFIND` connection checks for compatibility with servers like FileBrowser Quantum, with consistent trailing slash handling.
- **yt-dlp Audio Quality Selection**: Preferred audio stream quality selector (Auto, 64, 128, 192, 256 kbps) for network media links.
- **Lua Script Module Syncing**: Automatically syncs `script-modules/` to internal storage so Lua `require()` calls locate custom MPV modules.

### 🤖 AI Providers & Smart Tools
- **Multi-Provider AI Engine**: Integrated support for OpenAI, Anthropic, Groq, OpenRouter, Together AI, and OpenCode.
- **Resilient AI Parsing**: Added reasoning-block stripping, fallback JSON parsers, and provider-specific model memory for smart file renaming and subtitle cleanup.

### 🔍 Subtitle System & Search
- **Real-Time Subtitle Merging**: Online subtitle search results from SubHub and Wyzie stream in live as requests complete.
- **Anime Skip Integration**: Added Anime Skip provider integration (GraphQL api.anime-skip.com) for intro/ending detection, and removed obsolete TMDB mirror.
- **Hitbox & Gesture Adjustments**: Dynamic multi-line wrapped text hitbox calculations under zoom/pan, option to invert swipe subtitle gesture direction, and automatic secondary subtitle position offset using primary hitbox to prevent overlap.
- **Subtitle Track Roles**: Subtitle track sheet now displays track roles (primary/secondary) for multi-role subtitle tracks.
- **Stream Subtitle Fix**: Subtitles load reliably on network streams without breaking background playback.
- **Korean Subtitle Fix**: Fixed broken Korean Jamo rendering using NFC Unicode normalization.
- **Subtitle Persistence & Search Keyboard Fix**: Subtitles persist across sessions reliably (`addSubtitleSuspend`), and soft keyboard no longer covers the subtitle search dialog (`adjustResize`).

### ⚙️ Settings, Lifecycle, i18n & Binary Footprint
- **Dedicated MediaSearchEngine**: High-performance search engine indexing files and folders with VideoFolder references.
- **Incremental Folder Scanning**: Hidden folder scanning is now incremental and DB-backed, so revisiting large libraries is dramatically faster.
- **Settings Search & Suggestions**: Dynamic reflection-based settings search with query history and real-time suggestions.
- **App Language Preference**: Added per-app language selection independent of system language settings.
- **Redesigned Permission Setup**: Storage permission page redesigned with separate File & Notification sections and a guided Next-button flow, now responsive across screen sizes.
- **mpv Config Validation**: The config editor validates `mpv.conf` before saving and properly closes editor streams to avoid file lockups.
- **Folder Deletion Behavior**: Media-only deletion mode by default (removes video/audio files while protecting documents/images) with full recursive deletion toggle in settings.
- **Unified Background Playback Lifecycle**: Streamlined PiP transitions, screen lock/unlock handling, and task removal behavior for Android 15/16.
- **Android 16 Compatibility**: Native libmpv subprocess handling updated for Android 16, with a cache-safe, coordinated MPV teardown process.
- **Binary Footprint Optimization**: Removed ~50 MB of unused `libpython_bin.so` binaries across all CPU architectures.
- **Toast Notifications for Blocked Audio**: Displays helpful toast alerts when background playback is restricted by notification settings.
- **Memory Leak & Crash Fixes**: Plugged 5 memory leaks across player activity, main activity, and background services, and fixed audio player back navigation crashes.
- **Full Multi-Language Localization**: Complete string key synchronization and translations across English, Arabic, German, Spanish, French, Japanese, Brazilian Portuguese, Russian, and Simplified Chinese.
- **Code Cleanup**: Removed dead code, extracted shared utilities, DRY/SOLID cleanup across codebase.

### 🔒 Secure Folder
- **PIN Gate & Grid Screen**: Secure Folder now requires PIN authentication before access, with a grid view of secured media.
- **Biometric Authentication**: Added fingerprint and face unlock support for quick access (when device supports BIOMETRIC_STRONG).
- **Layout Mode**: Enabled List/Grid layout toggle in Secure Folder sort options.
- **Back Button Behavior**: Pressing back in selection mode now deselects all items first before navigating back.
- **Hide Entry Point**: Option to hide Secure Folder from preferences (still accessible via title double-tap).
- **Don't Ask Again Flags**: Added "don't ask again" options for move, restore, delete, and hide entry point confirmations.
- **Tablet Responsiveness**: Improved Secure Folder UI for tablet and foldable devices.

### 🏷️ Branding, Licensing & Cleanup
- **App Rename**: The app is now branded **mpvRx** (renamed from "MpvRx") across UI, docs, and metadata.
- **New License**: Relicensed to **CC BY-NC 4.0** with license headers applied across the codebase.
- **Acknowledgements**: Added credit for MpvRex and Pixel Player (UI and thumbnail pipeline inspiration) and AFinity.
- **Code Quality**: Added ktlint formatting enforcement and fixed AAPT resource and Kotlin compiler warnings.

## 1.5.0-preview.5 — Preview Release

### Syncplay
- **Synchronized room playback**: Added Syncplay server, room, username, and optional password controls to Network Streaming.
- **Live player integration**: Local pause, resume, seek, file, and playback-position changes are shared with the room, while remote room updates are applied to mpv.
- **Protocol compatibility fixes**: Added the legacy and real protocol version handshake fields, MD5 server-password handling, latency compensation, user-list updates, and `ignoringOnTheFly` feedback suppression.
- **Localized interface**: Extracted all Syncplay UI text into Android resources and translated it for Arabic, German, Spanish, French, Japanese, Brazilian Portuguese, Russian, and Simplified Chinese.

### Google Cast
- **Native Cast control**: Added the standard stateful Google Cast button to portrait and landscape player controls, including a one-time layout migration for existing users.
- **Local and remote media handoff**: Receiver-accessible HTTP(S) streams are loaded directly; Android-local `file://` and `content://` media use a tokenized temporary LAN server with byte-range seeking and CORS support.
- **Playback continuity**: The sender transfers title, play state, duration, and current position, pauses local playback after a successful receiver load, and restores local playback at the receiver position when casting ends.
- **Complete sender controls**: Added Cast SDK expanded controls, notification/lock-screen integration, reconnection support, and receiver volume controls.
- **Receiver compatibility guardrail**: Casting uses Google's Default Media Receiver, so containers and codecs unsupported by the selected TV/Chromecast are not transcoded automatically.

### Playback Lifecycle & Background Playback
- **One background playback switch**: Audio preferences and the player background button now control the same persistent setting for both audio and video.
- **Reliable screen lock and unlock handling**: Playback now follows the selected background policy across screen-off, lock-screen, unlock, and resume transitions without losing the prior play state.
- **PiP lifecycle coordination**: Picture-in-picture transitions, PiP dismissal, screen locking while in PiP, and activity teardown now share one lifecycle policy instead of competing playback paths.
- **Persistent task-removal playback**: Swiping the app out of Recents no longer kills an active foreground playback session, so background audio continues until explicitly stopped.
- **Foreground service cleanup**: Disabling background playback immediately pauses playback and stops its foreground service and notification.
- **Preference migration**: Existing users who enabled the legacy audio-only screen-lock option are migrated to the unified background playback setting.

### Subtitle Search
- **Results appear as they arrive**: Online subtitle results are merged into the list as each selected SubHub source or Wyzie completes instead of waiting for every request.
- **Race-free repeat searches**: Starting another subtitle search cancels the previous request and clears stale results before streaming the new matches.

### Playback Engine
- **Updated bundled mpvlib**: Refreshed the packaged `mpvlib.aar` used by all preview APK variants.

### Media Library & Audio Browsing
- **Preference-aware media switch**: The Video/Audio selector appears only when audio browsing is enabled, remembers the selected library type, and resets to Video when audio browsing is disabled.
- **Complete audio playlists**: Audio launches now populate the active playlist so previous and next controls update and navigate correctly.
- **Square audio artwork**: Audio thumbnails use a square presentation in both grid and list cards while video thumbnails remain 16:9.
- **Audio-safe editing**: Video compression actions are hidden for audio selections and guarded from opening with audio files.

### Player Polish
- **Rounded streaming cache indicator**: Buffered seekbar progress is clipped to the same pill-shaped ends as the normal seek track.
- **Smoother natural visualizer**: Higher-rate spectrum capture, tuned frequency envelopes, frame-time-aware interpolation, and responsive beat decay make the audio blob react fluidly without harsh jumps.

### AI Providers & Smart Tools
- **Current provider protocols**: OpenAI, Anthropic, Groq, OpenRouter, Together, and OpenCode Zen requests now follow their current API shapes; OpenCode models are routed through Responses, Messages, Gemini, or Chat Completions as required.
- **Resilient response parsing**: Text, multipart content, reasoning blocks, citations, provider errors, and both object- and array-based model lists are parsed without relying on one rigid response schema.
- **Thinking-model rename fixes**: AI rename and subtitle-title output now discard reasoning and code fences, accept structured JSON fields, preserve extensions, and reject empty or unsafe names.
- **Provider-specific model memory**: Each provider keeps its own selected model and cached model list, preventing stale selections when switching services.
- **Correct speech endpoints**: Groq and OpenAI use supported transcription models, while OpenRouter speech requests use its current base64 JSON audio format.

## 1.5.0-preview.4 — Preview Release

### Google Cast
- **Native Cast control**: Added the standard stateful Google Cast button to portrait and landscape player controls, including a one-time layout migration for existing users.
- **Local and remote media handoff**: Receiver-accessible HTTP(S) streams are loaded directly; Android-local `file://` and `content://` media use a tokenized temporary LAN server with byte-range seeking and CORS support.
- **Playback continuity**: The sender transfers title, play state, duration, and current position, pauses local playback after a successful receiver load, and restores local playback at the receiver position when casting ends.
- **Complete sender controls**: Added Cast SDK expanded controls, notification/lock-screen integration, reconnection support, and receiver volume controls.
- **Receiver compatibility guardrail**: Casting uses Google's Default Media Receiver, so containers and codecs unsupported by the selected TV/Chromecast are not transcoded automatically.

### Playback Lifecycle & Background Playback
- **One background playback switch**: Audio preferences and the player background button now control the same persistent setting for both audio and video.
- **Reliable screen lock and unlock handling**: Playback now follows the selected background policy across screen-off, lock-screen, unlock, and resume transitions without losing the prior play state.
- **PiP lifecycle coordination**: Picture-in-picture transitions, PiP dismissal, screen locking while in PiP, and activity teardown now share one lifecycle policy instead of competing playback paths.
- **Persistent task-removal playback**: Swiping the app out of Recents no longer kills an active foreground playback session, so background audio continues until explicitly stopped.
- **Foreground service cleanup**: Disabling background playback immediately pauses playback and stops its foreground service and notification.
- **Preference migration**: Existing users who enabled the legacy audio-only screen-lock option are migrated to the unified background playback setting.

### Subtitle Search
- **Results appear as they arrive**: Online subtitle results are merged into the list as each selected SubHub source or Wyzie completes instead of waiting for every request.
- **Race-free repeat searches**: Starting another subtitle search cancels the previous request and clears stale results before streaming the new matches.

### Playback Engine
- **Updated bundled mpvlib**: Refreshed the packaged `mpvlib.aar` used by all preview APK variants.

### Media Library & Audio Browsing
- **Preference-aware media switch**: The Video/Audio selector appears only when audio browsing is enabled, remembers the selected library type, and resets to Video when audio browsing is disabled.
- **Complete audio playlists**: Audio launches now populate the active playlist so previous and next controls update and navigate correctly.
- **Square audio artwork**: Audio thumbnails use a square presentation in both grid and list cards while video thumbnails remain 16:9.
- **Audio-safe editing**: Video compression actions are hidden for audio selections and guarded from opening with audio files.

### Player Polish
- **Rounded streaming cache indicator**: Buffered seekbar progress is clipped to the same pill-shaped ends as the normal seek track.
- **Smoother natural visualizer**: Higher-rate spectrum capture, tuned frequency envelopes, frame-time-aware interpolation, and responsive beat decay make the audio blob react fluidly without harsh jumps.

### AI Providers & Smart Tools
- **Current provider protocols**: OpenAI, Anthropic, Groq, OpenRouter, Together, and OpenCode Zen requests now follow their current API shapes; OpenCode models are routed through Responses, Messages, Gemini, or Chat Completions as required.
- **Resilient response parsing**: Text, multipart content, reasoning blocks, citations, provider errors, and both object- and array-based model lists are parsed without relying on one rigid response schema.
- **Thinking-model rename fixes**: AI rename and subtitle-title output now discard reasoning and code fences, accept structured JSON fields, preserve extensions, and reject empty or unsafe names.
- **Provider-specific model memory**: Each provider keeps its own selected model and cached model list, preventing stale selections when switching services.
- **Correct speech endpoints**: Groq and OpenAI use supported transcription models, while OpenRouter speech requests use its current base64 JSON audio format.

## 1.5.0-preview.3 — Preview Release

### Playback Hotfix
- **Audio and video transitions rebuilt**: Every item is loaded with file-local video-track selection, preventing audio state from producing a black screen on the next video.
- **Reliable next-item playback**: Runtime navigation now sends an actual mpv `loadfile` replacement and clears the previous EOF pause state.
- **Preference-safe EOF handling**: Autoplay, Repeat One, Repeat All, Shuffle, playlist mode, and close-at-end now behave consistently, including very short audio files.
- **Safe seeking**: Audio never enters the native video-thumbnail path, and video thumbnails are generated only on demand while scrubbing—not automatically during file load.
- **Separated render surfaces**: The blob surface is mounted only for media independently identified as audio, preventing it from covering video during track-list transitions.
- **True beat response**: With audio-capture permission, real FFT data exclusively drives the blob; brightness and bloom are reduced while bass onsets pulse more clearly.

### 🔊 Audio Blob Visualizer
- **OpenGL ES 3.0 blob visualizer** — when playing audio without cover art, a reactive 3D blob appears behind the player controls. The blob morphs, pulses, and shifts color based on audio energy with a bloom/glow post-process.
- **Touch rotation**: Drag the blob to rotate it in 3D.
- **Pinch zoom fixed**: The blob now properly shrinks/enlarges when pinching — `pinchScaleFromRenderer()` was hardcoded to always return `1f`, resetting zoom on every new gesture.
- **Smaller default size**: Camera distance increased so the blob fits cleanly within screen bounds instead of nearly filling the height.
- **Audio Preferences toggle**: New "Audio blob visualizer" switch in Settings > Audio to enable or disable it.
- **Audio filter setting moved**: Audio filter (compressor/equalizer) options now live in Audio Preferences instead of the player's MoreSheet.

### 🎬 yt-dlp Changes
- **Audio quality preferences**: Independent bitrate caps for `Auto`, 64, 128, 192, and 256 kbps — composed with existing codec, resolution, FPS, HDR, and container selectors.
- **Serialized URL loading**: Initial and replacement URL loads now use one cancellable serialized job, preventing overlapping libmpv commands when links are pasted rapidly.
- **Graceful error recovery**: Recoverable URL load failures return to the player UI with an error message instead of escaping to the process-wide crash handler.
- **Audio quality removed from MoreSheet**: yt-dlp audio quality selector moved from the player's MoreSheet to yt-dlp settings.

### 📻 Audio Browsing
- **MediaStore + filesystem discovery** for common audio formats, neutral media counts, audio MIME mapping, and Android 13 `READ_MEDIA_AUDIO` permission handling.
- **Audio cards** show metadata titles, embedded cover artwork when available (via `audio-display=embedded-first`), and a music-note fallback icon.
- **Portrait-only playback** — audio files force sensor-portrait orientation and prevent the rotation action from switching back to landscape.
- **Sibling playlist includes audio** — when "Include audio" is on, the next/previous track list includes audio files from the same folder.
- **Audio icon placeholder fix**: Exported icon now displays correctly for audio files without cover art.
- **Audio pitch correction fix**: Pitch correction no longer persists when switching to a new video — resets to default per-file.
- **Audio autoplay race condition fixed**: Eliminated a race condition that could cause audio files to fail starting playback.

### 🌐 Network & External Playback
- **WebDAV PROPFIND fix**: Connection checks now use a depth-zero `PROPFIND` request instead of Sardine's `HEAD`-based `exists()` call, making it work with servers like FileBrowser Quantum that reject `HEAD` on DAV collections.
- **WebDAV trailing slash**: Collection URLs consistently keep a trailing slash during validation and browsing.
- **External-player discovery**: Added a MIME-only intent filter so external-player pickers can find mpvRx before attaching the final video or audio URI.
- **More protocol support**: Added `gopher://`, `sctp://`, and `data://` to network stream detection and intent filters.
- **Stream compatibility improved**: Better handling of edge-case media streams and improved browsing reliability.

### 🌲 Tree View Navigation
- **Configurable path compression**: New `Off`, 1–5, and `Unlimited` choices for single-child folder flattening. Applied independently per navigation step, preserving predictable physical paths. Tree View refreshes instantly when the depth changes.

### 🎨 Icon Consistency
- Converted all three `painterResource(R.drawable.ic_material_symbols_check)` usages to `Icons.Default.Check` through the app's `AppIcon` / `Icon` system.
- `SectionHeader.leadingIcon` and `CompactExpressiveIconButton.imageVector` now accept `AppIcon` instead of raw `ImageVector`, keeping everything on the unified icon pipeline.

### 🔊 Audio Playback Runtime Fixes
- **`local_media_path` extra**: Internal launches now pass the resolved filesystem path alongside the content URI, giving mpv a reliable fallback when `content://` URIs fail.
- **Serialized load dispatcher**: Added a dedicated `Dispatchers.Default.limitedParallelism(1)` dispatcher for media loading — prevents race conditions when queuing multiple load commands.
- **`vid=auto` before playback**: Non-M3U file loads explicitly reset the video track to auto before loading, avoiding "no video track" state from previous audio-only plays.
- **`audio-display=embedded-first`**: Enabled MPV's embedded cover art rendering for audio files.
- **Orientation on audio launch**: `setOrientation()` checks `isKnownAudioLaunch()` immediately, before the track-list event settles — fixes the black-screen + landscape glitch on audio start.
- **Subtitle "Off" option**: Added an explicit "Off" choice in the player subtitle sheet so users can disable subtitles without cycling through all tracks.

### 🗑️ Folder Deletion Behavior
- **Media-only deletion (default)**: Deleting a folder now only removes audio/video files — other files (images, logs, documents) are left untouched.
- **"Delete folder + all contents" toggle**: New option in Appearance Settings > File Browser to switch back to full recursive deletion when needed.
- **Album View cleanup fix**: Fixed an issue where the last video file in a folder was deleted but the empty folder remained.

### 📱 Tablet & Display
- **Dynamic refresh rate**: The player can now dynamically adjust the display refresh rate to match the video frame rate for smoother playback.
- **Dual-pane navigation fix**: Resolved a state leak that could cause crashes when navigating in tablet dual-pane mode.
- **Dual-pane settings button hidden**: The redundant settings button is no longer shown in dual-pane mode on tablets.
- **Folder card height fix**: Manual grid column counts now align properly with folder card heights in dual-pane layout.

### 🔍 Settings Search
- **Search history**: Recently searched terms are saved and displayed for quick re-selection.
- **Search suggestions**: The search field now shows contextual suggestions as you type.
- **Reflection-based fallback**: Settings search can dynamically discover settings via reflection when static indices are incomplete.



## 1.5.0-preview.2 — Preview Release

### 📦 MpvLib Update
- Updated mpv library and its dependencies

### ⚡ Performance & Startup
- **Faster video open**: Opening a video file now uses `Dispatchers.Default` for the `playFile()` call — keeps the UI thread free and the player starts faster
- **Leaner startup sync**: The MPV directory sync no longer blanket-copies `shaders/` and `fonts/` on every launch — only config files, scripts, and `script-opts/` are synced upfront. Shaders referenced in `mpv.conf` are pulled on demand via `syncReferencedShaders()`, and fonts are handled by the font manager. This cuts down startup time noticeably, especially for users with large shader packs
- **Removed Dynamic Speed Overlay**: The old `SpeedControlSlider` (a full-size overlay with a dot-track slider) and `CompactSpeedIndicator` are gone. Hold-speed is now shown as a simple, clean text pill (e.g. "2x"). The `showDynamicSpeedOverlay` preference has been removed too — no more toggles, no more clutter
- **Snap-to-preset hold speed**: The hold-speed gesture now snaps to fixed presets (0.5x → 1x → 1.5x → 2x → 2.5x → 3x → 3.5x → 4x) instead of a free-form slider. The settings slider also snaps to these values, so what you see is what you get
- **Hold speed range capped**: Boost speed is now capped at 0.5x–4x range (previously went up to 6x)

### 📱 Tablet Dual-Pane Layouts
- **Folder view dual pane**: On tablets (600dp+ smallest screen width), you can now see your folder list on the left and a video list on the right — tap a folder, see its contents immediately beside it. A new "Dual Pane View" toggle in Appearance Settings lets you turn this on/off
- **Settings dual pane**: The Settings screen also gets a two-panel layout on tablets — the section list stays on the left, and the selected settings page opens on the right. The currently active section is highlighted with a subtle background
- **Back navigation in dual pane**: Pressing back in dual-pane mode deselects the folder/settings page instead of closing the screen

### 🎥 Player & Subtitles
- **Invert swipe subtitle direction**: New "Invert swipe subtitles direction" setting in Gesture Preferences. When enabled, swiping left-to-right seeks backward and right-to-left seeks forward — useful if you prefer the mirrored behaviour
- **Screenshot overhaul**: Screenshot filename templates got a proper rework:
  - `%wH`, `%wM`, `%wS`, `%wT` now use the **video playback position** (not the wall clock time) — so screenshot filenames actually match the video timestamp
  - New template placeholders: `%F` (filename without extension), `%P` (position as `HH:MM:SS.mmm`), `%p` (position as `HH:MM:SS`)
  - `%f` now resolves from the actual filename first, falling back to media-title — more reliable naming
- **Korean Jamo subtitle fix**: Downloaded subtitles that use Korean Jamo (composite characters) now go through NFC Unicode normalization. No more broken/corrupted Korean glyphs in subtitles
- **Subtitle search keyboard fix**: Added `android:windowSoftInputMode="adjustResize"` to PlayerActivity — the subtitle search dialog no longer gets hidden behind the on-screen keyboard
- **Subtitle persistence fix**: External subtitles and subtitle settings now survive across playback sessions more reliably. Added `addSubtitleSuspend()` (suspend version) for better coroutine handling during subtitle loading
- **Cache indicator fix**: The buffered range on the seekbar no longer double-counts the played portion — it now shows the correct remaining buffer ahead of the playhead
- **Color hex fix**: `toColorHexString()` now manually extracts ARGB components instead of using `Int.toHexString()` which produced wrong values for some colors

### 🛠️ Lua Script Improvements
- **Lua `require()` support**: Custom Lua scripts using `require()` can now find modules in `script-modules/` subdirectories. The app recursively syncs helper folders from `scripts/` to internal storage, so Lua's C-level `fopen()` can actually read them. Modules are also cleaned up when scripts are disabled in settings

### 🧹 Cleanup
- **Removed libpython binaries**: Deleted ~50 MB of unused `libpython_bin.so` files from all 4 architectures — they were never loaded by the app
- **Simplified MPV version display**: Removed the `cleanBundledMpvVersion()` hack in CrashActivity — MPV version now shows cleanly without needing string patching
- **Fonts folder no longer auto-set**: When changing the base storage root, the fonts folder preference is no longer blindly overwritten — it's only cleared if it was pointing at the old root. This prevents accidental font-folder resets

## 1.5.0-preview.1 — Preview Release

### 📦 MpvLib Update
- Updated mpv library and its dependencies 

### ⚡ Performance & Stability
- **Startup optimization**: Deferred cold-start DB initialization, grammar pre-load, and auto-update check to cut first-frame time significantly
- **Memory leak fixes**: Plugged 5 memory leaks across PlayerActivity (screen-state receiver), PlayerViewModel (LRU caches, temp subtitle cleanup), MediaPlaybackService (bitmap leak), MainActivity (unused scope), and NetworkLifecycleObserver (uncancelled coroutine)
- **UI smoothness**: Optimized seekbar spring animation, precomputed skip-segment colors, memoized immutable copies in PlayerControls, hoisted per-card preference collectors
- **Player crash fix**: Resolved a crash in PlayerActivity during stream initialization
- **Streaming optimizations**: Load network streams on `Dispatchers.IO` instead of Default to reduce CPU pool contention

### 🎨 UI/UX Improvements
- **Dynamic Grids**: Added responsive grid layouts — auto-adjusts column count based on screen width across FileSystemBrowser, FolderList, VideoList, Playlist, and RecentlyPlayed screens
- **Centered controls**: Play/pause and navigation buttons now center in portrait mode
- **Haptic feedback**: Sort dialog sliders now provide haptic ticks on snap
- **Slider layout**: Column sliders placed side-by-side for better space usage
- **Theme refresh**: Boosted container saturation and surface tints across all themes (light/dark) for vivid, non-washed-out appearance
- **Subtitle sheet redesign**: Inline SeriesSelectionControls directly in the OnlineSubtitleSearchSheet search row
- **Compressor back-press fix**: Video compressor overlay now handles system back press correctly

### 🎥 Player & Streaming
- **HLS/DASH streaming fix**: Direct media URLs (.m3u8/.mpd/.mp4/.ts) now bypass yt-dlp and use mpv's native ffmpeg HLS demuxer — these streams previously failed in yt-dlp's generic extractor
- **yt-dlp audio selection**: New audio track selector in the yt-dlp panel; preferred languages sanitization for better auto-selection
- **Collapsible advanced settings**: YtdlpSettingsScreen now organizes advanced options under collapsible sections
- **Console option**: Added Console toggle in MoreSheet stats rows — opens the mpv debug console via script-message
- **Subtitle hitbox fixes**: Dynamic hitbox detection for multi-line wrapped text; fixed hitbox under zoom/pan; lowered minimum subtitle scale limit
- **Subtitle loading fix**: Fixed subtitle loading and player overlay issues with IntentSubtitleLoadPolicy and M3uPlaybackPolicy
- **Negative brightness**: Added negative brightness range support in vertical sliders
- **Streaming overlays**: Fixed streaming playback overlays and thumbnail rendering

### 🔍 Anime Skip Provider
- New **Anime Skip** provider (api.anime-skip.com GraphQL) for intro/ending detection — searches via MAL ID, pairs consecutive timestamps
- Removed dead db.videasy.net TMDB mirror (providers now skip IMDB resolution without it)
- Cleaned up WyzieSearchRepository: removed fallback mirror logic and dead VideasyTmdbTrendingResponse

### 📁 Media Search Engine
- **New MediaSearchEngine**: Dedicated search engine for searching files/folders with optimized indexing
- **FolderListScreen integration**: Search across folders and videos using the new engine with VideoFolder references
- **Multiple refactors**: Cleaned up search logic, improved readability, and added Kotlin smart-casting clarifications

### 🐛 Bug Fixes
- Fixed mpv console ytdl_hook warnings (removed invalid 'all_subtitles' option)
- Fixed video-aspect-override deprecation warnings
- Resolved player crash during stream playback
- Fixed subtitle swipe gesture hitbox
- Fixed dynamic grid rendering edge cases

## 1.4.1-final

### Player, Playback & Stability
- Fixed stuttering and massive frame drops by optimizing video and screen synchronization.
- Fixed overlapping video frames/glitches when skipping rapidly through videos.
- Fixed picture-in-picture mode progress sync so your playback progress is correctly saved on exit.
- Prevented screen locking when opening the video's chapter list if the video has no chapters.
- Fixed visible screen rotation animation glitches when using the swipe-back gesture to exit the player.
- Adjusted player bottom controls bar padding to a more comfortable size.
- Cleaned up redundant HDR colorspace and tone mapping settings to avoid visual confusion.
- Fixed sudden crashes when loading or playing certain media files.
- Added outlined text with a black border to the Stats Page 6 overlay so text stays readable over any video content.

### Library, File Browser & Sizing
- Fixed playback, titles showing up as raw IDs (like `msf:1000`), missing thumbnails, and progress tracking for local M3U/M3U8 playlists.
- Added support for folder thumbnails in folder grid view (disabled by default, can be turned on in settings).
- Centralized file sorting options and improved title alignment in grid layouts.
- Fixed selection bar background in library list and grid layouts.
- Added Copy, Move, and Compress/Downscale options to the library selection bar.
- Fixed top toolbar delete button showing during folder selection to prevent accidental folder deletions.
- Fixed thumbnail taps so they toggle item selection instead of selecting ranges of items.
- Fixed library Media Info button to open details page instead of playing the file.

### Gestures & Dialogs
- Fixed subtitle swipe and pinch gesture detection zones so they are more responsive and natural to use.
- Added custom skip keywords for video openings and endings to automatically skip intros/outros.
- Redesigned the Sort and View Options Dialog to be more compact, with a collapsible "Fields" toggle section.
- Fixed bottom control bar icon scaling for smaller/larger screens.
- Fixed a visual glitch where the seekbar layout preview looked wavy.

### Network & Connections
- Added support for sending your playback progress to Jellyfin when playing videos externally.

- Many More Small QOL Fixes and Optimizations 

## 1.4.0

### Player And Seek Preview

- added the ThumbFast-style seek preview UI and thumbnail cache improvements.
- restored the legacy live-video seek preview path and added a Player setting to switch between ThumbFast thumbnail preview and legacy live seeking.
- tightened ThumbFast preview accuracy by using smaller preview time buckets and preventing stale thumbnail requests from replacing the newest preview frame.
- fixed glitched player vector icons and tightened subtitle/notch safe-area behavior.
-  improved launch smoothness and predictive back behavior.

### Subtitles

-  added subtitle font management in Settings > Subtitles: choose a fonts directory, see the selected source folder, reload fonts, clear the font cache, and select the default subtitle font.
-  added custom subtitle border styles and a shadow offset slider.
- **Arnab Sadhukhan** added subtitle zoom gestures.
- **Arnab Sadhukhan** optimized the subtitle pinch hit-zone for multi-line subtitles.
- **Arnab Sadhukhan** added horizontal swipe on subtitles to seek dialog lines.

### Browser, Library, And Storage

- redesigned the Media Info page to use a premium, tabbed Material 3 interface with beautiful overview stats, container metadata detail, track summaries, and customizable sharing.
- implemented the unified Media Library view mode.
- added multi-select range handling, folder copy/move/rename, and SMB mutex/reconnection guards.
- redesigned settings sections and moved progress-related options into cleaner places.
- fixed segmented button unchecked color handling.
- fixed Lua script copy behavior.

### Gestures And Quality Of Life

- **Arnab Sadhukhan** added the playlist swipe-up gesture in the player when swiped up from middle of screen now playlists open.
- **Arnab Sadhukhan** added auto-scroll to the currently selected theme.
- Removed Avif / Jpeg-Xl type images from the settings selection
- Added Font selection in the Subtitle Settings section.
- Fixed issue of Lua script when copying specific part Copied whole Lua script.
- added Expressive Scrollbar like in Pixel player 

## 1.3.9

> # 🚀 **CURL IS NOW SUPPORTED!** 
> ### ⚠️ **EXPERIMENTAL** — This is brand new and may or may not work properly on your device. ⚠️
> Lua and JavaScript scripts can now make HTTP requests through the new native libcurl bridge via JNI.
> Use it, break it, and report issues so we can make it stable!, see `MPVRX_CUSTOM_COMMANDS.d` for tutorial on how to use in Lua and JS.
>
> **What this means:** You can now write scripts that fetch data from the internet — APIs, subtitles, metadata, you name it — all through libcurl compiled directly into the app.

- AI support has been updated. Gemini is removed and OpenCode Zen AI is now available for AI rename, subtitle formatting, and subtitle translation.
- AI model lists now come from the provider APIs instead of a saved model list in the app. OpenRouter also marks free models using the pricing data returned by OpenRouter itself.
- Background playback is fixed so repeat keeps working after using the headphone button, and returning to the player no longer restarts the current stream from the beginning.
- Subtitle search has been updated for the latest Wyzie source changes.
- Added Hybrid Skip Markers. The player can now check IntroDB, TIDB, and AniSkip together and use whichever result is found first.
- Anime4K settings are now easier to use with a collapsible section in Decoder Preferences and also Added Optimization by Sunny Vishnu .
- Added a setting to show or hide Media Info from Android's share/open-with screen.
- Added documentation for custom Lua/JS player commands in `MPVRX_CUSTOM_COMMANDS.md`.

## 1.3.8

- **Integrated yt-dlp by [**SunnyVishnu3**](https://github.com/SunnyVishnu3)** — Added full yt-dlp integration for video watching audio/video from YouTube and other supported sites directly within the app (_Dont expect from me to add Download Functionality_). **Note: You need to download yt-dlp first (Settings > Advanced > yt-dlp Manager) before playing YouTube links — don't be clueless.**
- Fixed Issue of USer defined Colors Filters were not getting Saved and not getting applied through Mpv conf by [**SunnyVishnu3**](https://github.com/SunnyVishnu3)
- Fixed Gemini AI Error Generating / Translating Subs.
- Fixed Crashing issue of MpvRx , in a nutshell Ambient mode and Custom lua were not initialized in Sync causing to crash player sometimes
- That's all for Today Adiosss!!

## 1.3.7

- **Updated Wyzie subtitle API integration** — Synced with latest Wyzie API changes: added `ai` field for AI-translated subtitle detection, updated provider sources list (removed `subdl`, `podnapisi`, `ajatttools`; added `tvsubtitles`), and fixed TMDB endpoints to include API key authentication.
- **Material 3 Expressive Design** — Complete visual overhaul using Material 3 Expressive design system for a more modern, fluid, and engaging experience
- **Smoother Animations** — Replaced rigid linear transitions with spring-based physics animations throughout the app (navigation, controls, browser, dialogs)
- Added Voltage Battery Temperature And improved the style of Page 6 
- Added Optimized Ambient Mode with Eco Battery Saver Mode who want to take feel of Ambient without much Battery Impact
- Removed Dead Code and also Optimized some File Handling / Ui Rendering Operations
- **Settings export now stores app version**  exported XML files include the app version, so import dialogs show the correct version instead of "unknown".
- **HEVC 10bit thumbnails**  added a software-decoder fallback using Android's MediaCodec API. When the system can't decode a video frame (e.g. HEVC 10bit on devices without hardware support), the app now tries Google's software decoder before giving up. This means more thumbnails will show up on devices with limited codec support ( To be tested Propelry on unsupported device).
- **Fixed app icon on Android 16**  changed the adaptive icon background from transparent to opaque black so the icon doesn't disappear on launchers that don't handle transparency well.
- **User mpv.conf now has highest priority**  during player startup, your mpv.conf settings are re-applied after all app defaults so they always take precedence. but some of the Hardcoded things doesnt change like for example `sid, aid`.
- 

## 1.3.6

- **Six AI providers, one gorgeous settings page**  OpenAI, Anthropic, OpenRouter, and Together joined Groq and Gemini in a completely redesigned UI. Every provider gets its own API key, every single model is visible (free ones get a bold green badge), and the new searchable model picker sorts free models to the top. The offline model experience got a premium card-based overhaul too  tiers, speed/translation badges, device recommendations, DeepSeek-R1 support, reasoning toggles, and a benchmark button for downloaded models. One-tap download, delete, and switch between models without ever leaving the screen.

- **Subtitle translation**  SUPPORTS ASS Subs Translation tooooooooo..... , you can now configure your target languages once in settings. One language means one tap to translate. Two or more means a clean picker showing only the languages you chose. Translation progress appears right on the video screen (even with the sheet closed), partially translated subs survive restarts, and a red X lets you cancel mid-translation instantly. When using local models, the system automatically picks the best downloaded model for each language, keeps it warm between chunks, and never runs two local AI jobs at once.

- **Generate subtitles from video audio**  **_(EXPERIMENTAL)_** This is work in progress might not work Don't baby Cry that this shit aint working ,i ain't getting paid enough to implement this whole heartedly , so what it does is -> one tap generates subtitles using the audio you're already playing. Media3 extraction feeds Groq, Gemini, or offline Whisper, and the resulting SRT/VTT saves automatically.

- **Smarter AI across the board**  reasoning tags are automatically stripped from final results, token limits prevent stalls in heavy tasks, and every AI feature (rename, formatting, translation) comes with customizable prompts that fall back gracefully to built-in instructions.

- **Real-time subtitle toggle**  new on/off switch in AI settings to control real-time subtitle generation from audio. When off, the indicator and generate button are hidden from the player.

- **AI features respect the master switch**  turning off AI Integration now hides all AI indicators (translation, real-time subs) and buttons (generate, translate, format) from the player view. Renamed "AI Subtitle Search Formatting" to "AI Search" for clarity.


## 1.3.5

- **Removed Play Store and F-Droid build variants**  streamlined to a single `standard` flavor with full update support and all features enabled.
- **Revamped README**  comprehensive feature documentation organized by category, UPI QR code and Buy Me a Coffee links in the Support section.
- **SMB Network Thumbnail Generation**  fixed thumbnail generation for SMB shares through Codex AI (Beta).
- **Bulk AI Rename**  rename multiple files at once using Gemini or Groq with concurrency limiting and edge case handling.
- **AI Subtitle Translation**  translate subtitles using AI providers with custom prompts, progress indication, and user preference management.
- **AI Subtitle Translation Enhancements**  in-house developed translation pipeline with fully customizable prompts and per-user preference overrides.

## 1.3.4

- Capped generated thumbnails to safer preview sizes so large videos do not waste memory while browsing.
- Improved MKV/WebM thumbnail handling, including embedded artwork and smarter fallback frames.
- Cleaned old thumbnail cache paths when clearing thumbnail cache.
- Fixed the About and crash info screen showing `UNKNOWN` in the bundled mpv version.
- Updated Gradle, Kotlin, Compose, Koin, Navigation 3, AndroidX, and related dependency versions through the version catalog.
- Added SUbHub MpvRx specific Subtitle Fetching nd Downloading featured developed by me
- Added Video COmpresser Overlay in Tree Mode also
- Cleaned up codebase and Improved Playback bottlenecks
- Added Window Offset to prevent Camera notch overlap issues


## 1.3.3

- Fixed Background Playback and Pip issues 
- Anime4K should feel much smoother now. The player now uses the clean six-preset Anime4K flow from the reference app and avoids piling old shader work on top of the new preset when you switch modes.
- Anime4K is still off by default, but when you turn it on the picker is simpler: Off, A, B, C, A+, B+, and C+.
- Moved the Fast / Balanced / High Anime4K choice into Decoder settings, with Balanced as the default.
- Removed frame interpolation because it added a lot of GPU load and did not add enough real value.
- Removed the old OneThird and Halfway thumbnail choices.
- Removed the unused old player screen path.
- Cleaned up the track sheets so audio, subtitle, chapter, decoder, and online subtitle lists no longer depend on the old generic sheet.
- Removed SubDL from subtitle search sources.
- Network streaming is now opt-in instead of being enabled on a fresh install.
- HDR and Ambient controls are no longer placed on the default player buttons, so heavy visual extras stay out of the way unless you add them yourself.
- Turning HDR on now starts with Linear HDR by default.
- The app now does less background media scanning and cache cleanup on startup, which should help large libraries open with less churn.
- Added new MpvLib File with Some Optimization and Removing Deprecated Andorid Versions
- Thumbnails are now Loaded Faster and more Precisly

## 1.3.2

### HDR hdr-toys Pipeline

- Replaced the old 3-mode HDR system (Off / SDR with HDR / Normal HDR) with a proper shader-based pipeline powered by [hdr-toys](https://github.com/natural-harmonia-gropius/hdr-toys).
- Four HDR modes are now available: **BT.2100 PQ** (HDR10), **BT.2100 HLG**, **BT.2020**, and **Linear HDR** (mpv-native, no shaders).
- 77 GLSL shaders are bundled in the app and copied to the mpv config directory on first use  no manual setup required.
- The HDR panel no longer shows an "Off" option. Off is the default and is toggled by the HDR button; the panel only presents the four active modes.
- Selecting a mode while GPU Next + Vulkan is unavailable shows a clear error pill and falls back to Off safely.
- Added `boostSdrToHdr` preference (used by the Linear HDR path).
- `HdrToysManager` cleanly removes all hdr-toys shaders when switching to Off or when the pipeline is not ready, so no stale shaders leak between sessions.

### Thermal & Battery Improvements

- Added `ThermalMonitor`  samples `PowerManager.getThermalHeadroom()` (Android 11+) every 10 seconds during playback.
- Ambient shader sample budget is automatically capped based on thermal headroom: 8 samples (severe), 12 (moderate), 18 (mild), uncapped (cool).
- Anime4K is proactively downgraded to C/Fast when thermal headroom drops below 40%, before frame drops even start.
- Ambient shader recompilation is now skipped when all parameters are identical to the last compiled version  reduces unnecessary GPU stutter on orientation changes and no-op callbacks.
- Removed redundant dual position polling: the event-driven `time-pos` observer and the polling loop were both updating the same StateFlow, causing double seek-bar recompositions on every MPV event.
- Background playback position poll interval halved from 250 ms to 500 ms when controls are not visible, cutting idle JNI wake-ups by 50%.

### Stats Page 6  Fixes

- **GPU estimate bar fixed**: was using cumulative drop + delay totals that drifted to 100% after long sessions and added a fixed FPS-proportional baseline (120fps with zero drops showed 70% GPU load). Now uses per-second delta counts relative to the current frame rate  0 drops = 0%, all frames dropped = 100%.
- **CPU label corrected**: relabelled from "CPU Usage" to "App CPU (this process)" to accurately reflect that `getElapsedCpuTime()` measures only MpvRx's own process, not the whole device.
- **Frame drop text now shows per-second deltas** alongside the all-time totals, so you can tell current rendering pressure at a glance.
- **Pause-aware poll backoff**: the stats loop backs off from 1 s to 2 s intervals when playback is paused, cutting pointless JNI calls when metrics are static.

### Gesture & Action Overlay Toggles

- Added a new **"Gesture & Action Overlays"** section in Player Settings with seven independent on/off switches:
  - **Volume slider overlay**  vertical pill shown during volume swipe
  - **Brightness slider overlay**  vertical pill shown during brightness swipe
  - **Hold speed overlay**  speed badge and slider shown during long-press speed boost
  - **Aspect ratio feedback**  pill shown when cycling aspect ratio
  - **Zoom level feedback**  pill shown when pinching to zoom
  - **Repeat & shuffle feedback**  pill shown when toggling repeat or shuffle
  - **Action feedback pills**  brief text pills from custom buttons, ambient toggle, subtitle drag, and Lua/JS scripts
- All overlays default to **on**, so existing behaviour is unchanged until the user opts out.
- Disabling an overlay suppresses only the visual pill  the underlying gesture action (volume change, speed change, etc.) still happens normally.

## 1.3.1

- Update FFmpeg to n8.1 (latest stable)
- Update Android SDK to 36, build tools 36.0.0
- Update Kotlin to 2.1.21, Gradle to 8.11.1
- Update dependencies: unibreak 6.2, harfbuzz 11.5.0, fribidi 1.0.17, freetype 2.13.4, mbedtls 3.6.5
- Add mujs 1.3.5 support for JavaScript scripting inside mpv
- JavaScript (.js) scripts are now supported alongside Lua scripts, with "Scripts (Lua / JS)" kept to the main section titles.
- Script editor now uses the native Sora editor with TextMate syntax highlighting for Lua and JavaScript.
- Script editor includes a chip toggle to choose between `.lua` and `.js` file extensions when creating or editing scripts.
- Custom player buttons can now run either Lua or JavaScript, with language selection per button and import/export support.
- Long-pressing the HDR button now opens an HDR Output panel with Off, SDR with HDR, and Normal HDR modes.
- Media title resolution improved: MPV's resolved title is preferred for non-direct-media URLs and when the current filename looks like a generic route (e.g., `/watch`, `/stream`).
- Updated mpv library dependency from `mpv-android-lib-v0.0.1.aar` to `mpvlib.aar` and removed the old AAR.
- Added Multiple new provider to Wyzie subtitle sources.
- PiP and background playback now save the latest watched position instead of returning to the timestamp from before PiP started.
- Video lists refresh playback progress as soon as the saved position changes, so returning from the player shows the current progress.
- Folder thumbnails now begin rendering immediately when a folder opens, while still using cached thumbnail data first.

## 1.3.0

- The project now carries the `MpvRx` name across the app, docs, and release files.
- Tree View `NEW` labels now work properly and update as you watch.
- Single-child folders now flatten automatically so you reach files faster.
- Subtitle matching is smarter and better at finding subtitles that line up.
- Cached library data shows up first, then refreshes quietly in the background.
- Browser updates now react to changes instead of constantly polling.
- The player now remembers your chosen aspect ratio.
- Seeking feels steadier and cleanup after playback is smoother.
- Ambient mode and Lua scripting were reverted.
- The settings page was revamped.
- New tab and video animations were added.
- Icons were refreshed across the app.
- Network and playlist behavior was cleaned up.
- Folder pinning was added.
- A video size downgrade option was added in the video editing section.
- Page 6 was added to More Sheet for battery usage and extra system info.
- A new status icon row can show network speed, battery percentage, and time.

## 1.2.9

- Library scanning became faster and more dependable.
- Subtitle search got a noticeable improvement.
- Theme picking now jumps to the active theme more cleanly.
- Ambient mode got another round of polish and fixes.

## 1.2.8-hotfix

- A rough ambient mode change was rolled back to keep playback stable.
- The zoom sheet layout was cleaned up.
- Playback profiles became easier to manage.

## 1.2.8

- Background playback became more dependable.
- File rename and delete flows became safer and clearer.
- Custom buttons load more reliably.
- Play Store and F-Droid releases were cleaned up.
- The update and media tools were reorganized.

## 1.2.7

- The seekbar was cleaned up and accidental swipe behavior was reduced.
- F-Droid builds were added.
- Release packaging and signing became more reliable.

## 1.2.6

- Background playback and notifications became steadier.
- Filter presets and video quality controls were improved.
- External subtitle scaling and positioning were fixed.

## 1.2.5

- Video scaling and smooth motion options were added.
- Thumbnail generation became faster and more consistent.
- Browser spacing and player gestures were cleaned up.

## 1.2.4

- New videos now show a `NEW` label more reliably.
- Rotated videos and aspect handling were improved.
- Subtitle styling controls were expanded.
- Playlist order and storage permission handling were cleaned up.

## 1.2.3

- Network thumbnails became optional.
- Recently Played works better with network items.
- Thumbnail loading became faster.
- Browser navigation and floating actions became more consistent.

## 1.2.2

- Repeat and shuffle now stay the way you left them.
- Subtitle preferences now carry across playback more reliably.
- Hardware decoding falls back more safely on tricky devices.
- Player rotation and status bar behavior were improved.
- SMB playback became more dependable.

## 1.2.1

- Grid mode arrived for folders and videos.
- Scroll position is remembered when you come back.
- Thumbnail visibility can be toggled.
- A background playback edge case was fixed.

## 1.2.0

- The app got a major Material 3 refresh.
- Settings were reorganized into a cleaner card layout.
- Local M3U playlists were added.
- Recently Played got pull-to-refresh.
- Track and subtitle handling became smarter.

## 1.1.0

- Network browsing arrived for SMB, FTP, and WebDAV.
- File manager mode and breadcrumb navigation were added.
- Playlist mode became more useful.
- Recently Played learned how to handle playlists too.
- The project website and screenshots were refreshed.

## 1.0.0

- First public release.
- Media info viewing and sharing were added.
- F-Droid release work was prepared.
