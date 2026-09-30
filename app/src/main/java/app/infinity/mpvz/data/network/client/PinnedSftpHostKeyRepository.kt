/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package app.infinity.mpvz.data.network.client

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/** Per-connection SSH host-key pin; it never accepts or learns a key automatically. */
internal class PinnedSftpHostKeyRepository(
  host: String,
  port: Int,
  private val expectedFingerprint: String,
) : HostKeyRepository {
  private val expectedHost =
    if (port == 22) normalizeHost(host) else "[${normalizeHost(host)}]:$port"
  @Volatile
  private var acceptedHostKey: HostKey? = null

  override fun check(host: String?, key: ByteArray?): Int {
    if (host == null || key == null || normalizeHost(host) != expectedHost) {
      return HostKeyRepository.NOT_INCLUDED
    }
    if (fingerprint(key) != expectedFingerprint) return HostKeyRepository.CHANGED
    acceptedHostKey = HostKey(host, key)
    return HostKeyRepository.OK
  }

  override fun getKnownHostsRepositoryID(): String = "pinned:$expectedHost"

  override fun add(
    hostkey: HostKey?,
    ui: UserInfo?,
  ) = Unit

  override fun remove(
    host: String?,
    type: String?,
  ) = Unit

  override fun remove(
    host: String?,
    type: String?,
    key: ByteArray?,
  ) = Unit

  override fun getHostKey(): Array<HostKey> = acceptedHostKey?.let { arrayOf(it) } ?: emptyArray()

  override fun getHostKey(
    host: String?,
    type: String?,
  ): Array<HostKey> {
    val accepted = acceptedHostKey ?: return emptyArray()
    if (host != null && normalizeHost(host) != expectedHost) return emptyArray()
    if (type != null && accepted.type != type) return emptyArray()
    return arrayOf(accepted)
  }

  private fun fingerprint(key: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

  private fun normalizeHost(host: String): String =
    host.trim().lowercase(Locale.ROOT)
}
