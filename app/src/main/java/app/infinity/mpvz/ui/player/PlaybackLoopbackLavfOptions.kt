/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

/**
 * lavf option sets for the player's own loopback proxies.
 *
 * `MPVView` opens every file with persistent HTTP connections (`http_persistent=1`), which is the
 * right setting for remote CDNs: it saves a TCP+TLS handshake per HLS/DASH segment. The torrent
 * proxy and the HLS/stream relays in this app are [NanoHTTPD] servers bound to 127.0.0.1, and they
 * close the socket after each response. libavformat's persistent connection then reuses a socket
 * the server has already dropped, so the next range request or segment switch fails instead of
 * reconnecting, and network playback stalls at the first seek.
 *
 * mpvRx solves this by giving loopback sources `http_persistent=0`; these constants carry that
 * override so the value is chosen per opened file rather than globally.
 */
internal object PlaybackLoopbackLavfOptions {
  private const val STREAM_RECONNECT =
    "reconnect=1,reconnect_on_network_error=1,reconnect_on_http_error=5xx," +
      "reconnect_streamed=1,reconnect_delay_max=5,reconnect_max_retries=5," +
      "reconnect_delay_total_max=20"

  private const val DEMUXER_RECONNECT =
    "reconnect=1,reconnect_on_network_error=1,reconnect_streamed=1," +
      "reconnect_delay_max=5,reconnect_max_retries=5,reconnect_delay_total_max=20"

  /** Primary http(s) URL, remote host: same value `MPVView` installs on init. */
  const val REMOTE_STREAM = STREAM_RECONNECT

  /** Demuxer-internal opens (HLS/DASH segments), remote host: same value `MPVView` installs. */
  const val REMOTE_DEMUXER = "http_persistent=1,$DEMUXER_RECONNECT"

  /** Primary http(s) URL served by one of our loopback proxies. */
  const val LOOPBACK_STREAM = "http_persistent=0,$STREAM_RECONNECT"

  /** Demuxer-internal opens against a loopback proxy. */
  const val LOOPBACK_DEMUXER = "http_persistent=0,$DEMUXER_RECONNECT"

  private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

  /**
   * True when [playableUri] points at this device: the torrent proxy and the stream relays all
   * serve plain http from 127.0.0.1 with an ephemeral port.
   */
  fun isLoopback(playableUri: String): Boolean {
    val authority =
      playableUri
        .substringAfter("://", missingDelimiterValue = "")
        .substringBefore('/')
        .substringBefore('?')
    if (authority.isEmpty()) return false
    val host =
      when {
        authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
        else -> authority.substringBefore(':')
      }
    return host.lowercase() in LOOPBACK_HOSTS
  }

  /**
   * Installs the lavf options for [playableUri], called right before `loadfile` so a switch
   * between remote and loopback sources never inherits the other's connection policy.
   */
  fun applyFor(playableUri: String) {
    val loopback = isLoopback(playableUri)
    PlaybackSession.setOptionString(
      "stream-lavf-o",
      if (loopback) LOOPBACK_STREAM else REMOTE_STREAM,
    )
    PlaybackSession.setOptionString(
      "demuxer-lavf-o",
      if (loopback) LOOPBACK_DEMUXER else REMOTE_DEMUXER,
    )
  }
}
