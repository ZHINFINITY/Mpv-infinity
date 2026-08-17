# Reference findings

The Google Play listing for Material Player (`com.akira.material`) describes a Material You video/music player with broad format support, hardware acceleration, subtitles, playlists, background playback, picture-in-picture, Dolby Vision, and Dolby Atmos. The listing does not disclose a mixed mpv/Media3 implementation.

A public search result and Reddit discussion describe the app as using Android Media3/ExoPlayer. Therefore, the safe architectural lesson is not to copy an undocumented internal implementation, but to provide explicit engine selection: Media3 for normal Android-supported media and libmpv for formats or features that require mpv. Only one renderer should be visible and playing at a time.

Reference URL: https://play.google.com/store/apps/details?id=com.akira.material
