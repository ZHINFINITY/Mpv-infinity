/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import android.net.Uri
import app.infinity.mpvz.domain.network.NetworkPlaybackUri
import java.io.File

/** Keeps raw filesystem paths playable without changing durable queue identity. */
internal object PlaybackUriPolicy {
  fun localPath(originalUri: String): String? = originalUri.takeIf { it.startsWith("/") }

  /** Returns a URI that preserves reserved filename characters such as `#` and spaces. */
  fun playableUri(originalUri: String): Uri =
    localPath(originalUri)?.let { Uri.fromFile(File(it)) }
      ?: Uri.parse(NetworkPlaybackUri.normalize(originalUri))

  fun playableUriString(originalUri: String): String = playableUri(originalUri).toString()
}
