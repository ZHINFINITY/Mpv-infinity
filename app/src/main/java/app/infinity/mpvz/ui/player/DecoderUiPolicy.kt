/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.ui.player

/** Pure presentation rules shared by decoder controls and tests. */
object DecoderUiPolicy {
  const val PROFILE_COUNT = 10

  fun profileForQuality(quality: Int): Int =
    ((quality.coerceIn(0, 100) * (PROFILE_COUNT - 1)) / 100f).roundToInt()

  fun qualityForProfile(profile: Int): Int =
    (profile.coerceIn(0, PROFILE_COUNT - 1) * 100f / (PROFILE_COUNT - 1)).roundToInt()

  fun profileLabel(profile: Int): String = when (profile.coerceIn(0, PROFILE_COUNT - 1)) {
    in 0..2 -> "Performance"
    in 7..9 -> "Quality"
    else -> "Balanced"
  }

  /** The in-player sheet deliberately exposes only concrete engines. */
  fun playerEngineTabs(): List<PlaybackEngineMode> =
    PlaybackEngineMode.entries.filterNot { it == PlaybackEngineMode.AUTO }
}

private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
