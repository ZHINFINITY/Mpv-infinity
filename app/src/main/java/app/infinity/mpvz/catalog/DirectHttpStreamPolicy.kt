package app.infinity.mpvz.catalog

import java.net.URI
import java.util.Locale

internal fun isDirectHttpStreamUrl(rawUrl: String): Boolean {
    val parsed = runCatching { URI(rawUrl) }.getOrNull() ?: return false
    val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "http" && scheme != "https") return false
    if (parsed.host.isNullOrBlank()) return false
    val path = parsed.path.orEmpty().trimEnd('/')
    return !path.endsWith(".torrent", ignoreCase = true)
}
