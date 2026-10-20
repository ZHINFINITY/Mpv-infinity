/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.data.network.client

import android.net.Uri
import app.infinity.mpvz.domain.network.NetworkFile
import java.io.InputStream

/**
 * Common interface for all network protocol clients
 */
interface NetworkClient {
  /**
   * Connect to the server
   */
  suspend fun connect(): Result<Unit>

  /**
   * Disconnect from the server
   */
  suspend fun disconnect()

  /**
   * Check if currently connected
   */
  fun isConnected(): Boolean

  /**
   * List files and directories at the given path
   */
  suspend fun listFiles(path: String): Result<List<NetworkFile>>

  /**
   * Get input stream for a file
   */
  suspend fun getFileStream(
    path: String,
    offset: Long = 0L,
    length: Long? = null,
  ): Result<InputStream>

  /**
   * Get file size when the protocol can expose it cheaply.
   */
  suspend fun getFileSize(path: String): Result<Long> =
    Result.failure(UnsupportedOperationException("File size is not supported by this client"))

  /**
   * Get a credential-free origin URI for diagnostics and anonymous sources.
   *
   * Authenticated playback must use [app.infinity.mpvz.data.network.proxy.NetworkStreamingProxy]
   * so secrets never enter intents, playlists, history, or logs.
   */
  suspend fun getFileUri(path: String): Result<Uri>
}
