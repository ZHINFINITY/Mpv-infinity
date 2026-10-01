/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package app.infinity.mpvz.repository.subtitle

import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val MAX_SUBTITLE_RESPONSE_BYTES = 32 * 1024 * 1024

/** Reads an untrusted subtitle response while enforcing a fixed byte limit. */
fun readBoundedSubtitleResponse(body: ResponseBody): ByteArray {
  val declaredLength = body.contentLength()
  if (declaredLength > MAX_SUBTITLE_RESPONSE_BYTES) {
    throw IOException("Subtitle response exceeds $MAX_SUBTITLE_RESPONSE_BYTES bytes")
  }

  val initialCapacity = declaredLength.takeIf { it > 0L }?.toInt()?.coerceAtMost(MAX_SUBTITLE_RESPONSE_BYTES) ?: 8192
  val output = ByteArrayOutputStream(initialCapacity)
  val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
  var total = 0
  body.byteStream().use { input ->
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      if (count > MAX_SUBTITLE_RESPONSE_BYTES - total) {
        throw IOException("Subtitle response exceeds $MAX_SUBTITLE_RESPONSE_BYTES bytes")
      }
      output.write(buffer, 0, count)
      total += count
    }
  }
  return output.toByteArray()
}
