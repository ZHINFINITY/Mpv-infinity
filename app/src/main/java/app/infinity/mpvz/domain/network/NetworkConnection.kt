/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.domain.network

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a network connection configuration
 */
@Entity(tableName = "network_connections")
@Immutable
data class NetworkConnection(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val name: String,
  val protocol: NetworkProtocol,
  val host: String,
  val port: Int,
  val username: String = "",
  val password: String = "",
  val path: String = "/",
  val isAnonymous: Boolean = false,
  val lastConnected: Long = 0,
  val autoConnect: Boolean = false,
  val useHttps: Boolean = false, // For WebDAV: use HTTPS instead of HTTP
  val sftpHostKeyFingerprint: String = "",
) {
  override fun toString(): String =
    "NetworkConnection(id=$id, name=$name, protocol=$protocol, credentials=<redacted>)"
}

/**
 * Supported network protocols
 */
enum class NetworkProtocol(
  val displayName: String,
  val defaultPort: Int,
) {
  SMB("SMB", 445),
  FTP("FTP", 21),
  WEBDAV("WebDAV", 80),
  SFTP("SFTP", 22),
}

/**
 * Runtime status of a network connection
 */
@Immutable
data class ConnectionStatus(
  val connectionId: Long,
  val isConnected: Boolean = false,
  val isConnecting: Boolean = false,
  val error: String? = null,
)
