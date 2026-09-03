/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.preferences

/**
 * Groups of mpv options that the user can hand over to their own mpv.conf instead of the
 * in-app settings. Each group is stored as a single preference key whose value is the set
 * of mpv option names that mpv.conf is allowed to own.
 */
enum class MpvConfigOverride(
  val preferenceKey: String,
  val optionNames: Set<String>,
) {
  RENDERER(
    "renderer",
    setOf("gpu-api", "gpu-context", "vo", "opengl-es-version"),
  ),
  DECODER(
    "decoder",
    setOf("hwdec", "hwdec-codecs", "vd-lavc-dr", "vd-lavc-software-fallback"),
  ),
  HDR_AND_SHADERS(
    "hdr_and_shaders",
    setOf(
      "target-peak",
      "target-colorspace-hint",
      "hdr-compute-peak",
      "target-prim",
      "target-trc",
      "glsl-shaders",
      "glsl-shader",
    ),
  ),
  VIDEO_FILTERS("video_filters", setOf("vf")),
  VIDEO_GEOMETRY(
    "video_geometry",
    setOf(
      "video-zoom",
      "video-pan-x",
      "video-pan-y",
      "video-rotate",
      "video-aspect",
      "video-aspect-override",
      "video-aspect-method",
      "video-unscaled",
      "video-crop",
    ),
  ),
  AUDIO_OUTPUT(
    "audio_output",
    setOf("ao", "audio-device", "audio-format", "audio-samplerate", "audio-channels"),
  ),
  AUDIO_FILTERS("audio_filters", setOf("af")),
  SUBTITLE_LOADING(
    "subtitle_loading",
    setOf("sub-auto", "sub-file-paths", "sub-files", "sid"),
  ),
  SUBTITLE_STYLE(
    "subtitle_style",
    setOf(
      "sub-font",
      "sub-font-size",
      "sub-color",
      "sub-border-size",
      "sub-shadow-offset",
      "sub-blur",
      "sub-ass-override",
      "sub-bold",
      "sub-italic",
    ),
  ),
  PLAYBACK_TIMING(
    "playback_timing",
    setOf("hr-seek", "hr-seek-framedrop", "keep-open", "video-sync"),
  ),
  NETWORK_BUFFERING(
    "network_buffering",
    setOf("cache", "cache-secs", "cache-pause-initial", "demuxer-max-bytes", "network-timeout"),
  ),
  YTDLP(
    "ytdlp",
    setOf("ytdl", "ytdl-format", "ytdl-raw-options", "flatten-editions"),
  ),
  OSD(
    "osd",
    setOf("osd-level", "osd-font", "osd-font-size", "osd-color", "osd-border-size", "osd-shadow-offset"),
  ),
  ;

  companion object {
    private val allOptionNames: Set<String> by lazy {
      entries.asSequence().flatMap { it.optionNames.asSequence() }.toSet()
    }

    /** Drops any stored values that don't name a known mpv option. */
    fun resolveOptionNames(stored: Set<String>): Set<String> =
      stored.filterTo(linkedSetOf()) { it in allOptionNames }

    /** Returns the groups that contain at least one of [optionNames]. */
    fun groupsContaining(optionNames: Set<String>): Set<MpvConfigOverride> =
      entries.filterTo(linkedSetOf()) { entry -> entry.optionNames.any(optionNames::contains) }
  }
}

/** Feature-scoped option sets queried by the player to decide whether mpv.conf owns a feature. */
object MpvConfigControlledFeatures {
  val HARDWARE_DECODER = setOf("hwdec", "hwdec-codecs", "vd-lavc-dr")
  val HDR_OUTPUT = setOf("target-peak", "target-colorspace-hint", "hdr-compute-peak", "target-prim", "target-trc")
  val ANIME4K = setOf("glsl-shaders", "glsl-shader")
  val AMBIENT = setOf("ambient-mode")
  val VIDEO_ASPECT = setOf("video-aspect", "video-aspect-override", "video-aspect-method", "video-unscaled")
  val AUTO_CROP = setOf("video-crop")
  val VIDEO_ZOOM = setOf("video-zoom", "video-scale-x", "video-scale-y")
  val AUDIO_TRACK_SELECTION = setOf("aid", "alang", "audio-file-auto")
  val SUBTITLE_TRACK_SELECTION = setOf("sid", "slang", "sub-auto")
}

/**
 * Non-composable entry point for the player engine. [configure] is fed the stored preference
 * value (typically from MPVView during setup); when nothing is configured the default is an
 * empty ownership set, so the app keeps full control of every option.
 */
object MpvConfigOverridePolicy {
  @Volatile
  private var ownedOptionNames: Set<String> = emptySet()

  fun configure(storedValues: Set<String>) {
    ownedOptionNames = effectiveOptionNames(MpvConfigOverride.resolveOptionNames(storedValues))
  }

  fun effectiveOptionNames(resolvedOptions: Set<String>): Set<String> = resolvedOptions

  fun isOwnedByMpvConf(optionName: String): Boolean = optionName in ownedOptionNames

  fun ownsAny(optionNames: Set<String>): Boolean =
    optionNames.isNotEmpty() && optionNames.any(ownedOptionNames::contains)

  /** Suppresses native commands that would override an option owned by the user's mpv.conf. */
  fun shouldSuppress(command: Array<out String>): Boolean {
    val name = command.firstOrNull() ?: return false
    val option = when (name) {
      "set", "set_property", "cycle" -> command.getOrNull(1)
      else -> null
    }
    return option != null && option in ownedOptionNames
  }
}
