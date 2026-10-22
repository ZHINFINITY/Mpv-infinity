/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

internal data class MpvRestartLoadFence(val generation: Long, val nativePath: String)

/** Only controlled seekable-video reloads use this; ordinary MPV redirects remain unchanged. */
internal object MpvRestartLoadFencePolicy {
  fun accepts(fence: MpvRestartLoadFence, generation: Long, path: String?, trackCount: Int): Boolean =
    fence.generation == generation && trackCount > 0 && path != null &&
      normalized(fence.nativePath) == normalized(path)

  private fun normalized(path: String): String =
    if (path.startsWith("file://")) runCatching { java.net.URI(path).path }.getOrNull() ?: path
    else path
}
