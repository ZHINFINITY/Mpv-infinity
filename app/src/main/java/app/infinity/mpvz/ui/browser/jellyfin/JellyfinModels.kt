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

internal data class JellyfinQuickConnectState(
  val serverUrl: String,
  val secret: String,
  val code: String,
)

internal data class JellyfinCollection(
  val id: String,
  val name: String,
  val collectionType: String?,
)

internal data class JellyfinTrack(
  val id: String,
  val title: String,
  val artist: String,
  val album: String,
  val durationMs: Long,
  val artworkUrl: String?,
  val streamUrl: String?,
  val mediaType: String = "Audio",
) {
  val isVideo: Boolean
    get() = mediaType.equals("Movie", ignoreCase = true) ||
      mediaType.equals("Episode", ignoreCase = true)

  val isPlayable: Boolean
    get() = !streamUrl.isNullOrBlank()

  val uri: Uri?
    get() = streamUrl?.let(Uri::parse)
}
