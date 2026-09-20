package app.infinity.mpvz.data.network

import android.net.Uri

/** Normalizes user-entered server URLs without changing the app or provider branding. */
object ServerUrlUtils {
  fun generateCandidateUrls(input: String, defaultPort: Int): List<String> {
    val normalized = input.trim().trimEnd('/')
    if (normalized.isBlank()) return emptyList()
    val parsed = runCatching { Uri.parse(normalized) }.getOrNull() ?: return listOf(normalized)
    val scheme = parsed.scheme?.lowercase() ?: "http"
    val host = parsed.host ?: return listOf(normalized)
    val path = parsed.encodedPath.orEmpty().trimEnd('/')
    val explicitPort = parsed.port
    val portSuffix = if (explicitPort > 0) ":$explicitPort" else ":$defaultPort"
    val primary = "$scheme://$host$portSuffix$path"
    return listOf(primary, normalized).distinct()
  }
}
