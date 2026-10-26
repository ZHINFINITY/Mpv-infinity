/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.ui.player

/** Selects tracks from the renderer that currently owns playback, not the global default. */
internal object PlaybackTrackSourcePolicy {
  fun <T> forActiveEngine(
    activeEngine: PlaybackEngineMode,
    mpvTracks: List<T>,
    nativeTracks: List<T>,
  ): List<T> =
    if (activeEngine == PlaybackEngineMode.NATIVE) nativeTracks else mpvTracks
}
