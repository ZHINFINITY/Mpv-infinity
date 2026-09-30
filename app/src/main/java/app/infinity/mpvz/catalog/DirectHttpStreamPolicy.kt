package app.infinity.mpvz.catalog

import java.net.URI
import java.util.Locale

internal fun isDirectHttpStreamUrl(rawUrl: String): Boolean {
  val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
  return uri.isAbsolute && !uri.isOpaque &&
    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
    !uri.host.isNullOrBlank()
}
