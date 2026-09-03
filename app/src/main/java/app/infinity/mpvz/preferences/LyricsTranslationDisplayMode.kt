/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.preferences

import androidx.annotation.StringRes
import app.infinity.mpvz.R

enum class LyricsTranslationDisplayMode(
  @StringRes val title: Int,
) {
  OFF(R.string.lyrics_display_mode_off),
  REPLACE(R.string.lyrics_display_mode_replace),
  BILINGUAL(R.string.lyrics_display_mode_bilingual),
}
