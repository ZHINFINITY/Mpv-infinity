package app.infinity.mpvz.domain.download

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Validators that are safe to use with HTTP If-Range. */
data class DownloadValidators(
  val etag: String? = null,
  val lastModified: String? = null,
) {
  val ifRange: String?
    get() = etag ?: lastModified

  fun matches(responseEtag: String?, responseLastModified: String?): Boolean =
    when {
      etag != null -> responseEtag == etag
      lastModified != null -> responseLastModified == lastModified
      else -> false
    }

  companion object {
    fun fromResponse(etag: String?, lastModified: String?): DownloadValidators =
      DownloadValidators(
        etag = etag?.takeIf(::isStrongEtag),
        lastModified = lastModified?.takeIf(::isValidLastModified),
      )

    fun isStrongEtag(value: String): Boolean =
      value != "*" && !value.startsWith("W/", ignoreCase = true) && value.matches(ETAG_PATTERN)

    fun isValidLastModified(value: String): Boolean =
      runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME) }.isSuccess

    private val ETAG_PATTERN = Regex("\\\"[^\\\"]*\\\"")
  }
}

data class DownloadRange(
  val start: Long,
  val end: Long,
  val total: Long,
) {
  val length: Long get() = end - start + 1L
}

/** Strict Content-Range check; a resumed response must describe exactly the requested suffix. */
fun parseResumedRange(value: String?, expectedStart: Long, bodyLength: Long): DownloadRange? {
  val match = value?.let { CONTENT_RANGE_PATTERN.matchEntire(it) } ?: return null
  val range = DownloadRange(
    start = match.groupValues[1].toLongOrNull() ?: return null,
    end = match.groupValues[2].toLongOrNull() ?: return null,
    total = match.groupValues[3].toLongOrNull() ?: return null,
  )
  if (range.start != expectedStart || range.end < range.start || range.end + 1L != range.total) return null
  if (bodyLength >= 0L && bodyLength != range.length) return null
  return range
}

private val CONTENT_RANGE_PATTERN = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
