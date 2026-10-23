/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

/**
 * Identity of the item represented by MediaSession metadata. URI is included because a queue may
 * temporarily contain duplicate/stale stable ids while a source is being rebuilt.
 */
data class MediaSessionItemIdentity(
  val stableId: String,
  val originalUri: String,
) {
  /** Length prefixes prevent delimiters in URIs from colliding with another item's cache key. */
  fun artworkKey(queueIndex: Int, artworkUri: String?): String =
    listOf(stableId, originalUri, artworkUri.orEmpty(), queueIndex.toString())
      .joinToString("") { "${it.length}:$it" }

  fun acceptsMetadata(identifier: String?, uri: String?): Boolean =
    (identifier == null || stableId == identifier) && (uri == null || originalUri == uri)
}

/**
 * Monotonic token for asynchronous notification work. Every current-item change invalidates all
 * artwork/favorite/metadata completions captured for the preceding item.
 */
internal class MediaSessionIdentityTracker {
  private var generation = 0L
  private var current: MediaSessionItemIdentity? = null

  fun advance(identity: MediaSessionItemIdentity): Long {
    if (current != identity) {
      generation += 1L
      current = identity
    }
    return generation
  }

  fun generation(): Long = generation

  fun isCurrent(identity: MediaSessionItemIdentity, expectedGeneration: Long): Boolean =
    generation == expectedGeneration && current == identity
}
