package app.infinity.mpvz.ui.player.ytdlp

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Validates the observed HubCloud redirect chain without accepting arbitrary wrapper URLs. */
internal object HubCloudLinkExtractor {
  const val SOURCE_HOST = "gpdl.hubcloud.ist"
  const val CRYPTONEWZ_HOST = "cryptonewz.online"
  const val MEDIA_HOST = "video-downloads.googleusercontent.com"
  const val MEDIA_REFERER = "https://cryptonewz.online/"

  private const val CLOUDFLARE_WORKER_HOST = "gpdl.rohitkiskk.workers.dev"
  private const val DOWNLOAD_PATH = "/dl.php"

  fun isHubCloudSourceHost(host: String): Boolean = host.equals(SOURCE_HOST, ignoreCase = true)

  fun isAllowedResolverHost(host: String): Boolean =
    host.lowercase() in
      setOf(
        SOURCE_HOST,
        CLOUDFLARE_WORKER_HOST,
        CRYPTONEWZ_HOST,
        MEDIA_HOST,
      )

  fun isAllowedResolverUrl(value: String): Boolean {
    val uri = parseUri(value) ?: return false
    val host = uri.host ?: return false
    return isTrustedHttpsUri(uri) && isAllowedResolverHost(host)
  }

  /** Returns a direct media candidate only for the exact wrapper and media hosts seen in the link. */
  fun unwrapCryptonewzDownloadUrl(wrapperUrl: String): String? {
    val wrapper = parseUri(wrapperUrl) ?: return null
    if (!isTrustedHttpsUri(wrapper) || wrapper.host?.equals(CRYPTONEWZ_HOST, ignoreCase = true) != true) return null
    if (!wrapper.path.equals(DOWNLOAD_PATH, ignoreCase = true)) return null

    val nestedUrl = queryParameter(wrapper, "link")?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val media = parseUri(nestedUrl) ?: return null
    if (!isTrustedHttpsUri(media) || media.host?.equals(MEDIA_HOST, ignoreCase = true) != true) return null
    if (media.rawPath.isNullOrBlank()) return null
    return media.toASCIIString()
  }

  fun shouldUseCryptonewzReferer(originalSource: String, resolvedMediaUrl: String): Boolean {
    val source = parseUri(originalSource) ?: return false
    val media = parseUri(resolvedMediaUrl) ?: return false
    return source.host?.equals(SOURCE_HOST, ignoreCase = true) == true &&
      isTrustedHttpsUri(media) && media.host?.equals(MEDIA_HOST, ignoreCase = true) == true
  }

  private fun queryParameter(uri: URI, expectedName: String): String? {
    val rawQuery = uri.rawQuery ?: return null
    for (part in rawQuery.split('&')) {
      val separator = part.indexOf('=')
      if (separator < 0) continue
      val name = decodeQueryComponent(part.substring(0, separator)) ?: continue
      if (name.equals(expectedName, ignoreCase = true)) {
        return decodeQueryComponent(part.substring(separator + 1))
      }
    }
    return null
  }

  private fun decodeQueryComponent(value: String): String? =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()

  private fun parseUri(value: String): URI? = runCatching { URI(value) }.getOrNull()

  private fun isTrustedHttpsUri(uri: URI): Boolean =
    uri.scheme.equals("https", ignoreCase = true) &&
      uri.userInfo == null &&
      (uri.port == -1 || uri.port == 443)
}
