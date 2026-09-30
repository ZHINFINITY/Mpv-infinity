package app.infinity.mpvz.utils.history

import java.security.MessageDigest

internal fun digestHistoryIdentity(identity: String): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
  val hex = "0123456789abcdef"
  return buildString(digest.size * 2) {
    digest.forEach { byte ->
      val value = byte.toInt() and 0xff
      append(hex[value ushr 4])
      append(hex[value and 0x0f])
    }
  }
}
