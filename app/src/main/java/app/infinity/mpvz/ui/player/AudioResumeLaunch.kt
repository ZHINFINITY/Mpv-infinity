/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import android.content.Intent
import android.net.Uri
import app.infinity.mpvz.domain.network.NetworkPlaybackUri
import java.io.File

/**
 * Points a relaunch intent at a saved music item (process-death restore and the mini player's
 * resume card).
 *
 * Two bugs used to make this show "Could not load this link" with a 00:01 duration:
 *  - `data = …` followed by `type = …` loses the data, because Intent.setType() clears it.
 *  - Local songs are saved as raw paths ("/storage/…/#song.flac"). Uri.parse() gives such a
 *    string no scheme and turns everything after '#' into a fragment, so it never resolved.
 */
object AudioResumeLaunch {
  const val EXTRA_LOCAL_MEDIA_PATH = "local_media_path"
  private const val DEFAULT_AUDIO_MIME = "audio/*"

  /** The saved item's filesystem path when it was stored as a raw path, else null. */
  fun localPath(originalUri: String): String? = originalUri.takeIf { it.startsWith("/") }

  /** A playable Uri: file:// for raw paths (reserved characters encoded), else the parsed URI. */
  fun playableUri(originalUri: String): Uri =
    localPath(originalUri)?.let { Uri.fromFile(File(it)) }
      ?: Uri.parse(NetworkPlaybackUri.normalize(originalUri))

  fun mimeType(saved: String?, fallback: String? = DEFAULT_AUDIO_MIME): String? =
    saved?.takeIf(String::isNotBlank) ?: fallback

  /** Sets data and type together and, for local files, the same path extra the library uses. */
  fun applyTo(
    intent: Intent,
    originalUri: String,
    savedMimeType: String?,
    fallbackMimeType: String? = DEFAULT_AUDIO_MIME,
  ): Intent {
    intent.setDataAndType(playableUri(originalUri), mimeType(savedMimeType, fallbackMimeType))
    // A copied intent may still carry another song's path, which the loader would prefer.
    val path = localPath(originalUri)
    if (path != null) intent.putExtra(EXTRA_LOCAL_MEDIA_PATH, path) else intent.removeExtra(EXTRA_LOCAL_MEDIA_PATH)
    return intent
  }
}
