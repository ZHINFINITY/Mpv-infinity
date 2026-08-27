/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.jellyfin

import android.net.Uri

internal data class JellyfinSession(
  val serverUrl: String,
  val userId: String,
  val accessToken: String,
)

internal data class JellyfinTrack(
  val id: String,
  val title: String,
  val artist: String,
  val album: String,
  val durationMs: Long,
  val artworkUrl: String?,
  val streamUrl: String,
) {
  val uri: Uri
    get() = Uri.parse(streamUrl)
}
