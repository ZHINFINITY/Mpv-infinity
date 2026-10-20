/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.utils.media

import app.infinity.mpvz.preferences.PlayerPreferences
import app.infinity.mpvz.ui.player.PlaybackSession

/**
 * Extracts the file extension from a path or URI string, stripping query
 * parameters and fragment identifiers before extracting the last dot-delimited
 * segment.  Returns a lowercase extension without the leading dot, or an empty
 * string if no extension is found.
 *
 * Example:
 * ```
 * "video.mkv?quality=1080#t=10".fileExtension() == "mkv"
 * "content://media/123".fileExtension() == ""
 * ```
 */
fun String.fileExtension(): String =
  substringBefore('?')
    .substringBefore('#')
    .substringAfterLast('.', "")
    .lowercase()

/**
 * Returns the MPV seek mode string based on the user's precise-seeking preference
 * and the current media duration.  Videos shorter than 2 minutes always use
 * precise seeking for a better scrubbing experience.
 */
fun resolveSeekMode(playerPreferences: PlayerPreferences): String {
  val duration = PlaybackSession.getPropertyInt("duration") ?: 0
  val usePrecise = playerPreferences.usePreciseSeeking.get() || duration < 120
  return if (usePrecise) "relative+exact" else "relative+keyframes"
}
