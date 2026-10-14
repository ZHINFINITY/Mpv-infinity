/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The single OkHttp instance every call site derives from via [OkHttpClient.newBuilder].
 *
 * Deriving instead of constructing shares one [Dispatcher] thread pool and one [ConnectionPool],
 * so sockets and TLS sessions are reused across WebDAV, Jellyfin, HLS proxying, script HTTP and
 * update checks instead of each subsystem paying its own handshake and thread cost.
 */
object SharedHttpClient {
  val base: OkHttpClient by lazy {
    OkHttpClient
      .Builder()
      .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
      .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(90, TimeUnit.SECONDS)
      .writeTimeout(90, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }

  /** Derives a client that shares this pool/dispatcher, overriding only what the caller needs. */
  fun derive(configure: OkHttpClient.Builder.() -> Unit): OkHttpClient = base.newBuilder().apply(configure).build()
}
