/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.data.network.client

import android.net.Uri
import android.util.Log
import app.infinity.mpvz.domain.network.NetworkConnection
import app.infinity.mpvz.domain.network.NetworkFile
import app.infinity.mpvz.domain.network.NetworkPath
import app.infinity.mpvz.network.SharedHttpClient
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class WebDavClient(
  private val connection: NetworkConnection,
) : NetworkClient {
  companion object {
    private const val TAG = "WebDavClient"
    private const val SKIP_BUFFER_BYTES = 64 * 1024
    private const val RANGE_CHUNK_BYTES = 8L * 1024 * 1024
    private val rangeHttpClient by lazy {
      SharedHttpClient.derive {
        // A call timeout covers the entire response body and would terminate healthy long streams.
        // Keep the shared connect/read timeouts, which still detect connection and socket stalls.
        callTimeout(0, TimeUnit.SECONDS)
      }
    }
    private val contentRangePattern = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    private val encodedPathSeparatorPattern = Regex("%(?:2f|5c)", RegexOption.IGNORE_CASE)
  }

  private data class RangedResponse(
    val response: Response,
    val start: Long,
    val endInclusive: Long,
    val totalLength: Long?,
  )

  private var sardine: Sardine? = null

  /**
   * TorBox supports either account-email/account-password or torbox/API-key authentication.
   * Keep the configured credentials as the primary choice, but retry a 401 with the documented
   * API-key form when the saved username is an email and the saved password is an API key.
   */
  private fun torboxFallbackAuthorization(): String? {
    if (connection.isAnonymous || connection.password.isBlank()) return null
    if (!connection.host.equals("webdav.torbox.app", ignoreCase = true)) return null
    if (connection.username.equals("torbox", ignoreCase = true)) return null
    return Credentials.basic("torbox", connection.password)
  }

  private fun executeWithAuthentication(request: Request): Response {
    val initialAuthorization = request.header("Authorization")
    Log.d(
      TAG,
      "request method=${request.method} url=${request.url} range=${request.header("Range") ?: "none"} auth=${if (initialAuthorization == null) "none" else "configured"}",
    )
    val response = rangeHttpClient.newCall(request).execute()
    val fallbackAuthorization = torboxFallbackAuthorization()
    if (response.code != 401 || fallbackAuthorization == null) {
      Log.d(TAG, "response method=${request.method} url=${request.url} code=${response.code} contentLength=${response.header("Content-Length") ?: "none"} contentRange=${response.header("Content-Range") ?: "none"} authRetry=false")
      return response
    }
    Log.d(TAG, "response method=${request.method} url=${request.url} code=401 authRetry=true")
    response.close()
    val retryRequest = request.newBuilder().header("Authorization", fallbackAuthorization).build()
    return rangeHttpClient.newCall(retryRequest).execute().also { retryResponse ->
      Log.d(TAG, "response method=${retryRequest.method} url=${retryRequest.url} code=${retryResponse.code} contentLength=${retryResponse.header("Content-Length") ?: "none"} contentRange=${retryResponse.header("Content-Range") ?: "none"} authRetry=true")
    }
  }

  /**
   * Builds WebDAV request URLs from decoded path segments. HttpUrl owns the wire encoding so
   * reserved filename characters such as '[', ']', '%', '#', '?' and spaces are encoded exactly
   * once and are never reparsed through java.net.URI.
   */
  private fun buildHttpUrl(
    relativePath: String,
    trailingSlash: Boolean = false,
  ): HttpUrl {
    val host = connection.host.trim().removePrefix("[").removeSuffix("]")
    val builder =
      HttpUrl
        .Builder()
        .scheme(if (connection.useHttps) "https" else "http")
        .host(host)
        .port(connection.port)

    NetworkPath.from(connection.path).segments.forEach(builder::addPathSegment)
    NetworkPath.from(relativePath).segments.forEach(builder::addPathSegment)
    if (trailingSlash) builder.addPathSegment("")
    return builder.build()
  }

  private fun buildUrl(
    relativePath: String,
    trailingSlash: Boolean = false,
  ): String = buildHttpUrl(relativePath, trailingSlash).toString()

  /**
   * Uses Sardine's parsed DavResource href as the source of truth for child identity. Sardine has
   * already URI-decoded the href path once; decoding it again corrupts literal percent sequences.
   * Relative hrefs are resolved against the requested collection path without creating another URI.
   */
  private fun toImmediateChild(
    resource: DavResource,
    directory: NetworkPath,
    directoryUrl: HttpUrl,
  ): NetworkFile? {
    val href = resource.href
    if (href.query != null || href.fragment != null) return null
    if (href.rawPath?.let(encodedPathSeparatorPattern::containsMatchIn) == true) return null

    val requestedSegments = directoryUrl.pathSegments.filter(String::isNotEmpty)
    val resolvedSegments =
      directoryUrl
        .resolve(href.toASCIIString())
        ?.pathSegments
        ?.filter(String::isNotEmpty)
        ?: return null

    if (resolvedSegments == requestedSegments) return null
    val exactChildName =
      resolvedSegments
        .takeIf { segments ->
          segments.size == requestedSegments.size + 1 &&
            segments.take(requestedSegments.size) == requestedSegments
        }?.last()

    // Reverse proxies sometimes rewrite the collection prefix in response hrefs. Prefer the href's
    // decoded final path component for fallback identity. URI.path decodes percent escapes while
    // preserving literal reserved filename characters such as '+', '#', '?', '%', '&', and Unicode;
    // resource.name is display metadata and must never be used to reconstruct a request path.
    val hrefName = href.path
      ?.trimEnd('/')
      ?.substringAfterLast('/')
      ?.takeIf(String::isNotBlank)
    if (
      exactChildName == null &&
      resource.isDirectory &&
      hrefName == requestedSegments.lastOrNull() &&
      resolvedSegments.lastOrNull() == requestedSegments.lastOrNull()
    ) {
      return null
    }

    val childName = exactChildName ?: hrefName ?: return null
    return runCatching {
      val filePath = directory.child(childName)
      val displayName = resource.name?.takeIf(String::isNotBlank) ?: childName
      NetworkFile(
        name = displayName,
        path = filePath.value,
        isDirectory = resource.isDirectory,
        size = resource.contentLength ?: -1L,
        lastModified = resource.modified?.time ?: 0,
        mimeType = if (!resource.isDirectory) NetworkMimeTypes.forFileName(displayName) else null,
      )
    }.getOrNull()
  }

  override suspend fun connect(): Result<Unit> =
    withContext(Dispatchers.IO) {
      var lastError: Exception? = null
      val credentialAttempts =
        buildList {
          if (connection.isAnonymous) {
            add(null)
          } else {
            add(connection.username to connection.password)
            if (connection.host.equals("webdav.torbox.app", ignoreCase = true) &&
              !connection.username.equals("torbox", ignoreCase = true) &&
              connection.password.isNotBlank()
            ) {
              add("torbox" to connection.password)
            }
          }
        }.distinct()

      for (credentials in credentialAttempts) {
        try {
          val candidate = OkHttpSardine()
          credentials?.let { (username, password) -> candidate.setCredentials(username, password) }

          // Reachability/credential probe only. Reverse proxies commonly reject or empty out a
          // depth-0 PROPFIND at the share root while every file below it stays fully streamable,
          // so only credential rejections are conclusive failures here.
          try {
            candidate.list(buildUrl("", trailingSlash = true), 0)
          } catch (probeError: SardineException) {
            if (probeError.statusCode == 401 || probeError.statusCode == 403) throw probeError
          }

          sardine = candidate
          return@withContext Result.success(Unit)
        } catch (cancellation: CancellationException) {
          sardine = null
          throw cancellation
        } catch (error: Exception) {
          lastError = error
        }
      }

      sardine = null
      Result.failure(lastError ?: IOException("Unable to connect to WebDAV"))
    }

  override suspend fun disconnect() {
    withContext(Dispatchers.IO) {
      sardine = null
    }
  }

  override fun isConnected(): Boolean = sardine != null

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      try {
        val client = sardine ?: return@withContext Result.failure(IOException("Not connected"))
        val directory = NetworkPath.from(path)
        val directoryUrl = buildHttpUrl(directory.value, trailingSlash = true)
        val resources = client.list(directoryUrl.toString())

        val files =
          resources
            .mapNotNull { resource -> toImmediateChild(resource, directory, directoryUrl) }
            // Some DAV servers emit the same href more than once with different propstat blocks.
            .distinctBy(NetworkFile::path)

        Result.success(files)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  override suspend fun getFileSize(path: String): Result<Long> =
    withContext(Dispatchers.IO) {
      try {
        if (sardine == null) return@withContext Result.failure(IOException("Not connected"))
        val filePath = NetworkPath.from(path)
        Log.d(TAG, "size path=${filePath.value} url=${buildUrl(filePath.value)}")
        // Prefer the authenticated DAV metadata response for TorBox and other hybrid servers.
        // Their HEAD/range endpoint can return 404 for a valid file after switching folders, while
        // PROPFIND still returns the file content length. Keep the HTTP probe as a fallback for
        // servers that omit getcontentlength from PROPFIND responses.
        val resourceSize =
          runCatching {
            sardine?.list(buildUrl(filePath.value), 0)?.firstOrNull()?.contentLength
          }.getOrNull()?.takeIf { it >= 0L }
        val size = resourceSize ?: probeSizeOverHttp(filePath)
        if (size == null || size < 0L) {
          Result.failure(IOException("WebDAV server did not provide a file size"))
        } else {
          Result.success(size)
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  private fun probeSizeOverHttp(path: NetworkPath): Long? {
    val headBuilder =
      Request
        .Builder()
        .url(buildUrl(path.value))
        .head()
        .header("Accept-Encoding", "identity")
    if (!connection.isAnonymous) {
      headBuilder.header("Authorization", Credentials.basic(connection.username, connection.password))
    }
    executeWithAuthentication(headBuilder.build()).use { response ->
      if (response.isSuccessful) {
        response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }?.let { return it }
      }
    }

    // Servers that omit a HEAD Content-Length still report the total in a ranged Content-Range.
    val rangeBuilder =
      Request
        .Builder()
        .url(buildUrl(path.value))
        .get()
        .header("Range", "bytes=0-0")
        .header("Accept-Encoding", "identity")
    if (!connection.isAnonymous) {
      rangeBuilder.header("Authorization", Credentials.basic(connection.username, connection.password))
    }
    executeWithAuthentication(rangeBuilder.build()).use { response ->
      val match = contentRangePattern.matchEntire(response.header("Content-Range").orEmpty())
      return match?.groupValues?.get(3)?.takeUnless { it == "*" }?.toLongOrNull()
    }
  }

  override suspend fun getFileStream(
    path: String,
    offset: Long,
  ): Result<InputStream> =
    withContext(Dispatchers.IO) {
      require(offset >= 0L) { "Stream offset must not be negative" }
      try {
        if (offset > 0L) {
          return@withContext getRangedFileStream(NetworkPath.from(path), offset)
        }

        // A per-call OkHttpSardine leaks its own OkHttpClient and applies a 10s read timeout
        // that kills healthy long-running media bodies; the shared ranged client does neither.
        val requestBuilder =
          Request
            .Builder()
            .url(buildUrl(NetworkPath.from(path).value))
            .get()
            .header("Accept-Encoding", "identity")
        if (!connection.isAnonymous) {
          requestBuilder.header("Authorization", Credentials.basic(connection.username, connection.password))
        }
        val request = requestBuilder.build()
        Log.d(TAG, "stream path=${NetworkPath.from(path).value} offset=$offset url=${request.url} range=none")
        val response = executeWithAuthentication(request)
        if (!response.isSuccessful) {
          response.close()
          throw IOException("WebDAV request failed with HTTP ${response.code}")
        }
        Result.success(response.body.byteStream())
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  private fun getRangedFileStream(
    path: NetworkPath,
    offset: Long,
  ): Result<InputStream> {
    val initial =
      try {
        openRangedResponse(path, offset)
      } catch (error: Exception) {
        return Result.failure(error)
      }

    return Result.success(
      object : InputStream() {
        private var current = initial
        private var stream = current.response.body.byteStream()
        private var position = current.start
        private var bytesRemaining = current.endInclusive - current.start + 1L
        private var totalLength = current.totalLength
        private var closed = false

        override fun read(): Int {
          val singleByte = ByteArray(1)
          val count = read(singleByte, 0, 1)
          return if (count < 0) -1 else singleByte[0].toInt() and 0xff
        }

        override fun read(
          b: ByteArray,
          off: Int,
          len: Int,
        ): Int {
          if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
          if (len == 0) return 0
          check(!closed) { "WebDAV range stream is closed" }

          while (true) {
            if (bytesRemaining == 0L) {
              val completeLength = totalLength ?: return -1
              if (position >= completeLength) return -1

              current.response.close()
              val next = openRangedResponse(path, position)
              if (next.totalLength != null && next.totalLength != completeLength) {
                next.response.close()
                throw IOException("WebDAV resource length changed during streaming")
              }
              current = next
              stream = next.response.body.byteStream()
              bytesRemaining = next.endInclusive - next.start + 1L
              totalLength = next.totalLength ?: completeLength
            }

            val toRead = minOf(len.toLong(), bytesRemaining).toInt()
            val count = stream.read(b, off, toRead)
            if (count > 0) {
              position += count
              bytesRemaining -= count
              return count
            }
            if (count == 0) continue
            throw IOException("WebDAV range response ended before its declared Content-Range")
          }
        }

        override fun available(): Int =
          if (closed) {
            0
          } else {
            minOf(stream.available().toLong(), bytesRemaining, Int.MAX_VALUE.toLong()).toInt()
          }

        override fun close() {
          if (closed) return
          closed = true
          current.response.close()
        }
      },
    )
  }

  private fun openRangedResponse(
    path: NetworkPath,
    offset: Long,
  ): RangedResponse {
    val requestBuilder =
      Request
        .Builder()
        .url(buildUrl(path.value))
        .get()
        .header(
          "Range",
          "bytes=$offset-${offset + RANGE_CHUNK_BYTES - 1L}",
        )
        .header("Accept-Encoding", "identity")

    if (!connection.isAnonymous) {
      requestBuilder.header("Authorization", Credentials.basic(connection.username, connection.password))
    }

    val request = requestBuilder.build()
    Log.d(TAG, "stream path=${path.value} offset=$offset url=${request.url} range=${request.header("Range") ?: "none"}")
    val response = executeWithAuthentication(request)
    val rangeMatch = contentRangePattern.matchEntire(response.header("Content-Range").orEmpty())
    val returnedStart = rangeMatch?.groupValues?.get(1)?.toLongOrNull()
    val returnedEnd = rangeMatch?.groupValues?.get(2)?.toLongOrNull()
    val totalLength = rangeMatch?.groupValues?.get(3)?.takeUnless { it == "*" }?.toLongOrNull()
    val returnedLength =
      if (returnedStart != null && returnedEnd != null && returnedEnd >= returnedStart) {
        returnedEnd - returnedStart + 1L
      } else {
        null
      }
    val bodyLength = response.body.contentLength()

    // Some DAV servers ignore Range and reply 200 with the full body. Consuming up to the offset
    // keeps seeking functional there; slow for deep seeks, but strictly better than failing.
    if (response.code == 200 && bodyLength > offset) {
      try {
        skipExactly(response.body.byteStream(), offset)
      } catch (error: Exception) {
        response.close()
        throw error
      }
      return RangedResponse(
        response = response,
        start = offset,
        endInclusive = bodyLength - 1L,
        totalLength = bodyLength,
      )
    }

    // A bare HTTP 200 means the server ignored Range. Returning it as if it started at
    // [offset] corrupts seeking, so only a validated 206 response is accepted.
    if (
      response.code != 206 ||
      returnedStart != offset ||
      returnedEnd == null ||
      returnedEnd < offset ||
      (bodyLength >= 0L && bodyLength != returnedLength)
    ) {
      response.close()
      throw IOException(
        if (response.code == 200) {
          "WebDAV server ignored the requested byte range"
        } else {
          "WebDAV ranged request failed with HTTP ${response.code}"
        },
      )
    }

    return RangedResponse(
      response = response,
      start = returnedStart,
      endInclusive = returnedEnd,
      totalLength = totalLength,
    )
  }

  private fun skipExactly(
    stream: InputStream,
    byteCount: Long,
  ) {
    val scratch = ByteArray(SKIP_BUFFER_BYTES)
    var remaining = byteCount
    while (remaining > 0L) {
      val read = stream.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
      if (read < 0) throw IOException("WebDAV stream ended before the requested offset")
      remaining -= read
    }
  }

  /** Credential-free origin URI. Authenticated playback must use the loopback proxy. */
  override suspend fun getFileUri(path: String): Result<Uri> =
    withContext(Dispatchers.IO) {
      try {
        Result.success(Uri.parse(buildUrl(NetworkPath.from(path).value)))
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }
}
